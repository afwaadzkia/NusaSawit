package id.nusaspigot.sawit.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Strongly typed configuration snapshot loaded from config.yml.
 */
public final class SawitConfig {

    private final boolean autoSyncNexoPack;
    private final boolean overwriteExistingNexoPack;

    private final long requiredBlocks;
    private final long milestoneInterval;
    private final Set<Material> validLogMaterials;
    private final int actionBarEvery;

    private final long stage1Seconds;
    private final long stage2Seconds;
    private final long ripenSeconds;
    private final boolean requireSneakToBreak;
    private final boolean returnSeedOnBreak;
    private final Set<Material> allowedSoilBlocks;

    private final int baseSlotLimit;
    private final int maxBonusSlots;
    private final double upgradeBaseCost;
    private final double upgradeCostPerSlot;

    private final long fertilizerReduceSeconds;
    private final int fertilizerMaxUsesPerCycle;

    private final int baseTbsMin;
    private final int baseTbsMax;
    private final double handSuperChance;
    private final int egrekTier1BonusTbs;
    private final double egrekTier1SuperChance;
    private final int egrekTier2BonusTbs;
    private final double egrekTier2SuperChance;

    private final String currencySymbol;
    private final double sellPriceTbs;
    private final double sellPriceTbsSuper;
    private final double sellPriceCpo;
    private final int tbsPerCpo;
    private final double cpoProcessingFee;

    private final double shopPriceSeed;
    private final double shopPricePupuk;
    private final double shopPriceEgrekTier1;
    private final double shopPriceEgrekTier2;
    private final double shopPriceCrate;

    public SawitConfig(FileConfiguration cfg) {
        this.autoSyncNexoPack = cfg.getBoolean("nexo.auto-sync-pack", true);
        this.overwriteExistingNexoPack = cfg.getBoolean("nexo.overwrite-existing", false);

        this.requiredBlocks = Math.max(1L, cfg.getLong("quest.required-blocks", 20_000L));
        this.milestoneInterval = Math.max(0L, cfg.getLong("quest.milestone-interval", 10_000L));
        this.validLogMaterials = parseMaterials(cfg.getStringList("quest.valid-log-materials"), Set.of(Material.OAK_LOG, Material.OAK_WOOD));
        this.actionBarEvery = Math.max(1, cfg.getInt("quest.actionbar-every", 1));

        this.stage1Seconds = Math.max(5L, cfg.getLong("farming.stage-1-seconds", 300L));
        this.stage2Seconds = Math.max(5L, cfg.getLong("farming.stage-2-seconds", 600L));
        this.ripenSeconds = Math.max(5L, cfg.getLong("farming.ripen-seconds", 420L));
        this.requireSneakToBreak = cfg.getBoolean("farming.require-sneak-to-break", true);
        this.returnSeedOnBreak = cfg.getBoolean("farming.return-seed-on-break", true);
        this.allowedSoilBlocks = parseMaterials(
            cfg.getStringList("farming.allowed-soil-blocks"),
            Set.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.ROOTED_DIRT, Material.MUD, Material.FARMLAND)
        );

        this.baseSlotLimit = Math.max(1, cfg.getInt("farming.slots.base-limit", 8));
        this.maxBonusSlots = Math.max(0, cfg.getInt("farming.slots.max-bonus-slots", 42));
        this.upgradeBaseCost = Math.max(0.0, cfg.getDouble("farming.slots.upgrade-base-cost", 15_000.0));
        this.upgradeCostPerSlot = Math.max(0.0, cfg.getDouble("farming.slots.upgrade-cost-per-slot", 7_500.0));

        this.fertilizerReduceSeconds = Math.max(5L, cfg.getLong("farming.fertilizer.reduce-seconds", 180L));
        this.fertilizerMaxUsesPerCycle = Math.max(1, cfg.getInt("farming.fertilizer.max-uses-per-cycle", 3));

        this.baseTbsMin = Math.max(1, cfg.getInt("farming.harvest.base-tbs-min", 1));
        this.baseTbsMax = Math.max(this.baseTbsMin, cfg.getInt("farming.harvest.base-tbs-max", 2));
        this.handSuperChance = clampChance(cfg.getDouble("farming.harvest.hand-super-chance", 0.03));
        this.egrekTier1BonusTbs = Math.max(0, cfg.getInt("farming.harvest.egrek-tier1-bonus-tbs", 1));
        this.egrekTier1SuperChance = clampChance(cfg.getDouble("farming.harvest.egrek-tier1-super-chance", 0.15));
        this.egrekTier2BonusTbs = Math.max(0, cfg.getInt("farming.harvest.egrek-tier2-bonus-tbs", 2));
        this.egrekTier2SuperChance = clampChance(cfg.getDouble("farming.harvest.egrek-tier2-super-chance", 0.35));

