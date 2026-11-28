package id.nusaspigot.sawit.hook;

import id.nusaspigot.sawit.config.SawitConfig;
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
 * Coordinates custom 3D block & item integration across Nexo, ItemsAdder, CraftEngine,
 * and Vanilla fallback. Automatically exports and syncs bundled 3D models & pixel-art
 * textures to whichever pack engine is installed on the server.
 */
public final class CustomPackHook {

    private static final List<String> SHARED_ASSET_PATHS = List.of(
        "models/block/sawit_stage_1.json",
        "models/block/sawit_stage_2.json",
        "models/block/sawit_stage_3_growing.json",
        "models/block/sawit_stage_3_ripe.json",
        "models/block/sawit_crate.json",
        "textures/block/sawit_trunk.png",
        "textures/block/sawit_spine.png",
        "textures/block/sawit_frond.png",
        "textures/block/sawit_frond_side.png",
        "textures/block/sawit_polybag.png",
        "textures/block/sawit_tbs_block.png",
        "textures/block/sawit_flower_bunch.png",
        "textures/block/sawit_crate_side.png",
        "textures/block/sawit_crate_top.png",
        "textures/item/sawit_seed.png",
        "textures/item/sawit_tbs.png",
        "textures/item/sawit_tbs_super.png",
        "textures/item/sawit_cpo.png",
        "textures/item/pupuk_npk.png",
        "textures/item/egrek_sawit.png",
        "textures/item/egrek_master.png"
    );

    private static final List<String> ITEMSADDER_EXTRA_MODELS = List.of(
        "models/item/egrek_sawit.json",
        "models/item/egrek_master.json"
    );

    private final Plugin plugin;
    private boolean nexoActive;
    private boolean itemsAdderActive;
    private boolean craftEngineActive;
    private CustomPackProvider activeProvider = CustomPackProvider.VANILLA;

    public CustomPackHook(Plugin plugin) {
        this.plugin = plugin;
    }

    public void initialize(SawitConfig config, PalmInteractionCallback callback, Runnable onPackLoaded) {
        Plugin nexoPlugin = Bukkit.getPluginManager().getPlugin("Nexo");
        Plugin iaPlugin = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        Plugin cePlugin = Bukkit.getPluginManager().getPlugin("CraftEngine");

        boolean nexoPresent = nexoPlugin != null && nexoPlugin.isEnabled();
        boolean iaPresent = iaPlugin != null && iaPlugin.isEnabled();
        boolean cePresent = cePlugin != null && cePlugin.isEnabled();

        // Always export standalone reference copies inside plugins/NusaSawit/
        exportNexoPackTo(plugin.getDataFolder().toPath().resolve("nexo-pack"), false);
        exportItemsAdderPackTo(plugin.getDataFolder().toPath().resolve("itemsadder-pack"), false);
        exportCraftEnginePackTo(plugin.getDataFolder().toPath().resolve("craftengine-pack"), false);
        exportBedrockPackTo(plugin.getDataFolder().toPath().resolve("bedrock-pack"), false);

        if (nexoPlugin != null && config.autoSyncNexoPack()) {
            int copied = syncToNexoFolder(config.overwriteExistingNexoPack());
            if (copied > 0) {
                plugin.getLogger().info("Synced " + copied + " NusaSawit custom assets into plugins/Nexo/. Run '/nexo reload all' to compile pack.");
            }
        }

        if (iaPlugin != null && config.autoSyncItemsAdderPack()) {
            int copied = syncToItemsAdderFolder(config.overwriteExistingItemsAdderPack());
            if (copied > 0) {
                plugin.getLogger().info("Synced " + copied + " NusaSawit custom assets into plugins/ItemsAdder/contents/nusasawit/. Run '/iazip' to compile pack.");
            }
        }

        if (cePlugin != null && config.autoSyncCraftEnginePack()) {
            int copied = syncToCraftEngineFolder(config.overwriteExistingCraftEnginePack());
            if (copied > 0) {
                plugin.getLogger().info("Synced " + copied + " NusaSawit custom assets into plugins/CraftEngine/resources/nusasawit/. Run '/ce reload all' to compile pack.");
            }
        }

        this.nexoActive = false;
        this.itemsAdderActive = false;
        this.craftEngineActive = false;

        CustomPackProvider preferred = config.packProvider();
        switch (preferred) {
            case NEXO -> this.nexoActive = nexoPresent;
            case ITEMSADDER -> this.itemsAdderActive = iaPresent;
            case CRAFTENGINE -> this.craftEngineActive = cePresent;
            case VANILLA -> {
            }
            case AUTO -> {
                if (nexoPresent) {
                    this.nexoActive = true;
                } else if (iaPresent) {
                    this.itemsAdderActive = true;
                } else if (cePresent) {
                    this.craftEngineActive = true;
                }
            }
        }

        if (this.nexoActive) {
            this.activeProvider = CustomPackProvider.NEXO;
            NexoBridge.registerLoadListener(plugin, onPackLoaded);
            NexoBridge.registerPalmListeners(plugin, callback);
        } else if (this.itemsAdderActive) {
            this.activeProvider = CustomPackProvider.ITEMSADDER;
            ItemsAdderBridge.registerLoadListener(plugin, onPackLoaded);
            ItemsAdderBridge.registerPalmListeners(plugin, callback);
        } else if (this.craftEngineActive) {
            this.activeProvider = CustomPackProvider.CRAFTENGINE;
            CraftEngineBridge.registerLoadListener(plugin, onPackLoaded);
            CraftEngineBridge.registerPalmListeners(plugin, callback);
        } else {
            this.activeProvider = CustomPackProvider.VANILLA;
        }
    }

