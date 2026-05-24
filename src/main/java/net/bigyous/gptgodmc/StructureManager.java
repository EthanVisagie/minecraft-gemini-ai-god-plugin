package net.bigyous.gptgodmc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import net.bigyous.gptgodmc.Structure.CiritiqueStatus;
import net.bigyous.gptgodmc.loggables.GenericEventLoggable;

import org.bukkit.entity.Player;

public class StructureManager implements Listener {
    private static final String SACRED_STRUCTURE_NAME = "Soul Spire";
    private static final int SACRED_STRUCTURE_MIN_SIZE = 1;
    private static final int RITUAL_SCAN_RADIUS = 4;
    private static final int MIN_RITUAL_COMPONENTS = 3;
    private static ConcurrentHashMap<String, Structure> structures = new ConcurrentHashMap<String, Structure>();
    private static final LinkedList<String> recentSacredChanges = new LinkedList<>();

    private enum RitualComponent {
        BELL,
        LECTERN,
        CAMPFIRE,
        SOUL_SOIL,
        CANDLE
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Location newBlock = event.getBlock().getLocation();
        addBlockToStructures(newBlock, event.getPlayer());
        promoteRitualAnchor(event.getBlock(), event.getPlayer());
        logSacredStructurePlacement(event.getBlock(), event.getPlayer().getName());
    }

    @EventHandler
    public void onBlockFade(BlockFadeEvent event) {
        logSacredStructureRemoval(event.getBlock(), "faded");
        removeBlockFromAllStructures(event.getBlock().getLocation());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        logSacredStructureRemoval(event.getBlock(), "was broken by " + event.getPlayer().getName());
        removeBlockFromAllStructures(event.getBlock().getLocation());
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent event) {
        logSacredStructureRemoval(event.getBlock(), "burned");
        removeBlockFromAllStructures(event.getBlock().getLocation());
        String structure = getStructureThatContains(event.getBlock().getLocation());
        if (structure != null) {
            EventLogger.addLoggable(new GenericEventLoggable(structure + " is on fire!"));
        }
    }

