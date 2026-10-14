package si.mintymc.advancium;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class Advancium extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Advancium-IO");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<UUID> scheduledPlayers = new HashSet<>(); // server main thread only
    private final AtomicBoolean boardScheduled = new AtomicBoolean(false);
    private final AtomicBoolean refreshScheduled = new AtomicBoolean(false);
    private volatile List<Advancement> advancements = List.of(); // only access Bukkit objects on main thread
    private volatile Set<String> allowedKeys = Set.of();
    private volatile List<ProgressStore.Record> leaderboard = List.of();
    private volatile Map<UUID, ProgressStore.Record> byUuid = Map.of();
    private volatile boolean ready;
    private ProgressStore store;
    private volatile WebhookBoard webhook;
    private String prefix;
    private List<Path> worldFolders = List.of();
    private Path serverRoot;

    @Override public void onEnable() {
        saveDefaultConfig();
        reloadPresentation();
        refreshRegistry();
        serverRoot = Bukkit.getWorldContainer().toPath();
        worldFolders = Bukkit.getWorlds().stream().map(w -> w.getWorldFolder().toPath()).toList();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("adv")).setExecutor(this);
        Objects.requireNonNull(getCommand("adv")).setTabCompleter(this);
        try {
            String serverId = getConfig().getString("server-id", "survival");
            if (!serverId.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("server-id must contain 1-80 safe characters");
            String type = getConfig().getString("storage.type", "sqlite");
            store = new ProgressStore(type, serverId, getDataFolder().toPath().toAbsolutePath(),
                    getConfig().getString("storage.sqlite-file", "players.sqlite3"),
                    getConfig().getString("storage.mariadb.host", "127.0.0.1"),
                    getConfig().getInt("storage.mariadb.port", 3306),
                    getConfig().getString("storage.mariadb.database", "minty_advancements"),
                    getConfig().getString("storage.mariadb.username", "minty_advancements"),
                    getConfig().getString("storage.mariadb.password", ""), getLogger());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "DB設定に誤りがあります", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        io.execute(() -> {
            try {
                store.open();
                ready = true;
                importOffline();
                runMain(() -> {
                    for (Player player : Bukkit.getOnlinePlayers()) schedulePlayerSync(player, false);
                });
                refreshBoard(true);
                getLogger().info("初期化完了。対象進捗=" + allowedKeys.size());
            } catch (Exception e) {
                // Do not leak connection URL or secrets into logs.
                getLogger().severe("DBの初期化に失敗しました: " + e.getClass().getSimpleName()
                        + "。接続設定とライブラリを確認してください。");
                runMain(() -> Bukkit.getPluginManager().disablePlugin(this));
            }
        });
        long refreshTicks = Math.max(15, getConfig().getInt("ranking.refresh-seconds", 60)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> executeIo(() -> refreshBoard(false)), refreshTicks, refreshTicks);
        long resyncTicks = Math.max(60, getConfig().getInt("ranking.resync-seconds", 300)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) schedulePlayerSync(player, false);
        }, resyncTicks, resyncTicks);
        long webhookTicks = Math.max(30, getConfig().getInt("webhook.periodic-seconds", 300)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> executeIo(() -> refreshBoard(true)), webhookTicks, webhookTicks);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new AdvanciumPlaceholders(this).register();
                getLogger().info("PlaceholderAPI に接続しました。");
            } catch (Exception | LinkageError e) { getLogger().warning("PlaceholderAPI 連携失敗: " + e.getClass().getSimpleName()); }
        }
    }

    private void reloadPresentation() {
        prefix = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&a[Advancium] &f"));
        if (getConfig().getBoolean("webhook.enabled", false)) {
            try {
                webhook = new WebhookBoard(getConfig().getString("webhook.url", ""),
                    getDataFolder().toPath().resolve("webhook-message-id.txt"), getLogger(),
                    getConfig().getInt("webhook.top-size", 10), getConfig().getInt("webhook.color", 5763719),
                    getConfig().getInt("webhook.min-update-seconds", 30),
                    getConfig().getString("webhook.title", "🏆 MintyMC 実績ランキング"),
                    getConfig().getString("webhook.footer", "Minecraft Java 26.3"));
            } catch (IllegalArgumentException e) {
                webhook = null;
                getLogger().warning("Webhookを無効にしました。HTTPSのDiscord Webhook URLを確認してください。");
            }
        } else webhook = null;
    }

    private void refreshRegistry() {
        List<Advancement> newList = new ArrayList<>();
        Set<String> newKeys = new LinkedHashSet<>();
        boolean custom = getConfig().getBoolean("advancements.include-custom", false);
        boolean withDisplay = getConfig().getBoolean("advancements.only-with-display", true);
        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            Advancement adv = iterator.next();
            if (!custom && !adv.getKey().getNamespace().equals("minecraft")) continue;
            if (withDisplay && adv.getDisplay() == null) continue;
            if (!custom && adv.getKey().getKey().startsWith("recipes/")) continue;
            if (newKeys.add(adv.getKey().toString())) newList.add(adv);
        }
        advancements = List.copyOf(newList);
        allowedKeys = Set.copyOf(newKeys);
        getLogger().info("集計対象の進捗: " + newList.size() + " 件（Minecraft 26.3向け自動取得）");
    }

    int total() { return allowedKeys.size(); }
    List<ProgressStore.Record> records() { return leaderboard; }
    ProgressStore.Record entry(UUID uuid) { return byUuid.get(uuid); }
    int position(UUID uuid) {
        List<ProgressStore.Record> rows = leaderboard;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).uuid().equals(uuid)) return i + 1;
        return 0;
    }
    String percent(int done) { return String.format(Locale.ROOT, "%.2f", total() <= 0 ? 0.0 : done * 100.0 / total()); }

    private Set<String> currentKeys(Player player) {
        Set<String> set = new HashSet<>();
        for (Advancement adv : advancements) {
            if (player.getAdvancementProgress(adv).isDone()) set.add(adv.getKey().toString());
        }
        return set;
    }

    private void schedulePlayerSync(Player player, boolean announce) {
        UUID id = player.getUniqueId();
        if (!scheduledPlayers.add(id)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            scheduledPlayers.remove(id);
            if (!player.isOnline()) return;
            Set<String> keys = currentKeys(player);
            if (announce && getConfig().getBoolean("messages.advancement-progress", true)) {
                player.sendMessage(prefix + "進捗達成率: " + ChatColor.AQUA + keys.size() + ChatColor.GRAY + "/" + total()
                        + ChatColor.YELLOW + " (" + percent(keys.size()) + "%)");
            }
            sendSnapshot(id, player.getName(), keys);
        }, 20L);
    }

    private void sendSnapshot(UUID id, String name, Set<String> snapshot) {
        executeIo(() -> {
            try {
                store.replaceSnapshot(id, name, snapshot);
                scheduleBoardRefresh();
                // Save one webhook edit per group of updates, not a post per advancement.
                scheduleWebhookEdit();
            } catch (Exception e) { getLogger().warning("実績保存失敗: " + e.getClass().getSimpleName()); }
        });
    }

    private void scheduleBoardRefresh() {
        if (!refreshScheduled.compareAndSet(false, true)) return;
        runMain(() -> Bukkit.getScheduler().runTaskLater(this, () -> {
            refreshScheduled.set(false);
            executeIo(() -> refreshBoard(false));
        }, 40L));
    }

    private void scheduleWebhookEdit() {
        if (webhook == null || !boardScheduled.compareAndSet(false, true)) return;
        runMain(() -> Bukkit.getScheduler().runTaskLater(this, () -> {
            boardScheduled.set(false);
            executeIo(() -> refreshBoard(true));
        }, Math.max(10, getConfig().getInt("webhook.min-update-seconds", 30)) * 20L));
    }

    private void refreshBoard(boolean publish) {
        if (!ready) return;
        try {
            List<ProgressStore.Record> newRecords = store.leaderboard(allowedKeys);
            Map<UUID, ProgressStore.Record> newMap = new HashMap<>();
            for (ProgressStore.Record row : newRecords) newMap.put(row.uuid(), row);
            leaderboard = newRecords;
            byUuid = Map.copyOf(newMap);
            WebhookBoard sender = webhook;
            if (publish && sender != null) sender.postOrEdit(newRecords, total());
        } catch (Exception e) { getLogger().warning("ランキング集計失敗: " + e.getClass().getSimpleName()); }
    }

    private void importOffline() {
        try {
            Map<UUID, String> names = ProgressStore.loadNameCache(serverRoot);
            int imported = 0;
            // Minecraft stores player advancements in its primary world. Import only one directory
            // so another Multiverse world cannot overwrite the same UUID with stale data.
            if (!worldFolders.isEmpty()) {
                imported += ProgressStore.importAdvancementFiles(worldFolders.get(0), names, store, allowedKeys);
            }
            getLogger().info("保存済み進捗を " + imported + " ファイル取り込みました。");
        } catch (Exception e) { getLogger().warning("オフライン進捗の一括取込失敗: " + e.getClass().getSimpleName()); }
    }

    private void executeIo(Runnable task) {
        if (ready && !io.isShutdown()) {
            try { io.execute(task); } catch (RejectedExecutionException ignored) { }
        }
    }
    private void runMain(Runnable task) {
        if (isEnabled()) Bukkit.getScheduler().runTask(this, task);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { if (ready) schedulePlayerSync(event.getPlayer(), false); }
    @EventHandler public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!allowedKeys.contains(event.getAdvancement().getKey().toString())) return;
        if (ready) schedulePlayerSync(event.getPlayer(), true);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (!ready) return;
        Player player = event.getPlayer();
        sendSnapshot(player.getUniqueId(), player.getName(), currentKeys(player));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!ready) { sender.sendMessage(prefix + ChatColor.RED + "データベース初期化中です。"); return true; }
        if (args.length > 0 && (args[0].equalsIgnoreCase("reload") || args[0].equalsIgnoreCase("sync"))) {
            if (!sender.hasPermission("advancium.admin")) { sender.sendMessage(ChatColor.RED + "権限がありません。"); return true; }
            if (args[0].equalsIgnoreCase("reload")) {
                reloadConfig(); reloadPresentation(); refreshRegistry();
                sender.sendMessage(prefix + "設定を再読込しました（DB設定・server-id変更は再起動が必要）。対象進捗: " + total());
                executeIo(() -> refreshBoard(true));
            } else {
                sender.sendMessage(prefix + "オフライン進捗を取り込み直します。");
                executeIo(() -> { importOffline(); refreshBoard(true); });
                for (Player p : Bukkit.getOnlinePlayers()) schedulePlayerSync(p, false);
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
            int page = 1;
            if (args.length > 1) {
                try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) { }
            }
            int size = Math.max(1, Math.min(20, getConfig().getInt("ranking.top-size", 10)));
            List<ProgressStore.Record> rows = leaderboard;
            int pages = Math.max(1, (rows.size() + size - 1) / size);
            if (page < 1 || page > pages) { sender.sendMessage(prefix + "ページは1〜" + pages + "です。"); return true; }
            sender.sendMessage(ChatColor.GOLD + "━━ MintyMC 実績ランキング " + page + "/" + pages + " ━━");
            for (int i = (page - 1) * size; i < Math.min(rows.size(), page * size); i++) {
                ProgressStore.Record r = rows.get(i);
                sender.sendMessage(ChatColor.YELLOW + "#" + (i + 1) + " " + ChatColor.WHITE + r.name()
                        + ChatColor.GRAY + " - " + r.done() + "/" + total() + " (" + percent(r.done()) + "%)");
            }
            if (rows.isEmpty()) sender.sendMessage(ChatColor.GRAY + "登録されたプレイヤーがいません。");
            return true;
        }
        UUID uuid;
        String name;
        Player online = null;
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { sender.sendMessage(prefix + "使い方: /adv <プレイヤー|top>"); return true; }
            online = p; uuid = p.getUniqueId(); name = p.getName();
        } else {
            name = args[0];
            online = Bukkit.getPlayerExact(name);
            if (online != null) { uuid = online.getUniqueId(); name = online.getName(); }
            else {
                String requestedName = name;
                Optional<ProgressStore.Record> found = leaderboard.stream().filter(r -> r.name().equalsIgnoreCase(requestedName)).findFirst();
                if (found.isEmpty()) { sender.sendMessage(prefix + "プレイヤーが見つかりません: " + name); return true; }
                uuid = found.get().uuid(); name = found.get().name();
            }
        }
        int done = online != null ? currentKeys(online).size() : Optional.ofNullable(byUuid.get(uuid)).map(ProgressStore.Record::done).orElse(0);
        int rank = position(uuid);
        sender.sendMessage(prefix + ChatColor.AQUA + name + ChatColor.WHITE + " の実績: " + done + "/" + total()
                + ChatColor.YELLOW + " (" + percent(done) + "%)" + ChatColor.GRAY + " / 順位: " + (rank == 0 ? "集計待ち" : rank + "位"));
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> candidates = new ArrayList<>(List.of("top"));
            if (sender.hasPermission("advancium.admin")) candidates.addAll(List.of("reload", "sync"));
            for (ProgressStore.Record r : leaderboard) candidates.add(r.name());
            return candidates.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).limit(100).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) return List.of("1", "2", "3");
        return List.of();
    }

    @Override public void onDisable() {
        ready = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (store != null) {
                Set<String> keys = currentKeys(player);
                UUID savedUuid = player.getUniqueId();
                String savedName = player.getName();
                try { io.execute(() -> {
                    try { store.replaceSnapshot(savedUuid, savedName, keys); }
                    catch (Exception e) { getLogger().warning("停止時保存失敗: " + e.getClass().getSimpleName()); }
                }); } catch (RejectedExecutionException ignored) { }
            }
        }
        if (store != null) {
            try { io.execute(() -> { try { store.close(); } catch (Exception ignored) { } }); }
            catch (RejectedExecutionException ignored) { }
        }
        io.shutdown();
        try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) getLogger().warning("DB処理の終了が間に合いませんでした。"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
