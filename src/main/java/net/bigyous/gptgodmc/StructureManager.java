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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import net.bigyous.gptgodmc.GPT.GptActions;
import net.bigyous.gptgodmc.Structure.CiritiqueStatus;
import net.bigyous.gptgodmc.loggables.GenericEventLoggable;
import net.bigyous.gptgodmc.memory.MemoryStore;
import net.bigyous.gptgodmc.memory.PlayerMemory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class StructureManager implements Listener {
    private static final String SACRED_STRUCTURE_NAME = "Soul Spire";
    private static final int SACRED_STRUCTURE_MIN_SIZE = 1;
    private static final int RITUAL_SCAN_RADIUS = 4;
    private static final int MIN_RITUAL_COMPONENTS = 3;
    private static final long RITUAL_MAJOR_SCENE_COOLDOWN_MS = 8_000L;
    private static final long RITUAL_MINOR_SCENE_COOLDOWN_MS = 25_000L;
    private static final long RITUAL_LOSS_SCENE_COOLDOWN_MS = 18_000L;
    private static final long RITUAL_GLOBAL_MINOR_SCENE_COOLDOWN_MS = 8_000L;
    private static final long RITUAL_OFFERING_SCENE_COOLDOWN_MS = 7_000L;
    private static final long RITUAL_OFFERING_REWARD_COOLDOWN_MS = 45_000L;
    private static final long RITUAL_INTERACTION_COOLDOWN_MS = 8_000L;
    private static final int RITUAL_OFFERING_RADIUS = 7;
    private static final int RITUAL_INTERACTION_RADIUS = 7;
    private static final int RITUAL_HINT_RADIUS = 16;
    private static final int RITUAL_OFFERING_CONSUME_DELAY_TICKS = 28;
    private static final int RITUAL_CHARGE_THRESHOLD = 10;
    private static final long RITUAL_HINT_COOLDOWN_MS = 22_000L;
    private static final int RITUAL_WISP_LIFETIME_SECONDS = 90;
    private static ConcurrentHashMap<String, Structure> structures = new ConcurrentHashMap<String, Structure>();
    private static final ConcurrentHashMap<String, Long> sacredSceneCooldowns = new ConcurrentHashMap<>();
    private static final LinkedList<String> recentSacredChanges = new LinkedList<>();
    private static final Set<String> achievedSpireMilestones = ConcurrentHashMap.newKeySet();
    private static int ritualCharge = 0;
    private static int ritualSurgeCount = 0;

    private enum RitualComponent {
        BELL,
        LECTERN,
        CAMPFIRE,
        SOUL_SOIL,
        CANDLE
    }

    private enum OfferingQuality {
        REJECTED,
        MODEST,
        WORTHY,
        SACRED
    }

    private static final class OfferingResponse {
        private final OfferingQuality quality;
        private final String message;
        private final int intensity;
        private final int experience;
        private final Material rewardMaterial;
        private final int rewardCount;

        private OfferingResponse(OfferingQuality quality, String message, int intensity, int experience,
                Material rewardMaterial, int rewardCount) {
            this.quality = quality;
            this.message = message;
            this.intensity = intensity;
            this.experience = experience;
            this.rewardMaterial = rewardMaterial;
            this.rewardCount = rewardCount;
        }
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

    @EventHandler(ignoreCancelled = true)
    public void onOfferingDropped(PlayerDropItemEvent event) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null) {
            return;
        }
        Item item = event.getItemDrop();
        Player player = event.getPlayer();
        GPTGOD.SERVER.getScheduler().runTaskLater(JavaPlugin.getPlugin(GPTGOD.class),
                () -> handlePossibleOffering(player, item), RITUAL_OFFERING_CONSUME_DELAY_TICKS);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRitualInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Structure sacredStructure = getSacredStructure();
        Block block = event.getClickedBlock();
        if (sacredStructure == null || !isNearSacredStructure(block.getLocation(), sacredStructure,
                RITUAL_INTERACTION_RADIUS)) {
            return;
        }
        handleRitualInteraction(event.getPlayer(), block);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRitualApproach(PlayerMoveEvent event) {
        if (event.getTo() == null || !changedBlock(event.getFrom(), event.getTo())) {
            return;
        }
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null || !event.getPlayer().getWorld().equals(sacredStructure.getLocation().getWorld())
                || event.getPlayer().getLocation().distanceSquared(sacredStructure.getLocation()) > RITUAL_HINT_RADIUS
                        * RITUAL_HINT_RADIUS
                || !sacredSceneReady("ritual-hint:" + event.getPlayer().getUniqueId(), RITUAL_HINT_COOLDOWN_MS, 0)) {
            return;
        }
        showRitualHint(event.getPlayer(), sacredStructure);
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

        Structure sacredStructure = getSacredStructure();
        if (sacredStructure != null && sacredStructure != structure) {
            if (!isNearSacredStructure(placedBlock, sacredStructure)) {
                return;
            }
            if (structure != null) {
                structure.removeBlock(placedBlock.getLocation());
                if (structure.getSize() < 1) {
                    structures.remove(structureKey);
                }
            }
            sacredStructure.addBlock(placedBlock.getLocation());
            structure = sacredStructure;
            structureKey = SACRED_STRUCTURE_NAME;
        }

        boolean wasSacred = structure.isSacred();
        boolean wasAwakened = structure.isSacredAwakened();
        Set<RitualComponent> nearbyComponents = getNearbyRitualComponents(structure);
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
            stageSacredScene("consecration", "spire", "soul", 2,
                    String.format("%s has been consecrated.", SACRED_STRUCTURE_NAME),
                    RITUAL_MAJOR_SCENE_COOLDOWN_MS, 0);
            EventLogger.addLoggable(new GenericEventLoggable(String.format(
                    "%s has consecrated a nascent ritual anchor as %s with %s",
                    builder.getName(),
                    SACRED_STRUCTURE_NAME,
                    placedComponent.name().toLowerCase().replace('_', ' '))));
        } else if (!wasAwakened && nearbyComponents.size() >= MIN_RITUAL_COMPONENTS) {
            noteSacredChange(String.format("%s awakened with %s", SACRED_STRUCTURE_NAME,
                    nearbyComponents.stream().map(Enum::name).map(name -> name.toLowerCase().replace('_', ' ')).sorted()
                            .toList()));
            stageSacredScene("awakening", "spire", "soul", 3,
                    String.format("%s awakens.", SACRED_STRUCTURE_NAME),
                    RITUAL_MAJOR_SCENE_COOLDOWN_MS, 0);
            spawnSpireWisps(structure.getLocation(), 2, "awakening");
            EventLogger.addLoggable(new GenericEventLoggable(String.format(
                    "%s's ritual anchor of %s has awakened as %s",
                    builder.getName(),
                    nearbyComponents.stream().map(Enum::name).map(name -> name.toLowerCase().replace('_', ' ')).sorted()
                            .toList(),
                    SACRED_STRUCTURE_NAME)));
        }
    }

    private static boolean isNearSacredStructure(Block block, Structure sacredStructure) {
        if (block == null || sacredStructure == null || block.getWorld() == null) {
            return false;
        }
        for (Block sacredBlock : sacredStructure.getBlocks()) {
            if (sacredBlock.getWorld() == null || !sacredBlock.getWorld().equals(block.getWorld())) {
                continue;
            }
            if (Math.abs(sacredBlock.getX() - block.getX()) <= RITUAL_SCAN_RADIUS
                    && Math.abs(sacredBlock.getY() - block.getY()) <= RITUAL_SCAN_RADIUS
                    && Math.abs(sacredBlock.getZ() - block.getZ()) <= RITUAL_SCAN_RADIUS) {
                return true;
            }
        }
        return false;
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

    private static Set<RitualComponent> getNearbyRitualComponents(Structure structure) {
        Set<RitualComponent> found = EnumSet.noneOf(RitualComponent.class);
        if (structure == null) {
            return found;
        }
        for (Block block : structure.getBlocks()) {
            RitualComponent ownComponent = getRitualComponent(block.getType());
            if (ownComponent != null) {
                found.add(ownComponent);
            }
        }
        found.addAll(getNearbyRitualComponents(structure.getLocation()));
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
        return getNearbyRitualComponents(structure).stream()
                .distinct()
                .map(StructureManager::componentLabel)
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

        Set<RitualComponent> nearbyComponents = getNearbyRitualComponents(sacredStructure);
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
        return String.format(
                "stage=%s, charge=%d/%d, surges=%d, nearby components=%s, missing components=%s, closest player=%s, last changes=%s",
                sacredStructure.isSacredAwakened() ? "awakened" : "nascent",
                ritualCharge,
                RITUAL_CHARGE_THRESHOLD,
                ritualSurgeCount,
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
        RitualComponent component = getRitualComponent(block.getType());
        String blockLabel = block.getType().name().toLowerCase().replace('_', ' ');
        noteSacredChange(String.format("%s gained %s from %s", SACRED_STRUCTURE_NAME,
                blockLabel, playerName));
        if (component != null) {
            stageSacredScene("component-gained", "spire", "soul", 1,
                    String.format("The Spire accepts %s.", componentLabel(component)),
                    RITUAL_MINOR_SCENE_COOLDOWN_MS, RITUAL_GLOBAL_MINOR_SCENE_COOLDOWN_MS);
        } else {
            stageSacredScene("structure-grew", "spire", "soul", 1,
                    "The Spire grows.",
                    RITUAL_MINOR_SCENE_COOLDOWN_MS, RITUAL_GLOBAL_MINOR_SCENE_COOLDOWN_MS);
        }
        evaluateSpireBuildMilestones(sacredStructure, playerName);
    }

    private void logSacredStructureRemoval(Block block, String cause) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null || !sacredStructure.containsBlock(block.getLocation())) {
            return;
        }
        RitualComponent component = getRitualComponent(block.getType());
        String blockLabel = block.getType().name().toLowerCase().replace('_', ' ');
        noteSacredChange(String.format("%s lost %s because it %s", SACRED_STRUCTURE_NAME,
                blockLabel, cause));
        if (component != null) {
            stageSacredScene("component-lost", "judgment", "wrath", 2,
                    String.format("The Spire has lost %s.", componentLabel(component)),
                    RITUAL_LOSS_SCENE_COOLDOWN_MS, RITUAL_GLOBAL_MINOR_SCENE_COOLDOWN_MS);
        } else {
            stageSacredScene("structure-damaged", "spire", "soul", 1,
                    "The Spire shudders.",
                    RITUAL_LOSS_SCENE_COOLDOWN_MS, RITUAL_GLOBAL_MINOR_SCENE_COOLDOWN_MS);
        }
    }

    private static void handlePossibleOffering(Player player, Item item) {
        if (player == null || item == null || item.isDead()) {
            return;
        }
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null || !isNearSacredStructure(item.getLocation(), sacredStructure,
                RITUAL_OFFERING_RADIUS)) {
            return;
        }
        if (!sacredSceneReady("offering-process:" + player.getUniqueId(), 2_500L, 0)) {
            return;
        }

        ItemStack stack = item.getItemStack();
        Material offeredMaterial = stack.getType();
        OfferingResponse response = evaluateOffering(offeredMaterial);
        String offeredLabel = offeredMaterial.name().toLowerCase(Locale.ROOT).replace('_', ' ');

        if (response.quality == OfferingQuality.REJECTED) {
            item.setGlowing(true);
            noteSacredChange(String.format("%s rejected %s from %s", SACRED_STRUCTURE_NAME, offeredLabel,
                    player.getName()));
            pulseOffering(item.getLocation(), false);
            stageSacredScene("offering-rejected:" + player.getUniqueId(), "judgment", "wrath", 1,
                    "The Spire rejects this tribute.", RITUAL_OFFERING_SCENE_COOLDOWN_MS, 0);
            GptActions.whisperPlayer(player.getName(), "The Spire refuses worthless tribute.");
            MemoryStore.rememberGodMessage(player, "The Spire refuses worthless tribute.");
            EventLogger.addLoggable(new GenericEventLoggable(String.format(
                    "%s offered %s at %s, but the Spire rejected it",
                    player.getName(), offeredLabel, SACRED_STRUCTURE_NAME)));
            GameLoop.triggerSoon("rejected Soul Spire offering", 30);
            return;
        }

        consumeOneOffering(item);
        noteSacredChange(String.format("%s accepted %s from %s", SACRED_STRUCTURE_NAME, offeredLabel,
                player.getName()));
        pulseOffering(item.getLocation(), true);
        stageSacredScene("offering-accepted:" + player.getUniqueId(), "spire", "soul", response.intensity,
                response.message, RITUAL_OFFERING_SCENE_COOLDOWN_MS, 0);
        applyOfferingReward(player, response, offeredMaterial);
        completeOfferingObjective(player);
        addRitualCharge(player, offeringCharge(response), response.quality.name().toLowerCase(Locale.ROOT) + " offering");
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s offered %s at %s; response=%s",
                player.getName(), offeredLabel, SACRED_STRUCTURE_NAME, response.quality.name().toLowerCase())));
        GameLoop.triggerSoon("accepted Soul Spire offering", 20);
    }

    private static void handleRitualInteraction(Player player, Block block) {
        Material material = block.getType();
        String interaction = ritualInteractionName(material);
        if (interaction == null || !sacredSceneReady("ritual-interact:" + player.getUniqueId() + ":" + interaction,
                RITUAL_INTERACTION_COOLDOWN_MS, 0)) {
            return;
        }

        String blockLabel = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String message;
        String whisper;
        int intensity;
        String[] objectiveKeywords;

        if (material == Material.BELL) {
            message = "The Spire answers the bell.";
            whisper = "The bell's voice reaches me.";
            intensity = 2;
            objectiveKeywords = new String[] { "bell", "ring", "voice", "forge" };
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_BELL_RESONATE, 1.0f, 0.72f);
        } else if (material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || material == Material.SOUL_SOIL || material == Material.SOUL_SAND) {
            message = "Soul-fire stirs beneath the Spire.";
            whisper = "Fire and soul have been witnessed.";
            intensity = material == Material.SOUL_CAMPFIRE || material == Material.SOUL_SOIL ? 2 : 1;
            objectiveKeywords = new String[] { "campfire", "fire", "ignite", "soul-fire", "soul fire" };
            block.getWorld().playSound(block.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.65f);
        } else if (Tag.CANDLES.isTagged(material) || material == Material.CANDLE_CAKE) {
            message = "The candlelight is accepted.";
            whisper = "Small lights matter at the Spire.";
            intensity = 1;
            objectiveKeywords = new String[] { "candle", "light" };
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_CANDLE_AMBIENT, 0.85f, 0.75f);
        } else if (material == Material.LECTERN) {
            message = "The Spire records a mortal hand.";
            whisper = "The lectern stands before me.";
            intensity = 1;
            objectiveKeywords = new String[] { "lectern", "book", "record", "scripture" };
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 0.75f);
        } else if (getRitualComponent(material) != null) {
            message = "The ritual component stirs.";
            whisper = "The Spire feels your hand.";
            intensity = 1;
            objectiveKeywords = new String[] { "ritual", "spire", componentLabel(getRitualComponent(material)) };
        } else {
            return;
        }

        noteSacredChange(String.format("%s was invoked by %s touching %s", SACRED_STRUCTURE_NAME,
                player.getName(), blockLabel));
        pulseRitualInteraction(block.getLocation(), intensity);
        stageSacredScene("ritual-invoked:" + interaction, "spire", "soul", intensity, message,
                RITUAL_INTERACTION_COOLDOWN_MS, 0);
        GptActions.whisperPlayer(player.getName(), whisper);
        MemoryStore.rememberGodMessage(player, whisper);
        completeObjectiveMatching(player, objectiveKeywords);
        addRitualCharge(player, intensity, interaction + " invocation");
        EventLogger.addLoggable(new GenericEventLoggable(String.format("%s invoked %s by touching %s",
                player.getName(), SACRED_STRUCTURE_NAME, blockLabel)));
        GameLoop.triggerSoon("Soul Spire ritual interaction", 25);
    }

    private static void evaluateSpireBuildMilestones(Structure sacredStructure, String playerName) {
        if (sacredStructure == null) {
            return;
        }
        int height = getStructureHeight(sacredStructure);
        int size = sacredStructure.getSize();
        Player builder = GPTGOD.SERVER.getPlayerExact(playerName);

        if (height >= 6 && triggerBuildMilestone(sacredStructure, builder, "height-6",
                "The Soul Spire rises.", "first height", 1, 2, Material.GLOWSTONE_DUST, 6)) {
            return;
        }
        if ((height >= 12 || size >= 24) && triggerBuildMilestone(sacredStructure, builder, "height-12",
                "The Soul Spire claims the sky.", "high spire", 2, 4, Material.EXPERIENCE_BOTTLE, 5)) {
            return;
        }
        if ((height >= 20 || size >= 40) && triggerBuildMilestone(sacredStructure, builder, "height-20",
                "The Soul Spire becomes a beacon.", "towering spire", 3, 7, Material.DIAMOND, 1)) {
            return;
        }
        if (sacredStructure.isSacredAwakened() && size >= 64) {
            triggerBuildMilestone(sacredStructure, builder, "size-64",
                    "The Soul Spire becomes a sanctuary.", "sanctuary", 3, 8, Material.EMERALD, 3);
        }
    }

    private static int getStructureHeight(Structure structure) {
        Location[] bounds = structure.getBounds();
        if (bounds == null || bounds.length < 2) {
            return 1;
        }
        return Math.max(1, Math.abs(bounds[1].getBlockY() - bounds[0].getBlockY()) + 1);
    }

    private static boolean triggerBuildMilestone(Structure sacredStructure, Player builder, String key, String message,
            String milestoneName, int intensity, int charge, Material rewardMaterial, int rewardCount) {
        if (!achievedSpireMilestones.add(key)) {
            return false;
        }

        int height = getStructureHeight(sacredStructure);
        int size = sacredStructure.getSize();
        String builderName = builder == null ? "unknown builder" : builder.getName();
        noteSacredChange(String.format("%s reached %s built by %s (height=%d, size=%d)", SACRED_STRUCTURE_NAME,
                milestoneName, builderName, height, size));
        stageSacredScene("build-milestone:" + key, "spire", "soul", intensity, message, 0, 0);
        pulseRitualSurge(sacredStructure.getLocation(), intensity);
        dropBuildMilestoneReward(sacredStructure.getLocation(), rewardMaterial, rewardCount, milestoneName);
        if (intensity >= 2) {
            spawnSpireWisps(sacredStructure.getLocation(), intensity, milestoneName);
        }

        if (builder != null && builder.isOnline()) {
            builder.sendActionBar(Component.text(message + " +" + charge + " charge", NamedTextColor.AQUA));
            GptActions.whisperPlayer(builder.getName(), message);
            MemoryStore.rememberGodMessage(builder, message);
            addRitualCharge(builder, charge, "Spire build milestone: " + milestoneName);
            if (height >= 12 || size >= 24) {
                completeObjectiveMatching(builder, "spire", "monument", "tower", "raise", "build", "vessel",
                        "shrine", "altar");
            }
        }

        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s reached build milestone %s at height %d and size %d",
                SACRED_STRUCTURE_NAME, milestoneName, height, size)));
        GameLoop.triggerSoon("Soul Spire build milestone", 20);
        return true;
    }

    private static void dropBuildMilestoneReward(Location center, Material rewardMaterial, int rewardCount,
            String milestoneName) {
        if (center.getWorld() == null || rewardMaterial == null || rewardCount <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(rewardMaterial, rewardCount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Spire " + milestoneName, NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true));
            stack.setItemMeta(meta);
        }
        Item drop = center.getWorld().dropItem(center.clone().add(0, 2.8, 0), stack);
        drop.setGlowing(true);
        drop.setCustomNameVisible(true);
        drop.customName(Component.text("Spire " + milestoneName, NamedTextColor.AQUA));
        drop.setVelocity(new Vector(0, 0.32, 0));
    }

    private static void spawnSpireWisps(Location center, int intensity, String reason) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        int count = Math.max(1, Math.min(3, intensity));
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2 * i) / count;
            Location spawn = center.clone().add(Math.cos(angle) * (2.2 + intensity * 0.4), 1.3 + (i * 0.35),
                    Math.sin(angle) * (2.2 + intensity * 0.4));
            Entity entity = center.getWorld().spawnEntity(spawn, EntityType.ALLAY, true);
            entity.setGlowing(true);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setCustomNameVisible(true);
            entity.customName(Component.text("Soul Wisp", NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true));
            entity.setVelocity(new Vector(Math.cos(angle) * 0.08, 0.08, Math.sin(angle) * 0.08));
            pulseWispSpawn(spawn);
            scheduleWispRemoval(entity, reason);
        }
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s released %d temporary soul wisps after %s",
                SACRED_STRUCTURE_NAME, count, reason)));
    }

    private static void pulseWispSpawn(Location location) {
        if (location.getWorld() == null) {
            return;
        }
        location.getWorld().spawnParticle(Particle.SOUL, location, 28, 0.35, 0.35, 0.35, 0.02);
        location.getWorld().spawnParticle(Particle.END_ROD, location, 14, 0.25, 0.25, 0.25, 0.01);
        location.getWorld().playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.55f, 1.25f);
    }

    private static void scheduleWispRemoval(Entity entity, String reason) {
        GPTGOD.SERVER.getScheduler().runTaskLater(JavaPlugin.getPlugin(GPTGOD.class), () -> {
            if (entity == null || entity.isDead()) {
                return;
            }
            Location location = entity.getLocation();
            if (location.getWorld() != null) {
                location.getWorld().spawnParticle(Particle.SOUL, location, 22, 0.3, 0.3, 0.3, 0.015);
                location.getWorld().playSound(location, Sound.PARTICLE_SOUL_ESCAPE, 0.5f, 1.15f);
            }
            entity.remove();
            EventLogger.addLoggable(new GenericEventLoggable(String.format(
                    "A temporary Soul Wisp faded after %s", reason)));
        }, RITUAL_WISP_LIFETIME_SECONDS * 20L);
    }

    private static String ritualInteractionName(Material material) {
        if (material == Material.BELL) {
            return "bell";
        }
        if (material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || material == Material.SOUL_SOIL || material == Material.SOUL_SAND) {
            return "fire";
        }
        if (Tag.CANDLES.isTagged(material) || material == Material.CANDLE_CAKE) {
            return "candle";
        }
        if (material == Material.LECTERN) {
            return "lectern";
        }
        RitualComponent component = getRitualComponent(material);
        return component == null ? null : componentLabel(component);
    }

    private static boolean changedBlock(Location from, Location to) {
        return from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()
                || from.getWorld() == null
                || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld());
    }

    private static void showRitualHint(Player player, Structure sacredStructure) {
        String hint = buildRitualHint(sacredStructure);
        player.sendActionBar(Component.text(hint, NamedTextColor.AQUA));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, 0.75f);
        if (sacredStructure.getLocation().getWorld() != null) {
            sacredStructure.getLocation().getWorld().spawnParticle(Particle.SOUL,
                    sacredStructure.getLocation().clone().add(0, 1.2, 0),
                    24, 0.9, 0.8, 0.9, 0.015);
        }
    }

    private static String buildRitualHint(Structure sacredStructure) {
        List<String> missing = getMissingRitualComponentLabels(sacredStructure);
        int height = getStructureHeight(sacredStructure);
        if (!sacredStructure.isSacredAwakened()) {
            if (missing.isEmpty()) {
                return "Soul Spire: add ritual components nearby to awaken it.";
            }
            return "Soul Spire needs " + compactList(missing, 3) + ".";
        }
        if (ritualCharge >= RITUAL_CHARGE_THRESHOLD - 2) {
            return "Soul Spire near surge: offer tribute or invoke a component.";
        }
        if (ritualCharge > 0) {
            return String.format("Soul Spire charge %d/%d: offer, ring, touch fire, or build higher.", ritualCharge,
                    RITUAL_CHARGE_THRESHOLD);
        }
        if (height < 6) {
            return "Raise the Spire taller, then offer tribute or invoke its components.";
        }
        return "Drop offerings, ring bells, touch fire, or build higher to charge the Spire.";
    }

    private static List<String> getMissingRitualComponentLabels(Structure sacredStructure) {
        Set<RitualComponent> nearbyComponents = getNearbyRitualComponents(sacredStructure);
        Set<RitualComponent> missingComponents = EnumSet.allOf(RitualComponent.class);
        missingComponents.removeAll(nearbyComponents);
        return missingComponents.stream().map(StructureManager::componentLabel).sorted().toList();
    }

    private static String compactList(List<String> items, int limit) {
        if (items.isEmpty()) {
            return "ritual components";
        }
        if (items.size() <= limit) {
            return String.join(", ", items);
        }
        return String.join(", ", items.subList(0, limit)) + ", and more";
    }

    private static OfferingResponse evaluateOffering(Material material) {
        if (material == null || material.isAir()) {
            return rejectedOffering();
        }
        if (isSacredOffering(material)) {
            return new OfferingResponse(OfferingQuality.SACRED, "The Spire drinks a sacred tribute.", 3, 36,
                    Material.ENCHANTED_GOLDEN_APPLE, 1);
        }
        if (isWorthyOffering(material)) {
            return new OfferingResponse(OfferingQuality.WORTHY, "The Spire accepts a worthy tribute.", 2, 24,
                    Material.EXPERIENCE_BOTTLE, 6);
        }
        if (isFoodOffering(material) || isRitualOffering(material)) {
            return new OfferingResponse(OfferingQuality.MODEST, "The offering is received.", 1, 12, null, 0);
        }
        if (isRejectedOffering(material)) {
            return rejectedOffering();
        }
        return new OfferingResponse(OfferingQuality.MODEST, "The offering is received.", 1, 6, null, 0);
    }

    private static OfferingResponse rejectedOffering() {
        return new OfferingResponse(OfferingQuality.REJECTED, "The Spire rejects this tribute.", 1, 0, null, 0);
    }

    private static boolean isSacredOffering(Material material) {
        return material == Material.NETHER_STAR
                || material == Material.DRAGON_EGG
                || material == Material.DRAGON_HEAD
                || material == Material.ELYTRA
                || material == Material.NETHERITE_BLOCK
                || material == Material.NETHERITE_INGOT
                || material == Material.ENCHANTED_GOLDEN_APPLE
                || material == Material.TOTEM_OF_UNDYING;
    }

    private static boolean isWorthyOffering(Material material) {
        return material == Material.DIAMOND
                || material == Material.DIAMOND_BLOCK
                || material == Material.EMERALD
                || material == Material.EMERALD_BLOCK
                || material == Material.GOLD_INGOT
                || material == Material.GOLD_BLOCK
                || material == Material.GOLDEN_APPLE
                || material == Material.ANCIENT_DEBRIS
                || material == Material.AMETHYST_SHARD
                || material == Material.ECHO_SHARD;
    }

    private static boolean isRitualOffering(Material material) {
        return getRitualComponent(material) != null
                || material == Material.BONE
                || material == Material.SOUL_SAND
                || material == Material.CRYING_OBSIDIAN
                || material == Material.OBSIDIAN
                || material == Material.GLOWSTONE_DUST
                || material == Material.BLAZE_ROD;
    }

    private static boolean isFoodOffering(Material material) {
        String normalized = material.name().toLowerCase(Locale.ROOT);
        return containsAny(normalized, "beef", "mutton", "porkchop", "chicken", "cod", "salmon", "rabbit",
                "potato", "carrot", "bread", "apple", "beetroot", "cookie", "melon", "pumpkin", "wheat");
    }

    private static boolean isRejectedOffering(Material material) {
        return material == Material.ROTTEN_FLESH
                || material == Material.POISONOUS_POTATO
                || material == Material.DEAD_BUSH
                || material == Material.STICK
                || material == Material.DIRT
                || material == Material.COARSE_DIRT
                || material == Material.GRAVEL;
    }

    private static int offeringCharge(OfferingResponse response) {
        return switch (response.quality) {
        case SACRED -> 7;
        case WORTHY -> 4;
        case MODEST -> 2;
        default -> 0;
        };
    }

    private static synchronized void addRitualCharge(Player player, int amount, String reason) {
        if (amount <= 0) {
            return;
        }
        ritualCharge = Math.min(RITUAL_CHARGE_THRESHOLD + 8, ritualCharge + amount);
        noteSacredChange(String.format("%s gained %d ritual charge from %s (%d/%d)", SACRED_STRUCTURE_NAME,
                amount, player.getName(), Math.min(ritualCharge, RITUAL_CHARGE_THRESHOLD),
                RITUAL_CHARGE_THRESHOLD));
        showRitualCharge(player, amount);
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s added %d ritual charge to %s through %s (%d/%d)",
                player.getName(), amount, SACRED_STRUCTURE_NAME, reason, Math.min(ritualCharge, RITUAL_CHARGE_THRESHOLD),
                RITUAL_CHARGE_THRESHOLD)));

        if (ritualCharge >= RITUAL_CHARGE_THRESHOLD) {
            ritualCharge -= RITUAL_CHARGE_THRESHOLD;
            triggerRitualSurge(player, reason);
        }
    }

    private static void showRitualCharge(Player source, int added) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null) {
            return;
        }
        String message = String.format("Soul Spire +%d charge  (%d/%d)", added,
                Math.min(ritualCharge, RITUAL_CHARGE_THRESHOLD), RITUAL_CHARGE_THRESHOLD);
        for (Player player : GPTGOD.SERVER.getOnlinePlayers()) {
            if (!player.getWorld().equals(sacredStructure.getLocation().getWorld())) {
                continue;
            }
            if (player.getLocation().distanceSquared(sacredStructure.getLocation()) > 80 * 80) {
                continue;
            }
            player.sendActionBar(Component.text(message, player.equals(source) ? NamedTextColor.AQUA
                    : NamedTextColor.DARK_AQUA));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 0.9f);
        }
    }

    private static void triggerRitualSurge(Player source, String reason) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null) {
            return;
        }
        ritualSurgeCount++;
        int intensity = Math.min(3, 1 + ((ritualSurgeCount - 1) % 3));
        Location center = sacredStructure.getLocation();
        noteSacredChange(String.format("%s surged after %s from %s", SACRED_STRUCTURE_NAME, reason,
                source.getName()));
        stageSacredScene("ritual-surge:" + ritualSurgeCount, "spire", "soul", intensity,
                intensity >= 3 ? "The Soul Spire erupts with power." : "The Soul Spire surges.",
                RITUAL_MAJOR_SCENE_COOLDOWN_MS, 0);
        pulseRitualSurge(center, intensity);
        blessNearbyRitualPlayers(center, intensity);
        dropRitualSurgeReward(center, intensity);
        spawnSpireWisps(center, intensity, "ritual surge");
        if (ritualSurgeCount % 3 == 0 && source.isOnline()) {
            GptActions.stageDivineScene(source.getName(), "trial", "soul", 1,
                    "The Spire demands proof.");
        }
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s unleashed ritual surge #%d at intensity %d after %s",
                SACRED_STRUCTURE_NAME, ritualSurgeCount, intensity, reason)));
        GameLoop.triggerSoon("Soul Spire ritual surge", 15);
    }

    private static void pulseRitualSurge(Location center, int intensity) {
        if (center.getWorld() == null) {
            return;
        }
        Location above = center.clone().add(0, 1.4, 0);
        int clamped = Math.max(1, Math.min(3, intensity));
        center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, above, 80 + clamped * 60, 1.4, 1.1, 1.4, 0.04);
        center.getWorld().spawnParticle(Particle.END_ROD, above, 35 + clamped * 35, 1.0, 1.6, 1.0, 0.03);
        center.getWorld().spawnParticle(Particle.FIREWORK, above, 25 + clamped * 20, 0.8, 0.8, 0.8, 0.02);
        center.getWorld().playSound(above, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.6f);
        center.getWorld().playSound(above, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.85f, 0.75f);
        if (clamped >= 3) {
            center.getWorld().strikeLightningEffect(center);
        }
    }

    private static void blessNearbyRitualPlayers(Location center, int intensity) {
        if (center.getWorld() == null) {
            return;
        }
        int duration = 20 * (18 + intensity * 6);
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) > 96 * 96) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, duration, Math.max(0, intensity - 1)));
            if (intensity >= 2) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * (8 + intensity * 3), 0));
            }
            player.giveExp(8 + intensity * 8);
            player.sendActionBar(Component.text("The Soul Spire surges through you.", NamedTextColor.AQUA));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.75f, 0.8f);
        }
    }

    private static void dropRitualSurgeReward(Location center, int intensity) {
        if (center.getWorld() == null) {
            return;
        }
        Material material = intensity >= 3 ? Material.DIAMOND : intensity == 2 ? Material.EXPERIENCE_BOTTLE
                : Material.AMETHYST_SHARD;
        int count = intensity >= 3 ? 1 : intensity == 2 ? 5 : 4;
        ItemStack stack = new ItemStack(material, count);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Soul Spire Surge", NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true));
            stack.setItemMeta(meta);
        }
        Item drop = center.getWorld().dropItem(center.clone().add(0, 2.4, 0), stack);
        drop.setGlowing(true);
        drop.setCustomNameVisible(true);
        drop.customName(Component.text("Soul Spire Surge", NamedTextColor.AQUA));
        drop.setVelocity(new Vector(0, 0.36, 0));
    }

    private static void consumeOneOffering(Item item) {
        ItemStack stack = item.getItemStack();
        int amount = stack.getAmount();
        if (amount <= 1) {
            item.remove();
            return;
        }
        stack.setAmount(amount - 1);
        item.setItemStack(stack);
        item.setVelocity(new Vector(0, 0.18, 0));
        item.setGlowing(true);
    }

    private static void pulseOffering(Location location, boolean accepted) {
        if (location.getWorld() == null) {
            return;
        }
        Location center = location.clone().add(0, 0.35, 0);
        location.getWorld().spawnParticle(accepted ? Particle.SOUL_FIRE_FLAME : Particle.SMOKE, center,
                accepted ? 70 : 35, 0.45, 0.35, 0.45, 0.03);
        location.getWorld().spawnParticle(accepted ? Particle.ENCHANT : Particle.WITCH, center,
                accepted ? 45 : 25, 0.6, 0.25, 0.6, 0.01);
        location.getWorld().playSound(center,
                accepted ? Sound.BLOCK_BEACON_POWER_SELECT : Sound.ENTITY_ENDERMAN_TELEPORT,
                accepted ? 0.9f : 0.65f, accepted ? 0.65f : 0.45f);
    }

    private static void pulseRitualInteraction(Location location, int intensity) {
        if (location.getWorld() == null) {
            return;
        }
        Location center = location.clone().add(0.5, 1.0, 0.5);
        int clamped = Math.max(1, Math.min(3, intensity));
        location.getWorld().spawnParticle(Particle.SOUL, center, 24 + clamped * 20, 0.45, 0.45, 0.45, 0.02);
        location.getWorld().spawnParticle(Particle.END_ROD, center, 10 + clamped * 12, 0.25, 0.5, 0.25, 0.01);
        location.getWorld().playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 0.45f + clamped * 0.18f, 0.85f);
    }

    private static void applyOfferingReward(Player player, OfferingResponse response, Material offeredMaterial) {
        if (response.experience > 0) {
            player.giveExp(response.experience);
        }
        int duration = 20 * (10 + response.intensity * 5);
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0));
        if (response.quality == OfferingQuality.WORTHY || response.quality == OfferingQuality.SACRED) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, duration, response.intensity - 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 8, 0));
        }

        String godMessage = response.quality == OfferingQuality.SACRED
                ? "Your sacred tribute shakes the Spire."
                : response.quality == OfferingQuality.WORTHY
                        ? "The Spire accepts your worthy tribute."
                        : "The Spire accepts your offering.";
        GptActions.whisperPlayer(player.getName(), godMessage);
        MemoryStore.rememberGodMessage(player, godMessage);
        MemoryStore.recordRewardGranted(player.getName(), "Soul Spire offering");

        if (response.rewardMaterial == null || !sacredSceneReady("offering-reward:" + player.getUniqueId(),
                RITUAL_OFFERING_REWARD_COOLDOWN_MS, 0)) {
            return;
        }

        ItemStack reward = new ItemStack(response.rewardMaterial, response.rewardCount);
        ItemMeta meta = reward.getItemMeta();
        if (meta != null) {
            String display = response.quality == OfferingQuality.SACRED ? "Spire-Blessed Relic" : "Spire's Favor";
            meta.displayName(Component.text(display, NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            reward.setItemMeta(meta);
        }
        Item drop = player.getWorld().dropItem(player.getLocation().clone().add(0, 2.2, 0), reward);
        drop.setGlowing(true);
        drop.setCustomNameVisible(true);
        drop.customName(Component.text(response.rewardMaterial.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                NamedTextColor.GOLD));
        drop.setVelocity(new Vector(0, 0.28, 0));
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s received %d %s after offering %s at %s",
                player.getName(), response.rewardCount, response.rewardMaterial.name().toLowerCase(Locale.ROOT),
                offeredMaterial.name().toLowerCase(Locale.ROOT), SACRED_STRUCTURE_NAME)));
    }

    private static void completeOfferingObjective(Player player) {
        completeObjectiveMatching(player, "offering", "sacrifice", "feast", "tribute", "repentance");
    }

    private static void completeObjectiveMatching(Player player, String... keywords) {
        PlayerMemory memory = MemoryStore.get(player);
        for (String objective : List.copyOf(memory.activeObjectives)) {
            String normalized = objective.toLowerCase(Locale.ROOT);
            if (containsAny(normalized, keywords)) {
                GptActions.completeObjective(objective);
                return;
            }
        }
    }

    private static void noteSacredChange(String message) {
        recentSacredChanges.addFirst(message);
        while (recentSacredChanges.size() > 8) {
            recentSacredChanges.removeLast();
        }
    }

    private static void stageSacredScene(String cooldownKey, String sceneType, String theme, int intensity, String message,
            long cooldownMs, long globalCooldownMs) {
        if (GPTGOD.SERVER == null || GPTGOD.SERVER.getOnlinePlayers().isEmpty()
                || !sacredSceneReady(cooldownKey, cooldownMs, globalCooldownMs)) {
            return;
        }
        try {
            GptActions.stageDivineScene(SACRED_STRUCTURE_NAME, sceneType, theme, intensity, message);
        } catch (RuntimeException e) {
            GPTGOD.LOGGER.error("Failed to stage Soul Spire ritual scene", e);
        }
    }

    private static boolean sacredSceneReady(String key, long cooldownMs, long globalCooldownMs) {
        long now = System.currentTimeMillis();
        Long globalPrevious = sacredSceneCooldowns.get("global");
        if (globalCooldownMs > 0 && globalPrevious != null && now - globalPrevious < globalCooldownMs) {
            return false;
        }
        Long previous = sacredSceneCooldowns.get(key);
        if (previous != null && now - previous < cooldownMs) {
            return false;
        }
        sacredSceneCooldowns.put(key, now);
        if (globalCooldownMs > 0) {
            sacredSceneCooldowns.put("global", now);
        }
        return true;
    }

    private static boolean isNearSacredStructure(Location location, Structure sacredStructure, int radius) {
        if (location == null || location.getWorld() == null || sacredStructure == null) {
            return false;
        }
        for (Block sacredBlock : sacredStructure.getBlocks()) {
            if (sacredBlock.getWorld() == null || !sacredBlock.getWorld().equals(location.getWorld())) {
                continue;
            }
            if (Math.abs(sacredBlock.getX() - location.getBlockX()) <= radius
                    && Math.abs(sacredBlock.getY() - location.getBlockY()) <= radius
                    && Math.abs(sacredBlock.getZ() - location.getBlockZ()) <= radius) {
                return true;
            }
        }
        return false;
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
        sacredSceneCooldowns.clear();
        recentSacredChanges.clear();
        achievedSpireMilestones.clear();
        ritualCharge = 0;
        ritualSurgeCount = 0;
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
