package id.nusaspigot.sawit.service;

import id.nusaspigot.sawit.config.SawitConfig;
import id.nusaspigot.sawit.item.SawitItemFactory;
import id.nusaspigot.sawit.item.SawitItemType;
import id.nusaspigot.sawit.model.PlayerSawitData;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tracks natural wood chopping toward the 20,000-block Oil Palm Plantation License
 * and recurring milestone seed rewards, preventing place-and-break exploits via
 * chunk-level PersistentDataContainer markers.
 */
public final class WoodQuestTracker implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final NamespacedKey placedLogsKey;
    private final Supplier<SawitConfig> configSupplier;
    private final Function<Player, PlayerSawitData> playerDataProvider;
    private final SawitItemFactory itemFactory;
    private final NumberFormat intFormat;

    public WoodQuestTracker(
        Plugin plugin,
        Supplier<SawitConfig> configSupplier,
        Function<Player, PlayerSawitData> playerDataProvider,
        SawitItemFactory itemFactory
    ) {
        this.placedLogsKey = new NamespacedKey(plugin, "placed_logs");
        this.configSupplier = configSupplier;
        this.playerDataProvider = playerDataProvider;
        this.itemFactory = itemFactory;
        this.intFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (configSupplier.get().validLogMaterials().contains(block.getType())) {
            markPlayerPlaced(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        markMovedLogs(event.getBlocks(), event.getDirection().getModX(), event.getDirection().getModY(), event.getDirection().getModZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        markMovedLogs(event.getBlocks(), event.getDirection().getModX(), event.getDirection().getModY(), event.getDirection().getModZ());
    }

    private void markMovedLogs(List<Block> blocks, int dx, int dy, int dz) {
        SawitConfig cfg = configSupplier.get();
        for (Block b : blocks) {
            if (cfg.validLogMaterials().contains(b.getType())) {
                Block dest = b.getRelative(dx, dy, dz);
                markPlayerPlaced(dest);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        SawitConfig cfg = configSupplier.get();
        Block block = event.getBlock();
        if (!cfg.validLogMaterials().contains(block.getType())) {
            return;
        }

        // Ignore logs that were previously placed by players or shifted by pistons
        if (consumeIfPlayerPlaced(block)) {
            return;
        }

        PlayerSawitData data = playerDataProvider.apply(player);
        long count = data.incrementWoodBroken();
        long required = cfg.requiredBlocks();

        if (!data.isLicenseUnlocked() && count >= required) {
            data.setLicenseUnlocked(true);
            grantSeedReward(player, data, 1);

            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1.2, 0), 35, 0.5, 0.6, 0.5, 0.15);
            player.sendMessage(MM.deserialize(""));
            player.sendMessage(MM.deserialize("<gold><bold>✦ LISENSI PERKEBUNAN SAWIT TERBUKA! ✦</bold></gold>"));
            player.sendMessage(MM.deserialize("<gray>Kamu telah menebang <green>" + intFormat.format(required) + "</green> blok kayu alami!"));
            player.sendMessage(MM.deserialize("<gray>Sekarang kamu resmi dapat menanam <green><bold>Bibit Sawit Tenera</bold></green> dan menerima <gold>1x Bibit Gratis</gold>!"));
            player.sendMessage(MM.deserialize("<yellow>Ketik <gold>/sawit</gold> untuk membuka menu perkebunan & koperasi sawit."));
            player.sendMessage(MM.deserialize(""));
            return;
        }

        long interval = cfg.milestoneInterval();
        if (data.isLicenseUnlocked() && count > required && interval > 0 && ((count - required) % interval == 0)) {
            grantSeedReward(player, data, 1);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
            player.sendMessage(MM.deserialize(
                "<green><bold>[NusaSawit]</bold></green> <gray>Milestone <gold>" + intFormat.format(count)
                    + "</gold> kayu tercapai! Kamu mendapat bonus <green>1x Bibit Sawit Tenera</green>!"
            ));
            return;
        }

        if (count % cfg.actionBarEvery() == 0) {
            if (!data.isLicenseUnlocked()) {
                String bar = buildProgressBar(count, required, 12);
                double pct = Math.min(100.0, (count * 100.0) / required);
                player.sendActionBar(MM.deserialize(
                    "<gold>Lisensi Sawit</gold> <dark_gray>[</dark_gray>" + bar + "<dark_gray>]</dark_gray> <yellow>"
                        + intFormat.format(count) + "<gray>/</gray>" + intFormat.format(required)
                        + "</yellow> <gray>(" + String.format(Locale.US, "%.1f%%", pct) + ")</gray>"
                ));
            } else if (interval > 0) {
                long intoCycle = (count - required) % interval;
                player.sendActionBar(MM.deserialize(
                    "<green>Milestone Bibit Sawit:</green> <yellow>" + intFormat.format(intoCycle)
                        + "<gray>/</gray>" + intFormat.format(interval) + "</yellow> <gray>kayu</gray>"
                ));
            }
        }
    }

    private void grantSeedReward(Player player, PlayerSawitData data, int amount) {
        if (player.getInventory().firstEmpty() == -1) {
            data.addPendingSeeds(amount);
            player.sendMessage(MM.deserialize(
                "<yellow>[NusaSawit] Inventory penuh! <gold>" + amount
                    + "x Bibit Sawit</gold> disimpan di <green>/sawit claim</green>."
            ));
        } else {
            itemFactory.giveOrDrop(player, SawitItemType.SAWIT_SEED, amount);
        }
    }

    public static String buildProgressBar(long current, long max, int segments) {
        int filled = max <= 0 ? segments : (int) Math.min(segments, Math.round(((double) current / max) * segments));
        StringBuilder sb = new StringBuilder();
        sb.append("<green>");
        for (int i = 0; i < filled; i++) {
            sb.append('■');
        }
        sb.append("</green><dark_gray>");
        for (int i = filled; i < segments; i++) {
            sb.append('■');
        }
        sb.append("</dark_gray>");
        return sb.toString();
    }

    public static int packLocalCoord(int x, int y, int z) {
        return (x & 0xF) | ((z & 0xF) << 4) | (((y + 2048) & 0xFFF) << 8);
    }

    private void markPlayerPlaced(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        int packed = packLocalCoord(block.getX(), block.getY(), block.getZ());
        int[] existing = pdc.get(placedLogsKey, PersistentDataType.INTEGER_ARRAY);
        if (existing == null || existing.length == 0) {
            pdc.set(placedLogsKey, PersistentDataType.INTEGER_ARRAY, new int[]{packed});
            return;
        }
        for (int v : existing) {
            if (v == packed) {
                return;
            }
        }
        int[] updated = Arrays.copyOf(existing, existing.length + 1);
        updated[existing.length] = packed;
        pdc.set(placedLogsKey, PersistentDataType.INTEGER_ARRAY, updated);
    }

    private boolean consumeIfPlayerPlaced(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        int[] existing = pdc.get(placedLogsKey, PersistentDataType.INTEGER_ARRAY);
        if (existing == null || existing.length == 0) {
            return false;
        }
        int target = packLocalCoord(block.getX(), block.getY(), block.getZ());
        Set<Integer> remaining = new HashSet<>(existing.length);
        boolean found = false;
        for (int v : existing) {
            if (v == target) {
                found = true;
            } else {
                remaining.add(v);
            }
        }
        if (!found) {
            return false;
        }
        if (remaining.isEmpty()) {
            pdc.remove(placedLogsKey);
        } else {
            int[] arr = remaining.stream().mapToInt(Integer::intValue).toArray();
            pdc.set(placedLogsKey, PersistentDataType.INTEGER_ARRAY, arr);
        }
        return true;
    }
}
