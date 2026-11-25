package id.nusaspigot.sawit.hook;

import java.util.Locale;

/**
 * Selects which custom item/3D block engine NusaSawit uses at runtime.
 */
public enum CustomPackProvider {
    AUTO,
    NEXO,
    ITEMSADDER,
    CRAFTENGINE,
    VANILLA;

    public static CustomPackProvider fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "NEXO" -> NEXO;
            case "ITEMSADDER", "ITEMS_ADDER", "IA" -> ITEMSADDER;
            case "CRAFTENGINE", "CRAFT_ENGINE", "CRAFT-ENGINE", "CE" -> CRAFTENGINE;
            case "VANILLA", "NONE", "OFF" -> VANILLA;
            default -> AUTO;
        };
    }
}
