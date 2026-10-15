package me.vanish;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Folia-compatible vanish plugin.
 * /vanish or /v toggles vanish: fake leave/join message, hidden from tab list and
 * from every player's view, flight enabled, mobs ignore you, no item pickup.
 *
 * Every Player operation runs on that player's own entity scheduler, as Folia requires.
 */
public final class VanishPlugin extends JavaPlugin implements Listener {

    private static final String SEE_PERMISSION = "vanish.see";

    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> previousFlight = new ConcurrentHashMap<>();
    private File dataFile;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Vanish enabled (" + vanished.size() + " vanished players remembered).");
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use /vanish.", NamedTextColor.RED));
            return true;
        }
        if (vanished.contains(player.getUniqueId())) {
            unvanish(player);
            player.sendMessage(Component.text("You are now visible.", NamedTextColor.GREEN));
        } else {
            vanish(player);
            player.sendMessage(Component.text("You are now vanished.", NamedTextColor.GREEN));
        }
        return true;
    }

    // ------------------------------------------------------------------ core logic

    /** Runs on the player's own thread (command execution). */
    private void vanish(Player player) {
        UUID id = player.getUniqueId();
        vanished.add(id);
        previousFlight.put(id, player.getAllowFlight());
        saveData();

        applyVanishState(player);
        player.setFlying(true);

        broadcast(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW,
                Component.text(player.getName())));
    }

    /** Runs on the player's own thread (command execution). */
    private void unvanish(Player player) {
        UUID id = player.getUniqueId();
        vanished.remove(id);
        Boolean prev = previousFlight.remove(id);
        saveData();

        GameMode mode = player.getGameMode();
        boolean keepFlight = (prev != null && prev)
                || mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
        player.setAllowFlight(keepFlight);
        player.setCollidable(true);
        player.setAffectsSpawning(true);
        player.setSleepingIgnored(false);

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) continue;
            other.getScheduler().run(this, task -> other.showPlayer(this, player), null);
        }

        broadcast(Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW,
                Component.text(player.getName())));
    }

    /** Applies the vanished state to a player. Must run on that player's thread. */
    private void applyVanishState(Player player) {
        player.setAllowFlight(true);
        player.setCollidable(false);
        player.setAffectsSpawning(false);
        player.setSleepingIgnored(true);

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player) || other.hasPermission(SEE_PERMISSION)) continue;
            other.getScheduler().run(this, task -> other.hidePlayer(this, player), null);
        }
    }

    private void broadcast(Component message) {
        Bukkit.getServer().broadcast(message);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player joiner = event.getPlayer();

        // Hide every currently vanished player from the person joining.
        if (!joiner.hasPermission(SEE_PERMISSION)) {
            for (UUID id : vanished) {
                Player hidden = Bukkit.getPlayer(id);
                if (hidden != null && !hidden.equals(joiner)) {
                    joiner.hidePlayer(this, hidden);
                }
            }
        }

        // The joiner was vanished when they left: rejoin silently and stay vanished.
        if (vanished.contains(joiner.getUniqueId())) {
            event.joinMessage(null);
            applyVanishState(joiner);
            joiner.sendMessage(Component.text("You are still vanished.", NamedTextColor.GRAY));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && vanished.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && vanished.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ persistence

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : yaml.getStringList("vanished")) {
            try {
                vanished.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
    }

    private synchronized void saveData() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : vanished) ids.add(id.toString());
        yaml.set("vanished", ids);
        try {
            getDataFolder().mkdirs();
            yaml.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }
}
