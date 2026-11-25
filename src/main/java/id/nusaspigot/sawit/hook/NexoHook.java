package id.nusaspigot.sawit.hook;

import id.nusaspigot.sawit.model.BlockPosKey;
import id.nusaspigot.sawit.model.PalmStage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Coordinates Nexo pack synchronization and delegates custom item/block calls
 * to NexoBridge only when Nexo is active on the server.
 */
public final class NexoHook {

    private static final List<String> BUNDLED_PACK_FILES = List.of(
        "items/nusasawit.yml",
        "pack/assets/nusasawit/models/block/sawit_stage_1.json",
        "pack/assets/nusasawit/models/block/sawit_stage_2.json",
        "pack/assets/nusasawit/models/block/sawit_stage_3_growing.json",
        "pack/assets/nusasawit/models/block/sawit_stage_3_ripe.json",
        "pack/assets/nusasawit/models/block/sawit_crate.json",
        "pack/assets/nusasawit/textures/block/sawit_trunk.png",
        "pack/assets/nusasawit/textures/block/sawit_spine.png",
        "pack/assets/nusasawit/textures/block/sawit_frond.png",
        "pack/assets/nusasawit/textures/block/sawit_frond_side.png",
        "pack/assets/nusasawit/textures/block/sawit_polybag.png",
        "pack/assets/nusasawit/textures/block/sawit_tbs_block.png",
        "pack/assets/nusasawit/textures/block/sawit_flower_bunch.png",
        "pack/assets/nusasawit/textures/block/sawit_crate_side.png",
        "pack/assets/nusasawit/textures/block/sawit_crate_top.png",
        "pack/assets/nusasawit/textures/item/sawit_seed.png",
        "pack/assets/nusasawit/textures/item/sawit_tbs.png",
        "pack/assets/nusasawit/textures/item/sawit_tbs_super.png",
        "pack/assets/nusasawit/textures/item/sawit_cpo.png",
        "pack/assets/nusasawit/textures/item/pupuk_npk.png",
        "pack/assets/nusasawit/textures/item/egrek_sawit.png",
        "pack/assets/nusasawit/textures/item/egrek_master.png"
    );

    private final Plugin plugin;
    private boolean nexoEnabled;

    public NexoHook(Plugin plugin) {
        this.plugin = plugin;
    }

    public void initialize(boolean autoSync, boolean overwriteExisting, Runnable onNexoLoaded) {
        Plugin nexoPlugin = Bukkit.getPluginManager().getPlugin("Nexo");
        this.nexoEnabled = nexoPlugin != null && nexoPlugin.isEnabled();

        // Also export a standalone copy in plugins/NusaSawit/nexo-pack-export for easy reference
        exportBundledPackTo(plugin.getDataFolder().toPath().resolve("nexo-pack"), false);

        if (nexoPlugin != null && autoSync) {
            int copied = syncToNexoFolder(overwriteExisting);
            if (copied > 0) {
                plugin.getLogger().info("Synced " + copied + " NusaSawit custom textures/models into plugins/Nexo/. Run '/nexo reload all' to compile pack.");
            }
        }

        if (this.nexoEnabled) {
            NexoBridge.registerLoadListener(plugin, onNexoLoaded);
        }
    }

    public int syncToNexoFolder(boolean overwrite) {
        Path pluginsDir = plugin.getDataFolder().toPath().getParent();
        if (pluginsDir == null) {
            return 0;
        }
        Path nexoDir = pluginsDir.resolve("Nexo");
        return exportBundledPackTo(nexoDir, overwrite);
    }

    public int exportBundledPackTo(Path targetRoot, boolean overwrite) {
        int copied = 0;
        for (String relPath : BUNDLED_PACK_FILES) {
            Path dest = targetRoot.resolve(relPath);
            if (!overwrite && Files.exists(dest)) {
                continue;
            }
            try (InputStream in = plugin.getResource("nexo-pack/" + relPath)) {
                if (in == null) {
                    continue;
                }
                if (dest.getParent() != null) {
                    Files.createDirectories(dest.getParent());
                }
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to export Nexo asset " + relPath, ex);
            }
        }
        return copied;
    }

    public boolean isNexoEnabled() {
        return nexoEnabled;
    }

    public ItemStack buildNexoItem(String nexoId) {
        if (!nexoEnabled) {
            return null;
        }
        return NexoBridge.buildItem(nexoId);
    }

    public String idFromItem(ItemStack stack) {
        if (!nexoEnabled) {
            return null;
        }
        return NexoBridge.idFromItem(stack);
    }

    public void placePalmStage(Location location, PalmStage stage) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (nexoEnabled && NexoBridge.placeStage(stage.nexoId(), location)) {
            return;
        }
        Block block = location.getBlock();
        block.setType(stage.fallbackMaterial(), false);
    }

    public void removePalmVisual(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (nexoEnabled) {
            NexoBridge.removeStage(location);
        }
        Block block = location.getBlock();
        if (!block.getType().isAir()) {
            block.setType(Material.AIR, false);
        }
    }

    public void resyncPalmHitbox(Player player, Location location, PalmStage stage) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null) {
            return;
        }
        if (nexoEnabled) {
            NexoBridge.resyncPalmHitbox(player, location, stage);
            return;
        }
        Block block = location.getBlock();
        player.sendBlockChange(block.getLocation(), block.getBlockData());
    }

    public int cleanupOrphanPalmsInChunk(Chunk chunk, Predicate<BlockPosKey> isTrackedPalm) {
        if (!nexoEnabled || chunk == null) {
            return 0;
        }
        return NexoBridge.cleanupOrphanPalmDisplays(chunk, isTrackedPalm);
    }

    public int cleanupAllLoadedOrphanPalms(Predicate<BlockPosKey> isTrackedPalm) {
        if (!nexoEnabled) {
            return 0;
        }
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                total += NexoBridge.cleanupOrphanPalmDisplays(chunk, isTrackedPalm);
            }
        }
        return total;
    }
}
