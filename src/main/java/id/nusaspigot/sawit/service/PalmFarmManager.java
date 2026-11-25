package id.nusaspigot.sawit.service;

import id.nusaspigot.sawit.config.SawitConfig;
import id.nusaspigot.sawit.hook.NexoBridge;
import id.nusaspigot.sawit.hook.NexoHook;
import id.nusaspigot.sawit.item.SawitItemFactory;
import id.nusaspigot.sawit.item.SawitItemType;
import id.nusaspigot.sawit.model.BlockPosKey;
import id.nusaspigot.sawit.model.PalmBreakOutcome;
import id.nusaspigot.sawit.model.PalmStage;
import id.nusaspigot.sawit.model.PlantedPalm;
import id.nusaspigot.sawit.model.PlayerSawitData;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Main-thread manager for planting, 3-stage growth, fertilizing, harvesting,
 * owner protection, and Nexo custom block synchronization.
 */
public final class PalmFarmManager implements Listener, NexoBridge.PalmInteractionCallback {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Supplier<SawitConfig> configSupplier;
    private final Function<UUID, PlayerSawitData> playerDataByUuid;
    private final SawitItemFactory itemFactory;
    private final NexoHook nexoHook;
    private final NumberFormat intFormat;

    private record BreakConfirmState(BlockPosKey pos, long expiresEpochMs) {}

    private final Map<BlockPosKey, PlantedPalm> palmsByPos = new HashMap<>();
    private final Map<UUID, Set<BlockPosKey>> palmsByOwner = new HashMap<>();
    private final Set<BlockPosKey> deletedPositions = new HashSet<>();
    private final Map<UUID, Integer> lastInteractTick = new HashMap<>();
    private final Map<BlockPosKey, Integer> lastUprootTick = new HashMap<>();
    private final Map<UUID, BreakConfirmState> breakConfirmByPlayer = new HashMap<>();
    private boolean firingSyntheticPlaceCheck = false;

