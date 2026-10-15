package si.mintymc.advancium;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

final class AdvanciumPlaceholders extends PlaceholderExpansion {
    private final Advancium plugin;
    AdvanciumPlaceholders(Advancium plugin) { this.plugin = plugin; }
    @Override public @NotNull String getIdentifier() { return "advancium"; }
    @Override public @NotNull String getAuthor() { return "MintyMC"; }
    @Override public @NotNull String getVersion() { return "1.0.1"; }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        ProgressStore.Record record = plugin.entry(player.getUniqueId());
        int count = record == null ? 0 : record.done();
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "done" -> String.valueOf(count);
            case "total" -> String.valueOf(plugin.total());
            case "percent" -> plugin.percent(count) + "%";
            case "rank" -> plugin.position(player.getUniqueId()) == 0 ? "-" : String.valueOf(plugin.position(player.getUniqueId()));
            default -> null;
        };
    }
}
