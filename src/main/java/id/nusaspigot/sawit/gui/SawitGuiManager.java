package id.nusaspigot.sawit.gui;

import id.nusaspigot.sawit.config.SawitConfig;
import id.nusaspigot.sawit.hook.VaultEconomyHook;
import id.nusaspigot.sawit.item.SawitItemFactory;
import id.nusaspigot.sawit.item.SawitItemType;
import id.nusaspigot.sawit.model.PlayerSawitData;
import id.nusaspigot.sawit.service.PalmFarmManager;
import id.nusaspigot.sawit.service.SawitEconomyService;
import id.nusaspigot.sawit.service.WoodQuestTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Interactive 54-slot Plantation Dashboard, Koperasi Market, CPO Mill, and Shop GUI (/sawit).
 */
public final class SawitGuiManager implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final int SLOT_LICENSE_INFO = 4;
    private static final int SLOT_SELL_ALL = 20;
    private static final int SLOT_CPO_MILL = 22;
    private static final int SLOT_UPGRADE_SLOTS = 24;
    private static final int SLOT_CLAIM_SEEDS = 13;

    private static final int SLOT_BUY_SEED = 38;
    private static final int SLOT_BUY_PUPUK = 39;
    private static final int SLOT_BUY_EGREK_1 = 40;
    private static final int SLOT_BUY_EGREK_2 = 41;
    private static final int SLOT_BUY_CRATE = 42;
    private static final int SLOT_ADMIN_PANEL = 53;

    private final Supplier<SawitConfig> configSupplier;
    private final Function<Player, PlayerSawitData> playerDataProvider;
    private final PalmFarmManager farmManager;
    private final SawitEconomyService economyService;
    private final VaultEconomyHook economyHook;
    private final SawitItemFactory itemFactory;
    private final NumberFormat intFormat;
    private java.util.function.Consumer<Player> adminMenuOpener;

    public SawitGuiManager(
        Supplier<SawitConfig> configSupplier,
        Function<Player, PlayerSawitData> playerDataProvider,
        PalmFarmManager farmManager,
        SawitEconomyService economyService,
        VaultEconomyHook economyHook,
        SawitItemFactory itemFactory
    ) {
        this.configSupplier = configSupplier;
        this.playerDataProvider = playerDataProvider;
        this.farmManager = farmManager;
        this.economyService = economyService;
        this.economyHook = economyHook;
        this.itemFactory = itemFactory;
        this.intFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"));
    }

    public void setAdminMenuOpener(java.util.function.Consumer<Player> adminMenuOpener) {
        this.adminMenuOpener = adminMenuOpener;
    }

    public static final class SawitMenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void openMainMenu(Player player) {
        SawitConfig cfg = configSupplier.get();
        PlayerSawitData data = playerDataProvider.apply(player);

        SawitMenuHolder holder = new SawitMenuHolder();
        Inventory inv = Bukkit.createInventory(
            holder,
            54,
            MM.deserialize("<dark_green><bold>Perkebunan NusaSawit</bold></dark_green>")
        );
        holder.inventory = inv;

        // Fill decorative borders
        ItemStack borderGreen = icon(Material.GREEN_STAINED_GLASS_PANE, "<dark_gray> ", List.of());
        ItemStack borderGray = icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray> ", List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) ? borderGreen : borderGray);
        }

        // 1. License & Plantation Status Card
        int ownedPalms = farmManager.countOwnedPalms(player.getUniqueId());
        int ripePalms = farmManager.countRipeOwnedPalms(player.getUniqueId());
        int maxSlots = cfg.maxTotalSlots(data.bonusSlots());
        String statusBadge = data.isLicenseUnlocked()
            ? "<green><bold>TERBUKA ✔</bold></green>"
            : "<red><bold>TERKUNCI ✖</bold></red>";

        List<String> statusLore = new ArrayList<>();
        statusLore.add("<dark_gray>Statistik Pekebun Sawit");
        statusLore.add("");
        statusLore.add("<gray>Status Lisensi: " + statusBadge);
        statusLore.add("<gray>Tebang Kayu Alami: <yellow>" + intFormat.format(data.woodBroken())
            + "<gray>/</gray>" + intFormat.format(cfg.requiredBlocks()) + "</yellow>");
        statusLore.add("<dark_gray>[" + WoodQuestTracker.buildProgressBar(data.woodBroken(), cfg.requiredBlocks(), 16) + "<dark_gray>]");
        if (cfg.milestoneInterval() > 0 && data.isLicenseUnlocked()) {
            long rem = cfg.milestoneInterval() - ((Math.max(0L, data.woodBroken() - cfg.requiredBlocks())) % cfg.milestoneInterval());
            statusLore.add("<gray>Bonus Bibit Berikutnya: <aqua>" + intFormat.format(rem) + " kayu lagi</aqua>");
        }
        statusLore.add("");
        statusLore.add("<gray>Pohon Tertanam: <gold>" + ownedPalms + "/" + maxSlots + "</gold> <gray>(Siap Panen: <green>" + ripePalms + "</green>)</gray>");
        statusLore.add("<gray>Total Panen: <yellow>" + intFormat.format(data.totalHarvests()) + "x</yellow>");
        statusLore.add("<gray>Total Pendapatan: <gold>" + economyHook.format(cfg.currencySymbol(), data.totalEarnings()) + "</gold>");
        inv.setItem(SLOT_LICENSE_INFO, icon(
            data.isLicenseUnlocked() ? Material.OAK_LOG : Material.IRON_AXE,
            "<gold><bold>✦ Sertifikat & Statistik Kebun</bold></gold>",
            statusLore
        ));

        // 2. Pending Seeds Claim Button (if any)
        if (data.pendingSeeds() > 0) {
            inv.setItem(SLOT_CLAIM_SEEDS, decorateClone(
                itemFactory.create(SawitItemType.SAWIT_SEED, Math.min(64, data.pendingSeeds())),
                "<green><bold>★ Klaim Bibit Tertunda (" + data.pendingSeeds() + "x)</bold></green>",
                List.of(
                    "<gray>Kamu memiliki hadiah bibit sawit",
                    "<gray>yang belum diambil!",
                    "",
                    "<yellow>Klik untuk mengambil ke inventory!"
                )
            ));
        }

        // 3. Koperasi Sawit (Sell All Harvest)
        int invTbs = itemFactory.countInInventory(player, SawitItemType.SAWIT_TBS);
        int invSuper = itemFactory.countInInventory(player, SawitItemType.SAWIT_TBS_SUPER);
        int invCpo = itemFactory.countInInventory(player, SawitItemType.SAWIT_CPO);
        double estValue = (invTbs * cfg.sellPriceTbs()) + (invSuper * cfg.sellPriceTbsSuper()) + (invCpo * cfg.sellPriceCpo());

        inv.setItem(SLOT_SELL_ALL, decorateClone(
            itemFactory.create(SawitItemType.SAWIT_TBS, 1),
            "<gradient:#ff7b00:#ffd000><bold>✦ Koperasi Jual Hasil Sawit</bold></gradient>",
            List.of(
                "<dark_gray>Penjualan Instan ke Koperasi",
                "",
                "<gray>Harga Beli Saat Ini:",
                " <dark_gray>• <white>TBS Segar: <gold>" + economyHook.format(cfg.currencySymbol(), cfg.sellPriceTbs()) + "</gold> <gray>(Punya: " + invTbs + ")</gray>",
                " <dark_gray>• <yellow>TBS Tenera Super: <gold>" + economyHook.format(cfg.currencySymbol(), cfg.sellPriceTbsSuper()) + "</gold> <gray>(Punya: " + invSuper + ")</gray>",
                " <dark_gray>• <gold>Minyak CPO: <gold>" + economyHook.format(cfg.currencySymbol(), cfg.sellPriceCpo()) + "</gold> <gray>(Punya: " + invCpo + ")</gray>",
                "",
                "<gray>Estimasi Nilai Jual Tasmu: <green><bold>" + economyHook.format(cfg.currencySymbol(), estValue) + "</bold></green>",
                "",
                "<yellow>Klik untuk menjual semua hasil panen!"
            )
        ));

        // 4. Kilang Pengolahan CPO
        inv.setItem(SLOT_CPO_MILL, decorateClone(
            itemFactory.create(SawitItemType.SAWIT_CPO, 1),
            "<gradient:#f59e0b:#fef08a><bold>✦ Kilang Pengolahan CPO</bold></gradient>",
            List.of(
                "<dark_gray>Ekstraksi Minyak Kelapa Sawit",
                "",
                "<gray>Olah tandan buah segar menjadi",
                "<gray>Minyak CPO bernilai jual jauh lebih tinggi!",
                "",
                "<gray>Resep Olah: <yellow>" + cfg.tbsPerCpo() + "x TBS Segar</yellow> <dark_gray>→ <gold>1x Minyak CPO</gold>",
                "<gray>Biaya Giling: <gold>" + economyHook.format(cfg.currencySymbol(), cfg.cpoProcessingFee()) + "</gold>",
                "<gray>Harga Jual CPO: <green>" + economyHook.format(cfg.currencySymbol(), cfg.sellPriceCpo()) + "/botol</green>",
                "",
                "<yellow>Klik Kiri <dark_gray>- <green>Olah 1 Botol CPO",
                "<gold>Shift + Klik <dark_gray>- <green>Olah Semua TBS di Tas"
            )
        ));

        // 5. Plantation Slot Upgrade
        boolean maxedSlots = data.bonusSlots() >= cfg.maxBonusSlots();
        List<String> slotLore = new ArrayList<>();
        slotLore.add("<dark_gray>Ekspansi Lahan Perkebunan");
        slotLore.add("");
        slotLore.add("<gray>Kapasitas Saat Ini: <gold>" + maxSlots + " Pohon</gold>");
        slotLore.add("<gray>Upgrade Terpakai: <aqua>" + data.bonusSlots() + "/" + cfg.maxBonusSlots() + "</aqua>");
        slotLore.add("");
        if (maxedSlots) {
            slotLore.add("<green><bold>✔ KAPASITAS MAKSIMAL TERCAPAI</bold></green>");
        } else {
            slotLore.add("<gray>Biaya +1 Slot Kebun: <gold>" + economyHook.format(cfg.currencySymbol(), cfg.nextSlotUpgradeCost(data.bonusSlots())) + "</gold>");
            slotLore.add("");
            slotLore.add("<yellow>Klik untuk menambah +1 batas tanam sawit!");
        }
        inv.setItem(SLOT_UPGRADE_SLOTS, icon(Material.GRASS_BLOCK, "<green><bold>✦ Upgrade Slot Kebun Sawit</bold></green>", slotLore));

        // 6. Plantation Shop Row
        inv.setItem(SLOT_BUY_SEED, buildShopIcon(SawitItemType.SAWIT_SEED, cfg.shopPriceSeed(), cfg.currencySymbol(), true, !data.isLicenseUnlocked()));
        inv.setItem(SLOT_BUY_PUPUK, buildShopIcon(SawitItemType.PUPUK_NPK, cfg.shopPricePupuk(), cfg.currencySymbol(), true, false));
        inv.setItem(SLOT_BUY_EGREK_1, buildShopIcon(SawitItemType.EGREK_SAWIT, cfg.shopPriceEgrekTier1(), cfg.currencySymbol(), false, false));
        inv.setItem(SLOT_BUY_EGREK_2, buildShopIcon(SawitItemType.EGREK_MASTER, cfg.shopPriceEgrekTier2(), cfg.currencySymbol(), false, false));
        inv.setItem(SLOT_BUY_CRATE, buildShopIcon(SawitItemType.SAWIT_CRATE, cfg.shopPriceCrate(), cfg.currencySymbol(), true, false));

        if (player.hasPermission("nusasawit.admin") && adminMenuOpener != null) {
            inv.setItem(SLOT_ADMIN_PANEL, icon(
                Material.COMPARATOR,
                "<red><bold>✦ Panel Pengaturan Admin</bold></red>",
                List.of(
                    "<dark_gray>Khusus Administrator",
                    "",
                    "<gray>Atur harga jual TBS/CPO, harga toko,",
                    "<gray>batas slot, serta target tebang kayu.",
                    "",
                    "<yellow>Klik untuk membuka Admin GUI!"
                )
            ));
        }

        player.openInventory(inv);
    }

    private ItemStack buildShopIcon(SawitItemType type, double price, String symbol, boolean allowBulk, boolean lockedLicense) {
        ItemStack base = itemFactory.create(type, 1);
        List<String> extraLore = new ArrayList<>(type.loreMiniMessage());
        extraLore.add("");
        extraLore.add("<gray>Harga Satuan: <gold><bold>" + economyHook.format(symbol, price) + "</bold></gold>");
        if (lockedLicense) {
            extraLore.add("<red>✖ Terkunci: Selesaikan 20.000 Kayu Oak dulu!</red>");
        } else {
            extraLore.add("<yellow>Klik Kiri <dark_gray>- <green>Beli 1x");
            if (allowBulk) {
                extraLore.add("<gold>Shift + Klik <dark_gray>- <green>Beli 8x (" + economyHook.format(symbol, price * 8) + ")");
            }
        }
        return decorateClone(base, type.displayMiniMessage(), extraLore);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SawitMenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        boolean shift = event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;

        switch (slot) {
            case SLOT_CLAIM_SEEDS -> {
                PlayerSawitData data = playerDataProvider.apply(player);
                if (data.pendingSeeds() > 0) {
                    int claimed = data.takePendingSeeds(64);
                    itemFactory.giveOrDrop(player, SawitItemType.SAWIT_SEED, claimed);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.1f);
                    openMainMenu(player);
                }
            }
            case SLOT_SELL_ALL -> {
                if (economyService.sellAllHarvest(player)) {
                    openMainMenu(player);
                }
            }
            case SLOT_CPO_MILL -> {
                if (economyService.refineToCpo(player, shift)) {
                    openMainMenu(player);
                }
            }
            case SLOT_UPGRADE_SLOTS -> {
                if (economyService.upgradePlantationSlot(player)) {
                    openMainMenu(player);
                }
            }
            case SLOT_BUY_SEED -> {
                if (economyService.buyShopItem(player, SawitItemType.SAWIT_SEED, shift ? 8 : 1)) {
                    openMainMenu(player);
                }
            }
            case SLOT_BUY_PUPUK -> {
                if (economyService.buyShopItem(player, SawitItemType.PUPUK_NPK, shift ? 8 : 1)) {
                    openMainMenu(player);
                }
            }
            case SLOT_BUY_EGREK_1 -> {
                if (economyService.buyShopItem(player, SawitItemType.EGREK_SAWIT, 1)) {
                    openMainMenu(player);
                }
            }
            case SLOT_BUY_EGREK_2 -> {
                if (economyService.buyShopItem(player, SawitItemType.EGREK_MASTER, 1)) {
                    openMainMenu(player);
                }
            }
            case SLOT_BUY_CRATE -> {
                if (economyService.buyShopItem(player, SawitItemType.SAWIT_CRATE, shift ? 8 : 1)) {
                    openMainMenu(player);
                }
            }
            case SLOT_ADMIN_PANEL -> {
                if (player.hasPermission("nusasawit.admin") && adminMenuOpener != null) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.1f);
                    adminMenuOpener.accept(player);
                }
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SawitMenuHolder) {
            event.setCancelled(true);
        }
    }

    private static ItemStack icon(Material mat, String nameMini, List<String> loreMini) {
        ItemStack stack = new ItemStack(mat);
        return decorateClone(stack, nameMini, loreMini);
    }

    private static ItemStack decorateClone(ItemStack stack, String nameMini, List<String> loreMini) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize(nameMini).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            List<Component> lore = new ArrayList<>(loreMini.size());
            for (String line : loreMini) {
                lore.add(MM.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
