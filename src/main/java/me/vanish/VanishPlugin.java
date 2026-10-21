package me.vanish;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Folia-compatible vanish plugin.
 *
 * /vanish or /v                -> toggle your own vanish
 * /vanish <player>             -> toggle vanish for another player (vanish.others)
 *
 * While vanished a player is hidden from everyone without vanish.see, ignored by mobs,
 * cannot pick up items or take damage, cannot be @pinged in chat, cannot be private
 * messaged, and is NOT counted by the PlaceholderAPI placeholders below.
 *
 * Placeholders (need PlaceholderAPI):
 *   %vanish_online%       online players, vanished ones excluded
 *   %vanish_staffonline%  online staff, vanished ones excluded
 *   %vanish_vanished%     number of vanished players
 *   %vanish_isvanished%   true/false for the player the placeholder is parsed for
 *
 * Every Player operation runs on that player's own entity scheduler, as Folia requires.
 */
public final class VanishPlugin extends JavaPlugin implements Listener {

    private static final String SEE_PERMISSION = "vanish.see";
    private static final String OTHERS_PERMISSION = "vanish.others";

    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> previousFlight = new ConcurrentHashMap<>();
    private volatile Set<String> blockedCommands = new HashSet<>();
    private volatile String staffPermission = "vanish.use";
    private volatile boolean fakeMessages = true;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            registerPlaceholders();
        }

        getLogger().info("Vanish enabled (" + vanished.size() + " vanished players remembered).");
    }

    private void registerPlaceholders() {
        new VanishExpansion(this).register();
        getLogger().info("PlaceholderAPI found: registered %vanish_...% placeholders.");
    }

    private void loadSettings() {
        reloadConfig();
        staffPermission = getConfig().getString("staff-permission", "vanish.use");
        fakeMessages = getConfig().getBoolean("fake-messages", true);

        Set<String> cmds = new HashSet<>();
        for (String s : getConfig().getStringList("blocked-commands")) {
            cmds.add(s.toLowerCase(Locale.ROOT));
        }
        blockedCommands = cmds;
    }

    // ------------------------------------------------------------------ public API

    public boolean isVanished(UUID id) {
        return vanished.contains(id);
    }

    public boolean isVanished(Player player) {
        return vanished.contains(player.getUniqueId());
    }

    public int getVanishedCount() {
        return vanished.size();
    }

    /** Online players that are not vanished. */
    public int getVisibleOnlineCount() {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!vanished.contains(p.getUniqueId())) count++;
        }
        return count;
    }

    /** Online staff (players with the configured staff permission) that are not vanished. */
    public int getVisibleStaffCount() {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (vanished.contains(p.getUniqueId())) continue;
            if (p.hasPermission(staffPermission)) count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final Player target;

        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text("Usage: /" + label + " <player>", NamedTextColor.RED));
                return true;
            }
            target = self;
        } else {
            if (!sender.hasPermission(OTHERS_PERMISSION)) {
                sender.sendMessage(Component.text("You don't have permission to vanish other players.",
                        NamedTextColor.RED));
                return true;
            }
            Player found = Bukkit.getPlayerExact(args[0]);
            if (found == null) {
                sender.sendMessage(Component.text("Player '" + args[0] + "' is not online.",
                        NamedTextColor.RED));
                return true;
            }
            target = found;
        }

        // The target's state must be changed on the target's own thread (Folia).
        target.getScheduler().run(this, task -> toggle(target, sender), null);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission(OTHERS_PERMISSION)) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(p.getName());
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ core logic

    /** Runs on the target player's own thread. */
    private void toggle(Player target, CommandSender requester) {
        boolean nowVanished;
        if (vanished.contains(target.getUniqueId())) {
            unvanish(target);
            nowVanished = false;
        } else {
            vanish(target);
            nowVanished = true;
        }

        target.sendMessage(Component.text(
                nowVanished ? "You are now vanished." : "You are now visible.", NamedTextColor.GREEN));

        boolean requesterIsTarget = requester instanceof Player rp
                && rp.getUniqueId().equals(target.getUniqueId());
        if (!requesterIsTarget) {
            requester.sendMessage(Component.text(
                    target.getName() + " is now " + (nowVanished ? "vanished." : "visible."),
                    NamedTextColor.GREEN));
        }
    }

    private void vanish(Player player) {
        UUID id = player.getUniqueId();
        vanished.add(id);
        previousFlight.put(id, player.getAllowFlight());
        saveData();

        applyVanishState(player);
        player.setFlying(true);

        if (fakeMessages) {
            broadcast(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW,
                    Component.text(player.getName())));
        }
    }

    private void unvanish(Player player) {
        UUID id = player.getUniqueId();
        vanished.remove(id);
        Boolean prev = previousFlight.remove(id);
        saveData();
        setVanishFlag(player, false);

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

        if (fakeMessages) {
            broadcast(Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW,
                    Component.text(player.getName())));
        }
    }

    /** Applies the vanished state to a player. Must run on that player's thread. */
    private void applyVanishState(Player player) {
        setVanishFlag(player, true);
        player.setAllowFlight(true);
        player.setCollidable(false);
        player.setAffectsSpawning(false);
        player.setSleepingIgnored(true);

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player) || other.hasPermission(SEE_PERMISSION)) continue;
            other.getScheduler().run(this, task -> other.hidePlayer(this, player), null);
        }
    }

    /** Marks the player as vanished for other plugins such as TAB. */
    private void setVanishFlag(Player player, boolean isVanished) {
        if (isVanished) {
            player.setMetadata("vanished", new FixedMetadataValue(this, true));
        } else {
            player.removeMetadata("vanished", this);
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

    /**
     * Stops @pings of vanished players. The "@" in front of a vanished player's name is
     * removed before any chat plugin sees the message, so no highlight or sound is triggered.
     * Players with vanish.see can still ping them.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        if (sender.hasPermission(SEE_PERMISSION)) return;

        Component message = event.message();
        boolean changed = false;

        for (UUID id : vanished) {
            Player hidden = Bukkit.getPlayer(id);
            if (hidden == null || hidden.equals(sender)) continue;

            String name = hidden.getName();
            message = message.replaceText(TextReplacementConfig.builder()
                    .match(Pattern.compile("@" + Pattern.quote(name), Pattern.CASE_INSENSITIVE))
                    .replacement(name)
                    .build());
            changed = true;
        }

        if (changed) {
            event.message(message);
        }
    }

    /** Vanished players look offline to /msg, /tell, /w, ... from players without vanish.see. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        if (sender.hasPermission(SEE_PERMISSION)) return;

        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2) return;

        String cmd = parts[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (!blockedCommands.contains(cmd)) return;

        for (UUID id : vanished) {
            Player hidden = Bukkit.getPlayer(id);
            if (hidden != null && !hidden.equals(sender) && hidden.getName().equalsIgnoreCase(parts[1])) {
                event.setCancelled(true);
                sender.sendMessage(Component.translatable("argument.entity.notfound.player",
                        NamedTextColor.RED));
                return;
            }
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

    /** Vanished players take no damage of any kind. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
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
