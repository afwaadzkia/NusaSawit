package id.nusaspigot.sawit.model;

import org.bukkit.Material;

/**
 * Growth and fruiting stages of a planted oil palm tree.
 */
public enum PalmStage {
    STAGE_1("sawit_stage_1", Material.OAK_SAPLING, "Tahap 1 - Tunas Bibit"),
    STAGE_2("sawit_stage_2", Material.JUNGLE_SAPLING, "Tahap 2 - Sawit Muda"),
    STAGE_3_GROWING("sawit_stage_3_growing", Material.MANGROVE_PROPAGULE, "Tahap 3 - Dewasa (Berbunga)"),
    STAGE_3_RIPE("sawit_stage_3_ripe", Material.SWEET_BERRY_BUSH, "Siap Panen TBS!");

    private final String nexoId;
    private final Material fallbackMaterial;
    private final String displayLabel;

    PalmStage(String nexoId, Material fallbackMaterial, String displayLabel) {
        this.nexoId = nexoId;
        this.fallbackMaterial = fallbackMaterial;
        this.displayLabel = displayLabel;
    }

    public String nexoId() {
        return nexoId;
    }

    public Material fallbackMaterial() {
        return fallbackMaterial;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public boolean isRipe() {
        return this == STAGE_3_RIPE;
    }

    public PalmStage nextGrowthStage() {
        return switch (this) {
            case STAGE_1 -> STAGE_2;
            case STAGE_2 -> STAGE_3_GROWING;
            case STAGE_3_GROWING, STAGE_3_RIPE -> STAGE_3_RIPE;
        };
    }

    public static PalmStage fromNameOrDefault(String raw) {
        if (raw == null) {
            return STAGE_1;
        }
        try {
            return PalmStage.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return STAGE_1;
        }
    }
}
