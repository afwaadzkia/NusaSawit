package id.nusaspigot.sawit;

import id.nusaspigot.sawit.command.SawitCommand;
import id.nusaspigot.sawit.config.SawitConfig;
import id.nusaspigot.sawit.gui.SawitAdminGuiManager;
import id.nusaspigot.sawit.gui.SawitGuiManager;
import id.nusaspigot.sawit.hook.NexoBridge;
import id.nusaspigot.sawit.hook.NexoHook;
import id.nusaspigot.sawit.hook.VaultEconomyHook;
import id.nusaspigot.sawit.item.SawitItemFactory;
import id.nusaspigot.sawit.model.BlockPosKey;
import id.nusaspigot.sawit.model.PlantedPalm;
import id.nusaspigot.sawit.model.PlayerSawitData;
import id.nusaspigot.sawit.service.PalmFarmManager;
import id.nusaspigot.sawit.service.SawitEconomyService;
import id.nusaspigot.sawit.service.WoodQuestTracker;
import id.nusaspigot.sawit.storage.SawitDatabase;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * NusaSawit main plugin entry point.
 * All world, entity, and player state changes run strictly on the main server thread,
 * while SQLite persistence runs asynchronously on immutable snapshots.
 */
public final class NusaSawitPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private SawitConfig sawitConfig;
    private SawitDatabase database;
    private VaultEconomyHook economyHook;
    private NexoHook nexoHook;
    private SawitItemFactory itemFactory;
    private PalmFarmManager farmManager;
    private SawitEconomyService economyService;
    private SawitGuiManager guiManager;
    private SawitAdminGuiManager adminGuiManager;

    private final Map<UUID, PlayerSawitData> playerCache = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.sawitConfig = new SawitConfig(getConfig());

        this.database = new SawitDatabase(getDataFolder().toPath().resolve("nusasawit.db"), getLogger());
        try {
            this.database.initialize();
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite database; disabling NusaSawit", ex);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.economyHook = new VaultEconomyHook();
        if (this.economyHook.setup()) {
            getLogger().info("Hooked into Vault Economy successfully.");
        } else {
            getLogger().warning("Vault Economy provider not detected yet; will retry lazily on transactions.");
        }

        this.nexoHook = new NexoHook(this);
        this.itemFactory = new SawitItemFactory(this, nexoHook);
        this.farmManager = new PalmFarmManager(
            this,
            this::sawitConfig,
            this::getPlayerData,
            itemFactory,
            nexoHook
        );

        try {
            for (PlayerSawitData p : database.loadAllPlayers()) {
                playerCache.put(p.uuid(), p);
            }
            List<PlantedPalm> loadedPalms = database.loadAllPalms();
            farmManager.loadInitialPalms(loadedPalms);
            getLogger().info("Loaded " + playerCache.size() + " player records and " + loadedPalms.size() + " planted oil palms.");
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "Failed to load saved NusaSawit data", ex);
        }

        this.nexoHook.initialize(
            sawitConfig.autoSyncNexoPack(),
            sawitConfig.overwriteExistingNexoPack(),
            farmManager::refreshAllLoadedPalmVisuals
        );
        if (nexoHook.isNexoEnabled()) {
            NexoBridge.registerPalmListeners(this, farmManager);
            getLogger().info("Hooked into Nexo custom blocks & items API.");
        } else {
            getLogger().info("Nexo not installed yet; running with vanilla visual fallback and exported nexo-pack.");
        }

        this.economyService = new SawitEconomyService(
            this::sawitConfig,
            p -> getPlayerData(p.getUniqueId()),
            economyHook,
            itemFactory
        );
        this.guiManager = new SawitGuiManager(
            this::sawitConfig,
            p -> getPlayerData(p.getUniqueId()),
            farmManager,
            economyService,
            economyHook,
            itemFactory
        );
        this.adminGuiManager = new SawitAdminGuiManager(
            this,
            economyHook,
            itemFactory,
            guiManager::openMainMenu
        );
        this.guiManager.setAdminMenuOpener(adminGuiManager::openAdminMenu);

        WoodQuestTracker questTracker = new WoodQuestTracker(
            this,
            this::sawitConfig,
            p -> getPlayerData(p.getUniqueId()),
            itemFactory
        );

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(questTracker, this);
        pm.registerEvents(farmManager, this);
        pm.registerEvents(guiManager, this);
        pm.registerEvents(adminGuiManager, this);

        PluginCommand cmd = getCommand("sawit");
        if (cmd != null) {
            SawitCommand executor = new SawitCommand(this, guiManager, adminGuiManager, farmManager, economyService, itemFactory, nexoHook);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // Main-thread growth stage ticker (every 20 ticks = 1 second)
        Bukkit.getScheduler().runTaskTimer(this, farmManager::tickGrowth, 20L, 20L);

        // Sweep any orphaned/stuck palm ItemDisplay entities once worlds & Nexo finish initializing
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int cleaned = nexoHook.cleanupAllLoadedOrphanPalms(farmManager::hasPalmAt);
            if (cleaned > 0) {
                getLogger().info("Cleaned up " + cleaned + " orphaned oil palm display entities.");
            }
        }, 60L);

        // Periodic async snapshot persistence (every 600 ticks = 30 seconds)
        Bukkit.getScheduler().runTaskTimer(this, this::flushDirtyAsync, 600L, 600L);
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (database != null && farmManager != null) {
            try {
                List<PlayerSawitData.Snapshot> dirtyPlayers = collectDirtyPlayerSnapshots();
                List<PlantedPalm.Snapshot> dirtyPalms = farmManager.collectDirtySnapshots();
                List<BlockPosKey> deletedPalms = farmManager.drainDeletedPositions();
                database.flushSync(dirtyPlayers, dirtyPalms, deletedPalms);
            } catch (SQLException ex) {
                getLogger().log(Level.SEVERE, "Failed to perform shutdown flush for NusaSawit", ex);
            } finally {
                database.close();
            }
        }
    }

    public SawitConfig sawitConfig() {
        return sawitConfig;
    }

    public void reloadSawitConfig() {
        reloadConfig();
        this.sawitConfig = new SawitConfig(getConfig());
    }

    public void recheckOnlinePlayerLicenses() {
        long target = sawitConfig.requiredBlocks();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerSawitData data = playerCache.get(p.getUniqueId());
            if (data != null && !data.isLicenseUnlocked() && data.woodBroken() >= target) {
                data.setLicenseUnlocked(true);
            }
        }
    }

    public PlayerSawitData getPlayerData(UUID uuid) {
        return playerCache.computeIfAbsent(uuid, PlayerSawitData::createNew);
    }

    private List<PlayerSawitData.Snapshot> collectDirtyPlayerSnapshots() {
        List<PlayerSawitData.Snapshot> list = new ArrayList<>();
        for (PlayerSawitData data : playerCache.values()) {
            if (data.isDirty()) {
                list.add(data.snapshot());
                data.markClean();
            }
        }
        return list;
    }

    private void flushDirtyAsync() {
        List<PlayerSawitData.Snapshot> dirtyPlayers = collectDirtyPlayerSnapshots();
        List<PlantedPalm.Snapshot> dirtyPalms = farmManager.collectDirtySnapshots();
        List<BlockPosKey> deletedPalms = farmManager.drainDeletedPositions();
        database.flushAsync(dirtyPlayers, dirtyPalms, deletedPalms);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerSawitData data = getPlayerData(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            var world = player.getWorld();
            int baseCx = player.getLocation().getBlockX() >> 4;
            int baseCz = player.getLocation().getBlockZ() >> 4;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (world.isChunkLoaded(baseCx + dx, baseCz + dz)) {
                        nexoHook.cleanupOrphanPalmsInChunk(world.getChunkAt(baseCx + dx, baseCz + dz), farmManager::hasPalmAt);
                    }
                }
            }
            if (data.pendingSeeds() > 0) {
                player.sendMessage(MM.deserialize(
                    "<green><bold>[NusaSawit]</bold></green> <gray>Kamu memiliki <gold>" + data.pendingSeeds()
                        + "x Bibit Sawit Tenera</gold> belum diklaim! Ketik <yellow>/sawit claim</yellow>.</gray>"
                ));
            }
        }, 40L);
    }
}