    @EventHandler
    public void onBlockExploded(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            Location location = block.getLocation();
            logSacredStructureRemoval(block, "was blasted");
            removeBlockFromAllStructures(location);
        }
    }

    @EventHandler
    public void onExploded(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            Location location = block.getLocation();
            logSacredStructureRemoval(block, "was blasted");
            removeBlockFromAllStructures(location);
        }
    }

    private void addBlockToStructures(Location block, Player builder) {
        Structure parentStructure = null;
        for (String structurekey : structures.keySet()) {
            Structure structure = structures.get(structurekey);
            if (structure.isBlockConnected(block)) {
                if (parentStructure != null) {
                    parentStructure.merge(structure);
                    structures.remove(structurekey);
                } else {
                    structure.addBlock(block);
                    parentStructure = structure;
                }
            }
        }
        if (parentStructure == null) {
            String name = nameStructure(block);
            structures.put(name, new Structure(block, builder, name));
        }
    }

    // for the AI to rename a structure and declare it pretty or ugly
    public static boolean updateStructureDetails(String originalStructureName, String newStructureName,
            String description, boolean isItUgly) {
        Structure structure = structures.get(originalStructureName);
        if (structure == null) {
            GPTGOD.LOGGER.error(String.format("Failed to update the %s structure %s to new name %s.",
                    isItUgly ? "ugly" : "pretty", originalStructureName, newStructureName));
            return false;
        }

        // fall back to original structure name
        if (newStructureName == null || newStructureName.length() < 1) {
            newStructureName = originalStructureName;
        }

        structure.setCritique(isItUgly);
        structure.setDescription(description);
        structure.setName(newStructureName);
        if (!originalStructureName.equals(newStructureName)) {
            structures.remove(originalStructureName);
        }
        // insert the structure under the new name
        structures.put(newStructureName, structure);

        return true;
    }

    private void promoteRitualAnchor(Block placedBlock, Player builder) {
        RitualComponent placedComponent = getRitualComponent(placedBlock.getType());
        if (placedComponent == null) {
            return;
        }

        String structureKey = getStructureThatContains(placedBlock.getLocation());
        if (structureKey == null) {
            return;
        }

        Structure structure = structures.get(structureKey);
        if (structure == null || structure.getSize() < SACRED_STRUCTURE_MIN_SIZE) {
            return;
        }

        Set<RitualComponent> nearbyComponents = getNearbyRitualComponents(placedBlock.getLocation());
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure != null && sacredStructure != structure) {
            return;
        }

        boolean wasSacred = structure.isSacred();
        boolean wasAwakened = structure.isSacredAwakened();
        String previousName = structureKey;
        structure.setSacred(true);
        if (nearbyComponents.size() >= MIN_RITUAL_COMPONENTS) {
            structure.setSacredAwakened(true);
            structure.setDescription(buildAwakenedSacredDescription(nearbyComponents, builder));
        } else if (structure.getDescription().isBlank()) {
            structure.setDescription(buildNascentSacredDescription(placedComponent, builder));
        }
        structure.setName(SACRED_STRUCTURE_NAME);
        if (!SACRED_STRUCTURE_NAME.equals(previousName)) {
            structures.remove(previousName);
        }
        structures.put(SACRED_STRUCTURE_NAME, structure);

        if (!wasSacred) {
            noteSacredChange(String.format("%s was consecrated by %s", SACRED_STRUCTURE_NAME, builder.getName()));
            EventLogger.addLoggable(new GenericEventLoggable(String.format(
                    "%s has consecrated a nascent ritual anchor as %s with %s",
                    builder.getName(),
                    SACRED_STRUCTURE_NAME,
                    placedComponent.name().toLowerCase().replace('_', ' '))));
        } else if (!wasAwakened && nearbyComponents.size() >= MIN_RITUAL_COMPONENTS) {
            noteSacredChange(String.format("%s awakened with %s", SACRED_STRUCTURE_NAME,
                    nearbyComponents.stream().map(Enum::name).map(name -> name.toLowerCase().replace('_', ' ')).sorted()
                            .toList()));
            EventLogger.addLoggable(new GenericEventLoggable(String.format(
                    "%s's ritual anchor of %s has awakened as %s",
                    builder.getName(),
                    nearbyComponents.stream().map(Enum::name).map(name -> name.toLowerCase().replace('_', ' ')).sorted()
                            .toList(),
                    SACRED_STRUCTURE_NAME)));
        }
    }

    private static Set<RitualComponent> getNearbyRitualComponents(Location center) {
        Set<RitualComponent> found = EnumSet.noneOf(RitualComponent.class);
        for (int x = -RITUAL_SCAN_RADIUS; x <= RITUAL_SCAN_RADIUS; x++) {
            for (int y = -RITUAL_SCAN_RADIUS; y <= RITUAL_SCAN_RADIUS; y++) {
                for (int z = -RITUAL_SCAN_RADIUS; z <= RITUAL_SCAN_RADIUS; z++) {
                    Material material = center.getWorld()
                            .getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z)
                            .getType();
                    RitualComponent component = getRitualComponent(material);
                    if (component != null) {
                        found.add(component);
                    }
                }
            }
        }
        return found;
    }

    private static RitualComponent getRitualComponent(Material material) {
        if (material == Material.BELL) {
            return RitualComponent.BELL;
        }
        if (material == Material.LECTERN) {
            return RitualComponent.LECTERN;
        }
        if (material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE) {
            return RitualComponent.CAMPFIRE;
        }
        if (material == Material.SOUL_SOIL) {
            return RitualComponent.SOUL_SOIL;
        }
        if (Tag.CANDLES.isTagged(material) || material == Material.CANDLE_CAKE) {
            return RitualComponent.CANDLE;
        }
        return null;
    }

    public static Structure getSacredStructure() {
        return structures.values().stream().filter(Structure::isSacred).findFirst().orElse(null);
    }

    private String buildAwakenedSacredDescription(Set<RitualComponent> components, Player builder) {
        String componentList = components.stream().map(Enum::name).map(name -> name.toLowerCase().replace('_', ' '))
                .sorted().reduce((left, right) -> left + ", " + right).orElse("ritual pieces");
        return String.format("A sacred ritual anchor raised by %s around %s.", builder.getName(), componentList);
    }

    private String buildNascentSacredDescription(RitualComponent component, Player builder) {
        return String.format("A nascent sacred ritual anchor started by %s with %s.", builder.getName(),
                component.name().toLowerCase().replace('_', ' '));
    }

    public static List<String> getRitualComponentNames(Structure structure) {
        if (structure == null) {
            return List.of();
        }
        return structure.getBlocks().stream()
                .map(Block::getType)
                .map(StructureManager::getRitualComponent)
                .filter(component -> component != null)
                .distinct()
                .map(component -> component.name().toLowerCase().replace('_', ' '))
                .sorted()
                .toList();
    }

    public static String getSacredStructureSummary() {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null) {
            return "NONE";
        }
        String stage = sacredStructure.isSacredAwakened() ? "Awakened" : "Nascent";
        String components = String.join(", ", getRitualComponentNames(sacredStructure));
        if (components.isBlank()) {
            components = "none";
        }
        String description = sacredStructure.getDescription().isBlank() ? "undescribed" : sacredStructure.getDescription();
        return String.format("%s (%s, size %d, components: %s, builder: %s, detail: %s)",
                sacredStructure.getName(),
                stage,
                sacredStructure.getSize(),
                components,
                sacredStructure.getBuilder().getName(),
                description);
    }

    public static String getRitualPhaseSummary() {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null) {
            return "NONE";
        }

        String objectives = String.join(" ",
                GPTGOD.SCOREBOARD.getEntries().stream().filter(entry -> GPTGOD.SERVER.getPlayer(entry) == null).toList())
                .toLowerCase();
        List<String> components = getRitualComponentNames(sacredStructure);

        if (!sacredStructure.isSacredAwakened()) {
            return "Consecration";
        }
        if (containsAny(objectives, "sacrifice", "offering", "feast")) {
            return "Offering";
        }
        if (containsAny(objectives, "bell", "forge")) {
            return "Bell Forging";
        }
        if (containsAny(objectives, "soul-fire", "soul fire", "ignite")) {
            return "Soul Fire";
        }
        if (containsAny(objectives, "vessel", "altar", "shrine", "spire", "monument", "build")) {
            return "Sanctuary Building";
        }
        if (components.contains("bell") && components.contains("soul soil")) {
            return "Awakened Vigil";
        }
        return "Awakened Ritual";
    }

    public static String getRitualAwarenessSummary() {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null) {
            return "no Soul Spire yet; first ritual step is to place a bell, lectern, campfire, candle, or soul soil as an anchor";
        }

        Set<RitualComponent> nearbyComponents = getNearbyRitualComponents(sacredStructure.getLocation());
        Set<RitualComponent> missingComponents = EnumSet.allOf(RitualComponent.class);
        missingComponents.removeAll(nearbyComponents);
        String found = nearbyComponents.isEmpty() ? "none"
                : nearbyComponents.stream().map(StructureManager::componentLabel).sorted()
                        .reduce((left, right) -> left + ", " + right).orElse("none");
        String missing = missingComponents.isEmpty() ? "none"
                : missingComponents.stream().map(StructureManager::componentLabel).sorted()
                        .reduce((left, right) -> left + ", " + right).orElse("none");

        String closestPlayer = GPTGOD.SERVER.getOnlinePlayers().stream()
                .filter(player -> player.getWorld().equals(sacredStructure.getLocation().getWorld()))
                .min((left, right) -> Double.compare(
                        left.getLocation().distanceSquared(sacredStructure.getLocation()),
                        right.getLocation().distanceSquared(sacredStructure.getLocation())))
                .map(player -> String.format("%s %d blocks away", player.getName(),
                        sacredStructure.getDistanceToI(player.getLocation())))
                .orElse("none in ritual world");

        String recent = getRecentSacredChangesSummary();
        return String.format("stage=%s, nearby components=%s, missing components=%s, closest player=%s, last changes=%s",
                sacredStructure.isSacredAwakened() ? "awakened" : "nascent",
                found,
                missing,
                closestPlayer,
                recent);
    }

    public static String getRecentSacredChangesSummary() {
        if (recentSacredChanges.isEmpty()) {
            return "NONE";
        }
        return String.join(" | ", recentSacredChanges.subList(0, Math.min(4, recentSacredChanges.size())));
    }

    private void logSacredStructurePlacement(Block block, String playerName) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null || !sacredStructure.containsBlock(block.getLocation())) {
            return;
        }
        noteSacredChange(String.format("%s gained %s from %s", SACRED_STRUCTURE_NAME,
                block.getType().name().toLowerCase().replace('_', ' '), playerName));
    }

    private void logSacredStructureRemoval(Block block, String cause) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null || !sacredStructure.containsBlock(block.getLocation())) {
            return;
        }
        noteSacredChange(String.format("%s lost %s because it %s", SACRED_STRUCTURE_NAME,
                block.getType().name().toLowerCase().replace('_', ' '), cause));
    }

    private static void noteSacredChange(String message) {
        recentSacredChanges.addFirst(message);
        while (recentSacredChanges.size() > 8) {
            recentSacredChanges.removeLast();
        }
    }

    private static String componentLabel(RitualComponent component) {
        return component.name().toLowerCase().replace('_', ' ');
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String nameStructure(Location block) {
        // Look we are just going to assume the first block placed will make up the
        // majority of blocks in the structure
        return String.format("%s_Structure_%d", block.getBlock().getType().name(), structures.size());
    }

    public static List<String> getStructures() {
        return structures.keySet().stream().filter(key -> {
            Structure structure = structures.get(key);
            return structure != null && (structure.getSize() > 20 || structure.isSacred());
        }).toList();
    }

    public static List<String> getAllStructures() {
        return structures.keySet().stream().toList();
    }

    public static Structure getStructure(String name) {
        String key = resolveStructureKey(name);
        return key == null ? null : structures.get(key);
    }

    public static void reset() {
        structures = new ConcurrentHashMap<String, Structure>();
    }

    private static String getStructureThatContains(Location block) {
        for (String key : structures.keySet()) {
            Structure structure = structures.get(key);
            if (structure.containsBlock(block)) {
                return key;
            }
        }
        return null;
    }

    private static void removeBlockFromAllStructures(Location block) {
        for (String key : structures.keySet()) {
            Structure structure = structures.get(key);
            if (structure.containsBlock(block)) {
                structure.removeBlock(block);
                if (structure.getSize() < 1)
                    structures.remove(key);
            }
        }
    }

    public static Structure getClosestStructureToLocation(Location location) {
        if (getStructures().isEmpty())
            return null;

        int distance = Integer.MAX_VALUE;
        Structure closest = null;
        for (String key : getStructures()) {
            Structure s = structures.get(key);
            if (s == null) continue;
            if (!s.getLocation().getWorld().equals(location.getWorld())) continue;
            int temp = s.getDistanceToI(location);
            if (temp < distance) {
                distance = temp;
                Structure newStructure = getStructure(key);;
                if(newStructure != null) closest = newStructure;
            }
        }

        return closest;
    }

    public static String getStructureDescription(Structure closestStructure, Location currentPlayerLocation) {
        if (!currentPlayerLocation.getWorld().getName().equals(WorldManager.getCurrentWorld().getName()))
            return "In a different dimension";
        if(closestStructure == null) return "";
        int distance = closestStructure.getDistanceToI(currentPlayerLocation);

        if (distance < 10) {
            return String.format("Location: near %s\n", closestStructure);
        } else if (distance < 50) {
            return String.format("Location: %d blocks away from %s\n", distance, closestStructure);
        } else {
            return "";
        }
    }

    public static StructureProximityData getStructureProximityData(Location location) {
        if (!location.getWorld().getName().equals(WorldManager.getCurrentWorld().getName()))
            return null;
        if (getStructures().isEmpty())
            return null;
        int distance = Integer.MAX_VALUE;
        String closest = "";
        for (String key : getStructures()) {
            int temp = Math.toIntExact(Math.round(location.distance(structures.get(key).getLocation())));
            if (temp < distance) {
                distance = temp;
                closest = key;
            }
        }
        if (distance < 50) {
            return new StructureProximityData(closest, distance);
        } else {
            return null;
        }
    }

    public static boolean hasStructure(String key) {
        return resolveStructureKey(key) != null;
    }

    public static String resolveStructureKey(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (structures.containsKey(name)) {
            return name;
        }

        String normalizedName = normalizeStructureName(name);
        for (String key : structures.keySet()) {
            if (normalizeStructureName(key).equals(normalizedName)) {
                return key;
            }
        }

        Structure sacredStructure = getSacredStructure();
        if (sacredStructure != null && isSacredStructureAlias(normalizedName)) {
            for (String key : structures.keySet()) {
                if (structures.get(key) == sacredStructure) {
                    return key;
                }
            }
        }

        String strippedName = stripStructureAdjectives(normalizedName);
        for (String key : structures.keySet()) {
            String normalizedKey = normalizeStructureName(key);
            if (normalizedKey.equals(strippedName) || strippedName.equals(stripStructureAdjectives(normalizedKey))) {
                return key;
            }
        }

        return null;
    }

    private static boolean isSacredStructureAlias(String normalizedName) {
        String strippedName = stripStructureAdjectives(normalizedName);
        String sacredName = normalizeStructureName(SACRED_STRUCTURE_NAME);
        return normalizedName.equals(sacredName)
                || normalizedName.contains(sacredName)
                || strippedName.equals(sacredName)
                || strippedName.contains(sacredName)
                || strippedName.equals("spire")
                || strippedName.equals("soul altar")
                || strippedName.equals("ritual anchor");
    }

    private static String stripStructureAdjectives(String normalizedName) {
        String out = normalizedName;
        String previous;
        do {
            previous = out;
            out = out.replaceAll("^(the|a|an|sacred|holy|divine|blessed|glowing|ritual|awakened|nascent)\\s+", "");
        } while (!out.equals(previous));
        return out.trim();
    }

    private static String normalizeStructureName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static class StructureProximityData {
        private int distance;
        private String structure;

        public StructureProximityData(String structure, int distance) {
            this.distance = distance;
            this.structure = structure;
        }

        public int getDistance() {
            return distance;
        }

        public String getStructure() {
            return structure;
        }
    }

    public static String getDisplayString(boolean getAll) {
        List<String> structureNames = getAll ? StructureManager.getAllStructures() : StructureManager.getStructures();
        Object[] structures = structureNames.stream().map((String key) -> {
            Structure structure = StructureManager.getStructure(key);

            String critique = "";
            if (structure.isSacred()) {
                critique = "Sacred ";
            } else if (structure.getCritique() == CiritiqueStatus.PRETTY) {
                critique = "Pretty ";
            } else if (structure.getCritique() == CiritiqueStatus.UGLY) {
                critique = "Ugly ";
            }

            return String.format("%s%s built by %s (at %s)", critique, key, structure.getBuilder().getName(),
                    structure.getLocation().toVector().toString());
        }).toArray();

        // explicitly tell the LLM if the array is empty
        return (structures.length > 0) ? Arrays.toString(structures) : "NONE";
    }

    public static String getDisplayString() {
        return getDisplayString(false);
    }
}
