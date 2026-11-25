package id.nusaspigot.sawit.service;

import id.nusaspigot.sawit.config.SawitConfig;
import id.nusaspigot.sawit.hook.VaultEconomyHook;
import id.nusaspigot.sawit.item.SawitItemFactory;
import id.nusaspigot.sawit.item.SawitItemType;
import id.nusaspigot.sawit.model.PlayerSawitData;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Handles Koperasi Sawit selling, Crude Palm Oil (CPO) refining,
 * shop item purchases, and plantation slot upgrades via Vault Economy.
 */
public final class SawitEconomyService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Supplier<SawitConfig> configSupplier;
    private final Function<Player, PlayerSawitData> playerDataProvider;
    private final VaultEconomyHook economyHook;
    private final SawitItemFactory itemFactory;

    public SawitEconomyService(
        Supplier<SawitConfig> configSupplier,
        Function<Player, PlayerSawitData> playerDataProvider,
        VaultEconomyHook economyHook,
        SawitItemFactory itemFactory
    ) {
        this.configSupplier = configSupplier;
        this.playerDataProvider = playerDataProvider;
        this.economyHook = economyHook;
        this.itemFactory = itemFactory;
    }

    public boolean ensureEconomy(Player player) {
        if (economyHook.isAvailable()) {
            return true;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
        player.sendMessage(MM.deserialize(
            "<red>[NusaSawit]</red> <gray>Plugin ekonomi Vault belum terdeteksi di server! Pasang Vault + EssentialsX/CMI terlebih dahulu.</gray>"
        ));
        return false;
    }

    public boolean sellAllHarvest(Player player) {
        if (!ensureEconomy(player)) {
            return false;
        }
        SawitConfig cfg = configSupplier.get();

        int tbsCount = itemFactory.countInInventory(player, SawitItemType.SAWIT_TBS);
        int superCount = itemFactory.countInInventory(player, SawitItemType.SAWIT_TBS_SUPER);
        int cpoCount = itemFactory.countInInventory(player, SawitItemType.SAWIT_CPO);

        if (tbsCount == 0 && superCount == 0 && cpoCount == 0) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f);
            player.sendMessage(MM.deserialize(
                "<yellow>[NusaSawit]</yellow> <gray>Kamu tidak memiliki <gold>TBS</gold> atau <gold>Minyak CPO</gold> di inventory untuk dijual!</gray>"
            ));
            return false;
        }

        int removedTbs = tbsCount > 0 ? itemFactory.removeFromInventory(player, SawitItemType.SAWIT_TBS, tbsCount) : 0;
        int removedSuper = superCount > 0 ? itemFactory.removeFromInventory(player, SawitItemType.SAWIT_TBS_SUPER, superCount) : 0;
        int removedCpo = cpoCount > 0 ? itemFactory.removeFromInventory(player, SawitItemType.SAWIT_CPO, cpoCount) : 0;

        double totalPayout = (removedTbs * cfg.sellPriceTbs())
            + (removedSuper * cfg.sellPriceTbsSuper())
            + (removedCpo * cfg.sellPriceCpo());

        if (!economyHook.deposit(player, totalPayout)) {
            // Rollback items if economy deposit fails unexpectedly
            if (removedTbs > 0) itemFactory.giveOrDrop(player, SawitItemType.SAWIT_TBS, removedTbs);
            if (removedSuper > 0) itemFactory.giveOrDrop(player, SawitItemType.SAWIT_TBS_SUPER, removedSuper);
            if (removedCpo > 0) itemFactory.giveOrDrop(player, SawitItemType.SAWIT_CPO, removedCpo);
            player.sendMessage(MM.deserialize("<red>[NusaSawit] Transaksi gagal diproses oleh Vault!</red>"));
            return false;
        }

        PlayerSawitData data = playerDataProvider.apply(player);
        data.addEarnings(totalPayout);

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.15f);
        player.sendMessage(MM.deserialize(
            "<green><bold>[Koperasi Sawit]</bold></green> <gray>Penjualan berhasil! Total diterima: <gold><bold>"
                + economyHook.format(cfg.currencySymbol(), totalPayout) + "</bold></gold> "
                + "<dark_gray>(TBS: " + removedTbs + ", Super: " + removedSuper + ", CPO: " + removedCpo + ")</dark_gray>"
        ));
        return true;
    }

    public boolean refineToCpo(Player player, boolean refineAll) {
        SawitConfig cfg = configSupplier.get();
        int tbsOwned = itemFactory.countInInventory(player, SawitItemType.SAWIT_TBS);
        int neededPerBottle = cfg.tbsPerCpo();

        if (tbsOwned < neededPerBottle) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.9f);
            player.sendMessage(MM.deserialize(
                "<red>[Kilang CPO]</red> <gray>Butuh minimal <gold>" + neededPerBottle + "x TBS Segar</gold> untuk mengolah 1 botol CPO!</gray>"
            ));
            return false;
        }

        int bottles = refineAll ? (tbsOwned / neededPerBottle) : 1;
        double totalFee = bottles * cfg.cpoProcessingFee();

        if (totalFee > 0.0D) {
            if (!ensureEconomy(player)) {
                return false;
            }
            if (!economyHook.has(player, totalFee)) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.9f);
                player.sendMessage(MM.deserialize(
                    "<red>[Kilang CPO]</red> <gray>Biaya penggilingan kurang (<gold>"
                        + economyHook.format(cfg.currencySymbol(), totalFee) + "</gold>)!</gray>"
                ));
                return false;
            }
            if (!economyHook.withdraw(player, totalFee)) {
                return false;
            }
        }

        itemFactory.removeFromInventory(player, SawitItemType.SAWIT_TBS, bottles * neededPerBottle);
        itemFactory.giveOrDrop(player, SawitItemType.SAWIT_CPO, bottles);

        player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1.0f, 1.1f);
        player.sendMessage(MM.deserialize(
            "<gold><bold>[Kilang CPO]</bold></gold> <gray>Berhasil mengekstrak <yellow>" + (bottles * neededPerBottle)
                + "x TBS</yellow> menjadi <gold><bold>" + bottles + "x Minyak Sawit Mentah (CPO)</bold></gold>!</gray>"
        ));
        return true;
    }

    public boolean buyShopItem(Player player, SawitItemType type, int amount) {
        if (!ensureEconomy(player)) {
            return false;
        }
        SawitConfig cfg = configSupplier.get();
        PlayerSawitData data = playerDataProvider.apply(player);

        if (type == SawitItemType.SAWIT_SEED && !data.isLicenseUnlocked() && !player.hasPermission("nusasawit.admin")) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
            player.sendMessage(MM.deserialize(
                "<red>[Toko Sawit]</red> <gray>Buka <yellow>Lisensi 20.000 Kayu Oak</yellow> terlebih dahulu sebelum membeli Bibit Sawit tambahan!</gray>"
            ));
            return false;
        }

        double unitPrice = switch (type) {
            case SAWIT_SEED -> cfg.shopPriceSeed();
            case PUPUK_NPK -> cfg.shopPricePupuk();
            case EGREK_SAWIT -> cfg.shopPriceEgrekTier1();
            case EGREK_MASTER -> cfg.shopPriceEgrekTier2();
            case SAWIT_CRATE -> cfg.shopPriceCrate();
            default -> -1.0D;
        };

        if (unitPrice < 0.0D) {
            return false;
        }

        double totalCost = unitPrice * amount;
        if (!economyHook.has(player, totalCost)) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
            player.sendMessage(MM.deserialize(
                "<red>[Toko Sawit]</red> <gray>Saldo tidak cukup! Harga: <gold>"
                    + economyHook.format(cfg.currencySymbol(), totalCost) + "</gold></gray>"
            ));
            return false;
        }

        if (!economyHook.withdraw(player, totalCost)) {
            return false;
        }

        itemFactory.giveOrDrop(player, type, amount);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        player.sendMessage(MM.deserialize(
            "<green>[Toko Sawit]</green> <gray>Berhasil membeli <yellow>" + amount + "x</yellow> "
                + type.displayMiniMessage() + " <gray>seharga <gold>"
                + economyHook.format(cfg.currencySymbol(), totalCost) + "</gold>!</gray>"
        ));
        return true;
    }

    public boolean upgradePlantationSlot(Player player) {
        if (!ensureEconomy(player)) {
            return false;
        }
        SawitConfig cfg = configSupplier.get();
        PlayerSawitData data = playerDataProvider.apply(player);

        if (data.bonusSlots() >= cfg.maxBonusSlots()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f);
            player.sendMessage(MM.deserialize(
                "<yellow>[NusaSawit]</yellow> <gray>Kapasitas slot kebunmu sudah mencapai batas maksimal (<gold>"
                    + cfg.maxTotalSlots(data.bonusSlots()) + " pohon</gold>)!</gray>"
            ));
            return false;
        }

        double cost = cfg.nextSlotUpgradeCost(data.bonusSlots());
        if (!economyHook.has(player, cost)) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
            player.sendMessage(MM.deserialize(
                "<red>[NusaSawit]</red> <gray>Saldo tidak cukup untuk upgrade lahan! Biaya: <gold>"
                    + economyHook.format(cfg.currencySymbol(), cost) + "</gold></gray>"
            ));
            return false;
        }

        if (!economyHook.withdraw(player, cost)) {
            return false;
        }

        data.addBonusSlots(1);
        int newMax = cfg.maxTotalSlots(data.bonusSlots());
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.35f);
        player.sendMessage(MM.deserialize(
            "<green><bold>[NusaSawit]</bold></green> <gray>Upgrade Lahan berhasil! Kapasitas maksimal pohon sawitmu sekarang: <gold><bold>"
                + newMax + " Pohon</bold></gold>.</gray>"
        ));
        return true;
    }
}
