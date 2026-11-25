package id.nusaspigot.sawit.model;

import java.util.UUID;

/**
 * Tracks a player's wood-chopping license quest, plantation slot upgrades,
 * queued seed rewards, and lifetime plantation statistics.
 */
public final class PlayerSawitData {

    private final UUID uuid;
    private long woodBroken;
    private boolean licenseUnlocked;
    private int bonusSlots;
    private int pendingSeeds;
    private long totalHarvests;
    private double totalEarnings;
    private boolean dirty;

    public PlayerSawitData(
        UUID uuid,
        long woodBroken,
        boolean licenseUnlocked,
        int bonusSlots,
        int pendingSeeds,
        long totalHarvests,
        double totalEarnings
    ) {
        this.uuid = uuid;
        this.woodBroken = woodBroken;
        this.licenseUnlocked = licenseUnlocked;
        this.bonusSlots = bonusSlots;
        this.pendingSeeds = pendingSeeds;
        this.totalHarvests = totalHarvests;
        this.totalEarnings = totalEarnings;
        this.dirty = false;
    }

    public static PlayerSawitData createNew(UUID uuid) {
        return new PlayerSawitData(uuid, 0L, false, 0, 0, 0L, 0.0D);
    }

    public UUID uuid() {
        return uuid;
    }

    public long woodBroken() {
        return woodBroken;
    }

    public void setWoodBroken(long woodBroken) {
        this.woodBroken = Math.max(0L, woodBroken);
        this.dirty = true;
    }

    public long incrementWoodBroken() {
        this.woodBroken++;
        this.dirty = true;
        return this.woodBroken;
    }

    public boolean isLicenseUnlocked() {
        return licenseUnlocked;
    }

    public void setLicenseUnlocked(boolean licenseUnlocked) {
        this.licenseUnlocked = licenseUnlocked;
        this.dirty = true;
    }

    public int bonusSlots() {
        return bonusSlots;
    }

    public void setBonusSlots(int bonusSlots) {
        this.bonusSlots = Math.max(0, bonusSlots);
        this.dirty = true;
    }

    public void addBonusSlots(int delta) {
        this.bonusSlots = Math.max(0, this.bonusSlots + delta);
        this.dirty = true;
    }

    public int pendingSeeds() {
        return pendingSeeds;
    }

    public void addPendingSeeds(int count) {
        if (count > 0) {
            this.pendingSeeds += count;
            this.dirty = true;
        }
    }

    public int takePendingSeeds(int maxCount) {
        int taken = Math.min(this.pendingSeeds, Math.max(0, maxCount));
        if (taken > 0) {
            this.pendingSeeds -= taken;
            this.dirty = true;
        }
        return taken;
    }

    public long totalHarvests() {
        return totalHarvests;
    }

    public void incrementTotalHarvests() {
        this.totalHarvests++;
        this.dirty = true;
    }

    public double totalEarnings() {
        return totalEarnings;
    }

    public void addEarnings(double amount) {
        if (amount > 0) {
            this.totalEarnings += amount;
            this.dirty = true;
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        this.dirty = false;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            uuid,
            woodBroken,
            licenseUnlocked,
            bonusSlots,
            pendingSeeds,
            totalHarvests,
            totalEarnings
        );
    }

    public record Snapshot(
        UUID uuid,
        long woodBroken,
        boolean licenseUnlocked,
        int bonusSlots,
        int pendingSeeds,
        long totalHarvests,
        double totalEarnings
    ) {}
}