    public CustomPackProvider activeProvider() {
        return activeProvider;
    }

    public boolean isNexoActive() {
        return nexoActive;
    }

    public boolean isItemsAdderActive() {
        return itemsAdderActive;
    }

    public boolean isCraftEngineActive() {
        return craftEngineActive;
    }

    public int syncToNexoFolder(boolean overwrite) {
        Path pluginsDir = plugin.getDataFolder().toPath().getParent();
        if (pluginsDir == null) {
            return 0;
        }
        return exportNexoPackTo(pluginsDir.resolve("Nexo"), overwrite);
    }

    public int syncToItemsAdderFolder(boolean overwrite) {
        Path pluginsDir = plugin.getDataFolder().toPath().getParent();
        if (pluginsDir == null) {
            return 0;
        }
        return exportItemsAdderPackTo(pluginsDir.resolve("ItemsAdder"), overwrite);
    }

    public int syncToCraftEngineFolder(boolean overwrite) {
        Path pluginsDir = plugin.getDataFolder().toPath().getParent();
        if (pluginsDir == null) {
            return 0;
        }
        return exportCraftEnginePackTo(pluginsDir.resolve("CraftEngine"), overwrite);
    }

    public int exportNexoPackTo(Path nexoRoot, boolean overwrite) {
        int copied = 0;
        copied += copyResource("nexo-pack/items/nusasawit.yml", nexoRoot.resolve("items/nusasawit.yml"), overwrite);
        for (String asset : SHARED_ASSET_PATHS) {
            copied += copyResource(
                "nexo-pack/pack/assets/nusasawit/" + asset,
                nexoRoot.resolve("pack/assets/nusasawit/" + asset),
                overwrite
            );
        }
        return copied;
    }

    public int exportItemsAdderPackTo(Path itemsAdderRoot, boolean overwrite) {
        int copied = 0;
        Path nsRoot = itemsAdderRoot.resolve("contents/nusasawit");
        copied += copyResource(
            "itemsadder-pack/contents/nusasawit/configs/nusasawit.yml",
            nsRoot.resolve("configs/nusasawit.yml"),
            overwrite
        );
        for (String extra : ITEMSADDER_EXTRA_MODELS) {
            copied += copyResource(
                "itemsadder-pack/contents/nusasawit/resourcepack/assets/nusasawit/" + extra,
                nsRoot.resolve("resourcepack/assets/nusasawit/" + extra),
                overwrite
            );
        }
        for (String asset : SHARED_ASSET_PATHS) {
            copied += copyResource(
                "nexo-pack/pack/assets/nusasawit/" + asset,
                nsRoot.resolve("resourcepack/assets/nusasawit/" + asset),
                overwrite
            );
        }
        return copied;
    }

