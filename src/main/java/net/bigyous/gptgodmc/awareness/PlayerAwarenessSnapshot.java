package net.bigyous.gptgodmc.awareness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.bigyous.gptgodmc.ServerInfoSummarizer;
import net.bigyous.gptgodmc.ServerInfoSummarizer.ObjectiveAssessment;
import net.bigyous.gptgodmc.Structure;
import net.bigyous.gptgodmc.StructureManager;
import net.bigyous.gptgodmc.memory.MemoryStore;

public record PlayerAwarenessSnapshot(
        UUID uuid,
        String playerName,
        boolean dead,
        String worldName,
        int x,
        int y,
        int z,
        String biome,
        String nearestStructure,
        int nearestStructureDistance,
        int health,
        int food,
        boolean burning,
        boolean swimming,
        boolean sleeping,
        boolean gliding,
        String mainHand,
        String offHand,
        String lookingAt,
        int nearbyHostiles,
        Set<String> nearbyPlayers,
        Set<String> affordances,
        Map<String, Integer> inventoryCounts,
        Map<String, ObjectiveSnapshot> objectiveSnapshots) {

    private static final int MOVEMENT_DELTA_BLOCKS = 12;
    private static final int HEALTH_DELTA = 4;
    private static final int FOOD_DELTA = 4;

    public record ObjectiveSnapshot(String tier, String summary) {
    }

    public static PlayerAwarenessSnapshot from(Player player) {
        Location location = player.getLocation();
        Structure closestStructure = StructureManager.getClosestStructureToLocation(location);
        String nearestStructure = "none";
        int nearestDistance = -1;
        if (closestStructure != null && closestStructure.getLocation().getWorld().equals(location.getWorld())) {
            nearestStructure = closestStructure.getName();
            nearestDistance = closestStructure.getDistanceToI(location);
        }

        return new PlayerAwarenessSnapshot(
                player.getUniqueId(),
                player.getName(),
                player.isDead() || player.getGameMode().equals(GameMode.SPECTATOR),
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                location.getBlock().getBiome().name().toLowerCase(Locale.ROOT),
                nearestStructure,
                nearestDistance,
                (int) Math.round(player.getHealth()),
                player.getFoodLevel(),
                player.getFireTicks() > 0,
                player.isSwimming(),
                player.isSleeping(),
                player.isGliding(),
                formatItem(player.getInventory().getItemInMainHand()),
                formatItem(player.getInventory().getItemInOffHand()),
                getTargetBlock(player),
                countNearbyHostiles(player),
                getNearbyPlayers(player),
                getAffordances(player),
                getInventoryCounts(player),
                getObjectiveSnapshots(player));
    }

    public String describeChangeFrom(PlayerAwarenessSnapshot previous) {
        if (previous == null) {
            return String.format("%s first observed at %s in %s%s.",
                    playerName,
                    coords(),
                    biome,
                    describeNearestStructure().isBlank() ? "" : ", " + describeNearestStructure());
        }

        List<String> changes = new ArrayList<>();
        if (dead != previous.dead) {
            changes.add(dead ? "died or entered spectator" : "returned alive");
        }

        if (!worldName.equals(previous.worldName)) {
            changes.add(String.format("changed world from %s to %s", previous.worldName, worldName));
        } else if (distanceFrom(previous) >= MOVEMENT_DELTA_BLOCKS) {
            changes.add(String.format("moved %d blocks to %s", distanceFrom(previous), coords()));
        }

        if (!biome.equals(previous.biome)) {
            changes.add(String.format("entered %s biome", biome));
        }

        String structureChange = describeStructureChange(previous);
        if (!structureChange.isBlank()) {
            changes.add(structureChange);
        }

        if (health <= 0 && previous.health > 0) {
            changes.add("health dropped to zero");
        } else if (Math.abs(health - previous.health) >= HEALTH_DELTA) {
            changes.add(health < previous.health
                    ? String.format("health fell from %d to %d", previous.health, health)
                    : String.format("health recovered from %d to %d", previous.health, health));
        }

        if (Math.abs(food - previous.food) >= FOOD_DELTA) {
            changes.add(food < previous.food
                    ? String.format("food fell from %d to %d", previous.food, food)
                    : String.format("food rose from %d to %d", previous.food, food));
        }

        addFlagChange(changes, previous.burning, burning, "started burning", "stopped burning");
        addFlagChange(changes, previous.swimming, swimming, "started swimming", "left water");
        addFlagChange(changes, previous.sleeping, sleeping, "went to sleep", "woke up");
        addFlagChange(changes, previous.gliding, gliding, "started gliding", "stopped gliding");

        if (!mainHand.equals(previous.mainHand)) {
            changes.add(String.format("main hand changed from %s to %s", previous.mainHand, mainHand));
        }

        if (nearbyHostiles == 0 && previous.nearbyHostiles > 0) {
            changes.add("nearby hostiles cleared");
        } else if (nearbyHostiles > 0 && previous.nearbyHostiles == 0) {
            changes.add(String.format("hostiles appeared nearby x%d", nearbyHostiles));
        } else if (Math.abs(nearbyHostiles - previous.nearbyHostiles) >= 3) {
            changes.add(String.format("nearby hostile count changed from %d to %d",
                    previous.nearbyHostiles, nearbyHostiles));
        }

        changes.addAll(describeInventoryChanges(previous));
        changes.addAll(describeObjectiveChanges(previous));
        changes.addAll(describeSetChanges(previous.nearbyPlayers, nearbyPlayers, "now near player", "no longer near player"));

        List<String> newAffordances = new ArrayList<>(affordances);
        newAffordances.removeAll(previous.affordances);
        if (!newAffordances.isEmpty()) {
            changes.add("new opportunities: " + String.join(", ", newAffordances.stream().limit(4).toList()));
        }

        if (!lookingAt.equals(previous.lookingAt) && !lookingAt.equals("none") && isNotableTarget(lookingAt)) {
            changes.add("now looking at " + lookingAt);
        }

        if (changes.isEmpty()) {
            return "";
        }
        return playerName + ": " + String.join("; ", changes) + ".";
    }

    private String coords() {
        return String.format("%d,%d,%d", x, y, z);
    }

    private String describeNearestStructure() {
        if (nearestStructureDistance < 0 || nearestStructure.equals("none")) {
            return "";
        }
        if (nearestStructureDistance <= 10) {
            return "near " + nearestStructure;
        }
        if (nearestStructureDistance <= 50) {
            return nearestStructureDistance + " blocks from " + nearestStructure;
        }
        return "";
    }

    private String describeStructureChange(PlayerAwarenessSnapshot previous) {
        boolean wasNear = previous.nearestStructureDistance >= 0 && previous.nearestStructureDistance <= 10;
        boolean isNear = nearestStructureDistance >= 0 && nearestStructureDistance <= 10;
        if (isNear && (!wasNear || !nearestStructure.equals(previous.nearestStructure))) {
            return "moved near " + nearestStructure;
        }
        if (wasNear && !isNear) {
            return "left " + previous.nearestStructure;
        }
        return "";
    }

    private int distanceFrom(PlayerAwarenessSnapshot previous) {
        int dx = x - previous.x;
        int dy = y - previous.y;
        int dz = z - previous.z;
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private static void addFlagChange(List<String> changes, boolean previous, boolean current, String started,
            String stopped) {
        if (previous == current) {
            return;
        }
        changes.add(current ? started : stopped);
    }

    private List<String> describeInventoryChanges(PlayerAwarenessSnapshot previous) {
        List<String> changes = new ArrayList<>();
        Map<String, Integer> deltas = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(previous.inventoryCounts.keySet());
        keys.addAll(inventoryCounts.keySet());
        for (String key : keys) {
            int delta = inventoryCounts.getOrDefault(key, 0) - previous.inventoryCounts.getOrDefault(key, 0);
            if (delta != 0 && isNotableInventoryItem(key, Math.abs(delta))) {
                deltas.put(key, delta);
            }
        }

        deltas.entrySet().stream()
                .sorted((left, right) -> Integer.compare(Math.abs(right.getValue()), Math.abs(left.getValue())))
                .limit(4)
                .forEach(entry -> changes.add(entry.getValue() > 0
                        ? String.format("gained %s x%d", entry.getKey(), entry.getValue())
                        : String.format("lost %s x%d", entry.getKey(), Math.abs(entry.getValue()))));
        return changes;
    }

    private List<String> describeObjectiveChanges(PlayerAwarenessSnapshot previous) {
        List<String> changes = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(previous.objectiveSnapshots.keySet());
        keys.addAll(objectiveSnapshots.keySet());
        for (String objective : keys) {
            ObjectiveSnapshot before = previous.objectiveSnapshots.get(objective);
            ObjectiveSnapshot after = objectiveSnapshots.get(objective);
            if (before == null && after != null) {
                changes.add(String.format("received objective '%s' (%s)", objective, after.summary()));
            } else if (before != null && after == null) {
                changes.add("objective cleared or retired '" + objective + "'");
            } else if (before != null && after != null
                    && (!before.tier().equals(after.tier()) || !before.summary().equals(after.summary()))) {
                changes.add(String.format("objective '%s' changed from %s/%s to %s/%s",
                        objective, before.tier(), before.summary(), after.tier(), after.summary()));
            }
        }
        return changes;
    }

    private static List<String> describeSetChanges(Set<String> previous, Set<String> current, String gainedPrefix,
            String lostPrefix) {
        List<String> changes = new ArrayList<>();
        List<String> gained = new ArrayList<>(current);
        gained.removeAll(previous);
        gained.stream().limit(2).forEach(value -> changes.add(gainedPrefix + " " + value));

        List<String> lost = new ArrayList<>(previous);
        lost.removeAll(current);
        lost.stream().limit(2).forEach(value -> changes.add(lostPrefix + " " + value));
        return changes;
    }

    private static String getTargetBlock(Player player) {
        var target = player.getTargetBlockExact(16);
        if (target == null) {
            return "none";
        }
        return materialName(target.getType());
    }

    private static int countNearbyHostiles(Player player) {
        return (int) player.getWorld().getNearbyEntities(player.getLocation(), 10, 5, 10).stream()
                .filter(entity -> entity instanceof Enemy)
                .count();
    }

    private static Set<String> getNearbyPlayers(Player player) {
        Set<String> nearby = new LinkedHashSet<>();
        for (Player other : player.getWorld().getPlayers()) {
            if (!other.equals(player) && other.getLocation().distanceSquared(player.getLocation()) <= 256) {
                nearby.add(other.getName());
            }
        }
        return nearby;
    }

    private static Set<String> getAffordances(Player player) {
        Set<String> affordances = new LinkedHashSet<>();
        Location center = player.getLocation();
        World world = player.getWorld();
        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();

        for (int x = -5; x <= 5; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -5; z <= 5; z++) {
                    addBlockAffordance(affordances, world.getBlockAt(baseX + x, baseY + y, baseZ + z).getType());
                }
            }
        }

        int livestock = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 12, 6, 12)) {
            if (entity instanceof Animals) {
                livestock++;
            }
        }
        if (livestock > 0) {
            affordances.add("near_livestock x" + livestock);
        }
        if (countNearbyHostiles(player) > 0) {
            affordances.add("hostile_mobs_nearby");
        }

        return affordances;
    }

    private static void addBlockAffordance(Set<String> affordances, Material material) {
        if (material == Material.CRAFTING_TABLE) {
            affordances.add("near_crafting_table");
        } else if (material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.BARREL) {
            affordances.add("near_storage");
        } else if (material == Material.FURNACE || material == Material.BLAST_FURNACE || material == Material.SMOKER) {
            affordances.add("near_furnace");
        } else if (material == Material.WATER) {
            affordances.add("near_water");
        } else if (material == Material.LAVA) {
            affordances.add("near_lava");
        } else if (material == Material.BELL) {
            affordances.add("near_bell");
        } else if (material == Material.LECTERN) {
            affordances.add("near_lectern");
        } else if (material == Material.SOUL_SOIL) {
            affordances.add("near_soul_soil");
        } else if (material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE) {
            affordances.add("near_campfire");
        } else if (Tag.LOGS.isTagged(material)) {
            affordances.add("near_logs");
        } else if (Tag.CANDLES.isTagged(material)) {
            affordances.add("near_candles");
        } else if (material.name().endsWith("_BED")) {
            affordances.add("near_bed");
        } else if (isCrop(material)) {
            affordances.add("near_crops");
        }
    }

    private static boolean isCrop(Material material) {
        String name = material.name();
        return name.contains("WHEAT")
                || name.contains("CARROT")
                || name.contains("POTATO")
                || name.contains("BEETROOT")
                || name.contains("COCOA");
    }

    private static Map<String, Integer> getInventoryCounts(Player player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            String key = materialName(item.getType());
            counts.put(key, counts.getOrDefault(key, 0) + item.getAmount());
        }
        return counts;
    }

    private static Map<String, ObjectiveSnapshot> getObjectiveSnapshots(Player player) {
        Map<String, ObjectiveSnapshot> snapshots = new LinkedHashMap<>();
        for (String objective : MemoryStore.get(player).activeObjectives) {
            ObjectiveAssessment assessment = ServerInfoSummarizer.assessObjectiveProgress(player, objective);
            snapshots.put(objective, new ObjectiveSnapshot(assessment.tier().name().toLowerCase(Locale.ROOT),
                    assessment.summary()));
        }
        return snapshots;
    }

    private static String formatItem(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return "none";
        }
        return materialName(item.getType());
    }

    private static String materialName(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }

    private static boolean isNotableInventoryItem(String item, int delta) {
        if (delta >= 4) {
            return true;
        }
        return item.contains("bell")
                || item.contains("lectern")
                || item.contains("campfire")
                || item.contains("soul_soil")
                || item.contains("flint_and_steel")
                || item.contains("gold")
                || item.contains("diamond")
                || item.contains("iron")
                || item.contains("candle")
                || item.contains("bucket")
                || item.contains("bed");
    }

    private static boolean isNotableTarget(String target) {
        return target.contains("chest")
                || target.contains("barrel")
                || target.contains("crafting_table")
                || target.contains("furnace")
                || target.contains("bell")
                || target.contains("lectern")
                || target.contains("campfire")
                || target.contains("soul_soil")
                || target.contains("bed")
                || target.contains("lava")
                || target.contains("water");
    }
}
