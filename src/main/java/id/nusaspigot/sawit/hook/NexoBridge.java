package id.nusaspigot.sawit.hook;

import com.nexomc.nexo.api.NexoBlocks;
import com.nexomc.nexo.api.NexoFurniture;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import com.nexomc.nexo.api.events.custom_block.NexoBlockBreakEvent;
import com.nexomc.nexo.api.events.custom_block.NexoBlockInteractEvent;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureBreakEvent;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent;
import com.nexomc.nexo.items.ItemBuilder;
import com.nexomc.nexo.mechanics.custom_block.CustomBlockMechanic;
import com.nexomc.nexo.mechanics.furniture.FurnitureMechanic;
import com.nexomc.nexo.utils.drops.Drop;
import id.nusaspigot.sawit.model.BlockPosKey;
import id.nusaspigot.sawit.model.PalmBreakOutcome;
import id.nusaspigot.sawit.model.PalmStage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Direct Nexo API calls isolated in a dedicated class so NusaSawit loads cleanly
 * across both Nexo 1.26+ and Nexo 1.30+, or even when Nexo is absent.
 */
public final class NexoBridge {

    private static final NamespacedKey NEXO_FURNITURE_PDC = new NamespacedKey("nexo", "furniture");

    private NexoBridge() {}

    public interface PalmInteractionCallback {
        boolean onPalmRightClick(Player player, Block block, ItemStack itemInHand, EquipmentSlot hand);
        PalmBreakOutcome onPalmBreak(Player player, Block block);
    }

