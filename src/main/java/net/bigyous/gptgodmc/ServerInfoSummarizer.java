package net.bigyous.gptgodmc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import net.bigyous.gptgodmc.GPT.CommandInsightTracker;
import net.bigyous.gptgodmc.awareness.ActionOutcomeTracker;
import net.bigyous.gptgodmc.awareness.AwarenessTracker;
import net.bigyous.gptgodmc.awareness.PlayerIntentTracker;
import net.bigyous.gptgodmc.enums.GptGameMode;
import net.bigyous.gptgodmc.memory.MemoryStore;
import net.bigyous.gptgodmc.memory.PlayerMemory;
import net.bigyous.gptgodmc.utils.GPTUtils;
import net.bigyous.gptgodmc.utils.NicknameCommand;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class ServerInfoSummarizer {
    public enum ObjectiveProgressTier {
        NONE,
        PREPARING,
        READY,
        COMPLETE;

        public boolean isMeaningful() {
            return this != NONE;
        }
    }

    public record ObjectiveAssessment(ObjectiveProgressTier tier, String summary, String nextStep,
            boolean progressVisible) {
        public String hint(String objective) {
            String guidance = switch (tier) {
            case COMPLETE -> "clear the objective and reward completion";
            case READY -> "player can likely complete it now; guide them to the exact place/action";
            case PREPARING -> "visible progress; do not punish for neglect";
            case NONE -> "no visible progress; give a concrete Minecraft step before punishment";
            };
            return String.format("%s -> %s; next: %s; guidance: %s", objective, summary, nextStep, guidance);
        }
    }

    public static String getInventoryInfo(Player player) {
        StringBuilder sb = new StringBuilder();
        // Armor Items
        StringBuilder armorString = new StringBuilder();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null) {
                armorString.append(formatItemStack(armor) + ", ");
            }
        }
        if (!armorString.isEmpty()) {
            sb.append("Armor: " + armorString.toString() + "\n");
        }

        // Inventory Items
        // sb.append("Inventory: ");
        // for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
        // ItemStack stack = player.getInventory().getItem(i);
        // if (!stack.isEmpty()) {
        // sb.append(formatItemStack(stack) + ", ");
        // }
        // }
        // sb.append("\n");

        // Equipped Item (Main Hand)
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        sb.append(String.format("Main Hand: %s, Off Hand %s\n", formatItemStack(main), formatItemStack(off)));
        return sb.toString();
    }

    private static String getInventorySummary(Player player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int emptySlots = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) {
                emptySlots++;
                continue;
            }
            String key = formatItemStack(item);
            counts.put(key, counts.getOrDefault(key, 0) + item.getAmount());
        }

        if (counts.isEmpty()) {
            return String.format("Inventory Summary: empty (%d open slots)", emptySlots);
        }

        String items = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(12)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("empty");
        return String.format("Inventory Summary: %s; open slots %d", items, emptySlots);
    }

    private static String getStructures() {
        return StructureManager.getDisplayString();
    }

    private static List<Entity> getNearbyEntities(Player player) {
        List<Entity> nearby = new ArrayList<Entity>();
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 16, 8, 16)) {
            if (entity.equals(player)) {
                continue;
            }
            if (player.getLocation().distanceSquared(entity.getLocation()) <= 256) {
                nearby.add(entity);
            }
        }
        return nearby;
    }

    private static String getDangerLevel(Player player) {
        List<Entity> nearby = getNearbyEntities(player).stream()
                .filter(entity -> player.getLocation().distanceSquared(entity.getLocation()) <= 100)
                .toList();
        List<Entity> enemies = nearby.stream().filter((Entity entity) -> entity instanceof Enemy).toList();
        List<Entity> bosses = nearby.stream().filter((Entity entity) -> entity instanceof Boss).toList();
        if (enemies.size() < 1 && bosses.size() < 1) {
            return "Safe";
        } else if (enemies.size() <= 3 && bosses.size() < 1) {
            return "Minor";
        } else if (enemies.size() < 10 && bosses.size() < 1) {
            return "Moderate";
        } else if (enemies.size() < 17 || bosses.size() == 1) {
            return "High";
        }
        return "Crtitical";
    }

    private static String formatItemStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "None";
        }
        // stack.getItem()
        String name = stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(stack.getItemMeta().displayName())
                : stack.getType().name();
        return name;
    }

    private static String formatMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }

    private static String getPlayerHealth(Player player) {
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue();
        double health = player.getHealth();
        double healthRatio = health / maxHealth;
        if (healthRatio > 0.50) {
            return "Healthy";
        }
        if (healthRatio <= 0.5 && healthRatio > 0.3) {
            return "Injured";
        }
        if (healthRatio <= 0.3) {
            return "Gravely Wounded";
        }
        return "Unknown";
    }

    private static String getWeather() {
        World world = WorldManager.getCurrentWorld();
        return String.format("%s%s", world.isThundering() ? "Thunder " : "",
                world.isClearWeather() ? "Clear" : "Storm");
    }

    private static int countItems(Player player, Predicate<Material> matcher) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.isEmpty() && matcher.test(item.getType())) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static int countItems(Player player, Material material) {
        return countItems(player, type -> type == material);
    }

    private static boolean sacredStructureHasAny(Material... materials) {
        Structure structure = StructureManager.getSacredStructure();
        if (structure == null) {
            return false;
        }
        for (var block : structure.getBlocks()) {
            for (Material material : materials) {
                if (block.getType() == material) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long countSacredStructureBlocks(Predicate<Material> matcher) {
        Structure structure = StructureManager.getSacredStructure();
        if (structure == null) {
            return 0;
        }
        return structure.getBlocks().stream()
                .map(block -> block.getType())
                .filter(matcher)
                .count();
    }

    private static boolean mentionsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String getObjectiveProgress(Player player) {
        PlayerMemory memory = MemoryStore.get(player);
        if (memory.activeObjectives.isEmpty()) {
            return "Objective Progress: NONE";
        }

        List<String> progress = new ArrayList<>();
        for (String objective : memory.activeObjectives) {
            progress.add(String.format("%s -> %s", objective, assessObjectiveProgress(player, objective).summary()));
        }
        return "Objective Progress: " + String.join(" | ", progress);
    }

    private static String getObjectiveHints(Player player) {
        PlayerMemory memory = MemoryStore.get(player);
        if (memory.activeObjectives.isEmpty()) {
            return "Objective Hints: NONE";
        }

        List<String> hints = new ArrayList<>();
        for (String objective : memory.activeObjectives) {
            hints.add(assessObjectiveProgress(player, objective).hint(objective));
        }
        return "Objective Hints: " + String.join(" | ", hints);
    }

    public static ObjectiveAssessment assessObjectiveProgress(Player player, String objective) {
        String normalized = objective.toLowerCase(Locale.ROOT);
        Structure sacredStructure = StructureManager.getSacredStructure();
        int goldIngots = countItems(player, Material.GOLD_INGOT);
        int bells = countItems(player, Material.BELL);
        int lecterns = countItems(player, Material.LECTERN);
        int campfires = countItems(player, type -> type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE);
        int candles = countItems(player, type -> Tag.CANDLES.isTagged(type) || type == Material.CANDLE_CAKE);
        int soulSoil = countItems(player, Material.SOUL_SOIL);
        int flintAndSteel = countItems(player, Material.FLINT_AND_STEEL);
        int logs = countItems(player, Tag.LOGS::isTagged);
        int planks = countItems(player, type -> type.name().endsWith("_PLANKS"));
        int sticks = countItems(player, Material.STICK);
        int offerings = countItems(player, type -> mentionsAny(type.name().toLowerCase(Locale.ROOT), "mutton", "beef",
                "porkchop", "chicken", "rabbit"));
        Map<String, Long> nearbyEntities = getNearbyEntities(player).stream().collect(
                java.util.stream.Collectors.groupingBy(entity -> entity.getType().name().toLowerCase(Locale.ROOT),
                        LinkedHashMap::new, java.util.stream.Collectors.counting()));

        if (mentionsAny(normalized, "bell")) {
            if (sacredStructureHasAny(Material.BELL)) {
                return assessment(ObjectiveProgressTier.COMPLETE, "bell already placed at Soul Spire",
                        "clear the objective and reward the bell offering");
            }
            if (bells > 0) {
                return assessment(ObjectiveProgressTier.READY, String.format("carrying bell x%d", bells),
                        "tell them to place or ring the bell at Soul Spire");
            }
            if (goldIngots >= 8) {
                return assessment(ObjectiveProgressTier.READY,
                        String.format("has bell materials (%d gold ingots)", goldIngots),
                        "guide them to craft, trade for, or bring a bell to Soul Spire");
            }
            if (goldIngots > 0 || sticks > 0) {
                return assessment(ObjectiveProgressTier.PREPARING,
                        String.format("collecting bell materials (%d gold, %d sticks)", goldIngots, sticks),
                        "encourage gathering more gold or finding a village bell");
            }
            return assessment(ObjectiveProgressTier.NONE, "no bell materials yet",
                    "tell them to mine or trade for gold, find a village bell, or seek a bell offering");
        }

        if (mentionsAny(normalized, "lectern")) {
            if (sacredStructureHasAny(Material.LECTERN)) {
                return assessment(ObjectiveProgressTier.COMPLETE, "lectern already placed at Soul Spire",
                        "clear the objective and reward the completed offering");
            }
            if (lecterns > 0) {
                return assessment(ObjectiveProgressTier.READY, String.format("carrying lectern x%d", lecterns),
                        "tell them to place the lectern at Soul Spire");
            }
            return assessment(ObjectiveProgressTier.NONE, "lectern not placed yet",
                    "tell them to craft a lectern from a bookshelf and slabs or find one in a village");
        }

        if (mentionsAny(normalized, "campfire", "fire")) {
            if (mentionsAny(normalized, "soul-fire", "soul fire")) {
                if (sacredStructureHasAny(Material.SOUL_FIRE, Material.SOUL_CAMPFIRE)) {
                    return assessment(ObjectiveProgressTier.COMPLETE, "soul fire already active at ritual site",
                            "clear the objective and reward the ignition");
                }
                if (soulSoil > 0 && flintAndSteel > 0) {
                    return assessment(ObjectiveProgressTier.READY,
                            String.format("ready to ignite soul fire (%d soul soil, %d flint and steel)", soulSoil,
                                    flintAndSteel),
                            "tell them to place soul soil at Soul Spire and ignite it");
                }
                if (soulSoil > 0) {
                    return assessment(ObjectiveProgressTier.PREPARING,
                            String.format("has soul soil x%d but needs ignition", soulSoil),
                            "tell them to get flint and steel or another ignition source");
                }
                return assessment(ObjectiveProgressTier.NONE, "missing soul-fire materials",
                        "tell them to gather soul soil plus flint and steel");
            }

            if (sacredStructureHasAny(Material.CAMPFIRE, Material.SOUL_CAMPFIRE)) {
                return assessment(ObjectiveProgressTier.COMPLETE, "campfire already present at ritual site",
                        "clear the objective and reward the fire");
            }
            if (campfires > 0) {
                return assessment(ObjectiveProgressTier.READY, String.format("carrying campfire x%d", campfires),
                        "tell them to place the campfire at Soul Spire");
            }
            if (logs > 0 || planks > 0) {
                return assessment(ObjectiveProgressTier.PREPARING,
                        String.format("has wood for a fire (%d logs, %d planks)", logs, planks),
                        "tell them to craft a campfire with logs, sticks, and coal or charcoal");
            }
            return assessment(ObjectiveProgressTier.NONE, "no campfire or wood signal yet",
                    "tell them to chop logs and gather coal or charcoal for a campfire");
        }

        if (mentionsAny(normalized, "candle")) {
            if (candles > 0) {
                return assessment(ObjectiveProgressTier.READY, String.format("carrying candles x%d", candles),
                        "tell them to place a candle at Soul Spire");
            }
            if (sacredStructure != null && !StructureManager.getRitualComponentNames(sacredStructure).isEmpty()) {
                return assessment(ObjectiveProgressTier.PREPARING, "ritual site exists but candle not detected",
                        "tell them to craft or find candles and bring one to the ritual site");
            }
            return assessment(ObjectiveProgressTier.NONE, "no candle detected",
                    "tell them to craft candles from honeycomb and string or find one");
        }

        if (mentionsAny(normalized, "wood", "log", "timber")) {
            if (logs + planks >= 16) {
                return assessment(ObjectiveProgressTier.READY,
                        String.format("good wood supply gathered (%d logs, %d planks)", logs, planks),
                        "tell them where to deliver or build with the wood");
            }
            if (logs + planks > 0) {
                return assessment(ObjectiveProgressTier.PREPARING,
                        String.format("gathering wood (%d logs, %d planks)", logs, planks),
                        "encourage continued chopping until they have enough blocks");
            }
            return assessment(ObjectiveProgressTier.NONE, "no wood collected yet",
                    "tell them to punch or chop trees and bring logs");
        }

        if (mentionsAny(normalized, "sacrifice", "offering", "feast")) {
            long nearbyLivestock = nearbyEntities.entrySet().stream()
                    .filter(entry -> mentionsAny(entry.getKey(), "sheep", "cow", "pig", "chicken", "rabbit"))
                    .mapToLong(Map.Entry::getValue).sum();
            if (offerings > 0) {
                return assessment(ObjectiveProgressTier.READY, String.format("carrying offering food x%d", offerings),
                        "tell them to bring or drop the offering at Soul Spire");
            }
            if (nearbyLivestock > 0) {
                return assessment(ObjectiveProgressTier.PREPARING,
                        String.format("ritual livestock nearby x%d", nearbyLivestock),
                        "tell them to lead, breed, or harvest the nearby livestock for the offering");
            }
            return assessment(ObjectiveProgressTier.NONE, "no offering signal nearby",
                    "tell them to find food, livestock, crops, or another tangible offering");
        }

        if (mentionsAny(normalized, "altar", "shrine", "spire", "monument", "shelter", "vessel", "build",
                "raise", "forge")) {
            if (sacredStructure != null) {
                long stoneBlocks = countSacredStructureBlocks(type -> Tag.BASE_STONE_OVERWORLD.isTagged(type)
                        || type == Material.COBBLESTONE || type == Material.STONE_BRICKS
                        || type == Material.COBBLED_DEEPSLATE || type == Material.DEEPSLATE_BRICKS);
                if (mentionsAny(normalized, "shelter", "humble shelter")) {
                    if (sacredStructure.getSize() >= 24) {
                        return assessment(ObjectiveProgressTier.COMPLETE,
                                String.format("shelter stands at %s (%d blocks)", sacredStructure.getName(),
                                        sacredStructure.getSize()),
                                "clear the objective and reward the completed shelter");
                    }
                    if (sacredStructure.getSize() >= 12) {
                        return assessment(ObjectiveProgressTier.READY,
                                String.format("shelter frame formed at %s (%d blocks)", sacredStructure.getName(),
                                        sacredStructure.getSize()),
                                "tell them to add walls, roof, light, or a bed to finish the shelter");
                    }
                }
                if (mentionsAny(normalized, "spire") && mentionsAny(normalized, "stone")) {
                    if (sacredStructure.getSize() >= 12 && stoneBlocks >= 8) {
                        return assessment(ObjectiveProgressTier.COMPLETE,
                                String.format("stone spire stands at %s (%d blocks, %d stone)",
                                        sacredStructure.getName(), sacredStructure.getSize(), stoneBlocks),
                                "clear the objective and reward the raised spire");
                    }
                    if (stoneBlocks >= 4) {
                        return assessment(ObjectiveProgressTier.READY,
                                String.format("stone spire is taking shape (%d stone blocks)", stoneBlocks),
                                "tell them to stack more stone blocks upward at Soul Spire");
                    }
                }
                if (mentionsAny(normalized, "spire", "monument", "raise", "forge")
                        && sacredStructure.isSacredAwakened() && sacredStructure.getSize() >= 20) {
                    return assessment(ObjectiveProgressTier.COMPLETE,
                            String.format("%s stands awakened (%d blocks)", sacredStructure.getName(),
                                    sacredStructure.getSize()),
                            "clear the objective and reward the awakened ritual build");
                }
                return assessment(
                        sacredStructure.isSacredAwakened() ? ObjectiveProgressTier.READY
                                : ObjectiveProgressTier.PREPARING,
                        String.format("%s is present (%d blocks, %s)",
                                sacredStructure.getName(),
                                sacredStructure.getSize(),
                                sacredStructure.isSacredAwakened() ? "awakened" : "nascent"),
                        sacredStructure.isSacredAwakened()
                                ? "tell them which final block, offering, or action completes the ritual phase"
                                : "tell them to add ritual components such as bell, lectern, campfire, candle, or soul soil");
            }
            return assessment(ObjectiveProgressTier.NONE, "no tracked ritual structure yet",
                    "tell them to place connected blocks at the ritual site to start the structure");
        }

        if (sacredStructure != null) {
            return assessment(ObjectiveProgressTier.PREPARING,
                    String.format("nearest ritual anchor is %s (%d blocks)", sacredStructure.getName(),
                            sacredStructure.getSize()),
                    "use the existing ritual anchor and give the player one concrete action there");
        }
        return assessment(ObjectiveProgressTier.NONE, "no direct progress signal yet",
                "ask for or assign a concrete Minecraft action with visible blocks, items, mobs, or travel");
    }

    private static String getNearbyEntitySummary(Player player) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Entity entity : getNearbyEntities(player)) {
            String key = entity.getType().name().toLowerCase(Locale.ROOT);
            counts.put(key, counts.getOrDefault(key, 0L) + 1);
        }
        if (counts.isEmpty()) {
            return "Nearby Entities: none";
        }

        String summary = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        return "Nearby Entities: " + summary;
    }

    private static String getPositionInfo(Player player) {
        Location location = player.getLocation();
        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        Block target = player.getTargetBlockExact(16);
        String targetSummary = "none within 16 blocks";
        if (target != null) {
            targetSummary = String.format("%s at %s, %.1f blocks away",
                    formatMaterial(target.getType()),
                    formatBlockCoords(target.getLocation()),
                    target.getLocation().toCenterLocation().distance(location));
        }

        return String.format(
                "Position: world=%s, xyz=%s, biome=%s, facing=%s, standing_on=%s, feet=%s, head=%s, light=%d, looking_at=%s",
                location.getWorld().getName(),
                formatBlockCoords(location),
                feet.getBiome().name().toLowerCase(Locale.ROOT),
                getCardinalFacing(location.getYaw()),
                formatMaterial(ground.getType()),
                formatMaterial(feet.getType()),
                formatMaterial(head.getType()),
                feet.getLightLevel(),
                targetSummary);
    }

    private static String formatBlockCoords(Location location) {
        return String.format("%d,%d,%d", location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static String getCardinalFacing(float yaw) {
        float normalized = (yaw % 360 + 360) % 360;
        if (normalized >= 315 || normalized < 45) {
            return "south";
        }
        if (normalized < 135) {
            return "west";
        }
        if (normalized < 225) {
            return "north";
        }
        return "east";
    }

    private static String getNearbyPlayerSummary(Player player) {
        List<String> players = GPTGOD.SERVER.getOnlinePlayers().stream()
                .filter(other -> !other.equals(player))
                .filter(other -> other.getWorld().equals(player.getWorld()))
                .filter(other -> other.getLocation().distanceSquared(player.getLocation()) <= 1600)
                .sorted(Comparator.comparingDouble(other -> other.getLocation().distanceSquared(player.getLocation())))
                .limit(4)
                .map(other -> String.format("%s %.0f blocks %s",
                        other.getName(),
                        other.getLocation().distance(player.getLocation()),
                        getRelativeDirection(player.getLocation(), other.getLocation())))
                .toList();
        return players.isEmpty() ? "Nearby Players: none" : "Nearby Players: " + String.join(", ", players);
    }

    private static String getRelativeDirection(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0 ? "east" : "west";
        }
        return dz >= 0 ? "south" : "north";
    }

    private static String getNearbyBlockSummary(Player player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Location center = player.getLocation();
        World world = player.getWorld();
        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();

        for (int x = -5; x <= 5; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -5; z <= 5; z++) {
                    Material material = world.getBlockAt(baseX + x, baseY + y, baseZ + z).getType();
                    if (!isUsefulNearbyMaterial(material)) {
                        continue;
                    }
                    String key = formatMaterial(material);
                    counts.put(key, counts.getOrDefault(key, 0) + 1);
                }
            }
        }

        if (counts.isEmpty()) {
            return "Nearby Useful Blocks: none";
        }

        String summary = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(8)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        return "Nearby Useful Blocks: " + summary;
    }

    private static String getThreatTags(Player player) {
        List<String> tags = new ArrayList<>();
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue();
        double healthRatio = player.getHealth() / maxHealth;
        long nearbyHostiles = getNearbyEntities(player).stream()
                .filter(entity -> entity instanceof Enemy)
                .count();

        if (healthRatio <= 0.3) {
            tags.add("gravely_wounded");
        } else if (healthRatio <= 0.5) {
            tags.add("injured");
        }
        if (player.getFoodLevel() <= 6) {
            tags.add("low_food");
        }
        if (nearbyHostiles > 0) {
            tags.add("hostiles_nearby x" + nearbyHostiles);
        }
        if (player.getFireTicks() > 0) {
            tags.add("burning");
        }
        if (player.getRemainingAir() < player.getMaximumAir() / 2) {
            tags.add("low_air");
        }
        if (nearbyHas(player, material -> material == Material.LAVA, 4, 3, 4)) {
            tags.add("near_lava");
        }
        if (!player.getWorld().isDayTime() && player.getLocation().getBlock().getLightLevel() < 8) {
            tags.add("dark_at_night");
        }
        if (player.getAttribute(Attribute.GENERIC_ARMOR) != null
                && player.getAttribute(Attribute.GENERIC_ARMOR).getValue() < 2.0
                && nearbyHostiles > 0) {
            tags.add("poorly_armored_in_danger");
        }

        return tags.isEmpty() ? "Threat Tags: none" : "Threat Tags: " + String.join(", ", tags);
    }

    private static String getOpportunityTags(Player player) {
        List<String> tags = new ArrayList<>();
        int logs = countItems(player, Tag.LOGS::isTagged);
        int planks = countItems(player, type -> type.name().endsWith("_PLANKS"));
        int sticks = countItems(player, Material.STICK);
        int coal = countItems(player, type -> type == Material.COAL || type == Material.CHARCOAL);
        int gold = countItems(player, Material.GOLD_INGOT);
        int bells = countItems(player, Material.BELL);
        int lecterns = countItems(player, Material.LECTERN);
        int campfires = countItems(player, type -> type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE);
        int soulSoil = countItems(player, Material.SOUL_SOIL);
        int flintAndSteel = countItems(player, Material.FLINT_AND_STEEL);

        addIf(tags, logs > 0 || planks > 0, "has_wood");
        addIf(tags, sticks > 0, "has_sticks");
        addIf(tags, coal > 0, "has_coal_or_charcoal");
        addIf(tags, gold > 0, "has_gold x" + gold);
        addIf(tags, bells > 0, "has_bell");
        addIf(tags, lecterns > 0, "has_lectern");
        addIf(tags, campfires > 0, "has_campfire");
        addIf(tags, soulSoil > 0, "has_soul_soil");
        addIf(tags, flintAndSteel > 0, "has_flint_and_steel");

        addIf(tags, nearbyHas(player, material -> material == Material.CRAFTING_TABLE, 5, 3, 5), "near_crafting_table");
        addIf(tags, nearbyHas(player, material -> material == Material.FURNACE || material == Material.BLAST_FURNACE
                || material == Material.SMOKER, 5, 3, 5), "near_furnace");
        addIf(tags, nearbyHas(player, material -> material == Material.CHEST || material == Material.TRAPPED_CHEST
                || material == Material.BARREL, 5, 3, 5), "near_storage");
        addIf(tags, nearbyHas(player, material -> material == Material.WATER, 5, 3, 5), "near_water");
        addIf(tags, nearbyHas(player, Tag.LOGS::isTagged, 5, 5, 5), "near_logs");
        addIf(tags, nearbyHas(player, material -> material == Material.BELL, 6, 4, 6), "near_bell");
        addIf(tags, nearbyHas(player, material -> material == Material.LECTERN, 6, 4, 6), "near_lectern");

        long livestock = getNearbyEntities(player).stream().filter(entity -> entity instanceof Animals).count();
        addIf(tags, livestock > 0, "near_livestock x" + livestock);

        Structure closest = StructureManager.getClosestStructureToLocation(player.getLocation());
        if (closest != null && closest.getLocation().getWorld().equals(player.getWorld())
                && closest.getDistanceToI(player.getLocation()) <= 20) {
            tags.add("near_" + closest.getName());
        }

        if (logs >= 3 && sticks >= 3 && coal > 0) {
            tags.add("can_craft_campfire");
        }
        if (soulSoil > 0 && flintAndSteel > 0) {
            tags.add("can_light_soul_fire");
        }
        if (gold >= 8) {
            tags.add("can_pursue_bell");
        }

        PlayerMemory memory = MemoryStore.get(player);
        boolean readyObjective = false;
        boolean visibleProgress = false;
        for (String objective : memory.activeObjectives) {
            ObjectiveAssessment assessment = assessObjectiveProgress(player, objective);
            readyObjective |= assessment.tier() == ObjectiveProgressTier.READY
                    || assessment.tier() == ObjectiveProgressTier.COMPLETE;
            visibleProgress |= assessment.progressVisible();
        }
        addIf(tags, readyObjective, "can_finish_or_advance_objective");
        addIf(tags, visibleProgress, "objective_progress_visible");

        return tags.isEmpty() ? "Opportunity Tags: none" : "Opportunity Tags: " + String.join(", ", tags);
    }

    private static boolean nearbyHas(Player player, Predicate<Material> matcher, int radiusXz, int radiusY,
            int limitXz) {
        Location center = player.getLocation();
        World world = player.getWorld();
        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();
        int scanXz = Math.min(radiusXz, limitXz);
        for (int x = -scanXz; x <= scanXz; x++) {
            for (int y = -radiusY; y <= radiusY; y++) {
                for (int z = -scanXz; z <= scanXz; z++) {
                    if (matcher.test(world.getBlockAt(baseX + x, baseY + y, baseZ + z).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void addIf(List<String> tags, boolean condition, String tag) {
        if (condition) {
            tags.add(tag);
        }
    }

    private static boolean isUsefulNearbyMaterial(Material material) {
        if (material.isAir()) {
            return false;
        }
        String name = material.name().toLowerCase(Locale.ROOT);
        return material == Material.WATER
                || material == Material.LAVA
                || material == Material.FIRE
                || material == Material.SOUL_FIRE
                || material == Material.CHEST
                || material == Material.TRAPPED_CHEST
                || material == Material.BARREL
                || material == Material.CRAFTING_TABLE
                || material == Material.FURNACE
                || material == Material.BLAST_FURNACE
                || material == Material.SMOKER
                || material == Material.CAMPFIRE
                || material == Material.SOUL_CAMPFIRE
                || material == Material.BELL
                || material == Material.LECTERN
                || material == Material.SOUL_SOIL
                || material == Material.SPAWNER
                || material == Material.ANVIL
                || Tag.LOGS.isTagged(material)
                || Tag.CANDLES.isTagged(material)
                || name.endsWith("_ore")
                || name.endsWith("_bed")
                || name.endsWith("_door")
                || name.endsWith("_sapling")
                || name.endsWith("_torch")
                || name.endsWith("_lantern")
                || name.endsWith("_cauldron")
                || name.contains("wheat")
                || name.contains("carrot")
                || name.contains("potato")
                || name.contains("beetroot")
                || name.contains("cocoa")
                || name.contains("nether_portal");
    }

    private static String getSurvivalState(Player player) {
        List<String> states = new ArrayList<>();
        states.add(String.format("Food %d/20", player.getFoodLevel()));
        double armor = player.getAttribute(Attribute.GENERIC_ARMOR) != null
                ? player.getAttribute(Attribute.GENERIC_ARMOR).getValue()
                : 0.0;
        states.add(String.format("Armor %.0f", armor));
        if (player.getRemainingAir() < player.getMaximumAir()) {
            states.add(String.format("Air %d/%d", player.getRemainingAir(), player.getMaximumAir()));
        }
        if (player.getFireTicks() > 0) {
            states.add("Burning");
        }
        if (player.getFreezeTicks() > 0) {
            states.add("Freezing");
        }
        if (player.isSwimming()) {
            states.add("Swimming");
        }
        if (player.isSprinting()) {
            states.add("Sprinting");
        }
        if (player.isGliding()) {
            states.add("Gliding");
        }

        List<String> effects = player.getActivePotionEffects().stream()
                .sorted(Comparator.comparing(effect -> effect.getType().getKey().getKey()))
                .limit(4)
                .map(ServerInfoSummarizer::formatPotionEffect)
                .toList();
        if (!effects.isEmpty()) {
            states.add("Effects " + String.join(", ", effects));
        }

        return "Survival State: " + String.join(", ", states);
    }

    private static String formatPotionEffect(PotionEffect effect) {
        String name = effect.getType().getKey().getKey().replace('_', ' ');
        return String.format("%s %d", name, effect.getAmplifier() + 1);
    }

    private static String getKeySupplies(Player player) {
        List<String> parts = new ArrayList<>();
        int bells = countItems(player, Material.BELL);
        int gold = countItems(player, Material.GOLD_INGOT);
        int lecterns = countItems(player, Material.LECTERN);
        int campfires = countItems(player, type -> type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE);
        int soulSoil = countItems(player, Material.SOUL_SOIL);
        int candles = countItems(player, type -> Tag.CANDLES.isTagged(type) || type == Material.CANDLE_CAKE);
        int flintAndSteel = countItems(player, Material.FLINT_AND_STEEL);
        int logs = countItems(player, Tag.LOGS::isTagged);
        int sticks = countItems(player, Material.STICK);

        if (bells > 0) parts.add("bell x" + bells);
        if (gold > 0) parts.add("gold_ingot x" + gold);
        if (lecterns > 0) parts.add("lectern x" + lecterns);
        if (campfires > 0) parts.add("campfire x" + campfires);
        if (soulSoil > 0) parts.add("soul_soil x" + soulSoil);
        if (candles > 0) parts.add("candle x" + candles);
        if (flintAndSteel > 0) parts.add("flint_and_steel x" + flintAndSteel);
        if (logs > 0) parts.add("logs x" + logs);
        if (sticks > 0) parts.add("stick x" + sticks);

        if (parts.isEmpty()) {
            return "Key Supplies: none";
        }
        return "Key Supplies: " + String.join(", ", parts);
    }

    private static String getObjectives() {
        return GPTGOD.SCOREBOARD.getObjectives().size() < 1 ? "NONE"
                : String.format("Objectives: %s", String.join(",", GPTGOD.SCOREBOARD.getEntries().stream()
                        .filter(entry -> GPTGOD.SERVER.getPlayer(entry) == null).toList()));
    }

    public static String compileStatus() {
        StringBuilder sb = new StringBuilder("Server Status:\n");
        sb.append(String.format("Game Loop Cycle: %d\n", GameLoop.getCycleCount()));
        sb.append(String.format("Time of day: %s %s\n", WorldManager.getCurrentWorld().isDayTime() ? "Day" : "Night",
                GPTUtils.getWorldTimeStamp(WorldManager.getCurrentWorld())));
        sb.append(String.format("Weather: %s\n", getWeather()));
        sb.append("Structures: " + getStructures() + "\n");
        sb.append("Sacred Structure: " + StructureManager.getSacredStructureSummary() + "\n");
        sb.append("Ritual Phase: " + StructureManager.getRitualPhaseSummary() + "\n");
        sb.append("Ritual Awareness: " + StructureManager.getRitualAwarenessSummary() + "\n");
        sb.append("Recent Sacred Changes: " + StructureManager.getRecentSacredChangesSummary() + "\n");
        sb.append("Command Truth: " + CommandInsightTracker.getSummary() + "\n");
        sb.append(ActionOutcomeTracker.getSummary() + "\n");
        sb.append("Unresolved Divine Debts: " + MemoryStore.getGlobalUnresolvedDebtSummary() + "\n");
        sb.append("Highest Priority Settlement: " + MemoryStore.getHighestPrioritySettlementDirective() + "\n");
        sb.append("Recent Settled Debts: " + MemoryStore.getRecentSettledDebtSummary() + "\n");
        sb.append(AwarenessTracker.compileChangeSummary(GPTGOD.SERVER.getOnlinePlayers()) + "\n");
        sb.append(getObjectives() + "\n");
        for (Player player : GPTGOD.SERVER.getOnlinePlayers()) {
            // player.getP
            String name = player.getName();
            String nickname = NicknameCommand.getNickname(player);
            boolean isDead = player.isDead() || player.getGameMode().equals(GameMode.SPECTATOR);
            String health = isDead ? "Dead" : getPlayerHealth(player);
            boolean isSleeping = player.isSleeping();
            // player.getInventory()
            String inventoryInfo = getInventoryInfo(player);
            sb.append("Status of Player " + name + ":\n");
            if (!nickname.isBlank())
                sb.append("Nickname: " + nickname + "\n");
            sb.append("God Memory: " + MemoryStore.getPromptSummary(player) + "\n");
            sb.append(PlayerIntentTracker.getSummary(player) + "\n");
            sb.append("Unresolved Divine Debts: " + MemoryStore.getUnresolvedDebtSummary(player) + "\n");
            sb.append("Highest Priority Settlement: " + MemoryStore.getHighestPrioritySettlement(player) + "\n");
            sb.append("Recent Settled Debts: " + MemoryStore.getRecentSettledDebtSummary(player) + "\n");
            sb.append("Judgment State: " + MemoryStore.get(player).getJudgmentSummary() + "\n");
            sb.append("Relationships: " + MemoryStore.get(player).getRelationshipSummary() + "\n");
            if (GPTGOD.gameMode.equals(GptGameMode.DEATHMATCH)) {
                sb.append(String.format("Team: %s\n", GPTGOD.SCOREBOARD.getEntityTeam(player).getName()));
            }
            sb.append("Health: " + health + '\n');
            if (!isDead) {
                sb.append(StructureManager.getStructureDescription(StructureManager.getClosestStructureToLocation(player.getLocation()),player.getLocation()));
                // sb.append("\tDead? " + isDead + "\n");
                // sb.append("\tInventory: " + inventoryInfo + "\n");
                // sb.append(isDead? "Dead\n" : "Alive\n");
                sb.append(isSleeping ? "Asleep\n" : "");
                sb.append(getPositionInfo(player) + "\n");
                sb.append(getSurvivalState(player) + "\n");
                sb.append("Danger Level: " + getDangerLevel(player) + "\n");
                sb.append(getThreatTags(player) + "\n");
                sb.append(getOpportunityTags(player) + "\n");
                sb.append(getNearbyPlayerSummary(player) + "\n");
                sb.append(getNearbyEntitySummary(player) + "\n");
                sb.append(getNearbyBlockSummary(player) + "\n");
                sb.append(getKeySupplies(player) + "\n");
                sb.append(getObjectiveProgress(player) + "\n");
                sb.append(getObjectiveHints(player) + "\n");
                sb.append(getInventorySummary(player) + "\n");
                sb.append(inventoryInfo + "\n");
            }
        }
        ;
        return sb.toString();
    }

    private static ObjectiveAssessment assessment(ObjectiveProgressTier tier, String summary) {
        return assessment(tier, summary, defaultNextStep(tier));
    }

    private static ObjectiveAssessment assessment(ObjectiveProgressTier tier, String summary, String nextStep) {
        return new ObjectiveAssessment(tier, summary, nextStep, tier.isMeaningful());
    }

    private static String defaultNextStep(ObjectiveProgressTier tier) {
        return switch (tier) {
        case COMPLETE -> "clear the objective and deliver the owed reward";
        case READY -> "tell the player exactly where to place, use, or deliver the prepared item";
        case PREPARING -> "encourage the current preparation and name the missing piece";
        case NONE -> "give one concrete Minecraft action that would start progress";
        };
    }
}
