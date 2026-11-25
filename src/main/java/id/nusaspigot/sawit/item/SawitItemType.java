package id.nusaspigot.sawit.item;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * Custom items managed by NusaSawit and backed by Nexo item definitions.
 */
public enum SawitItemType {
    SAWIT_SEED(
        "sawit_seed",
        Material.OAK_SAPLING,
        "<gradient:#65d649:#2ea329><bold>Bibit Sawit Tenera</bold></gradient>",
        List.of(
            "<dark_gray>Bibit Perkebunan Unggul",
            "",
            "<gray>Tanam di atas <green>Tanah / Rumput</green> untuk",
            "<gray>menumbuhkan pohon kelapa sawit produktif.",
            "",
            "<yellow>Syarat Tanam: <white>Lisensi 20.000 Kayu Oak",
            "<gold>Klik Kanan Tanah <dark_gray>- <green>Menanam Bibit"
        )
    ),
    SAWIT_TBS(
        "sawit_tbs",
        Material.TORCHFLOWER_SEEDS,
        "<gradient:#ff6a1a:#ffb326><bold>Tandan Buah Segar (TBS)</bold></gradient>",
        List.of(
            "<dark_gray>Hasil Panen Kebun Sawit",
            "",
            "<gray>Buah kelapa sawit matang segar yang",
            "<gray>siap dijual atau diolah menjadi CPO.",
            "",
            "<yellow>Jual atau olah di <gold>/sawit</gold>"
        )
    ),
    SAWIT_TBS_SUPER(
        "sawit_tbs_super",
        Material.GOLDEN_APPLE,
        "<gradient:#ffd700:#fff280><bold>TBS Tenera Super</bold></gradient>",
        List.of(
            "<dark_gray>Hasil Panen Kualitas Ekspor",
            "",
            "<gray>Tandan buah sawit kualitas tertinggi",
            "<gray>dengan rendemen minyak berlipat ganda!",
            "",
            "<yellow>Jual mahal di <gold>/sawit</gold>"
        )
    ),
    SAWIT_CPO(
        "sawit_cpo",
        Material.HONEY_BOTTLE,
        "<gradient:#f59e0b:#fde047><bold>Minyak Sawit Mentah (CPO)</bold></gradient>",
        List.of(
            "<dark_gray>Produk Olahan Kilang Sawit",
            "",
            "<gray>Crude Palm Oil murni bernilai ekonomi",
            "<gray>tinggi hasil ekstraksi tandan segar.",
            "",
            "<yellow>Jual dengan harga premium di <gold>/sawit</gold>"
        )
    ),
    PUPUK_NPK(
        "pupuk_npk",
        Material.BONE_MEAL,
        "<gradient:#84cc16:#22c55e><bold>Pupuk NPK Sawit</bold></gradient>",
        List.of(
            "<dark_gray>Nutrisi Percepatan Kebun",
            "",
            "<gray>Mempercepat pertumbuhan pohon sawit",
            "<gray>dan pematangan tandan buah secara instan.",
            "",
            "<gold>Klik Kanan Pohon Sawit <dark_gray>- <green>Beri Pupuk"
        )
    ),
    EGREK_SAWIT(
        "egrek_sawit",
        Material.IRON_HOE,
        "<gradient:#cbd5e1:#f8fafc><bold>Egrek Panen Sawit</bold></gradient>",
        List.of(
            "<dark_gray>Alat Panen Perkebunan (Tier I)",
            "",
            "<gray>Sabit galah tajam khusus memanen",
            "<gray>tandan sawit matang dengan efisien.",
            "",
            "<green>+1 Bonus TBS <gray>& <gold>15% Peluang TBS Super",
            "<gold>Klik Kanan Sawit Matang <dark_gray>- <green>Panen"
        )
    ),
    EGREK_MASTER(
        "egrek_master",
        Material.GOLDEN_HOE,
        "<gradient:#f59e0b:#fef08a><bold>Egrek Baja Nusantara</bold></gradient>",
        List.of(
            "<dark_gray>Alat Panen Master (Tier II)",
            "",
            "<gray>Egrek baja tempa berlapis emas untuk",
            "<gray>hasil panen melimpah tanpa merusak pelepah.",
            "",
            "<green>+2 Bonus TBS <gray>& <gold>35% Peluang TBS Super",
            "<gold>Klik Kanan Sawit Matang <dark_gray>- <green>Panen"
        )
    ),
    SAWIT_CRATE(
        "sawit_crate",
        Material.BARREL,
        "<gold><bold>Peti Gudang TBS</bold></gold>",
        List.of(
            "<dark_gray>Blok Dekorasi Kebun Sawit",
            "",
            "<gray>Peti kayu berisi tumpukan tandan buah",
            "<gray>sawit segar untuk menghias gudangmu."
        )
    );

    private final String nexoId;
    private final Material fallbackMaterial;
    private final String displayMiniMessage;
    private final List<String> loreMiniMessage;

    SawitItemType(String nexoId, Material fallbackMaterial, String displayMiniMessage, List<String> loreMiniMessage) {
        this.nexoId = nexoId;
        this.fallbackMaterial = fallbackMaterial;
        this.displayMiniMessage = displayMiniMessage;
        this.loreMiniMessage = loreMiniMessage;
    }

    public String nexoId() {
        return nexoId;
    }

    public Material fallbackMaterial() {
        return fallbackMaterial;
    }

    public String displayMiniMessage() {
        return displayMiniMessage;
    }

    public List<String> loreMiniMessage() {
        return loreMiniMessage;
    }

    public boolean isUnstackableTool() {
        return this == EGREK_SAWIT || this == EGREK_MASTER;
    }

    public static SawitItemType fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (SawitItemType type : values()) {
            if (type.nexoId.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
