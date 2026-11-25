package id.nusaspigot.sawit.hook;

import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.CustomFurniture;
import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.CustomBlockBreakEvent;
import dev.lone.itemsadder.api.Events.CustomBlockInteractEvent;
import dev.lone.itemsadder.api.Events.FurnitureBreakEvent;
import dev.lone.itemsadder.api.Events.FurnitureInteractEvent;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import id.nusaspigot.sawit.model.BlockPosKey;
import id.nusaspigot.sawit.model.PalmBreakOutcome;
import id.nusaspigot.sawit.model.PalmStage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Direct ItemsAdder API calls isolated in a dedicated class so NusaSawit loads
 * cleanly across ItemsAdder 3.6+ and 4.0+, or when ItemsAdder is not installed.
 */
public final class ItemsAdderBridge {

    public static final String NAMESPACE = "nusasawit";
    private static final String STAGE_PREFIX = NAMESPACE + ":sawit_stage_";

    private ItemsAdderBridge() {}

    public static String namespaced(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.contains(":") ? id : NAMESPACE + ":" + id;
    }

    public static void registerLoadListener(Plugin plugin, Runnable onItemsAdderLoaded) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onItemsAdderLoaded(ItemsAdderLoadDataEvent event) {
                if (onItemsAdderLoaded != null) {
                    Bukkit.getScheduler().runTask(plugin, onItemsAdderLoaded);
                }
            }
        }, plugin);
    }

    public static void registerPalmListeners(Plugin plugin, PalmInteractionCallback callback) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onCustomBlockInteract(CustomBlockInteractEvent event) {
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                    return;
                }
                if (callback.onPalmRightClick(event.getPlayer(), event.getBlockClicked(), event.getItem(), event.getHand())) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onCustomBlockBreak(CustomBlockBreakEvent event) {
                PalmBreakOutcome outcome = callback.onPalmBreak(event.getPlayer(), event.getBlock());
                if (outcome != PalmBreakOutcome.NOT_HANDLED) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onFurnitureInteract(FurnitureInteractEvent event) {
                Entity entity = event.getBukkitEntity();
                if (entity == null) {
                    return;
                }
                Block block = entity.getLocation().getBlock();
                Player player = event.getPlayer();
                ItemStack held = player.getInventory().getItemInMainHand();
                if (callback.onPalmRightClick(player, block, held, EquipmentSlot.HAND)) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onFurnitureBreak(FurnitureBreakEvent event) {
                Entity entity = event.getBukkitEntity();
                if (entity == null) {
                    return;
                }
                Block block = entity.getLocation().getBlock();
                PalmBreakOutcome outcome = callback.onPalmBreak(event.getPlayer(), block);

                if (outcome == PalmBreakOutcome.BLOCKED_KEEP_PALM) {
                    event.setCancelled(true);
                    return;
                }

                if (outcome == PalmBreakOutcome.UPROOTED_REMOVED || isPalmStageEntity(entity, event.getNamespacedID())) {
                    event.setCancelled(true);
                    destroyPalmFurnitureEntity(entity);
                    clearColumnBarriers(block);
                }
            }
        }, plugin);
    }

    public static ItemStack buildItem(String itemId) {
        String nsId = namespaced(itemId);
        if (!CustomStack.isInRegistry(nsId)) {
            return null;
        }
        CustomStack customStack = CustomStack.getInstance(nsId);
        if (customStack == null || customStack.getItemStack() == null) {
            return null;
        }
        return customStack.getItemStack().clone();
    }

    public static String idFromItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        CustomStack customStack = CustomStack.byItemStack(stack);
        if (customStack == null) {
            return null;
        }
        if (NAMESPACE.equalsIgnoreCase(customStack.getNamespace())) {
            return customStack.getId();
        }
        return customStack.getNamespacedID();
    }

    public static boolean placeStage(String stageId, Location location) {
        String nsId = namespaced(stageId);
        if (!CustomStack.isInRegistry(nsId)) {
            return false;
        }

        Block baseBlock = location.getBlock();

        if (CustomBlock.isInRegistry(nsId)) {
            CustomBlock existingBlock = CustomBlock.byAlreadyPlaced(baseBlock);
            if (existingBlock != null && nsId.equalsIgnoreCase(existingBlock.getNamespacedID())) {
                return true;
            }
            removeStage(location);
            return CustomBlock.place(nsId, baseBlock.getLocation()) != null;
        }

        if (CustomFurniture.getNamespacedIdsInRegistry().contains(nsId)) {
            Entity existingEntity = findPalmStageEntityAt(baseBlock);
            if (existingEntity != null) {
                String existingNsId = resolveEntityNamespacedId(existingEntity);
                if (nsId.equalsIgnoreCase(existingNsId)) {
                    return true;
                }
            }
            removeStage(location);
            CustomFurniture spawned = CustomFurniture.spawn(nsId, baseBlock);
            if (spawned == null) {
                spawned = CustomFurniture.spawnPreciseNonSolid(nsId, baseBlock.getLocation().add(0.5, 0.5, 0.5));
            }
            return spawned != null;
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

        // 1. Remove ALL matching palm furniture entities in this column FIRST
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        List<Entity> nearby = new ArrayList<>(world.getNearbyEntities(center, 0.9, 3.0, 0.9));
        for (Entity entity : nearby) {
            if (!isSupportedFurnitureCarrier(entity)) {
                continue;
            }
            Block eb = entity.getLocation().getBlock();
            if (eb.getX() == block.getX() && eb.getZ() == block.getZ()
                && eb.getY() >= block.getY() - 1 && eb.getY() <= block.getY() + 3) {
                if (isPalmStageEntity(entity, null)) {
                    destroyPalmFurnitureEntity(entity);
                    removed = true;
                }
            }
        }

        // 2. Also query ItemsAdder's block lookup along the trunk column
        for (int dy = 0; dy <= 3; dy++) {
            CustomFurniture cf = CustomFurniture.byAlreadySpawned(block.getRelative(0, dy, 0));
            if (cf != null && isPalmStageNamespacedId(cf.getNamespacedID())) {
                try {
                    cf.remove(false);
                } catch (Throwable ignored) {
                }
                Entity cfEntity = cf.getEntity();
                if (cfEntity != null && cfEntity.isValid()) {
                    cfEntity.remove();
                }
                removed = true;
            }
        }

        // 3. Remove any ItemsAdder custom block at base position
        if (CustomBlock.byAlreadyPlaced(block) != null) {
            CustomBlock.remove(block.getLocation());
            block.setType(Material.AIR, false);
            removed = true;
        }

        // 4. Clear trunk BARRIER blocks (y+0..3) and resync AIR to nearby clients
        clearColumnBarriers(block);
        return removed;
    }

    public static void resyncPalmHitbox(Player player, Location baseLoc, PalmStage stage) {
        if (player == null || !player.isOnline() || baseLoc == null || baseLoc.getWorld() == null) {
            return;
        }
        Block baseBlock = baseLoc.getBlock();
        int height = switch (stage) {
            case STAGE_1 -> 1;
            case STAGE_2 -> 2;
            case STAGE_3_GROWING, STAGE_3_RIPE -> 4;
        };
        for (int dy = 0; dy < height; dy++) {
            Block b = baseBlock.getRelative(0, dy, 0);
            BlockData data = b.getType().isAir() ? Material.BARRIER.createBlockData() : b.getBlockData();
            player.sendBlockChange(b.getLocation(), data);
        }
    }

    public static int cleanupOrphanPalmEntities(Chunk chunk, Predicate<BlockPosKey> isTrackedPalm) {
        int cleaned = 0;
        Set<BlockPosKey> seenTracked = new HashSet<>();
        for (Entity entity : chunk.getEntities()) {
            if (!entity.isValid() || !isSupportedFurnitureCarrier(entity)) {
                continue;
            }
            if (!isPalmStageEntity(entity, null)) {
                continue;
            }
            Block baseBlock = entity.getLocation().getBlock();
            BlockPosKey key = BlockPosKey.of(baseBlock);
            if (!isTrackedPalm.test(key) || !seenTracked.add(key)) {
                destroyPalmFurnitureEntity(entity);
                clearColumnBarriers(baseBlock);
                cleaned++;
            }
        }
        return cleaned;
    }

    private static Entity findPalmStageEntityAt(Block baseBlock) {
        Location center = baseBlock.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : baseBlock.getWorld().getNearbyEntities(center, 0.75, 1.5, 0.75)) {
            if (!isSupportedFurnitureCarrier(entity)) {
                continue;
            }
            Block eb = entity.getLocation().getBlock();
            if (eb.getX() == baseBlock.getX() && eb.getY() == baseBlock.getY() && eb.getZ() == baseBlock.getZ()
                && isPalmStageEntity(entity, null)) {
                return entity;
            }
        }
        return null;
    }

    private static boolean isSupportedFurnitureCarrier(Entity entity) {
        return entity instanceof ItemDisplay || entity instanceof ArmorStand || entity instanceof ItemFrame;
    }

    private static boolean isPalmStageEntity(Entity entity, String knownNamespacedId) {
        if (isPalmStageNamespacedId(knownNamespacedId)) {
            return true;
        }
        return isPalmStageNamespacedId(resolveEntityNamespacedId(entity));
    }

    private static boolean isPalmStageNamespacedId(String nsId) {
        if (nsId == null) {
            return false;
        }
        String lower = nsId.toLowerCase(Locale.ROOT);
        return lower.startsWith(STAGE_PREFIX) || lower.startsWith("sawit_stage_");
    }

    private static String resolveEntityNamespacedId(Entity entity) {
        if (entity == null) {
            return null;
        }
        try {
            CustomFurniture cf = CustomFurniture.byAlreadySpawned(entity);
            if (cf != null && cf.getNamespacedID() != null) {
                return cf.getNamespacedID();
            }
        } catch (Throwable ignored) {
        }
        ItemStack carried = null;
        if (entity instanceof ItemDisplay display) {
            carried = display.getItemStack();
        } else if (entity instanceof ArmorStand stand && stand.getEquipment() != null) {
            carried = stand.getEquipment().getHelmet();
        } else if (entity instanceof ItemFrame frame) {
            carried = frame.getItem();
        }
        if (carried != null && !carried.getType().isAir()) {
            CustomStack cs = CustomStack.byItemStack(carried);
            if (cs != null) {
                return cs.getNamespacedID();
            }
        }
        return null;
    }

    private static void destroyPalmFurnitureEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            CustomFurniture.remove(entity, false);
        } catch (Throwable ignored) {
        }
        if (entity.isValid()) {
            entity.remove();
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