    public static void registerLoadListener(Plugin plugin, Runnable onNexoLoaded) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onItemsLoaded(NexoItemsLoadedEvent event) {
                if (onNexoLoaded != null) {
                    Bukkit.getScheduler().runTask(plugin, onNexoLoaded);
                }
            }
        }, plugin);
    }

    public static void registerPalmListeners(Plugin plugin, PalmInteractionCallback callback) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onCustomBlockInteract(NexoBlockInteractEvent event) {
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                    return;
                }
                if (callback.onPalmRightClick(event.getPlayer(), event.getBlock(), event.getItemInHand(), event.getHand())) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onCustomBlockBreak(NexoBlockBreakEvent event) {
                PalmBreakOutcome outcome = callback.onPalmBreak(event.getPlayer(), event.getBlock());
                if (outcome != PalmBreakOutcome.NOT_HANDLED) {
                    event.setDrop(Drop.emptyDrop());
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onFurnitureInteract(NexoFurnitureInteractEvent event) {
                Block block = event.getBaseEntity().getLocation().getBlock();
                if (callback.onPalmRightClick(event.getPlayer(), block, event.getItemInHand(), event.getHand())) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onFurnitureBreak(NexoFurnitureBreakEvent event) {
                ItemDisplay baseEntity = event.getBaseEntity();
                Block block = baseEntity.getLocation().getBlock();
                PalmBreakOutcome outcome = callback.onPalmBreak(event.getPlayer(), block);

                if (outcome == PalmBreakOutcome.BLOCKED_KEEP_PALM) {
                    event.setCancelled(true);
                    return;
                }

                if (outcome == PalmBreakOutcome.UPROOTED_REMOVED || isPalmStageDisplay(baseEntity)) {
                    event.setDrop(Drop.emptyDrop());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        destroyPalmDisplayEntity(baseEntity);
                        clearColumnBarriers(block);
                    });
                }
            }
        }, plugin);
    }

    public static ItemStack buildItem(String nexoId) {
        if (!NexoItems.exists(nexoId)) {
            return null;
        }
        ItemBuilder builder = NexoItems.itemFromId(nexoId);
        return builder != null ? builder.build() : null;
    }

    public static String idFromItem(ItemStack stack) {
        return NexoItems.idFromItem(stack);
    }

    public static boolean placeStage(String nexoId, Location location) {
        if (!NexoItems.exists(nexoId)) {
            return false;
        }

        if (NexoBlocks.isCustomBlock(nexoId)) {
            CustomBlockMechanic currentBlock = NexoBlocks.customBlockMechanic(location);
            if (currentBlock != null && nexoId.equals(currentBlock.getItemID())) {
                return true;
            }
            removeStage(location);
            NexoBlocks.place(nexoId, location);
            return true;
        }

        if (NexoFurniture.isFurniture(nexoId)) {
            Block baseBlock = location.getBlock();
            ItemDisplay existing = findPalmStageDisplayAt(baseBlock);
            if (existing != null) {
                String existingId = resolveDisplayStageId(existing);
                if (nexoId.equals(existingId)) {
                    return true;
                }
            }
            removeStage(location);
            FurnitureMechanic targetFurn = NexoFurniture.furnitureMechanic(nexoId);
            if (targetFurn != null) {
                targetFurn.place(baseBlock.getLocation(), 0.0f, BlockFace.UP, false);
            } else {
                NexoFurniture.place(nexoId, baseBlock.getLocation(), Rotation.NONE, BlockFace.UP);
            }
            return true;
        }

        return false;
    }

    public static boolean removeStage(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        boolean removed = false;
        Block block = location.getBlock();

        // 1. Remove ALL matching palm ItemDisplay entities in this block column FIRST
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        List<ItemDisplay> nearby = new ArrayList<>(world.getNearbyEntitiesByType(ItemDisplay.class, center, 0.9, 3.0, 0.9));
        for (ItemDisplay display : nearby) {
            Block eb = display.getLocation().getBlock();
            if (eb.getX() == block.getX() && eb.getZ() == block.getZ()
                && eb.getY() >= block.getY() - 1 && eb.getY() <= block.getY() + 3) {
                if (isPalmStageDisplay(display)) {
                    destroyPalmDisplayEntity(display);
                    removed = true;
                }
            }
        }

        // 2. Also check Nexo's hitbox map for any baseEntity bound to this column
        for (int dy = 0; dy <= 3; dy++) {
            ItemDisplay mapped = NexoFurniture.baseEntity(block.getRelative(0, dy, 0));
            if (mapped != null && isPalmStageDisplay(mapped)) {
                destroyPalmDisplayEntity(mapped);
                removed = true;
            }
        }

        // 3. Remove any custom_block / legacy tripwire
        if (NexoBlocks.isCustomBlock(block)) {
            NexoBlocks.remove(location, null, Drop.emptyDrop());
            block.setType(Material.AIR, false);
            removed = true;
        } else if (block.getType() == Material.TRIPWIRE) {
            block.setType(Material.AIR, false);
            removed = true;
        }

        // 4. Clear physical and client-side packet BARRIER blocks (y+0..3)
        clearColumnBarriers(block);
        return removed;
    }

    public static void resyncPalmHitbox(Player player, Location baseLoc, PalmStage stage) {
        if (player == null || !player.isOnline() || baseLoc == null || baseLoc.getWorld() == null) {
            return;
        }
        Block baseBlock = baseLoc.getBlock();
        if (NexoFurniture.isFurniture(stage.nexoId())) {
            int barrierHeight = switch (stage) {
                case STAGE_1 -> 1;
                case STAGE_2 -> 2;
                case STAGE_3_GROWING, STAGE_3_RIPE -> 4;
            };
            BlockData barrierData = Material.BARRIER.createBlockData();
            for (int dy = 0; dy < barrierHeight; dy++) {
                player.sendBlockChange(baseBlock.getRelative(0, dy, 0).getLocation(), barrierData);
            }
        } else {
            player.sendBlockChange(baseBlock.getLocation(), baseBlock.getBlockData());
        }
    }

    public static int cleanupOrphanPalmDisplays(Chunk chunk, Predicate<BlockPosKey> isTrackedPalm) {
        int cleaned = 0;
        Set<BlockPosKey> seenTracked = new HashSet<>();
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay display) || !display.isValid()) {
                continue;
            }
            if (!isPalmStageDisplay(display)) {
                continue;
            }
            Block baseBlock = display.getLocation().getBlock();
            BlockPosKey key = BlockPosKey.of(baseBlock);
            if (!isTrackedPalm.test(key) || !seenTracked.add(key)) {
                destroyPalmDisplayEntity(display);
                clearColumnBarriers(baseBlock);
                cleaned++;
            }
        }
        return cleaned;
    }

    private static ItemDisplay findPalmStageDisplayAt(Block baseBlock) {
        Location center = baseBlock.getLocation().add(0.5, 0.5, 0.5);
        for (ItemDisplay display : baseBlock.getWorld().getNearbyEntitiesByType(ItemDisplay.class, center, 0.75, 1.5, 0.75)) {
            Block eb = display.getLocation().getBlock();
            if (eb.getX() == baseBlock.getX() && eb.getY() == baseBlock.getY() && eb.getZ() == baseBlock.getZ()
                && isPalmStageDisplay(display)) {
                return display;
            }
        }
        return null;
    }

    private static boolean isPalmStageDisplay(ItemDisplay display) {
        String id = resolveDisplayStageId(display);
        return id != null && id.startsWith("sawit_stage_");
    }

    private static String resolveDisplayStageId(ItemDisplay display) {
        if (display == null) {
            return null;
        }
        FurnitureMechanic fm = NexoFurniture.furnitureMechanic(display);
        if (fm != null && fm.getItemID() != null) {
            return fm.getItemID();
        }
        ItemStack shown = display.getItemStack();
        if (shown != null && !shown.getType().isAir()) {
            String fromItem = NexoItems.idFromItem(shown);
            if (fromItem != null) {
                return fromItem;
            }
        }
        return display.getPersistentDataContainer().get(NEXO_FURNITURE_PDC, PersistentDataType.STRING);
    }

    private static void destroyPalmDisplayEntity(ItemDisplay display) {
        if (display == null) {
            return;
        }
        try {
            FurnitureMechanic fm = NexoFurniture.furnitureMechanic(display);
            if (fm != null) {
                fm.removeBaseEntity(display);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (display.isValid()) {
                NexoFurniture.remove(display, null, Drop.emptyDrop());
            }
        } catch (Throwable ignored) {
        }
        if (display.isValid()) {
            display.remove();
        }
    }

    private static void clearColumnBarriers(Block baseBlock) {
        World world = baseBlock.getWorld();
        Location baseCenter = baseBlock.getLocation().add(0.5, 0.5, 0.5);
        for (int dy = 0; dy <= 3; dy++) {
            Block b = baseBlock.getRelative(0, dy, 0);
            if (b.getType() == Material.BARRIER) {
                b.setType(Material.AIR, false);
            }
            if (b.getType().isAir()) {
                Location bLoc = b.getLocation();
                BlockData airData = b.getBlockData();
                for (Player p : world.getPlayers()) {
                    if (p.getLocation().distanceSquared(baseCenter) <= 4096.0) {
                        p.sendBlockChange(bLoc, airData);
                    }
                }
            }
        }
    }
}
