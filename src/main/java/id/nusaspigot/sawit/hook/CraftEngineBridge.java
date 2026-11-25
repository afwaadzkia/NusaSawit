package id.nusaspigot.sawit.hook;

import id.nusaspigot.sawit.model.BlockPosKey;
import id.nusaspigot.sawit.model.PalmBreakOutcome;
import id.nusaspigot.sawit.model.PalmStage;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Direct CraftEngine API calls isolated in a dedicated class so NusaSawit loads cleanly
 * across both CraftEngine 0.0.67.x (1.21.11) and CraftEngine 26.x, or when absent.
 */
public final class CraftEngineBridge {

    public static final String NAMESPACE = "nusasawit";
    private static final String STAGE_PREFIX = NAMESPACE + ":sawit_stage_";

    private static volatile Method cachedByIdMethod;
    private static volatile Method cachedBuildStackMethod;

    private CraftEngineBridge() {}

    public static Key toKey(String id) {
        if (id == null || id.isBlank()) {
            return Key.of(NAMESPACE, "unknown");
        }
        int idx = id.indexOf(':');
        if (idx > 0) {
            return Key.of(id.substring(0, idx), id.substring(idx + 1));
        }
        return Key.of(NAMESPACE, id);
    }

    public static void registerLoadListener(Plugin plugin, Runnable onCraftEngineLoaded) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onReload(CraftEngineReloadEvent event) {
                if (onCraftEngineLoaded != null) {
                    Bukkit.getScheduler().runTask(plugin, onCraftEngineLoaded);
                }
            }
        }, plugin);
    }

    public static void registerPalmListeners(Plugin plugin, PalmInteractionCallback callback) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onCustomBlockInteract(CustomBlockInteractEvent event) {
                if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK) {
                    return;
                }
                EquipmentSlot slot = event.hand() == InteractionHand.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
                Player player = event.getPlayer();
                ItemStack held = slot == EquipmentSlot.OFF_HAND
                    ? player.getInventory().getItemInOffHand()
                    : player.getInventory().getItemInMainHand();
                if (callback.onPalmRightClick(player, event.bukkitBlock(), held, slot)) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onCustomBlockBreak(CustomBlockBreakEvent event) {
                PalmBreakOutcome outcome = callback.onPalmBreak(event.getPlayer(), event.bukkitBlock());
                if (outcome != PalmBreakOutcome.NOT_HANDLED) {
                    event.setDropItems(false);
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onFurnitureInteract(FurnitureInteractEvent event) {
                BukkitFurniture furniture = event.furniture();
                Block block = furniture.location().getBlock();
                Player player = event.getPlayer();
                EquipmentSlot slot = event.hand() == InteractionHand.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
                ItemStack held = slot == EquipmentSlot.OFF_HAND
                    ? player.getInventory().getItemInOffHand()
                    : player.getInventory().getItemInMainHand();
                if (callback.onPalmRightClick(player, block, held, slot)) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onFurnitureBreak(FurnitureBreakEvent event) {
                BukkitFurniture furniture = event.furniture();
                Block block = furniture.location().getBlock();
                PalmBreakOutcome outcome = callback.onPalmBreak(event.getPlayer(), block);

                if (outcome == PalmBreakOutcome.BLOCKED_KEEP_PALM) {
                    event.setCancelled(true);
                    return;
                }

                if (outcome == PalmBreakOutcome.UPROOTED_REMOVED || isPalmStageKey(furniture.id())) {
                    event.setDropItems(false);
                    event.setCancelled(true);
                    destroyFurniture(furniture);
                }
            }
        }, plugin);
    }

    public static ItemStack buildItem(String itemId) {
        try {
            Key key = toKey(itemId);
            if (!CraftEngineItems.loadedItems().containsKey(key)) {
                return null;
            }
            Method byId = cachedByIdMethod;
            if (byId == null) {
                byId = CraftEngineItems.class.getMethod("byId", Key.class);
                cachedByIdMethod = byId;
            }
            Object definition = byId.invoke(null, key);
            if (definition == null) {
                return null;
            }
            Method buildMethod = cachedBuildStackMethod;
            if (buildMethod == null || !buildMethod.getDeclaringClass().isInstance(definition)) {
                try {
                    // CraftEngine 26.x (BukkitItemDefinition#buildBukkitItem)
                    buildMethod = definition.getClass().getMethod("buildBukkitItem");
                } catch (NoSuchMethodException ex) {
                    // CraftEngine 0.0.67.x (BuildableItem#buildItemStack)
                    buildMethod = definition.getClass().getMethod("buildItemStack");
                }
                cachedBuildStackMethod = buildMethod;
            }
            Object built = buildMethod.invoke(definition);
            if (built instanceof ItemStack stack && !stack.getType().isAir()) {
                return stack.clone();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String idFromItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        try {
            Key key = CraftEngineItems.getCustomItemId(stack);
            if (key == null) {
                return null;
            }
            if (NAMESPACE.equalsIgnoreCase(key.namespace())) {
                return key.value();
            }
            return key.asString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean placeStage(String stageId, Location location) {
        Key key = toKey(stageId);
        Block baseBlock = location.getBlock();

        try {
            if (CraftEngineBlocks.loadedBlocks().containsKey(key)) {
                ImmutableBlockState currentState = CraftEngineBlocks.getCustomBlockState(baseBlock);
                if (currentState != null && !currentState.isEmpty() && currentState.owner().matchesKey(key)) {
                    return true;
                }
                removeStage(location);
                return CraftEngineBlocks.place(baseBlock.getLocation(), key, false);
            }

            if (CraftEngineFurniture.loadedFurniture().containsKey(key)) {
                BukkitFurniture existing = findPalmStageFurnitureAt(baseBlock);
                if (existing != null && key.equals(existing.id())) {
                    return true;
                }
                removeStage(location);
                Location anchor = baseBlock.getLocation().add(0.5, 0.0, 0.5);
                BukkitFurniture placed = CraftEngineFurniture.place(anchor, key);
                return placed != null;
            }
        } catch (Throwable ignored) {
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

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        List<Entity> nearby = new ArrayList<>(world.getNearbyEntities(center, 0.9, 3.0, 0.9));
        for (Entity entity : nearby) {
            Block eb = entity.getLocation().getBlock();
            if (eb.getX() == block.getX() && eb.getZ() == block.getZ()
                && eb.getY() >= block.getY() - 1 && eb.getY() <= block.getY() + 3) {
                BukkitFurniture bf = resolveFurniture(entity);
                if (bf != null && isPalmStageKey(bf.id())) {
                    destroyFurniture(bf);
                    removed = true;
                } else if (entity instanceof ItemDisplay display && isPalmStageItemDisplay(display)) {
                    destroyEntityFallback(display);
                    removed = true;
                }
            }
        }

        try {
            if (CraftEngineBlocks.isCustomBlock(block)) {
                CraftEngineBlocks.remove(block);
                block.setType(Material.AIR, false);
                removed = true;
            }
        } catch (Throwable ignored) {
        }

        for (int dy = 0; dy <= 3; dy++) {
            Block b = block.getRelative(0, dy, 0);
            if (b.getType() == Material.BARRIER) {
                b.setType(Material.AIR, false);
            }
        }
        return removed;
    }

    public static void resyncPalmHitbox(Player player, Location baseLoc, PalmStage stage) {
        if (player == null || !player.isOnline() || baseLoc == null || baseLoc.getWorld() == null) {
            return;
        }
        Block baseBlock = baseLoc.getBlock();
        BukkitFurniture bf = findPalmStageFurnitureAt(baseBlock);
        if (bf != null) {
            try {
                bf.refresh();
                return;
            } catch (Throwable ignored) {
            }
        }
        player.sendBlockChange(baseBlock.getLocation(), baseBlock.getBlockData());
    }

    public static int cleanupOrphanPalmEntities(Chunk chunk, Predicate<BlockPosKey> isTrackedPalm) {
        int cleaned = 0;
        Set<BlockPosKey> seenTracked = new HashSet<>();
        for (Entity entity : chunk.getEntities()) {
            if (!entity.isValid()) {
                continue;
            }
            BukkitFurniture bf = resolveFurniture(entity);
            if (bf != null && isPalmStageKey(bf.id())) {
                Block baseBlock = bf.location().getBlock();
                BlockPosKey key = BlockPosKey.of(baseBlock);
                if (!isTrackedPalm.test(key) || !seenTracked.add(key)) {
                    destroyFurniture(bf);
                    cleaned++;
                }
            } else if (entity instanceof ItemDisplay display && isPalmStageItemDisplay(display)) {
                Block baseBlock = display.getLocation().getBlock();
                BlockPosKey key = BlockPosKey.of(baseBlock);
                if (!isTrackedPalm.test(key) || !seenTracked.add(key)) {
                    destroyEntityFallback(display);
                    cleaned++;
                }
            }
        }
        return cleaned;
    }

    private static BukkitFurniture findPalmStageFurnitureAt(Block baseBlock) {
        Location center = baseBlock.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : baseBlock.getWorld().getNearbyEntities(center, 0.8, 1.5, 0.8)) {
            BukkitFurniture bf = resolveFurniture(entity);
            if (bf != null && isPalmStageKey(bf.id())) {
                Block fb = bf.location().getBlock();
                if (fb.getX() == baseBlock.getX() && fb.getY() == baseBlock.getY() && fb.getZ() == baseBlock.getZ()) {
                    return bf;
                }
            }
        }
        return null;
    }

    private static BukkitFurniture resolveFurniture(Entity entity) {
        try {
            if (CraftEngineFurniture.isFurniture(entity)) {
                return CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isPalmStageKey(Key key) {
        if (key == null) {
            return false;
        }
        String full = key.asString().toLowerCase(Locale.ROOT);
        return full.startsWith(STAGE_PREFIX) || key.value().toLowerCase(Locale.ROOT).startsWith("sawit_stage_");
    }

    private static boolean isPalmStageItemDisplay(ItemDisplay display) {
        ItemStack shown = display.getItemStack();
        if (shown == null || shown.getType().isAir()) {
            return false;
        }
        String id = idFromItem(shown);
        return id != null && id.toLowerCase(Locale.ROOT).startsWith("sawit_stage_");
    }

    private static void destroyFurniture(BukkitFurniture furniture) {
        if (furniture == null) {
            return;
        }
        Entity base = null;
        try {
            base = furniture.bukkitEntity();
        } catch (Throwable ignored) {
        }
        try {
            if (base != null) {
                CraftEngineFurniture.remove(base, false, false);
            }
        } catch (Throwable ignored) {
        }
        try {
            furniture.destroy();
        } catch (Throwable ignored) {
        }
        if (base != null && base.isValid()) {
            base.remove();
        }
    }

    private static void destroyEntityFallback(Entity entity) {
        try {
            CraftEngineFurniture.remove(entity, false, false);
        } catch (Throwable ignored) {
        }
        if (entity.isValid()) {
            entity.remove();
        }
    }
}
