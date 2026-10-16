package com.jalebismp.nametag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class NametagPlugin extends JavaPlugin implements Listener {

    private static final String TEAM_NAME = "jalebi_nt";

    // GUI slots
    private static final int SLOT_NAME_ICON = 10, SLOT_NAME_TOGGLE = 19;
    private static final int SLOT_MONEY_ICON = 13, SLOT_MONEY_TOGGLE = 22;
    private static final int SLOT_HEARTS_ICON = 16, SLOT_HEARTS_TOGGLE = 25;

    private final Map<UUID, Settings> settings = new HashMap<>();
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private Economy economy;
    private File dataFile;
    private Team hiddenNameTeam;

    /** Per-player toggles. Everything is ON by default. */
    private static class Settings {
        boolean name = true, money = true, hearts = true;
    }

    /** Marker holder so we can recognise our own GUI. */
    private static class NametagGui implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        setupEconomy();
        setupTeam();

        Bukkit.getPluginManager().registerEvents(this, this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            hiddenNameTeam.addEntry(p.getName());
        }

        // Refresh hearts / money regularly (every 10 ticks = 0.5s)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                update(p);
            }
        }, 20L, 10L);

        getLogger().info("JalebiNametag enabled" + (economy == null ? " (Vault economy not found - money line disabled)" : "") + ".");
    }

    @Override
    public void onDisable() {
        for (TextDisplay d : displays.values()) {
            if (d.isValid()) d.remove();
        }
        displays.clear();
        if (hiddenNameTeam != null) {
            try {
                hiddenNameTeam.unregister();
            } catch (IllegalStateException ignored) {
            }
        }
        saveData();
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void setupTeam() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(TEAM_NAME);
        if (team == null) team = sb.registerNewTeam(TEAM_NAME);
        // Hide the vanilla nametag - we draw the username ourselves so it can be toggled.
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        hiddenNameTeam = team;
    }

    // ------------------------------------------------------------------ persistence

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = yml.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Settings s = new Settings();
                s.name = sec.getBoolean(key + ".name", true);
                s.money = sec.getBoolean(key + ".money", true);
                s.hearts = sec.getBoolean(key + ".hearts", true);
                settings.put(id, s);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Settings> e : settings.entrySet()) {
            String base = "players." + e.getKey();
            yml.set(base + ".name", e.getValue().name);
            yml.set(base + ".money", e.getValue().money);
            yml.set(base + ".hearts", e.getValue().hearts);
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    private Settings settings(Player p) {
        return settings.computeIfAbsent(p.getUniqueId(), k -> new Settings());
    }

    // ------------------------------------------------------------------ overhead display

    private void update(Player p) {
        Settings s = settings(p);
        boolean visible = p.isValid() && !p.isDead()
                && p.getGameMode() != GameMode.SPECTATOR
                && !p.isInvisible();

        List<Component> lines = new ArrayList<>();
        if (visible) {
            if (s.name) {
                lines.add(Component.text(p.getName(), NamedTextColor.WHITE));
            }
            if (s.hearts) {
                lines.add(Component.text("\u2764 ", NamedTextColor.RED)
                        .append(Component.text(formatHearts(p.getHealth()), NamedTextColor.WHITE)));
            }
            if (s.money && economy != null) {
                lines.add(Component.text("$ ", NamedTextColor.GREEN)
                        .append(Component.text(formatMoney(economy.getBalance(p)), NamedTextColor.WHITE)));
            }
        }

        if (lines.isEmpty()) {
            removeDisplay(p);
            return;
        }

        TextDisplay d = displays.get(p.getUniqueId());
        if (d == null || !d.isValid() || !p.getPassengers().contains(d)) {
            if (d != null && d.isValid()) d.remove();
            d = spawnDisplay(p);
            displays.put(p.getUniqueId(), d);
        }
        d.text(Component.join(JoinConfiguration.newlines(), lines));
    }

    private TextDisplay spawnDisplay(Player p) {
        TextDisplay d = p.getWorld().spawn(p.getLocation(), TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(false);
            // Raise the text a little so it floats above the head.
            // Increase the 0.25f if you want it higher, decrease to lower it.
            display.setTransformation(new Transformation(
                    new Vector3f(0f, 0.25f, 0f),
                    new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f),
                    new AxisAngle4f()));
        });
        p.addPassenger(d);
        // The owner doesn't see their own tag (just like vanilla nametags).
        p.hideEntity(this, d);
        return d;
    }

    private void removeDisplay(Player p) {
        TextDisplay d = displays.remove(p.getUniqueId());
        if (d != null && d.isValid()) d.remove();
    }

    private static final String[] MONEY_SUFFIXES = {"", "K", "M", "B", "T", "Qa", "Qi"};

    /** 999 -> 999, 1000 -> 1K, 1500 -> 1.5K, 1,000,000 -> 1M, 2,350,000,000 -> 2.35B ... */
    private String formatMoney(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) return "0";

        boolean negative = amount < 0;
        BigDecimal value = BigDecimal.valueOf(Math.abs(amount));

        int index = 0;
        while (value.compareTo(BigDecimal.valueOf(1000)) >= 0 && index < MONEY_SUFFIXES.length - 1) {
            value = value.movePointLeft(3);
            index++;
        }

        // Exact math: cut (not round) to 2 decimals, then drop trailing zeros.
        // 1000 -> 1K, 1123 -> 1.12K, 1150 -> 1.15K, 999,999 -> 999.99K
        String number = value.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        return (negative ? "-" : "") + number + MONEY_SUFFIXES[index];
    }

    private String formatHearts(double health) {
        double hearts = health / 2.0;
        if (hearts == Math.floor(hearts)) return String.valueOf((int) hearts);
        return String.format(Locale.US, "%.1f", hearts);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        hiddenNameTeam.addEntry(e.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeDisplay(e.getPlayer());
        hiddenNameTeam.removeEntry(e.getPlayer().getName());
        saveData();
    }

    // ------------------------------------------------------------------ command + GUI

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        openGui(p);
        return true;
    }

    private Component title() {
        return Component.text("JALEBI", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" SMP", NamedTextColor.WHITE, TextDecoration.BOLD));
    }

    private void openGui(Player p) {
        NametagGui holder = new NametagGui();
        Inventory inv = Bukkit.createInventory(holder, 27, title());
        holder.inventory = inv;
        fillGui(inv, settings(p));
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    private void fillGui(Inventory inv, Settings s) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of(), false);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        inv.setItem(SLOT_NAME_ICON, icon(Material.NAME_TAG, "Username", s.name, "Show your username above your head."));
        inv.setItem(SLOT_NAME_TOGGLE, toggleItem(s.name));

        String moneyDesc = economy == null
                ? "Vault economy not found!"
                : "Show your money above your head.";
        inv.setItem(SLOT_MONEY_ICON, icon(Material.GOLD_INGOT, "Money", s.money, moneyDesc));
        inv.setItem(SLOT_MONEY_TOGGLE, toggleItem(s.money));

        inv.setItem(SLOT_HEARTS_ICON, icon(Material.APPLE, "Hearts", s.hearts, "Show your hearts above your head."));
        inv.setItem(SLOT_HEARTS_TOGGLE, toggleItem(s.hearts));
    }

    private ItemStack icon(Material mat, String name, boolean on, String desc) {
        List<Component> lore = new ArrayList<>();
        lore.add(plain(desc, NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(status(on));
        lore.add(plain("Click to toggle", NamedTextColor.DARK_GRAY));
        return item(mat, Component.text(name, NamedTextColor.GOLD, TextDecoration.BOLD), lore, on);
    }

    private ItemStack toggleItem(boolean on) {
        return item(on ? Material.LIME_DYE : Material.GRAY_DYE,
                status(on),
                List.of(plain("Click to toggle", NamedTextColor.DARK_GRAY)),
                false);
    }

    private Component status(boolean on) {
        return on
                ? Component.text("ENABLED", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                : Component.text("DISABLED", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    private Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material mat, Component name, List<Component> lore, boolean glow) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof NametagGui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        Settings s = settings(p);
        switch (e.getSlot()) {
            case SLOT_NAME_ICON, SLOT_NAME_TOGGLE -> s.name = !s.name;
            case SLOT_MONEY_ICON, SLOT_MONEY_TOGGLE -> s.money = !s.money;
            case SLOT_HEARTS_ICON, SLOT_HEARTS_TOGGLE -> s.hearts = !s.hearts;
            default -> {
                return;
            }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.3f);
        fillGui(e.getView().getTopInventory(), s);
        update(p);
        saveData();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof NametagGui) {
            e.setCancelled(true);
        }
    }
}
