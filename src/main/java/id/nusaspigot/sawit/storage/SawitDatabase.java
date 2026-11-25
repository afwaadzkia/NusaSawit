package id.nusaspigot.sawit.storage;

import id.nusaspigot.sawit.model.BlockPosKey;
import id.nusaspigot.sawit.model.PalmStage;
import id.nusaspigot.sawit.model.PlantedPalm;
import id.nusaspigot.sawit.model.PlayerSawitData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe SQLite storage layer using immutable snapshots so I/O runs off
 * the main server thread without touching Bukkit state.
 */
public final class SawitDatabase implements AutoCloseable {

    private final Path dbFile;
    private final Logger logger;
    private final ExecutorService ioExecutor;
    private Connection connection;

    public SawitDatabase(Path dbFile, Logger logger) {
        this.dbFile = dbFile;
        this.logger = logger;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "NusaSawit-SQLite-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void initialize() throws SQLException {
        try {
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
            Class.forName("org.sqlite.JDBC");
        } catch (Exception ex) {
            throw new SQLException("Failed to prepare SQLite database directory or driver", ex);
        }

        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL;");
            st.execute("PRAGMA synchronous = NORMAL;");
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_progress (
                    uuid TEXT PRIMARY KEY,
                    wood_broken INTEGER NOT NULL DEFAULT 0,
                    license_unlocked INTEGER NOT NULL DEFAULT 0,
                    bonus_slots INTEGER NOT NULL DEFAULT 0,
                    pending_seeds INTEGER NOT NULL DEFAULT 0,
                    total_harvests INTEGER NOT NULL DEFAULT 0,
                    total_earnings REAL NOT NULL DEFAULT 0.0
                );
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS planted_palms (
                    world TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    owner_name TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    planted_at INTEGER NOT NULL,
                    next_stage_at INTEGER NOT NULL,
                    fertilized_count INTEGER NOT NULL DEFAULT 0,
                    total_harvests INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (world, x, y, z)
                );
                """);
        }
    }

    public synchronized List<PlayerSawitData> loadAllPlayers() throws SQLException {
        List<PlayerSawitData> result = new ArrayList<>();
        String sql = "SELECT uuid, wood_broken, license_unlocked, bonus_slots, pending_seeds, total_harvests, total_earnings FROM player_progress";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                result.add(new PlayerSawitData(
                    uuid,
                    rs.getLong("wood_broken"),
                    rs.getInt("license_unlocked") != 0,
                    rs.getInt("bonus_slots"),
                    rs.getInt("pending_seeds"),
                    rs.getLong("total_harvests"),
                    rs.getDouble("total_earnings")
                ));
            }
        }
        return result;
    }

    public synchronized List<PlantedPalm> loadAllPalms() throws SQLException {
        List<PlantedPalm> result = new ArrayList<>();
        String sql = "SELECT world, x, y, z, owner_uuid, owner_name, stage, planted_at, next_stage_at, fertilized_count, total_harvests FROM planted_palms";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BlockPosKey pos = new BlockPosKey(
                    rs.getString("world"),
                    rs.getInt("x"),
                    rs.getInt("y"),
                    rs.getInt("z")
                );
                result.add(new PlantedPalm(
                    pos,
                    UUID.fromString(rs.getString("owner_uuid")),
                    rs.getString("owner_name"),
                    PalmStage.fromNameOrDefault(rs.getString("stage")),
                    rs.getLong("planted_at"),
                    rs.getLong("next_stage_at"),
                    rs.getInt("fertilized_count"),
                    rs.getInt("total_harvests")
                ));
            }
        }
        return result;
    }

    public CompletableFuture<Void> flushAsync(
        Collection<PlayerSawitData.Snapshot> players,
        Collection<PlantedPalm.Snapshot> upsertedPalms,
        Collection<BlockPosKey> deletedPalms
    ) {
        if (players.isEmpty() && upsertedPalms.isEmpty() && deletedPalms.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<PlayerSawitData.Snapshot> playerCopy = List.copyOf(players);
        List<PlantedPalm.Snapshot> palmCopy = List.copyOf(upsertedPalms);
        List<BlockPosKey> deleteCopy = List.copyOf(deletedPalms);

        return CompletableFuture.runAsync(() -> {
            try {
                flushSync(playerCopy, palmCopy, deleteCopy);
            } catch (SQLException ex) {
                logger.log(Level.SEVERE, "Failed to flush NusaSawit snapshots to SQLite", ex);
            }
        }, ioExecutor);
    }

    public synchronized void flushSync(
        Collection<PlayerSawitData.Snapshot> players,
        Collection<PlantedPalm.Snapshot> upsertedPalms,
        Collection<BlockPosKey> deletedPalms
    ) throws SQLException {
        if (connection == null || connection.isClosed()) {
            return;
        }
        boolean prevAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (!players.isEmpty()) {
                String playerSql = """
                    INSERT INTO player_progress (uuid, wood_broken, license_unlocked, bonus_slots, pending_seeds, total_harvests, total_earnings)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        wood_broken = excluded.wood_broken,
                        license_unlocked = excluded.license_unlocked,
                        bonus_slots = excluded.bonus_slots,
                        pending_seeds = excluded.pending_seeds,
                        total_harvests = excluded.total_harvests,
                        total_earnings = excluded.total_earnings;
                    """;
                try (PreparedStatement ps = connection.prepareStatement(playerSql)) {
                    for (PlayerSawitData.Snapshot p : players) {
                        ps.setString(1, p.uuid().toString());
                        ps.setLong(2, p.woodBroken());
                        ps.setInt(3, p.licenseUnlocked() ? 1 : 0);
                        ps.setInt(4, p.bonusSlots());
                        ps.setInt(5, p.pendingSeeds());
                        ps.setLong(6, p.totalHarvests());
                        ps.setDouble(7, p.totalEarnings());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            if (!deletedPalms.isEmpty()) {
                String delSql = "DELETE FROM planted_palms WHERE world = ? AND x = ? AND y = ? AND z = ?";
                try (PreparedStatement ps = connection.prepareStatement(delSql)) {
                    for (BlockPosKey key : deletedPalms) {
                        ps.setString(1, key.worldName());
                        ps.setInt(2, key.x());
                        ps.setInt(3, key.y());
                        ps.setInt(4, key.z());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            if (!upsertedPalms.isEmpty()) {
                String palmSql = """
                    INSERT INTO planted_palms (world, x, y, z, owner_uuid, owner_name, stage, planted_at, next_stage_at, fertilized_count, total_harvests)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(world, x, y, z) DO UPDATE SET
                        owner_uuid = excluded.owner_uuid,
                        owner_name = excluded.owner_name,
                        stage = excluded.stage,
                        planted_at = excluded.planted_at,
                        next_stage_at = excluded.next_stage_at,
                        fertilized_count = excluded.fertilized_count,
                        total_harvests = excluded.total_harvests;
                    """;
                try (PreparedStatement ps = connection.prepareStatement(palmSql)) {
                    for (PlantedPalm.Snapshot palm : upsertedPalms) {
                        ps.setString(1, palm.pos().worldName());
                        ps.setInt(2, palm.pos().x());
                        ps.setInt(3, palm.pos().y());
                        ps.setInt(4, palm.pos().z());
                        ps.setString(5, palm.ownerUuid().toString());
                        ps.setString(6, palm.ownerName());
                        ps.setString(7, palm.stage().name());
                        ps.setLong(8, palm.plantedAtEpochSec());
                        ps.setLong(9, palm.nextStageEpochSec());
                        ps.setInt(10, palm.fertilizedInCycle());
                        ps.setInt(11, palm.totalHarvests());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(prevAutoCommit);
        }
    }

    @Override
    public void close() {
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    logger.log(Level.WARNING, "Error closing NusaSawit SQLite connection", ex);
                }
            }
        }
    }
}
