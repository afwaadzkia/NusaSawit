package id.nusaspigot.sawit;

import id.nusaspigot.sawit.model.BlockPosKey;
import id.nusaspigot.sawit.model.PalmStage;
import id.nusaspigot.sawit.model.PlantedPalm;
import id.nusaspigot.sawit.model.PlayerSawitData;
import id.nusaspigot.sawit.service.WoodQuestTracker;
import id.nusaspigot.sawit.storage.SawitDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SawitCoreTest {

    @Test
    void testDatabasePersistenceAndPalmLifecycle(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("nusasawit_test.db");
        UUID playerUuid = UUID.randomUUID();
        BlockPosKey palmPos = new BlockPosKey("world", 120, 65, -340);

        try (SawitDatabase db = new SawitDatabase(dbPath, Logger.getLogger("Test"))) {
            db.initialize();

            PlayerSawitData player = PlayerSawitData.createNew(playerUuid);
            player.setWoodBroken(20_000L);
            player.setLicenseUnlocked(true);
            player.addBonusSlots(4);
            player.addPendingSeeds(2);
            player.incrementTotalHarvests();
            player.addEarnings(14_500.0);

            PlantedPalm palm = new PlantedPalm(
                palmPos,
                playerUuid,
                "PetaniNusa",
                PalmStage.STAGE_1,
                1000L,
                1300L,
                0,
                0
            );
            palm.reduceTimer(180L, 1050L);
            assertEquals(1120L, palm.nextStageEpochSec());
            assertEquals(1, palm.fertilizedInCycle());

            // Advance through all stages to ripe, then harvest (recurring cycle)
            palm.setStage(palm.stage().nextGrowthStage(), 1900L);
            assertEquals(PalmStage.STAGE_2, palm.stage());
            palm.setStage(palm.stage().nextGrowthStage(), 2320L);
            assertEquals(PalmStage.STAGE_3_GROWING, palm.stage());
            palm.setStage(palm.stage().nextGrowthStage(), 2320L);
            assertTrue(palm.stage().isRipe());

            palm.recordHarvest(2740L);
            assertEquals(PalmStage.STAGE_3_GROWING, palm.stage());
            assertEquals(1, palm.totalHarvests());

            db.flushSync(List.of(player.snapshot()), List.of(palm.snapshot()), List.of());
        }

        // Re-open database and verify persistence
        try (SawitDatabase db = new SawitDatabase(dbPath, Logger.getLogger("Test"))) {
            db.initialize();
            List<PlayerSawitData> players = db.loadAllPlayers();
            assertEquals(1, players.size());
            PlayerSawitData loadedPlayer = players.getFirst();
            assertEquals(20_000L, loadedPlayer.woodBroken());
            assertTrue(loadedPlayer.isLicenseUnlocked());
            assertEquals(4, loadedPlayer.bonusSlots());
            assertEquals(2, loadedPlayer.pendingSeeds());
            assertEquals(1L, loadedPlayer.totalHarvests());
            assertEquals(14_500.0, loadedPlayer.totalEarnings(), 0.01);

            List<PlantedPalm> palms = db.loadAllPalms();
            assertEquals(1, palms.size());
            PlantedPalm loadedPalm = palms.getFirst();
            assertEquals(palmPos, loadedPalm.pos());
            assertEquals(PalmStage.STAGE_3_GROWING, loadedPalm.stage());
            assertEquals(1, loadedPalm.totalHarvests());

            // Test deletion
            db.flushSync(List.of(), List.of(), List.of(palmPos));
            assertTrue(db.loadAllPalms().isEmpty());
        }
    }

    @Test
    void testChunkLocalCoordPackingNoCollisions() {
        Set<Integer> seen = new HashSet<>();
        for (int y : new int[]{-64, -1, 0, 64, 128, 319}) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int packed = WoodQuestTracker.packLocalCoord(x + 320, y, z - 480);
                    assertTrue(seen.add(packed), "Collision at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void testNexoPackAssetsExistAndAreValid() throws Exception {
        Path packRoot = Path.of("nexo-pack");
        assertTrue(Files.exists(packRoot.resolve("items/nusasawit.yml")), "Missing nusasawit.yml");

        List<String> pngs = List.of(
            "block/sawit_trunk.png",
            "block/sawit_spine.png",
            "block/sawit_frond.png",
            "block/sawit_frond_side.png",
            "block/sawit_polybag.png",
            "block/sawit_tbs_block.png",
            "block/sawit_flower_bunch.png",
            "block/sawit_crate_side.png",
            "block/sawit_crate_top.png",
            "item/sawit_seed.png",
            "item/sawit_tbs.png",
            "item/sawit_tbs_super.png",
            "item/sawit_cpo.png",
            "item/pupuk_npk.png",
            "item/egrek_sawit.png",
            "item/egrek_master.png"
        );

        for (String rel : pngs) {
            Path p = packRoot.resolve("pack/assets/nusasawit/textures/" + rel);
            assertTrue(Files.exists(p), "Missing texture: " + rel);
            BufferedImage img = ImageIO.read(p.toFile());
            assertNotNull(img, "Unreadable PNG: " + rel);
            assertTrue(img.getWidth() >= 16 && img.getHeight() >= 16, "Invalid dimensions for " + rel);
        }

        List<String> models = List.of(
            "sawit_stage_1.json",
            "sawit_stage_2.json",
            "sawit_stage_3_growing.json",
            "sawit_stage_3_ripe.json",
            "sawit_crate.json"
        );
        for (String m : models) {
            Path mp = packRoot.resolve("pack/assets/nusasawit/models/block/" + m);
            assertTrue(Files.exists(mp), "Missing 3D model: " + m);
            String json = Files.readString(mp);
            assertTrue(json.contains("nusasawit:block/"), "Model missing texture namespace: " + m);
        }
    }
}
