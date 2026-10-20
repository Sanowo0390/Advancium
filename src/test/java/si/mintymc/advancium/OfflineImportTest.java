package si.mintymc.advancium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OfflineImportTest {
    @TempDir Path temp;

    private ProgressStore open() throws Exception {
        Path pluginDir = Files.createDirectories(temp.resolve("plugin"));
        ProgressStore store = new ProgressStore("sqlite", "survival", pluginDir,
                "players.sqlite3", "localhost", 3306, "test", "test", "",
                Logger.getLogger("AdvanciumTest"));
        store.open();
        return store;
    }

    private Path write(Path world, String folder, UUID id, String json, long modified) throws Exception {
        Path dir = Files.createDirectories(world.resolve(folder));
        Path file = dir.resolve(id + ".json");
        Files.writeString(file, json);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }

    @Test void importsOfflinePlayersAndCountsOnlyCompletedAllowedKeys() throws Exception {
        UUID id = UUID.randomUUID();
        Path world = temp.resolve("Seikatsu");
        write(world, "players/advancements", id,
                "{\"minecraft:story/mine_stone\":{\"done\":true},"
                + "\"minecraft:story/iron_tools\":{\"done\":false},"
                + "\"minecraft:recipes/misc/foo\":{\"done\":true}}", 1000);
        try (ProgressStore store = open()) {
            Set<String> allowed = Set.of("minecraft:story/mine_stone", "minecraft:story/iron_tools");
            ProgressStore.ImportStats stats = ProgressStore.importAdvancementFiles(
                    List.of(world), Map.of(id, "OfflinePlayer"), store, allowed);
            assertEquals(1, stats.imported());
            assertEquals(0, stats.failed());
            List<ProgressStore.Record> ranked = store.leaderboard(allowed);
            assertEquals(1, ranked.size());
            assertEquals("OfflinePlayer", ranked.getFirst().name());
            assertEquals(1, ranked.getFirst().done());
        }
    }

    @Test void importsAllWorldsAndSelectsNewestSnapshotForDuplicates() throws Exception {
        UUID id = UUID.randomUUID();
        Path lobby = temp.resolve("Lobby");
        Path survival = temp.resolve("Seikatsu");
        write(lobby, "players/advancements", id,
                "{\"minecraft:story/mine_stone\":{\"done\":true}}", 1000);
        write(survival, "players/advancements", id,
                "{\"minecraft:story/mine_stone\":{\"done\":true},"
                + "\"minecraft:story/iron_tools\":{\"done\":true}}", 2000);
        try (ProgressStore store = open()) {
            Set<String> allowed = Set.of("minecraft:story/mine_stone", "minecraft:story/iron_tools");
            ProgressStore.ImportStats stats = ProgressStore.importAdvancementFiles(
                    List.of(lobby, survival), Map.of(id, "Survivor"), store, allowed);
            assertEquals(2, stats.filesFound());
            assertEquals(1, stats.duplicates());
            assertEquals(1, stats.imported());
            assertEquals(2, store.leaderboard(allowed).getFirst().done());
        }
    }

    @Test void prefersModernFolderWhenLegacyAndNewFilesCoexistInOneWorld() throws Exception {
        UUID id = UUID.randomUUID();
        Path world = temp.resolve("World");
        write(world, "advancements", id, "{\"minecraft:story/mine_stone\":{\"done\":true}}", 5000);
        write(world, "players/advancements", id, "{\"minecraft:story/mine_stone\":{\"done\":false}}", 1000);
        try (ProgressStore store = open()) {
            Set<String> allowed = Set.of("minecraft:story/mine_stone");
            ProgressStore.ImportStats stats = ProgressStore.importAdvancementFiles(
                    List.of(world), Map.of(), store, allowed);
            assertEquals(1, stats.imported());
            assertEquals(1, stats.duplicates());
            assertEquals(0, store.leaderboard(allowed).getFirst().done());
        }
    }

    @Test void brokenJsonDoesNotEraseExistingDatabaseRecord() throws Exception {
        UUID id = UUID.randomUUID();
        Path world = temp.resolve("World");
        write(world, "players/advancements", id, "{not valid json}", 1000);
        try (ProgressStore store = open()) {
            Set<String> allowed = Set.of("minecraft:story/mine_stone");
            store.replaceSnapshot(id, "PriorPlayer", allowed);
            ProgressStore.ImportStats stats = ProgressStore.importAdvancementFiles(
                    List.of(world), Map.of(id, "PriorPlayer"), store, allowed);
            assertEquals(0, stats.imported());
            assertEquals(1, stats.failed());
            assertEquals(1, store.leaderboard(allowed).getFirst().done());
        }
    }
    @Test void sharedDatabaseUnionsProgressWithoutDoubleCounting() throws Exception {
        UUID id = UUID.randomUUID();
        Set<String> first = Set.of("minecraft:story/mine_stone");
        Set<String> second = Set.of("minecraft:story/iron_tools");
        Set<String> allowed = Set.of("minecraft:story/mine_stone", "minecraft:story/iron_tools");
        try (ProgressStore survival = open()) {
            survival.replaceSnapshot(id, "SharedPlayer", first);
        }
        Path pluginDir = temp.resolve("plugin");
        try (ProgressStore resource = new ProgressStore("sqlite", "resource", pluginDir,
                "players.sqlite3", "localhost", 3306, "test", "test", "",
                Logger.getLogger("AdvanciumTest"))) {
            resource.open();
            resource.replaceSnapshot(id, "SharedPlayer", second);
            assertEquals(1, resource.leaderboard(allowed).size());
            assertEquals(2, resource.leaderboard(allowed).getFirst().done());
            resource.replaceSnapshot(id, "SharedPlayer", first);
            assertEquals(1, resource.leaderboard(allowed).getFirst().done());
        }
    }

    @Test void historicalPlayersWithoutAdvancementFilesAppearAtZeroPercent() throws Exception {
        UUID withData = UUID.randomUUID();
        UUID withStats = UUID.randomUUID();
        UUID withLegacyData = UUID.randomUUID();
        Path world = temp.resolve("OldWorld");
        Path modern = Files.createDirectories(world.resolve("players/data"));
        Path stats = Files.createDirectories(world.resolve("players/stats"));
        Path legacy = Files.createDirectories(world.resolve("playerdata"));
        Files.write(modern.resolve(withData + ".dat"), new byte[] {1, 2, 3});
        Files.writeString(stats.resolve(withStats + ".json"), "{}");
        Files.write(legacy.resolve(withLegacyData + ".dat_old"), new byte[] {1});
        try (ProgressStore store = open()) {
            Set<String> allowed = Set.of("minecraft:story/mine_stone");
            ProgressStore.ImportStats statsResult = ProgressStore.importAdvancementFiles(
                    List.of(world),
                    Map.of(withData, "OldPlayer", withStats, "StatsOnly", withLegacyData, "LegacyPlayer"),
                    store, allowed);
            assertEquals(3, statsResult.participants());
            assertEquals(3, statsResult.withoutAdvancements());
            assertEquals(0, statsResult.imported());
            assertEquals(3, store.leaderboard(allowed).size());
            assertTrue(store.leaderboard(allowed).stream().allMatch(row -> row.done() == 0));
            assertTrue(store.leaderboard(allowed).stream().anyMatch(row -> row.name().equals("OldPlayer")));
        }
    }

    @Test void historicalZeroPercentImportNeverErasesStoredProgress() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path world = temp.resolve("OldWorld");
        Path dir = Files.createDirectories(world.resolve("playerdata"));
        Files.write(dir.resolve(uuid + ".dat"), new byte[] {0});
        try (ProgressStore store = open()) {
            Set<String> allowed = Set.of("minecraft:story/mine_stone");
            store.replaceSnapshot(uuid, "BeforeInstall", allowed);
            ProgressStore.importAdvancementFiles(List.of(world), Map.of(), store, allowed);
            assertEquals(1, store.leaderboard(allowed).getFirst().done());
            assertEquals("BeforeInstall", store.leaderboard(allowed).getFirst().name());
        }
    }

    @Test void discoversUnloadedWorldsWithoutScanningBackupTrees() throws Exception {
        Path base = temp.resolve("server");
        Path loaded = Files.createDirectories(base.resolve("Lobby"));
        Path old = Files.createDirectories(base.resolve("Seikatsu"));
        Path backup = Files.createDirectories(base.resolve("backups/Seikatsu"));
        Files.write(old.resolve("level.dat"), new byte[] {42});
        Files.write(backup.resolve("level.dat"), new byte[] {42});
        List<Path> discovered = ProgressStore.discoverWorldFolders(base, List.of(loaded),
                true, Logger.getLogger("AdvanciumTest"));
        assertTrue(discovered.contains(loaded));
        assertTrue(discovered.contains(old));
        assertFalse(discovered.contains(backup));
        List<Path> manual = ProgressStore.discoverWorldFolders(base, List.of(loaded),
                false, Logger.getLogger("AdvanciumTest"));
        assertEquals(List.of(loaded), manual);
    }

    @Test void loadNamesFromUsercacheAndWhitelist() throws Exception {
        Path base = Files.createDirectories(temp.resolve("server"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Files.writeString(base.resolve("whitelist.json"),
                "[{\\"uuid\\":\\"" + first + "\\",\\"name\\":\\"WhitelistPlayer\\"}]");
        Files.writeString(base.resolve("usercache.json"),
                "[{\\"uuid\\":\\"" + first + "\\",\\"name\\":\\"CurrentName\\"},"
                + "{\\"uuid\\":\\"" + second + "\\",\\"name\\":\\"OldPlayer\\"}]");
        Map<UUID, String> names = ProgressStore.loadNameCache(base);
        assertEquals("CurrentName", names.get(first));
        assertEquals("OldPlayer", names.get(second));
    }

}