        this.currencySymbol = cfg.getString("economy.currency-symbol", "Rp");
        this.sellPriceTbs = Math.max(0.0, cfg.getDouble("economy.sell-prices.sawit-tbs", 350.0));
        this.sellPriceTbsSuper = Math.max(0.0, cfg.getDouble("economy.sell-prices.sawit-tbs-super", 1_200.0));
        this.sellPriceCpo = Math.max(0.0, cfg.getDouble("economy.sell-prices.sawit-cpo", 1_450.0));
        this.tbsPerCpo = Math.max(1, cfg.getInt("economy.cpo-mill.tbs-per-cpo", 3));
        this.cpoProcessingFee = Math.max(0.0, cfg.getDouble("economy.cpo-mill.processing-fee", 50.0));

        this.shopPriceSeed = Math.max(0.0, cfg.getDouble("economy.shop-prices.sawit-seed", 25_000.0));
        this.shopPricePupuk = Math.max(0.0, cfg.getDouble("economy.shop-prices.pupuk-npk", 1_200.0));
        this.shopPriceEgrekTier1 = Math.max(0.0, cfg.getDouble("economy.shop-prices.egrek-sawit", 18_000.0));
        this.shopPriceEgrekTier2 = Math.max(0.0, cfg.getDouble("economy.shop-prices.egrek-master", 65_000.0));
        this.shopPriceCrate = Math.max(0.0, cfg.getDouble("economy.shop-prices.sawit-crate", 2_500.0));
    }

    private static double clampChance(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static Set<Material> parseMaterials(List<String> names, Set<Material> fallback) {
        EnumSet<Material> set = EnumSet.noneOf(Material.class);
        if (names != null) {
            for (String raw : names) {
                if (raw == null || raw.isBlank()) continue;
                Material mat = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
                if (mat != null) {
                    set.add(mat);
                }
            }
        }
        if (set.isEmpty()) {
            set.addAll(fallback);
        }
        return Set.copyOf(set);
    }

    public boolean autoSyncNexoPack() { return autoSyncNexoPack; }
    public boolean overwriteExistingNexoPack() { return overwriteExistingNexoPack; }
    public long requiredBlocks() { return requiredBlocks; }
    public long milestoneInterval() { return milestoneInterval; }
    public Set<Material> validLogMaterials() { return validLogMaterials; }
    public int actionBarEvery() { return actionBarEvery; }

    public long stage1Seconds() { return stage1Seconds; }
    public long stage2Seconds() { return stage2Seconds; }
    public long ripenSeconds() { return ripenSeconds; }
    public boolean requireSneakToBreak() { return requireSneakToBreak; }
    public boolean returnSeedOnBreak() { return returnSeedOnBreak; }
    public Set<Material> allowedSoilBlocks() { return allowedSoilBlocks; }

    public int baseSlotLimit() { return baseSlotLimit; }
    public int maxBonusSlots() { return maxBonusSlots; }
    public int maxTotalSlots(int bonusSlots) { return baseSlotLimit + Math.min(maxBonusSlots, Math.max(0, bonusSlots)); }
    public double nextSlotUpgradeCost(int currentBonusSlots) {
        return upgradeBaseCost + (Math.max(0, currentBonusSlots) * upgradeCostPerSlot);
    }

    public long fertilizerReduceSeconds() { return fertilizerReduceSeconds; }
    public int fertilizerMaxUsesPerCycle() { return fertilizerMaxUsesPerCycle; }

    public int baseTbsMin() { return baseTbsMin; }
    public int baseTbsMax() { return baseTbsMax; }
    public double handSuperChance() { return handSuperChance; }
    public int egrekTier1BonusTbs() { return egrekTier1BonusTbs; }
    public double egrekTier1SuperChance() { return egrekTier1SuperChance; }
    public int egrekTier2BonusTbs() { return egrekTier2BonusTbs; }
    public double egrekTier2SuperChance() { return egrekTier2SuperChance; }

    public String currencySymbol() { return currencySymbol; }
    public double sellPriceTbs() { return sellPriceTbs; }
    public double sellPriceTbsSuper() { return sellPriceTbsSuper; }
    public double sellPriceCpo() { return sellPriceCpo; }
    public int tbsPerCpo() { return tbsPerCpo; }
    public double cpoProcessingFee() { return cpoProcessingFee; }

    public double shopPriceSeed() { return shopPriceSeed; }
    public double shopPricePupuk() { return shopPricePupuk; }
    public double shopPriceEgrekTier1() { return shopPriceEgrekTier1; }
    public double shopPriceEgrekTier2() { return shopPriceEgrekTier2; }
    public double shopPriceCrate() { return shopPriceCrate; }
}
