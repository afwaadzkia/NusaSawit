package id.nusaspigot.sawit.model;

import java.util.UUID;

/**
 * Represents a single oil palm planted in the world.
 * Mutated only on the main server thread; snapshots are copied for async SQLite writes.
 */
public final class PlantedPalm {

    private final BlockPosKey pos;
    private final UUID ownerUuid;
    private String ownerName;
    private PalmStage stage;
    private final long plantedAtEpochSec;
    private long nextStageEpochSec;
    private int fertilizedInCycle;
    private int totalHarvests;
    private boolean dirty;

    public PlantedPalm(
        BlockPosKey pos,
        UUID ownerUuid,
        String ownerName,
        PalmStage stage,
        long plantedAtEpochSec,
        long nextStageEpochSec,
        int fertilizedInCycle,
        int totalHarvests
    ) {
        this.pos = pos;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.stage = stage;
        this.plantedAtEpochSec = plantedAtEpochSec;
        this.nextStageEpochSec = nextStageEpochSec;
        this.fertilizedInCycle = fertilizedInCycle;
        this.totalHarvests = totalHarvests;
        this.dirty = false;
    }

    public BlockPosKey pos() {
        return pos;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String ownerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
        this.dirty = true;
    }

    public PalmStage stage() {
        return stage;
    }

    public void setStage(PalmStage stage, long nextStageEpochSec) {
        this.stage = stage;
        this.nextStageEpochSec = nextStageEpochSec;
        this.fertilizedInCycle = 0;
        this.dirty = true;
    }

    public long plantedAtEpochSec() {
        return plantedAtEpochSec;
    }

    public long nextStageEpochSec() {
        return nextStageEpochSec;
    }

    public void reduceTimer(long seconds, long nowEpochSec) {
        this.nextStageEpochSec = Math.max(nowEpochSec, this.nextStageEpochSec - seconds);
        this.fertilizedInCycle++;
        this.dirty = true;
    }

    public int fertilizedInCycle() {
        return fertilizedInCycle;
    }

    public int totalHarvests() {
        return totalHarvests;
    }

    public void recordHarvest(long nextRipenEpochSec) {
        this.stage = PalmStage.STAGE_3_GROWING;
        this.nextStageEpochSec = nextRipenEpochSec;
        this.fertilizedInCycle = 0;
        this.totalHarvests++;
        this.dirty = true;
    }

    public long remainingSeconds(long nowEpochSec) {
        if (stage.isRipe()) {
            return 0L;
        }
        return Math.max(0L, nextStageEpochSec - nowEpochSec);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        this.dirty = false;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            pos,
            ownerUuid,
            ownerName,
            stage,
            plantedAtEpochSec,
            nextStageEpochSec,
            fertilizedInCycle,
            totalHarvests
        );
    }

    public record Snapshot(
        BlockPosKey pos,
        UUID ownerUuid,
        String ownerName,
        PalmStage stage,
        long plantedAtEpochSec,
        long nextStageEpochSec,
        int fertilizedInCycle,
        int totalHarvests
    ) {}
}
