package id.nusaspigot.sawit.gui;

import id.nusaspigot.sawit.NusaSawitPlugin;
import id.nusaspigot.sawit.config.SawitConfig;
import id.nusaspigot.sawit.hook.VaultEconomyHook;
import id.nusaspigot.sawit.item.SawitItemFactory;
import id.nusaspigot.sawit.item.SawitItemType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Interactive Admin Configuration GUI (/sawit admin gui) allowing server admins
 * to customize wood chopping targets, TBS/Super/CPO sell revenue, shop prices,
 * slot limits, and growth durations live in-game via chat input or click steps.
 */
public final class SawitAdminGuiManager implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final int SLOT_BACK = 49;

    public enum SettingKind {
        INTEGER_BLOCKS,
        INTEGER_COUNT,
        INTEGER_SECONDS,
        CURRENCY
    }

    public record SettingEntry(
        int slot,
        String configPath,
        String titleMini,
        List<String> descriptionLines,
        SettingKind kind,
        double stepAmount,
        double minVal,
        Material fallbackIcon,
        SawitItemType customIcon
    ) {}

    private static final List<SettingEntry> SETTINGS = List.of(
        // Row 2: Quest & Plantation Slot Targets
        new SettingEntry(
            10,
            "quest.required-blocks",
            "<gold><bold>✦ Target Kayu Buka Lisensi</bold></gold>",
            List.of("Jumlah blok kayu alami yang wajib", "ditebang untuk membuka izin tanam sawit."),
            SettingKind.INTEGER_BLOCKS,
            1000,
            1,
            Material.OAK_LOG,
            null
        ),
        new SettingEntry(
            11,
            "quest.milestone-interval",
            "<aqua><bold>✦ Interval Milestone Bonus Bibit</bold></aqua>",
            List.of("Setiap kelipatan X tebangan setelah lulus", "lisensi akan menghadiahkan +1 Bibit (0 = mati)."),
            SettingKind.INTEGER_BLOCKS,
            1000,
            0,
            Material.DIAMOND_AXE,
            null
        ),
        new SettingEntry(
            12,
            "farming.slots.base-limit",
            "<green><bold>✦ Batas Pohon Awal per Player</bold></green>",
            List.of("Kapasitas maksimal pohon sawit dasar", "sebelum pemain membeli upgrade slot."),
            SettingKind.INTEGER_COUNT,
            1,
            1,
            Material.GRASS_BLOCK,
            null
        ),
        new SettingEntry(
            13,
            "farming.slots.upgrade-base-cost",
            "<yellow><bold>✦ Biaya Dasar Upgrade Slot</bold></yellow>",
            List.of("Harga awal pembelian +1 kapasitas", "pohon sawit di menu /sawit."),
            SettingKind.CURRENCY,
            2500,
            0,
            Material.GOLD_INGOT,
            null
        ),
        // Growth Timers
        new SettingEntry(
            15,
            "farming.stage-1-seconds",
            "<green><bold>✦ Durasi Tumbuh Tahap 1 → 2</bold></green>",
            List.of("Waktu tumbuh dari Tunas Polybag", "menjadi Pohon Sawit Muda (detik)."),
            SettingKind.INTEGER_SECONDS,
            30,
            5,
            Material.CLOCK,
            null
        ),
        new SettingEntry(
            16,
            "farming.stage-2-seconds",
            "<green><bold>✦ Durasi Tumbuh Tahap 2 → 3</bold></green>",
            List.of("Waktu tumbuh dari Sawit Muda", "menjadi Sawit Dewasa (detik)."),
            SettingKind.INTEGER_SECONDS,
            60,
            5,
            Material.CLOCK,
            null
        ),

        // Row 3: Revenue / Sell Prices (TBS, TBS Super, CPO) & Mill
        new SettingEntry(
            19,
            "economy.sell-prices.sawit-tbs",
            "<gradient:#ff6a1a:#ffb326><bold>✦ Harga Jual TBS Segar</bold></gradient>",
            List.of("Pendapatan uang per 1x Tandan Buah", "Segar (TBS) saat dijual ke Koperasi."),
            SettingKind.CURRENCY,
            50,
            0,
            Material.TORCHFLOWER_SEEDS,
            SawitItemType.SAWIT_TBS
        ),
        new SettingEntry(
            20,
            "economy.sell-prices.sawit-tbs-super",
            "<gradient:#ffd700:#fff280><bold>✦ Harga Jual TBS Tenera Super</bold></gradient>",
            List.of("Pendapatan uang per 1x TBS kualitas", "Super saat dijual ke Koperasi."),
            SettingKind.CURRENCY,
            100,
            0,
            Material.GOLDEN_APPLE,
            SawitItemType.SAWIT_TBS_SUPER
        ),
        new SettingEntry(
            21,
            "economy.sell-prices.sawit-cpo",
            "<gradient:#f59e0b:#fde047><bold>✦ Harga Jual Minyak CPO</bold></gradient>",
            List.of("Pendapatan uang per 1x botol Minyak", "Sawit Mentah (CPO) hasil olahan kilang."),
            SettingKind.CURRENCY,
            100,
            0,
            Material.HONEY_BOTTLE,
            SawitItemType.SAWIT_CPO
        ),
        new SettingEntry(
            22,
            "economy.cpo-mill.tbs-per-cpo",
            "<gold><bold>✦ Kebutuhan TBS per 1 CPO</bold></gold>",
            List.of("Jumlah TBS Segar yang dibutuhkan", "untuk menggiling 1 botol Minyak CPO."),
            SettingKind.INTEGER_COUNT,
            1,
            1,
            Material.BREWING_STAND,
            null
        ),
        new SettingEntry(
            23,
            "economy.cpo-mill.processing-fee",
            "<yellow><bold>✦ Biaya Giling Kilang CPO</bold></yellow>",
            List.of("Biaya pengolahan per 1 botol CPO", "di menu Kilang Pengolahan."),
            SettingKind.CURRENCY,
            25,
            0,
            Material.CAULDRON,
            null
        ),
        new SettingEntry(
            24,
            "farming.ripen-seconds",
            "<gold><bold>✦ Durasi Berbuah / Siklus Panen</bold></gold>",
            List.of("Waktu pematangan buah berulang", "pada pohon sawit dewasa (detik)."),
            SettingKind.INTEGER_SECONDS,
            30,
            5,
            Material.SUNFLOWER,
            null
        ),
        new SettingEntry(
            25,
            "farming.fertilizer.reduce-seconds",
            "<green><bold>✦ Efek Percepatan Pupuk NPK</bold></green>",
            List.of("Jumlah detik yang dipangkas setiap", "1x pemberian Pupuk NPK Sawit."),
            SettingKind.INTEGER_SECONDS,
            30,
            5,
            Material.BONE_MEAL,
            SawitItemType.PUPUK_NPK
        ),

        // Row 4: Shop Item Prices
        new SettingEntry(
            29,
            "economy.shop-prices.sawit-seed",
            "<green><bold>✦ Harga Toko: Bibit Sawit</bold></green>",
            List.of("Harga beli 1x Bibit Sawit Tenera", "di Toko Perkebunan /sawit."),
            SettingKind.CURRENCY,
            2500,
            0,
            Material.OAK_SAPLING,
            SawitItemType.SAWIT_SEED
        ),
        new SettingEntry(
            30,
            "economy.shop-prices.pupuk-npk",
            "<green><bold>✦ Harga Toko: Pupuk NPK</bold></green>",
            List.of("Harga beli 1x Pupuk NPK Sawit", "di Toko Perkebunan /sawit."),
            SettingKind.CURRENCY,
            100,
            0,
            Material.BONE_MEAL,
            SawitItemType.PUPUK_NPK
        ),
        new SettingEntry(
            31,
            "economy.shop-prices.egrek-sawit",
            "<aqua><bold>✦ Harga Toko: Egrek Panen (Tier I)</bold></aqua>",
            List.of("Harga beli alat panen Egrek Sawit", "Tier I di Toko Perkebunan."),
            SettingKind.CURRENCY,
            1000,
            0,
            Material.IRON_HOE,
            SawitItemType.EGREK_SAWIT
        ),
        new SettingEntry(
            32,
            "economy.shop-prices.egrek-master",
            "<gold><bold>✦ Harga Toko: Egrek Baja (Tier II)</bold></gold>",
            List.of("Harga beli alat panen Egrek Baja", "Nusantara di Toko Perkebunan."),
            SettingKind.CURRENCY,
            5000,
            0,
            Material.GOLDEN_HOE,
            SawitItemType.EGREK_MASTER
        ),
        new SettingEntry(
            33,
            "economy.shop-prices.sawit-crate",
            "<gold><bold>✦ Harga Toko: Peti Gudang TBS</bold></gold>",
            List.of("Harga beli blok dekorasi Peti TBS", "di Toko Perkebunan."),
            SettingKind.CURRENCY,
            500,
            0,
            Material.BARREL,
            SawitItemType.SAWIT_CRATE
        )
    );

    private final NusaSawitPlugin plugin;
    private final VaultEconomyHook economyHook;
    private final SawitItemFactory itemFactory;
    private final Consumer<Player> openPlayerMainMenu;
    private final NumberFormat intFormat;
    private final Map<UUID, SettingEntry> pendingChatPrompts = new ConcurrentHashMap<>();

    public SawitAdminGuiManager(
        NusaSawitPlugin plugin,
        VaultEconomyHook economyHook,
        SawitItemFactory itemFactory,
        Consumer<Player> openPlayerMainMenu
    ) {
        this.plugin = plugin;
        this.economyHook = economyHook;
        this.itemFactory = itemFactory;
        this.openPlayerMainMenu = openPlayerMainMenu;
        this.intFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"));
    }

    public static final class AdminMenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void openAdminMenu(Player player) {
        if (!player.hasPermission("nusasawit.admin")) {
            player.sendMessage(MM.deserialize("<red>[NusaSawit] Kamu tidak memiliki izin admin!</red>"));
            return;
        }

        SawitConfig cfg = plugin.sawitConfig();
        FileConfiguration rawCfg = plugin.getConfig();

        AdminMenuHolder holder = new AdminMenuHolder();
        Inventory inv = Bukkit.createInventory(
            holder,
            54,
            MM.deserialize("<dark_red><bold>Admin Pengaturan NusaSawit</bold></dark_red>")
        );
        holder.inventory = inv;

        ItemStack borderRed = makeIcon(Material.RED_STAINED_GLASS_PANE, "<dark_gray> ", List.of());
        ItemStack borderGray = makeIcon(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray> ", List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) ? borderRed : borderGray);
        }

        inv.setItem(4, makeIcon(
            Material.COMPARATOR,
            "<gold><bold>✦ Panel Konfigurasi Live NusaSawit</bold></gold>",
            List.of(
                "<gray>Atur nilai ekonomi TBS/CPO, target kayu,",
                "<gray>dan waktu tumbuh langsung dari dalam game.",
                "",
                "<yellow>Klik Kiri Item <dark_gray>→ <green>Ketik Nilai Bebas di Chat",
                "<aqua>Klik Kanan <dark_gray>→ <green>+Tambah Cepat",
                "<gold>Shift + Klik Kanan <dark_gray>→ <red>-Kurangi Cepat"
            )
        ));

        for (SettingEntry entry : SETTINGS) {
            inv.setItem(entry.slot(), buildSettingItem(entry, rawCfg, cfg));
        }

        inv.setItem(SLOT_BACK, makeIcon(
            Material.ARROW,
            "<yellow><bold>← Kembali ke Menu Perkebunan</bold></yellow>",
            List.of("<gray>Kembali ke tampilan utama <gold>/sawit</gold>.")
        ));

        player.openInventory(inv);
    }

    private ItemStack buildSettingItem(SettingEntry entry, FileConfiguration rawCfg, SawitConfig cfg) {
        ItemStack base = entry.customIcon() != null
            ? itemFactory.create(entry.customIcon(), 1)
            : new ItemStack(entry.fallbackIcon());

        double currentVal = rawCfg.getDouble(entry.configPath(), entry.minVal());
        String formattedCurrent = formatValue(entry.kind(), currentVal, cfg.currencySymbol());
        String formattedStep = formatValue(entry.kind(), entry.stepAmount(), cfg.currencySymbol());

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>Key: " + entry.configPath());
        lore.add("");
        for (String line : entry.descriptionLines()) {
            lore.add("<gray>" + line);
        }
        lore.add("");
        lore.add("<gray>Nilai Saat Ini: <green><bold>" + formattedCurrent + "</bold></green>");
        lore.add("");
        lore.add("<yellow>Klik Kiri <dark_gray>- <white>Ketik Angka Baru di Chat");
        lore.add("<aqua>Klik Kanan <dark_gray>- <green>+" + formattedStep);
        lore.add("<gold>Shift + Klik Kanan <dark_gray>- <red>-" + formattedStep);

        return decorate(base, entry.titleMini(), lore);
    }

    private String formatValue(SettingKind kind, double val, String currencySymbol) {
        return switch (kind) {
            case CURRENCY -> economyHook.format(currencySymbol, val);
            case INTEGER_BLOCKS -> intFormat.format(Math.round(val)) + " Blok";
            case INTEGER_COUNT -> intFormat.format(Math.round(val));
            case INTEGER_SECONDS -> intFormat.format(Math.round(val)) + " Detik";
        };
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminMenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!player.hasPermission("nusasawit.admin")) {
            player.closeInventory();
            return;
        }

        int slot = event.getSlot();
        if (slot == SLOT_BACK) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
            openPlayerMainMenu.accept(player);
            return;
        }

        SettingEntry target = null;
        for (SettingEntry entry : SETTINGS) {
            if (entry.slot() == slot) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            return;
        }

        ClickType click = event.getClick();
        if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
            pendingChatPrompts.put(player.getUniqueId(), target);
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.3f);
            player.sendMessage(MM.deserialize(""));
            player.sendMessage(MM.deserialize("<gold><bold>[Admin NusaSawit]</bold></gold> <gray>Mengubah: </gray>" + target.titleMini()));
            player.sendMessage(MM.deserialize("<gray>Ketik angka baru di chat sekarang (atau ketik <red>batal</red> untuk membatalkan).</gray>"));
            player.sendMessage(MM.deserialize(""));
            return;
        }

        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            double delta = (click == ClickType.SHIFT_RIGHT) ? -target.stepAmount() : target.stepAmount();
            double current = plugin.getConfig().getDouble(target.configPath(), target.minVal());
            double next = Math.max(target.minVal(), current + delta);
            applySettingValue(player, target, next);
            openAdminMenu(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AdminMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAdminChatInput(AsyncChatEvent event) {
        Player player = event.getPlayer();
        SettingEntry entry = pendingChatPrompts.remove(player.getUniqueId());
        if (entry == null) {
            return;
        }

        event.setCancelled(true);
        String rawInput = PLAIN.serialize(event.message()).trim();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (rawInput.equalsIgnoreCase("batal") || rawInput.equalsIgnoreCase("cancel")) {
                player.sendMessage(MM.deserialize("<yellow>[Admin NusaSawit] Perubahan dibatalkan.</yellow>"));
                openAdminMenu(player);
                return;
            }

            // Support clean numeric entry such as "20000", "20.000", or "1450.5"
            String normalized = normalizeNumberInput(rawInput, entry.kind());
            double parsed;
            try {
                parsed = Double.parseDouble(normalized);
            } catch (NumberFormatException ex) {
                pendingChatPrompts.put(player.getUniqueId(), entry);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
                player.sendMessage(MM.deserialize(
                    "<red>[Admin NusaSawit]</red> <gray>Angka <yellow>'" + rawInput
                        + "'</yellow> tidak valid! Ketik angka murni (contoh: <green>1500</green>) atau ketik <red>batal</red>.</gray>"
                ));
                return;
            }

            if (parsed < entry.minVal() || Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                pendingChatPrompts.put(player.getUniqueId(), entry);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
                player.sendMessage(MM.deserialize(
                    "<red>[Admin NusaSawit]</red> <gray>Nilai minimal adalah <gold>" + entry.minVal() + "</gold>!</gray>"
                ));
                return;
            }

            applySettingValue(player, entry, parsed);
            openAdminMenu(player);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingChatPrompts.remove(event.getPlayer().getUniqueId());
    }

    private static String normalizeNumberInput(String raw, SettingKind kind) {
        String s = raw.replace("Rp", "").replace("rp", "").replace("_", "").replace(" ", "");
        if (kind != SettingKind.CURRENCY) {
            // For integer counts/blocks/seconds, treat '.' and ',' as thousand separators (e.g., "20.000" -> "20000")
            return s.replace(".", "").replace(",", "");
        }
        // For currency, if user typed Indonesian thousand dots like "25.000", strip dots when 3 digits follow
        if (s.matches("^\\d{1,3}(\\.\\d{3})+$")) {
            return s.replace(".", "");
        }
        return s.replace(",", ".");
    }

    private void applySettingValue(Player player, SettingEntry entry, double value) {
        if (entry.kind() == SettingKind.CURRENCY) {
            double rounded = Math.round(value * 100.0) / 100.0;
            plugin.getConfig().set(entry.configPath(), rounded);
        } else {
            long intVal = Math.round(value);
            plugin.getConfig().set(entry.configPath(), intVal);
        }
        plugin.saveConfig();
        plugin.reloadSawitConfig();
        plugin.recheckOnlinePlayerLicenses();

        String formatted = formatValue(entry.kind(), value, plugin.sawitConfig().currencySymbol());
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.25f);
        player.sendMessage(MM.deserialize(
            "<green><bold>[Admin NusaSawit]</bold></green> <gray>Berhasil menyimpan </gray>"
                + entry.titleMini() + " <dark_gray>→</dark_gray> <gold><bold>" + formatted + "</bold></gold>"
        ));
    }

    private static ItemStack makeIcon(Material mat, String titleMini, List<String> loreMini) {
        return decorate(new ItemStack(mat), titleMini, loreMini);
    }

    private static ItemStack decorate(ItemStack stack, String titleMini, List<String> loreMini) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize(titleMini).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
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
