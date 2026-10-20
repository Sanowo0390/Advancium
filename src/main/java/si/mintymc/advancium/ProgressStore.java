package si.mintymc.advancium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** JDBC connection is only accessed by the single dedicated database executor. */
final class ProgressStore implements AutoCloseable {
    record Record(UUID uuid, String name, int done) {}

    private final String type;
    private final String serverId;
    private final String url;
    private final String user;
    private final String password;
    private final Logger logger;
    private Connection connection;

    ProgressStore(String type, String serverId, Path pluginDir, String file,
                  String host, int port, String database, String user, String password, Logger logger) {
        this.type = type.equalsIgnoreCase("mariadb") ? "mariadb" : "sqlite";
        this.serverId = serverId;
        this.logger = logger;
        if (this.type.equals("mariadb")) {
            this.url = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?connectTimeout=5000&socketTimeout=15000";
        } else {
            Path db = pluginDir.resolve(file).normalize();
            if (!db.startsWith(pluginDir.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("sqlite-file must be inside the plugin folder");
            }
            this.url = "jdbc:sqlite:" + db.toAbsolutePath();
        }
        this.user = user;
        this.password = password;
    }

    void open() throws SQLException, ClassNotFoundException {
        Class.forName(type.equals("sqlite") ? "org.sqlite.JDBC" : "org.mariadb.jdbc.Driver");
        connection = type.equals("sqlite") ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
        if (type.equals("sqlite")) {
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA journal_mode=WAL");
            }
        }
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS adv_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, last_seen BIGINT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS adv_progress (server_id VARCHAR(80) NOT NULL, uuid VARCHAR(36) NOT NULL, adv_key VARCHAR(255) NOT NULL, PRIMARY KEY (server_id, uuid, adv_key))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS adv_progress_uuid ON adv_progress(uuid)");
        } catch (SQLException ex) {
            // MariaDB versions before 10.11 don't support CREATE INDEX IF NOT EXISTS consistently.
            if (!type.equals("mariadb")) throw ex;
            try (Statement s = connection.createStatement()) {
                // Tables are guaranteed by explicit statements below in case INDEX syntax fails.
                s.executeUpdate("CREATE TABLE IF NOT EXISTS adv_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, last_seen BIGINT NOT NULL)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS adv_progress (server_id VARCHAR(80) NOT NULL, uuid VARCHAR(36) NOT NULL, adv_key VARCHAR(255) NOT NULL, PRIMARY KEY (server_id, uuid, adv_key))");
            }
        }
    }

    void replaceSnapshot(UUID uuid, String name, Set<String> keys) throws SQLException {
        // Transaction protects readers from observing half-written snapshots.
        connection.setAutoCommit(false);
        try (PreparedStatement upsert = connection.prepareStatement(type.equals("mariadb")
                    ? "INSERT INTO adv_players(uuid,name,last_seen) VALUES(?,?,?) ON DUPLICATE KEY UPDATE name=CASE WHEN VALUES(name)=SUBSTR(VALUES(uuid),1,8) THEN name ELSE VALUES(name) END,last_seen=VALUES(last_seen)"
                    : "INSERT INTO adv_players(uuid,name,last_seen) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=CASE WHEN excluded.name=substr(excluded.uuid,1,8) THEN adv_players.name ELSE excluded.name END,last_seen=excluded.last_seen");
             PreparedStatement delete = connection.prepareStatement("DELETE FROM adv_progress WHERE server_id=? AND uuid=?");
             PreparedStatement insert = connection.prepareStatement("INSERT INTO adv_progress(server_id,uuid,adv_key) VALUES(?,?,?)")) {
            String id = uuid.toString();
            upsert.setString(1, id); upsert.setString(2, name); upsert.setLong(3, System.currentTimeMillis()); upsert.executeUpdate();
            delete.setString(1, serverId); delete.setString(2, id); delete.executeUpdate();
            for (String key : keys) {
                insert.setString(1, serverId); insert.setString(2, id); insert.setString(3, key); insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Global ranking: union of advancement keys over all backend IDs, never sum server counts. */
    List<Record> leaderboard(Set<String> available) throws SQLException {
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Set<String>> merged = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT uuid, name FROM adv_players")) {
            while (rs.next()) {
                try { UUID id = UUID.fromString(rs.getString(1)); names.put(id, rs.getString(2)); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT uuid, adv_key FROM adv_progress")) {
            while (rs.next()) {
                String key = rs.getString(2);
                if (!available.contains(key)) continue;
                try {
                    UUID id = UUID.fromString(rs.getString(1));
                    merged.computeIfAbsent(id, k -> new HashSet<>()).add(key);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        List<Record> out = new ArrayList<>();
        for (Map.Entry<UUID, String> row : names.entrySet()) {
            out.add(new Record(row.getKey(), row.getValue(), merged.getOrDefault(row.getKey(), Set.of()).size()));
        }
        out.sort(Comparator.comparingInt(Record::done).reversed()
                .thenComparing(Record::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.uuid().toString()));
        return List.copyOf(out);
    }

    /** Summary of the best saved file selected for each UUID across all configured worlds. */
    record ImportStats(int filesFound, int imported, int failed, int duplicates) {}

    /**
     * Read old and 26.1+ player advancements on the dedicated IO executor.
     * Multiple world directories may contain the same player: use the most recently
     * written file, rather than merging stale snapshots or replacing a newer one.
     */
    static ImportStats importAdvancementFiles(Collection<Path> worldFolders,
                                               Map<UUID, String> nameCache,
                                               ProgressStore store,
                                               Set<String> allowed) {
        Map<UUID, Path> chosen = new HashMap<>();
        int found = 0;
        int duplicates = 0;
        Set<Path> visited = new HashSet<>();
        for (Path root : worldFolders) {
            for (String relative : List.of("players/advancements", "advancements")) {
                Path dir = root.resolve(relative).toAbsolutePath().normalize();
                if (!visited.add(dir) || !Files.isDirectory(dir)) continue;
                try (Stream<Path> stream = Files.list(dir)) {
                    for (Path file : (Iterable<Path>) stream::iterator) {
                        String filename = file.getFileName().toString();
                        if (!filename.endsWith(".json") || !Files.isRegularFile(file)) continue;
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(filename.substring(0, filename.length() - 5));
                        } catch (IllegalArgumentException invalidName) {
                            continue;
                        }
                        found++;
                        Path previous = chosen.get(uuid);
                        if (previous != null) {
                            duplicates++;
                            // 26.1+ data is authoritative when both locations coexist
                            // within the same world (such as after a version upgrade).
                            boolean currentModern = relative.equals("players/advancements");
                            boolean previousModern = previous.getParent().endsWith(Paths.get("players", "advancements"));
                            Path previousRoot = previousModern
                                    ? previous.getParent().getParent().getParent()
                                    : previous.getParent().getParent();
                            if (currentModern != previousModern && previousRoot.equals(root.toAbsolutePath().normalize())) {
                                if (currentModern) chosen.put(uuid, file);
                            } else if (Files.getLastModifiedTime(file).toMillis() > Files.getLastModifiedTime(previous).toMillis()) {
                                chosen.put(uuid, file);
                            }
                        } else {
                            chosen.put(uuid, file);
                        }
                    }
                } catch (Exception ex) {
                    store.logger.warning("進捗ディレクトリを読めません: " + dir + " (" + ex.getClass().getSimpleName() + ")");
                }
            }
        }

        int imported = 0;
        int failed = 0;
        for (Map.Entry<UUID, Path> entry : chosen.entrySet()) {
            UUID uuid = entry.getKey();
            Path file = entry.getValue();
            try {
                // Reject abnormally large JSON rather than exhausting the IO worker.
                if (Files.size(file) > 16 * 1024 * 1024L) throw new IOException("Advancement JSON too large");
                Map<String, Object> data = SimpleJson.object(SimpleJson.parse(Files.readString(file, StandardCharsets.UTF_8)));
                Set<String> completed = new HashSet<>();
                for (Map.Entry<String, Object> item : data.entrySet()) {
                    if (allowed.contains(item.getKey())
                            && Boolean.TRUE.equals(SimpleJson.object(item.getValue()).get("done"))) {
                        completed.add(item.getKey());
                    }
                }
                String name = nameCache.getOrDefault(uuid, uuid.toString().substring(0, 8));
                store.replaceSnapshot(uuid, name, completed);
                imported++;
            } catch (Exception ex) {
                failed++;
                store.logger.warning("進捗ファイルを読み飛ばしました: " + file + " (" + ex.getClass().getSimpleName() + ")");
            }
        }
        return new ImportStats(found, imported, failed, duplicates);
    }

    static Map<UUID, String> loadNameCache(Path serverRoot) {
        Map<UUID, String> result = new HashMap<>();
        Path file = serverRoot.resolve("usercache.json");
        if (!Files.isRegularFile(file)) return result;
        try {
            Object parsed = SimpleJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed instanceof List<?> entries) for (Object item : entries) {
                Map<String, Object> row = SimpleJson.object(item);
                try {
                    UUID id = UUID.fromString(SimpleJson.string(row.get("uuid")));
                    String name = SimpleJson.string(row.get("name"));
                    if (!name.isBlank()) result.put(id, name);
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (Exception ignored) { }
        return result;
    }

    @Override public void close() throws SQLException { if (connection != null) connection.close(); }
}
