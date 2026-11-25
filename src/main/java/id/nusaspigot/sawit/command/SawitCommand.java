package id.nusaspigot.sawit.command;

import id.nusaspigot.sawit.NusaSawitPlugin;
import id.nusaspigot.sawit.config.SawitConfig;
import id.nusaspigot.sawit.gui.SawitAdminGuiManager;
import id.nusaspigot.sawit.gui.SawitGuiManager;
import id.nusaspigot.sawit.hook.NexoHook;
import id.nusaspigot.sawit.item.SawitItemFactory;
import id.nusaspigot.sawit.item.SawitItemType;
import id.nusaspigot.sawit.model.PlayerSawitData;
import id.nusaspigot.sawit.service.PalmFarmManager;
import id.nusaspigot.sawit.service.SawitEconomyService;
import id.nusaspigot.sawit.service.WoodQuestTracker;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Handles /sawit player and administrator subcommands.
 */
public final class SawitCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final NusaSawitPlugin plugin;
    private final SawitGuiManager guiManager;
    private final SawitAdminGuiManager adminGuiManager;
    private final PalmFarmManager farmManager;
    private final SawitEconomyService economyService;
    private final SawitItemFactory itemFactory;
    private final NexoHook nexoHook;
    private final NumberFormat intFormat;

    public SawitCommand(
        NusaSawitPlugin plugin,
        SawitGuiManager guiManager,
        SawitAdminGuiManager adminGuiManager,
        PalmFarmManager farmManager,
        SawitEconomyService economyService,
        SawitItemFactory itemFactory,
        NexoHook nexoHook
    ) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.adminGuiManager = adminGuiManager;
        this.farmManager = farmManager;
        this.economyService = economyService;
        this.itemFactory = itemFactory;
        this.nexoHook = nexoHook;
        this.intFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Use /sawit admin ... from console.");
                return true;
            }
            guiManager.openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "gui", "shop" -> {
                if (sender instanceof Player player) {
                    guiManager.openMainMenu(player);
                }
                return true;
            }
            case "sell", "jual" -> {
                if (sender instanceof Player player) {
                    economyService.sellAllHarvest(player);
                }
                return true;
            }
            case "claim", "klaim" -> {
                if (sender instanceof Player player) {
                    PlayerSawitData data = plugin.getPlayerData(player.getUniqueId());
                    if (data.pendingSeeds() <= 0) {
                        player.sendMessage(MM.deserialize("<yellow>[NusaSawit]</yellow> <gray>Tidak ada bibit tertunda untuk diklaim.</gray>"));
                        return true;
                    }
                    int claimed = data.takePendingSeeds(64);
                    itemFactory.giveOrDrop(player, SawitItemType.SAWIT_SEED, claimed);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.1f);
                    player.sendMessage(MM.deserialize("<green>[NusaSawit]</green> <gray>Berhasil mengklaim <gold>" + claimed + "x Bibit Sawit Tenera</gold>!</gray>"));
                }
                return true;
            }
            case "progress", "info", "stats" -> {
                if (sender instanceof Player player) {
                    sendProgressSummary(player);
                }
                return true;
            }
            case "admin" -> {
                if (!sender.hasPermission("nusasawit.admin")) {
                    sender.sendMessage(MM.deserialize("<red>[NusaSawit] Kamu tidak memiliki izin admin!</red>"));
                    return true;
                }
                handleAdmin(sender, args);
                return true;
            }
            default -> {
                sender.sendMessage(MM.deserialize(
                    "<gold>[NusaSawit]</gold> <gray>Gunakan: <yellow>/sawit</yellow>, <yellow>/sawit sell</yellow>, <yellow>/sawit progress</yellow>, <yellow>/sawit claim</yellow></gray>"
                ));
                return true;
            }
        }
    }

    private void sendProgressSummary(Player player) {
        SawitConfig cfg = plugin.sawitConfig();
        PlayerSawitData data = plugin.getPlayerData(player.getUniqueId());
        int owned = farmManager.countOwnedPalms(player.getUniqueId());
        int ripe = farmManager.countRipeOwnedPalms(player.getUniqueId());
        int maxSlots = cfg.maxTotalSlots(data.bonusSlots());

        player.sendMessage(MM.deserialize("<dark_green><st>                                                    </st></dark_green>"));
        player.sendMessage(MM.deserialize(" <gold><bold>✦ Progres Perkebunan NusaSawit</bold></gold>"));
        player.sendMessage(MM.deserialize(" <gray>Lisensi Tanam: " + (data.isLicenseUnlocked() ? "<green>TERBUKA ✔</green>" : "<red>TERKUNCI ✖</red>")));
        player.sendMessage(MM.deserialize(" <gray>Tebang Kayu Alami: <yellow>" + intFormat.format(data.woodBroken())
            + "<gray>/</gray>" + intFormat.format(cfg.requiredBlocks()) + "</yellow> "
            + "<dark_gray>[" + WoodQuestTracker.buildProgressBar(data.woodBroken(), cfg.requiredBlocks(), 14) + "<dark_gray>]"));
        player.sendMessage(MM.deserialize(" <gray>Kebun Aktif: <gold>" + owned + "/" + maxSlots + " Pohon</gold> <gray>(Siap Panen: <green>" + ripe + "</green>)</gray>"));
        player.sendMessage(MM.deserialize(" <gray>Total Panen: <yellow>" + intFormat.format(data.totalHarvests()) + "x</yellow>"));
        player.sendMessage(MM.deserialize("<dark_green><st>                                                    </st></dark_green>"));
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player player) {
                adminGuiManager.openAdminMenu(player);
                return;
            }
            sender.sendMessage(MM.deserialize(
                "<gold>[NusaSawit Admin]</gold> <gray>Subcommand: <yellow>gui, give, setwood, unlock, addslots, cleanup, syncnexo, reload</yellow></gray>"
            ));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "gui", "menu", "config", "settings" -> {
                if (sender instanceof Player player) {
                    adminGuiManager.openAdminMenu(player);
                } else {
                    sender.sendMessage("Admin GUI can only be opened in-game.");
                }
            }
            case "reload" -> {
                plugin.reloadSawitConfig();
                sender.sendMessage(MM.deserialize("<green>[NusaSawit] Konfigurasi berhasil dimuat ulang!</green>"));
            }
            case "cleanup", "clean", "fixstuck" -> {
                int cleaned = nexoHook.cleanupAllLoadedOrphanPalms(farmManager::hasPalmAt);
                sender.sendMessage(MM.deserialize(
                    "<green>[NusaSawit]</green> <gray>Berhasil membersihkan <gold>" + cleaned
                        + "</gold> entitas model pohon sawit yang nyangkut (orphan) di chunk aktif.</gray>"
                ));
            }
            case "syncnexo" -> {
                int copied = nexoHook.syncToNexoFolder(true);
                farmManager.refreshAllLoadedPalmVisuals();
                sender.sendMessage(MM.deserialize(
                    "<green>[NusaSawit]</green> <gray>Berhasil menyalin ulang <gold>" + copied
                        + "</gold> file tekstur & model 3D ke <yellow>plugins/Nexo/</yellow>! Jalankan <gold>/nexo reload all</gold>.</gray>"
                ));
            }
            case "give" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /sawit admin give <player> <item> [amount]</red>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    sender.sendMessage(MM.deserialize("<red>Player tidak ditemukan atau sedang offline!</red>"));
                    return;
                }
                SawitItemType itemType = SawitItemType.fromId(args[3]);
                if (itemType == null) {
                    sender.sendMessage(MM.deserialize("<red>Item tidak valid! Pilihan: sawit_seed, sawit_tbs, sawit_tbs_super, sawit_cpo, pupuk_npk, egrek_sawit, egrek_master, sawit_crate</red>"));
                    return;
                }
                int amt = 1;
                if (args.length >= 5) {
                    try {
                        amt = Math.max(1, Integer.parseInt(args[4]));
                    } catch (NumberFormatException ignored) {
                    }
                }
                itemFactory.giveOrDrop(target, itemType, amt);
                sender.sendMessage(MM.deserialize(
                    "<green>[NusaSawit]</green> <gray>Memberikan <gold>" + amt + "x</gold> "
                        + itemType.displayMiniMessage() + " <gray>kepada <yellow>" + target.getName() + "</yellow>.</gray>"
                ));
            }
            case "setwood" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /sawit admin setwood <player> <amount></red>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    sender.sendMessage(MM.deserialize("<red>Player tidak ditemukan!</red>"));
                    return;
                }
                long amount;
                try {
                    amount = Math.max(0L, Long.parseLong(args[3]));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(MM.deserialize("<red>Jumlah kayu tidak valid!</red>"));
                    return;
                }
                PlayerSawitData data = plugin.getPlayerData(target.getUniqueId());
                data.setWoodBroken(amount);
                if (amount >= plugin.sawitConfig().requiredBlocks()) {
                    data.setLicenseUnlocked(true);
                }
                sender.sendMessage(MM.deserialize(
                    "<green>[NusaSawit]</green> <gray>Progres kayu <yellow>" + target.getName()
                        + "</yellow> diatur menjadi <gold>" + intFormat.format(amount) + "</gold>.</gray>"
                ));
            }
            case "unlock" -> {
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /sawit admin unlock <player></red>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    sender.sendMessage(MM.deserialize("<red>Player tidak ditemukan!</red>"));
                    return;
                }
                PlayerSawitData data = plugin.getPlayerData(target.getUniqueId());
                data.setWoodBroken(Math.max(data.woodBroken(), plugin.sawitConfig().requiredBlocks()));
                data.setLicenseUnlocked(true);
                sender.sendMessage(MM.deserialize(
                    "<green>[NusaSawit]</green> <gray>Lisensi Perkebunan Sawit untuk <yellow>" + target.getName() + "</yellow> telah dibuka!</gray>"
                ));
            }
            case "addslots" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /sawit admin addslots <player> <amount></red>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    sender.sendMessage(MM.deserialize("<red>Player tidak ditemukan!</red>"));
                    return;
                }
                int delta;
                try {
                    delta = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(MM.deserialize("<red>Angka slot tidak valid!</red>"));
                    return;
                }
                PlayerSawitData data = plugin.getPlayerData(target.getUniqueId());
                data.addBonusSlots(delta);
                sender.sendMessage(MM.deserialize(
                    "<green>[NusaSawit]</green> <gray>Bonus slot kebun <yellow>" + target.getName()
                        + "</yellow> sekarang: <gold>+" + data.bonusSlots() + "</gold>.</gray>"
                ));
            }
            default -> sender.sendMessage(MM.deserialize("<red>Subcommand admin tidak dikenal.</red>"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("menu", "sell", "progress", "claim"));
            if (sender.hasPermission("nusasawit.admin")) {
                base.add("admin");
            }
            return filterPrefix(base, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("nusasawit.admin")) {
            return filterPrefix(List.of("gui", "give", "setwood", "unlock", "addslots", "cleanup", "syncnexo", "reload"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("nusasawit.admin")) {
            String act = args[1].toLowerCase(Locale.ROOT);
            if (List.of("give", "setwood", "unlock", "addslots").contains(act)) {
                return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("give") && sender.hasPermission("nusasawit.admin")) {
            return filterPrefix(Arrays.stream(SawitItemType.values()).map(SawitItemType::nexoId).toList(), args[3]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> candidates, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
            .filter(c -> c.toLowerCase(Locale.ROOT).startsWith(lower))
            .collect(Collectors.toList());
    }
}
