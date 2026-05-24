package net.bigyous.gptgodmc.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import net.bigyous.gptgodmc.GPTGOD;

public class BukkitUtils {
    // converts seconds into ticks
    public static long secondsToTicks(long seconds) {
        return seconds * 20;
    }

    // checks if block is one of a few different blocktypes which players can be
    // inside of
    public static boolean isBlockBreathable(Block block) {
        return
        // block is passable but not liquid (might be too permissive?)
        (block.isPassable() && !block.isLiquid())
                // or the block is a cobweb, ladder, or vine
                || block.getType().equals(Material.COBWEB)
                || block.getType().equals(Material.LADDER)
                || block.getType().equals(Material.VINE);
    }

    public static boolean testBlocks(Location loc, boolean ignoreWater) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        Location underplayer = new Location(loc.getWorld(), loc.getBlockX(), (loc.getBlockY() - 1), loc.getBlockZ());// Block
                                                                                                                     // under
                                                                                                                     // player
        Location topblock = new Location(loc.getWorld(), loc.getBlockX(), (loc.getBlockY() + 1), loc.getBlockZ());// player
                                                                                                                  // location
                                                                                                                  // top

        // no liquid that is lava
        boolean belowSafe = !(underplayer.getBlock().isLiquid()
                && underplayer.getBlock().getType().equals(Material.LAVA))
                // either we are ignoring water or it is not water
                && (ignoreWater || !(underplayer.getBlock().isLiquid()
                        && underplayer.getBlock().getType().equals(Material.WATER)))
                // not air
                && !underplayer.getBlock().isEmpty()
                // not bedrock (in case of falling out of world glitches)
                && !underplayer.getBlock().getType().equals(Material.BEDROCK);

        // empty = good for the blocks the player is in
        boolean upSafe = topblock.getBlock().isEmpty() || isBlockBreathable(topblock.getBlock());
        boolean locSafe = loc.getBlock().isEmpty() || isBlockBreathable(loc.getBlock());

        // safety is based on all three factors
        return belowSafe && upSafe && locSafe;
    };

    // ensure that spawns or teleports are not in a block
    // moves the spawn up until a safe position is found
    // or returns null if none is found
    public static Location getSafeLocation(Location destination, boolean ignoreWater, int maxDistance) {
        if (destination == null || destination.getWorld() == null || maxDistance < 0) {
            return null;
        }

        World world = destination.getWorld();
        int baseY = Math.max(world.getMinHeight() + 1,
                Math.min(world.getMaxHeight() - 2, destination.getBlockY()));
        int maxUp = Math.min(maxDistance, world.getMaxHeight() - 2 - baseY);
        int maxDown = Math.min(maxDistance, baseY - (world.getMinHeight() + 1));

        for (int offset = 0; offset <= maxDistance; offset++) {
            if (offset <= maxUp) {
                Location up = destination.clone();
                up.setY(baseY + offset);
                if (testBlocks(up, ignoreWater)) {
                    return centerForTeleport(up, destination);
                }
            }
            if (offset > 0 && offset <= maxDown) {
                Location down = destination.clone();
                down.setY(baseY - offset);
                if (testBlocks(down, ignoreWater)) {
                    return centerForTeleport(down, destination);
                }
            }
        }

        GPTGOD.LOGGER.warn(String.format(
                "getSafeLocation found no safe location within %d blocks of (%f, %f, %f)",
                maxDistance, destination.getX(), destination.getY(), destination.getZ()));
        return null;
    }

    private static Location centerForTeleport(Location safeBlock, Location original) {
        Location centered = safeBlock.clone();
        centered.setX(safeBlock.getBlockX() + 0.5);
        centered.setZ(safeBlock.getBlockZ() + 0.5);
        centered.setYaw(original.getYaw());
        centered.setPitch(original.getPitch());
        return centered;
    }

    // ensures that the player who is being teleported by our blind god has at least
    // some chance of survival
    public static boolean safeTeleport(Player player, Location destination) {
        // checks current location
        Location safeLocation = getSafeLocation(destination, false, 128);
        if (safeLocation == null) {
            return false;
        }

        return player.teleport(safeLocation);
    }
}
