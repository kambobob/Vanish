package me.vanish;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * PlaceholderAPI expansion so your tab list / scoreboard / chat plugin can show counts
 * that ignore vanished players.
 */
public final class VanishExpansion extends PlaceholderExpansion {

    private final VanishPlugin plugin;

    public VanishExpansion(VanishPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "vanish";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Vanish";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "online" -> String.valueOf(plugin.getVisibleOnlineCount());
            case "staffonline" -> String.valueOf(plugin.getVisibleStaffCount());
            case "vanished" -> String.valueOf(plugin.getVanishedCount());
            case "isvanished" -> String.valueOf(player != null && plugin.isVanished(player.getUniqueId()));
            default -> null;
        };
    }
}