    public int exportCraftEnginePackTo(Path craftEngineRoot, boolean overwrite) {
        int copied = 0;
        Path nsRoot = craftEngineRoot.resolve("resources/nusasawit");
        copied += copyResource(
            "craftengine-pack/resources/nusasawit/pack.yml",
            nsRoot.resolve("pack.yml"),
            overwrite
        );
        copied += copyResource(
            "craftengine-pack/resources/nusasawit/configuration/nusasawit.yml",
            nsRoot.resolve("configuration/nusasawit.yml"),
            overwrite
        );
        for (String asset : SHARED_ASSET_PATHS) {
            copied += copyResource(
                "nexo-pack/pack/assets/nusasawit/" + asset,
                nsRoot.resolve("resourcepack/assets/nusasawit/" + asset),
                overwrite
            );
        }
        return copied;
    }

    public int exportBedrockPackTo(Path bedrockRoot, boolean overwrite) {
        int copied = 0;
        copied += copyResource(
            "bedrock-pack/NusaSawit_Bedrock.mcpack",
            bedrockRoot.resolve("NusaSawit_Bedrock.mcpack"),
            overwrite
        );
        copied += copyResource(
            "bedrock-pack/custom_mappings/nusasawit.json",
            bedrockRoot.resolve("custom_mappings/nusasawit.json"),
            overwrite
        );
        return copied;
    }

    private int copyResource(String resourcePath, Path dest, boolean overwrite) {
        if (!overwrite && Files.exists(dest)) {
            return 0;
        }
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return 0;
            }
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            return 1;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to export pack asset " + resourcePath, ex);
            return 0;
        }
    }

    public ItemStack buildCustomItem(String itemId) {
        if (nexoActive) {
            return NexoBridge.buildItem(itemId);
        }
        if (itemsAdderActive) {
            return ItemsAdderBridge.buildItem(itemId);
        }
        if (craftEngineActive) {
            return CraftEngineBridge.buildItem(itemId);
        }
        return null;
    }

    public String idFromItem(ItemStack stack) {
        if (nexoActive) {
            return NexoBridge.idFromItem(stack);
        }
        if (itemsAdderActive) {
            return ItemsAdderBridge.idFromItem(stack);
        }
        if (craftEngineActive) {
            return CraftEngineBridge.idFromItem(stack);
        }
        return null;
    }

    public void placePalmStage(Location location, PalmStage stage) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (nexoActive && NexoBridge.placeStage(stage.nexoId(), location)) {
            return;
        }
        if (itemsAdderActive && ItemsAdderBridge.placeStage(stage.nexoId(), location)) {
            return;
        }
        if (craftEngineActive && CraftEngineBridge.placeStage(stage.nexoId(), location)) {
            return;
        }
        Block block = location.getBlock();
        block.setType(stage.fallbackMaterial(), false);
    }

    public void removePalmVisual(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (nexoActive) {
            NexoBridge.removeStage(location);
        }
        if (itemsAdderActive) {
            ItemsAdderBridge.removeStage(location);
        }
        if (craftEngineActive) {
            CraftEngineBridge.removeStage(location);
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
        if (nexoActive) {
            NexoBridge.resyncPalmHitbox(player, location, stage);
            return;
        }
        if (itemsAdderActive) {
            ItemsAdderBridge.resyncPalmHitbox(player, location, stage);
            return;
        }
        if (craftEngineActive) {
            CraftEngineBridge.resyncPalmHitbox(player, location, stage);
            return;
        }
        Block block = location.getBlock();
        player.sendBlockChange(block.getLocation(), block.getBlockData());
    }

    public int cleanupOrphanPalmsInChunk(Chunk chunk, Predicate<BlockPosKey> isTrackedPalm) {
        if (chunk == null) {
            return 0;
        }
        int cleaned = 0;
        if (nexoActive) {
            cleaned += NexoBridge.cleanupOrphanPalmDisplays(chunk, isTrackedPalm);
        }
        if (itemsAdderActive) {
            cleaned += ItemsAdderBridge.cleanupOrphanPalmEntities(chunk, isTrackedPalm);
        }
        if (craftEngineActive) {
            cleaned += CraftEngineBridge.cleanupOrphanPalmEntities(chunk, isTrackedPalm);
        }
        return cleaned;
    }

    public int cleanupAllLoadedOrphanPalms(Predicate<BlockPosKey> isTrackedPalm) {
        if (!nexoActive && !itemsAdderActive && !craftEngineActive) {
            return 0;
        }
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                total += cleanupOrphanPalmsInChunk(chunk, isTrackedPalm);
            }
        }
        return total;
    }
}
