package id.nusaspigot.sawit.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Objects;

/**
 * Immutable block coordinate key used for O(1) lookup of planted oil palms.
 */
public record BlockPosKey(String worldName, int x, int y, int z) {

    public static BlockPosKey of(Block block) {
        return new BlockPosKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockPosKey of(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "Location world must not be null");
        return new BlockPosKey(world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public BlockPosKey below() {
        return new BlockPosKey(worldName, x, y - 1, z);
    }

    public BlockPosKey above() {
        return new BlockPosKey(worldName, x, y + 1, z);
    }

    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z);
    }

    public boolean isChunkLoaded() {
        World world = Bukkit.getWorld(worldName);
        return world != null && world.isChunkLoaded(x >> 4, z >> 4);
    }

    public long chunkKey() {
        return ((long) (x >> 4) & 0xFFFFFFFFL) | (((long) (z >> 4) & 0xFFFFFFFFL) << 32);
    }
}