    public PalmFarmManager(
        Plugin plugin,
        Supplier<SawitConfig> configSupplier,
        Function<UUID, PlayerSawitData> playerDataByUuid,
        SawitItemFactory itemFactory,
        NexoHook nexoHook
    ) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.playerDataByUuid = playerDataByUuid;
        this.itemFactory = itemFactory;
        this.nexoHook = nexoHook;
        this.intFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"));
    }

    public void loadInitialPalms(Collection<PlantedPalm> loaded) {
        palmsByPos.clear();
        palmsByOwner.clear();
        for (PlantedPalm palm : loaded) {
            indexPalm(palm);
        }
    }

    private void indexPalm(PlantedPalm palm) {
        palmsByPos.put(palm.pos(), palm);
        palmsByOwner.computeIfAbsent(palm.ownerUuid(), k -> new HashSet<>()).add(palm.pos());
    }

    private void unindexPalm(BlockPosKey pos) {
        PlantedPalm removed = palmsByPos.remove(pos);
        if (removed != null) {
            Set<BlockPosKey> set = palmsByOwner.get(removed.ownerUuid());
            if (set != null) {
                set.remove(pos);
                if (set.isEmpty()) {
                    palmsByOwner.remove(removed.ownerUuid());
                }
            }
            deletedPositions.add(pos);
        }
    }

    public boolean hasPalmAt(BlockPosKey pos) {
        return palmsByPos.containsKey(pos);
    }

    public int countOwnedPalms(UUID ownerUuid) {
        Set<BlockPosKey> set = palmsByOwner.get(ownerUuid);
        return set == null ? 0 : set.size();
    }

    public int countRipeOwnedPalms(UUID ownerUuid) {
        Set<BlockPosKey> set = palmsByOwner.get(ownerUuid);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int ripe = 0;
        for (BlockPosKey key : set) {
            PlantedPalm palm = palmsByPos.get(key);
            if (palm != null && palm.stage().isRipe()) {
                ripe++;
            }
        }
        return ripe;
    }

    public Collection<PlantedPalm> allPalms() {
        return Collections.unmodifiableCollection(palmsByPos.values());
    }

    public List<PlantedPalm.Snapshot> collectDirtySnapshots() {
        List<PlantedPalm.Snapshot> list = new ArrayList<>();
        for (PlantedPalm palm : palmsByPos.values()) {
            if (palm.isDirty()) {
                list.add(palm.snapshot());
                palm.markClean();
            }
        }
        return list;
    }

    public List<BlockPosKey> drainDeletedPositions() {
        if (deletedPositions.isEmpty()) {
            return List.of();
        }
        List<BlockPosKey> copy = List.copyOf(deletedPositions);
        deletedPositions.clear();
        return copy;
    }

    /**
     * Runs periodically on the main server thread to advance palm growth stages.
     */
    public void tickGrowth() {
        if (palmsByPos.isEmpty()) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        SawitConfig cfg = configSupplier.get();

        for (PlantedPalm palm : palmsByPos.values()) {
            if (palm.stage().isRipe() || now < palm.nextStageEpochSec()) {
                continue;
            }
            advanceStage(palm, now, cfg, true);
        }
    }

    private void advanceStage(PlantedPalm palm, long now, SawitConfig cfg, boolean celebrate) {
        PalmStage next = palm.stage().nextGrowthStage();
        long nextTimer = switch (next) {
            case STAGE_1 -> now + cfg.stage1Seconds();
            case STAGE_2 -> now + cfg.stage2Seconds();
            case STAGE_3_GROWING -> now + cfg.ripenSeconds();
            case STAGE_3_RIPE -> now;
        };
        palm.setStage(next, nextTimer);

        if (palm.pos().isChunkLoaded()) {
            Location loc = palm.pos().toLocation();
            if (loc != null) {
                nexoHook.placePalmStage(loc, next);
                if (celebrate) {
                    loc.getWorld().spawnParticle(
                        next.isRipe() ? Particle.WAX_ON : Particle.HAPPY_VILLAGER,
                        loc.clone().add(0.5, 1.0, 0.5),
                        10, 0.35, 0.45, 0.35, 0.02
                    );
                }
            }
        }
    }

    public void refreshAllLoadedPalmVisuals() {
        for (PlantedPalm palm : palmsByPos.values()) {
            if (palm.pos().isChunkLoaded()) {
                Location loc = palm.pos().toLocation();
                if (loc != null) {
                    nexoHook.placePalmStage(loc, palm.stage());
                }
            }
        }
        nexoHook.cleanupAllLoadedOrphanPalms(this::hasPalmAt);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        String worldName = chunk.getWorld().getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        for (PlantedPalm palm : palmsByPos.values()) {
            BlockPosKey pos = palm.pos();
            if (pos.worldName().equals(worldName) && (pos.x() >> 4) == cx && (pos.z() >> 4) == cz) {
                Location loc = pos.toLocation();
                if (loc != null) {
                    nexoHook.placePalmStage(loc, palm.stage());
                }
            }
        }
        nexoHook.cleanupOrphanPalmsInChunk(chunk, this::hasPalmAt);
    }

    // --- PLANTING & VANILLA INTERACTION ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (firingSyntheticPlaceCheck) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack handItem = event.getItem();

        // 1. Check if player clicked an existing planted palm or its trunk barrier hitbox
        Block palmBase = resolvePalmBaseBlock(clicked);
        if (palmBase != null) {
            if (onPalmRightClick(player, palmBase, handItem, event.getHand())) {
                event.setCancelled(true);
            }
            return;
        }

        // 2. Check if player is trying to plant a Bibit Sawit Tenera
        if (handItem == null || itemFactory.identify(handItem) != SawitItemType.SAWIT_SEED) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND && itemFactory.identify(player.getInventory().getItemInMainHand()) == SawitItemType.SAWIT_SEED) {
            return;
        }
        if (isDuplicateTickInteraction(player)) {
            return;
        }

        handlePlantAttempt(player, clicked, event.getBlockFace(), handItem, event.getHand());
    }

    private void handlePlantAttempt(Player player, Block clickedBlock, BlockFace face, ItemStack seedItem, EquipmentSlot hand) {
        SawitConfig cfg = configSupplier.get();
        PlayerSawitData data = playerDataByUuid.apply(player.getUniqueId());

        // Auto-unlock if admin reduced required-blocks below player's current progress
        if (!data.isLicenseUnlocked() && data.woodBroken() >= cfg.requiredBlocks()) {
            data.setLicenseUnlocked(true);
        }

        if (!data.isLicenseUnlocked() && !player.hasPermission("nusasawit.admin")) {
            long remaining = Math.max(0L, cfg.requiredBlocks() - data.woodBroken());
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
            player.sendMessage(MM.deserialize(
                "<red><bold>[NusaSawit]</bold></red> <gray>Kamu belum memiliki <yellow>Lisensi Perkebunan Sawit</yellow>! "
                    + "Tebang <gold>" + intFormat.format(remaining) + "</gold> blok kayu alami lagi (<green>"
                    + intFormat.format(data.woodBroken()) + "/" + intFormat.format(cfg.requiredBlocks()) + "</green>)."
            ));
            return;
        }

        if (face != BlockFace.UP || !cfg.allowedSoilBlocks().contains(clickedBlock.getType())) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.9f);
            player.sendMessage(MM.deserialize(
                "<red>[NusaSawit]</red> <gray>Bibit sawit hanya bisa ditanam di bagian atas <green>Tanah / Rumput / Podzol / Farmland</green>!"
            ));
            return;
        }

        Block targetBlock = clickedBlock.getRelative(BlockFace.UP);
        BlockPosKey targetPos = BlockPosKey.of(targetBlock);

        boolean clearColumn = !palmsByPos.containsKey(targetPos);
        for (int dy = 0; dy <= 3 && clearColumn; dy++) {
            if (!targetBlock.getRelative(0, dy, 0).getType().isAir()) {
                clearColumn = false;
            }
        }
        if (!clearColumn) {
            player.sendMessage(MM.deserialize(
                "<red>[NusaSawit]</red> <gray>Pastikan terdapat ruang kosong minimal 4 blok ke atas untuk menanam pohon sawit tinggi!</gray>"
            ));
            return;
        }

        int owned = countOwnedPalms(player.getUniqueId());
        int maxSlots = cfg.maxTotalSlots(data.bonusSlots());
        if (owned >= maxSlots && !player.hasPermission("nusasawit.admin")) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
            player.sendMessage(MM.deserialize(
                "<red>[NusaSawit]</red> <gray>Batas lahan sawitmu penuh (<gold>" + owned + "/" + maxSlots
                    + "</gold>)! Upgrade kapasitas slot kebun di <yellow>/sawit</yellow>.</gray>"
            ));
            return;
        }

        // Respect external claim protection plugins (WorldGuard, Lands, GriefPrevention, etc.)
        if (!canBuildAt(player, targetBlock, clickedBlock, seedItem, hand)) {
            player.sendMessage(MM.deserialize(
                "<red>[NusaSawit]</red> <gray>Kamu tidak memiliki izin menanam di area proteksi ini!</gray>"
            ));
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            seedItem.setAmount(seedItem.getAmount() - 1);
        }

        long now = Instant.now().getEpochSecond();
        PlantedPalm palm = new PlantedPalm(
            targetPos,
            player.getUniqueId(),
            player.getName(),
            PalmStage.STAGE_1,
            now,
            now + cfg.stage1Seconds(),
            0,
            0
        );
        palm.markDirty();
        deletedPositions.remove(targetPos);
        indexPalm(palm);

        nexoHook.placePalmStage(targetBlock.getLocation(), PalmStage.STAGE_1);
        player.playSound(targetBlock.getLocation(), Sound.ITEM_CROP_PLANT, 1.0f, 0.95f);
        targetBlock.getWorld().spawnParticle(
            Particle.HAPPY_VILLAGER,
            targetBlock.getLocation().add(0.5, 0.5, 0.5),
            8, 0.25, 0.25, 0.25, 0.02
        );
        player.sendActionBar(MM.deserialize(
            "<green>✔ Bibit Sawit ditanam!</green> <gray>Kapasitas Kebun: <gold>" + (owned + 1) + "/" + maxSlots + "</gold></gray>"
        ));
    }

    private boolean canBuildAt(Player player, Block targetBlock, Block againstBlock, ItemStack item, EquipmentSlot hand) {
        BlockState replacedState = targetBlock.getState();
        firingSyntheticPlaceCheck = true;
        try {
            BlockPlaceEvent checkEvent = new BlockPlaceEvent(targetBlock, replacedState, againstBlock, item, player, true, hand);
            Bukkit.getPluginManager().callEvent(checkEvent);
            return !checkEvent.isCancelled() && checkEvent.canBuild();
        } finally {
            firingSyntheticPlaceCheck = false;
        }
    }

    // --- PALM RIGHT-CLICK (HARVEST / FERTILIZE / INSPECT) ---

    @Override
    public boolean onPalmRightClick(Player player, Block block, ItemStack itemInHand, EquipmentSlot hand) {
        BlockPosKey key = BlockPosKey.of(block);
        PlantedPalm palm = palmsByPos.get(key);
        if (palm == null) {
            return false;
        }
        if (hand == EquipmentSlot.OFF_HAND) {
            return true;
        }
        if (isDuplicateTickInteraction(player)) {
            return true;
        }

        boolean isOwnerOrAdmin = palm.ownerUuid().equals(player.getUniqueId()) || player.hasPermission("nusasawit.admin");
        SawitConfig cfg = configSupplier.get();
        long now = Instant.now().getEpochSecond();

        // Catch up stage immediately if timer already elapsed
        if (!palm.stage().isRipe() && now >= palm.nextStageEpochSec()) {
            advanceStage(palm, now, cfg, false);
        }

        if (!isOwnerOrAdmin) {
            player.sendActionBar(MM.deserialize(
                "<yellow>✦ Pohon Sawit milik <gold>" + palm.ownerName() + "</gold> <dark_gray>• <gray>" + palm.stage().displayLabel()
            ));
            return true;
        }

        SawitItemType heldType = itemFactory.identify(itemInHand);

        // 1. Apply Pupuk NPK Sawit if not yet ripe
        if (heldType == SawitItemType.PUPUK_NPK && !palm.stage().isRipe()) {
            if (palm.fertilizedInCycle() >= cfg.fertilizerMaxUsesPerCycle()) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f);
                player.sendActionBar(MM.deserialize(
                    "<red>Pohon ini sudah mencapai batas maksimal pemupukan (<gold>" + cfg.fertilizerMaxUsesPerCycle() + "x</gold>) pada tahap ini!</red>"
                ));
                return true;
            }
            if (player.getGameMode() != GameMode.CREATIVE && itemInHand != null) {
                itemInHand.setAmount(itemInHand.getAmount() - 1);
            }

            palm.reduceTimer(cfg.fertilizerReduceSeconds(), now);
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 1.0, 0.5), 16, 0.4, 0.5, 0.4, 0.05);
            player.playSound(block.getLocation(), Sound.ITEM_BONE_MEAL_USE, 1.0f, 1.1f);

            if (palm.remainingSeconds(now) <= 0L) {
                advanceStage(palm, now, cfg, true);
                player.sendActionBar(MM.deserialize(
                    "<green>✦ Pupuk NPK berhasil mempercepat pohon ke <gold>" + palm.stage().displayLabel() + "</gold>!</green>"
                ));
            } else {
                player.sendActionBar(MM.deserialize(
                    "<green>✦ Pupuk NPK diberikan!</green> <gray>Sisa waktu: <yellow>" + formatDuration(palm.remainingSeconds(now)) + "</yellow></gray>"
                ));
            }
            return true;
        }

        // 2. Harvest ripe Fresh Fruit Bunches (TBS)
        if (palm.stage().isRipe()) {
            harvestPalm(player, block, palm, heldType, now, cfg);
            return true;
        }

        // 3. Inspect growing status
        player.sendActionBar(MM.deserialize(
            "<green>✦ " + palm.stage().displayLabel() + "</green> <dark_gray>• <gray>Matang dalam: <yellow>"
                + formatDuration(palm.remainingSeconds(now)) + "</yellow> <dark_gray>• <gray>Pupuk: <aqua>"
                + palm.fertilizedInCycle() + "/" + cfg.fertilizerMaxUsesPerCycle() + "</aqua>"
        ));
        return true;
    }

    private void harvestPalm(Player player, Block block, PlantedPalm palm, SawitItemType heldType, long now, SawitConfig cfg) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int tbsAmount = rng.nextInt(cfg.baseTbsMin(), cfg.baseTbsMax() + 1);
        double superChance = cfg.handSuperChance();
        String toolBadge = "<gray>(Tangan Kosong)</gray>";

        if (heldType == SawitItemType.EGREK_MASTER) {
            tbsAmount += cfg.egrekTier2BonusTbs();
            superChance = cfg.egrekTier2SuperChance();
            toolBadge = "<gold>(Egrek Baja Nusantara)</gold>";
        } else if (heldType == SawitItemType.EGREK_SAWIT) {
            tbsAmount += cfg.egrekTier1BonusTbs();
            superChance = cfg.egrekTier1SuperChance();
            toolBadge = "<aqua>(Egrek Sawit)</aqua>";
        }

        boolean gotSuper = rng.nextDouble() < superChance;

        palm.recordHarvest(now + cfg.ripenSeconds());
        nexoHook.placePalmStage(block.getLocation(), PalmStage.STAGE_3_GROWING);

        itemFactory.giveOrDrop(player, SawitItemType.SAWIT_TBS, tbsAmount);
        if (gotSuper) {
            itemFactory.giveOrDrop(player, SawitItemType.SAWIT_TBS_SUPER, 1);
        }

        PlayerSawitData pData = playerDataByUuid.apply(player.getUniqueId());
        pData.incrementTotalHarvests();

        block.getWorld().spawnParticle(Particle.COMPOSTER, block.getLocation().add(0.5, 1.3, 0.5), 18, 0.4, 0.4, 0.4, 0.05);
        player.playSound(block.getLocation(), Sound.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, 1.0f, 0.9f);
        if (gotSuper) {
            player.playSound(block.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.3f);
            player.sendActionBar(MM.deserialize(
                "<gold><bold>★ PANEN SUPER!</bold></gold> <green>+" + tbsAmount + " TBS</green> <yellow>+1 TBS Tenera Super!</yellow> " + toolBadge
            ));
        } else {
            player.sendActionBar(MM.deserialize(
                "<green>✔ Panen berhasil: <gold>+" + tbsAmount + " Tandan Buah Segar (TBS)</gold></green> " + toolBadge
            ));
        }
    }

    // --- BREAKING & WORLD PROTECTION ---

    @Override
    public PalmBreakOutcome onPalmBreak(Player player, Block block) {
        BlockPosKey pos = BlockPosKey.of(block);
        int currentTick = Bukkit.getCurrentTick();
        Integer recentTick = lastUprootTick.get(pos);
        if (recentTick != null && recentTick == currentTick) {
            return PalmBreakOutcome.UPROOTED_REMOVED;
        }

        PlantedPalm palm = palmsByPos.get(pos);
        if (palm == null) {
            return PalmBreakOutcome.NOT_HANDLED;
        }

        boolean isOwner = palm.ownerUuid().equals(player.getUniqueId());
        boolean isAdmin = player.hasPermission("nusasawit.admin");
        if (!isOwner && !isAdmin) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.8f);
            player.sendMessage(MM.deserialize(
                "<red>[NusaSawit]</red> <gray>Pohon sawit ini terkunci milik <gold>" + palm.ownerName() + "</gold>!</gray>"
            ));
            resyncPalmHitbox(player, palm);
            return PalmBreakOutcome.BLOCKED_KEEP_PALM;
        }

        SawitConfig cfg = configSupplier.get();
        if (cfg.requireSneakToBreak() && player.getGameMode() != GameMode.CREATIVE && !player.isSneaking()) {
            long nowMs = System.currentTimeMillis();
            BreakConfirmState prev = breakConfirmByPlayer.get(player.getUniqueId());
            if (prev == null || !prev.pos().equals(pos) || nowMs > prev.expiresEpochMs()) {
                breakConfirmByPlayer.put(player.getUniqueId(), new BreakConfirmState(pos, nowMs + 3500L));
                player.sendActionBar(MM.deserialize(
                    "<yellow>✦ Klik kiri sekali lagi (atau tahan <gold>Shift</gold>) untuk membongkar pohon sawit!</yellow>"
                ));
                resyncPalmHitbox(player, palm);
                return PalmBreakOutcome.BLOCKED_KEEP_PALM;
            }
        }

        breakConfirmByPlayer.remove(player.getUniqueId());
        lastUprootTick.put(pos, currentTick);
        unindexPalm(pos);
        nexoHook.removePalmVisual(block.getLocation());
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_WOOD_BREAK, 1.0f, 0.9f);

        if (cfg.returnSeedOnBreak() && player.getGameMode() != GameMode.CREATIVE) {
            itemFactory.giveOrDrop(player, SawitItemType.SAWIT_SEED, 1);
        }
        player.sendActionBar(MM.deserialize("<yellow>✔ Pohon sawit berhasil dibongkar.</yellow>"));
        return PalmBreakOutcome.UPROOTED_REMOVED;
    }

    private Block resolvePalmBaseBlock(Block clicked) {
        BlockPosKey direct = BlockPosKey.of(clicked);
        if (palmsByPos.containsKey(direct)) {
            return clicked;
        }
        if (clicked.getType() == Material.BARRIER || clicked.getType().isAir()) {
            for (int dy = 1; dy <= 3; dy++) {
                Block below = clicked.getRelative(0, -dy, 0);
                if (palmsByPos.containsKey(BlockPosKey.of(below))) {
                    return below;
                }
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockPosKey clickedKey = BlockPosKey.of(block);
        Integer recentTick = lastUprootTick.get(clickedKey);
        if (recentTick != null && recentTick == Bukkit.getCurrentTick()) {
            event.setDropItems(false);
            event.setExpToDrop(0);
            return;
        }

        Block palmBase = resolvePalmBaseBlock(block);
        if (palmBase != null) {
            PalmBreakOutcome outcome = onPalmBreak(event.getPlayer(), palmBase);
            if (outcome == PalmBreakOutcome.BLOCKED_KEEP_PALM) {
                event.setCancelled(true);
            } else if (outcome == PalmBreakOutcome.UPROOTED_REMOVED) {
                event.setDropItems(false);
                event.setExpToDrop(0);
            }
            return;
        }

        // Prevent breaking the soil block directly underneath a planted palm
        BlockPosKey above = clickedKey.above();
        if (palmsByPos.containsKey(above)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(MM.deserialize(
                "<red>[NusaSawit]</red> <gray>Tidak dapat menghancurkan tanah penyangga pohon sawit! Bongkar sawitnya terlebih dahulu.</gray>"
            ));
            return;
        }

        if (block.getType() == Material.BARRIER || block.getType().isAir()) {
            nexoHook.cleanupOrphanPalmsInChunk(block.getChunk(), this::hasPalmAt);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (palmsByPos.containsKey(BlockPosKey.of(event.getToBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (palmsByPos.containsKey(BlockPosKey.of(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSoilFade(BlockFadeEvent event) {
        if (palmsByPos.containsKey(BlockPosKey.of(event.getBlock()).above())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> {
            BlockPosKey k = BlockPosKey.of(b);
            return palmsByPos.containsKey(k) || palmsByPos.containsKey(k.above());
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> {
            BlockPosKey k = BlockPosKey.of(b);
            return palmsByPos.containsKey(k) || palmsByPos.containsKey(k.above());
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonPushPalm(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            BlockPosKey k = BlockPosKey.of(b);
            if (palmsByPos.containsKey(k) || palmsByPos.containsKey(k.above())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonPullPalm(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            BlockPosKey k = BlockPosKey.of(b);
            if (palmsByPos.containsKey(k) || palmsByPos.containsKey(k.above())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void resyncPalmHitbox(Player player, PlantedPalm palm) {
        Location loc = palm.pos().toLocation();
        PalmStage stage = palm.stage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && loc != null && loc.getWorld() != null) {
                nexoHook.resyncPalmHitbox(player, loc, stage);
            }
        });
    }

    private boolean isDuplicateTickInteraction(Player player) {
        int currentTick = Bukkit.getCurrentTick();
        Integer prev = lastInteractTick.put(player.getUniqueId(), currentTick);
        return prev != null && prev == currentTick;
    }

    public static String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0L) {
            return "Siap!";
        }
        long mins = totalSeconds / 60L;
        long secs = totalSeconds % 60L;
        if (mins >= 60L) {
            long hrs = mins / 60L;
            long remMins = mins % 60L;
            return hrs + "j " + remMins + "m";
        }
        if (mins > 0L) {
            return mins + "m " + secs + "d";
        }
        return secs + "d";
    }
}
