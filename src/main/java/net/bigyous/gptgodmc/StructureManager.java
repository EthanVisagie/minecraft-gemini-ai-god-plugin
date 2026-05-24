package net.bigyous.gptgodmc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
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
    private static final String SOUL_WISP_TAG = "gptgodmc_soul_wisp";
    private static final String SOUL_SIGIL_KEY = "soul_sigil";
    private static final double SOUL_WISP_PICKUP_RADIUS = 1.7D;
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
    private static final long RITUAL_PRAYER_COOLDOWN_MS = 9_000L;
    private static final long RITUAL_SIGIL_GUIDE_COOLDOWN_MS = 5_000L;
    private static final int RITUAL_OFFERING_RADIUS = 7;
    private static final int RITUAL_INTERACTION_RADIUS = 7;
    private static final int RITUAL_HINT_RADIUS = 16;
    private static final int RITUAL_BOSSBAR_RADIUS = 80;
    private static final int RITUAL_SANCTUARY_BASE_RADIUS = 8;
    private static final int RITUAL_SANCTUARY_PULSE_PERIOD_TICKS = 20;
    private static final int RITUAL_LAST_LIGHT_RADIUS = 36;
    private static final int RITUAL_SIGIL_RECALL_MIN_DISTANCE = 48;
    private static final int RITUAL_SIGIL_RECALL_RADIUS = 7;
    private static final int RITUAL_OFFERING_CONSUME_DELAY_TICKS = 28;
    private static final int RITUAL_CHARGE_THRESHOLD = 10;
    private static final long RITUAL_MOMENTUM_WINDOW_MS = 45_000L;
    private static final long RITUAL_CONVERGENCE_WINDOW_MS = 60_000L;
    private static final long RITUAL_LAST_LIGHT_COOLDOWN_MS = 90_000L;
    private static final int RITUAL_CONVERGENCE_THRESHOLD = 5;
    private static final long RITUAL_HINT_COOLDOWN_MS = 22_000L;
    private static final int RITUAL_WISP_LIFETIME_SECONDS = 90;
    private static ConcurrentHashMap<String, Structure> structures = new ConcurrentHashMap<String, Structure>();
    private static final ConcurrentHashMap<String, Long> sacredSceneCooldowns = new ConcurrentHashMap<>();
    private static final LinkedList<String> recentSacredChanges = new LinkedList<>();
    private static final Set<String> achievedSpireMilestones = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, RitualMomentum> ritualMomentum = new ConcurrentHashMap<>();
    private static final Set<UUID> ritualConvergenceContributors = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> lastLightCooldowns = new ConcurrentHashMap<>();
    private static BossBar ritualBossBar;
    private static BukkitTask activeSanctuaryTask;
    private static int ritualCharge = 0;
    private static int ritualSurgeCount = 0;
    private static int ritualConvergenceScore = 0;
    private static long ritualConvergenceLastActionMs = 0L;
    private static int activeSanctuaryIntensity = 0;
    private static long activeSanctuaryExpiresAtMs = 0L;
    private static String activeSanctuaryReason = "";

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

    private static final class RitualMomentum {
        private int streak;
        private int lastMilestone;
        private long lastActionMs;
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
    public void onPlayerNearDeath(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (player.getHealth() - event.getFinalDamage() > 0.5) {
            return;
        }
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null || !sacredStructure.isSacredAwakened()
                || !isNearSacredStructure(player.getLocation(), sacredStructure, RITUAL_LAST_LIGHT_RADIUS)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastLightCooldowns.get(player.getUniqueId());
        if (previous != null && now - previous < RITUAL_LAST_LIGHT_COOLDOWN_MS) {
            return;
        }
        lastLightCooldowns.put(player.getUniqueId(), now);
        triggerLastLightIntervention(player, sacredStructure, event);
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
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (tryUseSoulSigil(event)) {
            return;
        }
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
    public void onSoulWispInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!clicked.getScoreboardTags().contains(SOUL_WISP_TAG)) {
            return;
        }
        event.setCancelled(true);
        collectSoulWisp(event.getPlayer(), clicked);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRitualPrayer(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        handleRitualPrayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (ritualBossBar != null) {
            ritualBossBar.removePlayer(event.getPlayer());
        }
        ritualMomentum.remove(event.getPlayer().getUniqueId());
        lastLightCooldowns.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onRitualApproach(PlayerMoveEvent event) {
        if (event.getTo() == null || !changedBlock(event.getFrom(), event.getTo())) {
            return;
        }
        Structure sacredStructure = getSacredStructure();
        syncRitualBossBarViewer(event.getPlayer(), sacredStructure);
        collectNearbySoulWisps(event.getPlayer());
        showSoulSigilRecallGuide(event.getPlayer(), sacredStructure);
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
            displayRitualBossBar("Consecrated");
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
            displayRitualBossBar("Awakened");
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

    public static String getRitualMechanicsSummary() {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null) {
            return "NONE";
        }
        long now = System.currentTimeMillis();
        int convergenceScore;
        int convergenceContributors;
        synchronized (StructureManager.class) {
            boolean convergenceFresh = now - ritualConvergenceLastActionMs <= RITUAL_CONVERGENCE_WINDOW_MS;
            convergenceScore = convergenceFresh ? ritualConvergenceScore : 0;
            convergenceContributors = convergenceFresh ? ritualConvergenceContributors.size() : 0;
        }

        String sanctuary = activeSanctuaryTask == null
                ? "inactive"
                : String.format("active intensity=%d, %ds left, reason=%s",
                        activeSanctuaryIntensity,
                        Math.max(0L, (activeSanctuaryExpiresAtMs - now + 999L) / 1000L),
                        activeSanctuaryReason.isBlank() ? "unknown" : activeSanctuaryReason);
        String lastLight = getLastLightSummary(sacredStructure, now);
        String nextAction = ritualCharge >= RITUAL_CHARGE_THRESHOLD - 2
                ? "nearly charged; ask players for one offering, sigil, prayer, bell, fire, or build action"
                : convergenceScore >= RITUAL_CONVERGENCE_THRESHOLD - 2
                        ? "convergence close; invite nearby players to chain ritual actions"
                        : "build charge through offerings, Soul Sigils, wisps, prayers, bells, fire, or taller Spire work";
        return String.format(
                "sanctuary=%s; lastLight=%s; convergence=%d/%d from %d contributor%s; soulSigils=right-click for burst near Spire or recall from %d+ blocks away; wisps=collectible allays; next=%s",
                sanctuary,
                lastLight,
                convergenceScore,
                RITUAL_CONVERGENCE_THRESHOLD,
                convergenceContributors,
                convergenceContributors == 1 ? "" : "s",
                RITUAL_SIGIL_RECALL_MIN_DISTANCE,
                nextAction);
    }

    private static String getLastLightSummary(Structure sacredStructure, long now) {
        if (!sacredStructure.isSacredAwakened()) {
            return "locked until Soul Spire awakens";
        }
        int ready = 0;
        int cooling = 0;
        for (Player player : GPTGOD.SERVER.getOnlinePlayers()) {
            if (!isNearSacredStructure(player.getLocation(), sacredStructure, RITUAL_LAST_LIGHT_RADIUS)) {
                continue;
            }
            Long previous = lastLightCooldowns.get(player.getUniqueId());
            if (previous != null && now - previous < RITUAL_LAST_LIGHT_COOLDOWN_MS) {
                cooling++;
            } else {
                ready++;
            }
        }
        return String.format("rescues lethal damage within %d blocks; ready=%d, cooling=%d",
                RITUAL_LAST_LIGHT_RADIUS, ready, cooling);
    }

    public static String getRitualMomentumSummary() {
        long now = System.currentTimeMillis();
        List<String> leaders = ritualMomentum.entrySet().stream()
                .sorted((left, right) -> Integer.compare(getFreshMomentumStreak(right.getValue(), now),
                        getFreshMomentumStreak(left.getValue(), now)))
                .map(entry -> formatRitualMomentumEntry(entry.getKey(), entry.getValue(), now))
                .filter(text -> !text.isBlank())
                .limit(4)
                .toList();
        return leaders.isEmpty() ? "NONE" : String.join(" | ", leaders);
    }

    private static int getFreshMomentumStreak(RitualMomentum momentum, long now) {
        synchronized (momentum) {
            return now - momentum.lastActionMs > RITUAL_MOMENTUM_WINDOW_MS ? 0 : momentum.streak;
        }
    }

    private static String formatRitualMomentumEntry(UUID playerId, RitualMomentum momentum, long now) {
        synchronized (momentum) {
            if (momentum.streak <= 0 || now - momentum.lastActionMs > RITUAL_MOMENTUM_WINDOW_MS) {
                return "";
            }
            Player player = GPTGOD.SERVER.getPlayer(playerId);
            String name = player == null ? playerId.toString().substring(0, 8) : player.getName();
            long secondsLeft = Math.max(0L, (RITUAL_MOMENTUM_WINDOW_MS - (now - momentum.lastActionMs) + 999L) / 1000L);
            return String.format("%s x%d %ds left", name, momentum.streak, secondsLeft);
        }
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
        recordRitualMomentum(player, response.quality.name().toLowerCase(Locale.ROOT) + " offering",
                item.getLocation());
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
        recordRitualMomentum(player, interaction + " invocation", block.getLocation());
        EventLogger.addLoggable(new GenericEventLoggable(String.format("%s invoked %s by touching %s",
                player.getName(), SACRED_STRUCTURE_NAME, blockLabel)));
        GameLoop.triggerSoon("Soul Spire ritual interaction", 25);
    }

    private static void handleRitualPrayer(Player player) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null || !sacredStructure.isSacredAwakened()
                || !isNearSacredStructure(player.getLocation(), sacredStructure, RITUAL_INTERACTION_RADIUS)
                || !sacredSceneReady("ritual-prayer:" + player.getUniqueId(), RITUAL_PRAYER_COOLDOWN_MS, 0)) {
            return;
        }

        Location center = player.getLocation().clone().add(0, 0.4, 0);
        if (center.getWorld() != null) {
            center.getWorld().spawnParticle(Particle.SOUL, center, 34, 0.55, 0.45, 0.55, 0.018);
            center.getWorld().spawnParticle(Particle.END_ROD, center.clone().add(0, 0.65, 0), 14,
                    0.35, 0.45, 0.35, 0.008);
            center.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.65f, 1.2f);
            center.getWorld().playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.35f, 1.55f);
        }

        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 5, 0));
        player.sendActionBar(Component.text("Your prayer reaches the Soul Spire. +1 charge", NamedTextColor.AQUA));
        GptActions.whisperPlayer(player.getName(), "I hear your prayer at the Spire.");
        MemoryStore.rememberGodMessage(player, "I hear your prayer at the Spire.");
        noteSacredChange(String.format("%s heard a prayer from %s", SACRED_STRUCTURE_NAME, player.getName()));
        displayRitualBossBar("Prayer from " + player.getName());
        addRitualCharge(player, 1, "ritual prayer");
        recordRitualMomentum(player, "ritual prayer", player.getLocation());
        completeObjectiveMatching(player, "pray", "prayer", "kneel", "worship", "repentance");
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s prayed at %s", player.getName(), SACRED_STRUCTURE_NAME)));
        GameLoop.triggerSoon("Soul Spire prayer", 25);
    }

    private static boolean tryUseSoulSigil(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return false;
        }
        ItemStack stack = event.getItem();
        int intensity = getSoulSigilIntensity(stack);
        if (intensity <= 0) {
            return false;
        }
        event.setCancelled(true);
        if (!invokeSoulSigil(event.getPlayer(), intensity)) {
            return true;
        }
        consumeOneHeldItem(event.getPlayer());
        return true;
    }

    private static boolean invokeSoulSigil(Player player, int intensity) {
        int clamped = Math.max(1, Math.min(3, intensity));
        if (tryRecallToSoulSpire(player, clamped)) {
            return true;
        }
        Location location = player.getLocation();
        Location burst = location.clone().add(0, 1.0, 0);
        if (location.getWorld() != null) {
            location.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, burst, 70 + clamped * 45,
                    0.75, 0.75, 0.75, 0.04);
            location.getWorld().spawnParticle(Particle.END_ROD, burst, 28 + clamped * 18,
                    0.55, 0.75, 0.55, 0.02);
            location.getWorld().spawnParticle(Particle.FIREWORK, burst, 16 + clamped * 12,
                    0.45, 0.45, 0.45, 0.02);
            location.getWorld().playSound(burst, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 0.55f);
            location.getWorld().playSound(burst, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.35f);
            if (clamped >= 3) {
                location.getWorld().strikeLightningEffect(location);
            }
        }

        int duration = 20 * (18 + clamped * 8);
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, duration, Math.max(0, clamped - 1)));
        if (clamped >= 2) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * (5 + clamped * 2), 0));
        }
        player.giveExp(10 + clamped * 8);
        player.sendActionBar(Component.text("The Soul Sigil opens in your hand.", NamedTextColor.AQUA));

        Structure sacredStructure = getSacredStructure();
        boolean nearSpire = sacredStructure != null && isNearSacredStructure(location, sacredStructure,
                RITUAL_BOSSBAR_RADIUS);
        if (nearSpire) {
            stageSacredScene("soul-sigil:" + player.getUniqueId(), "spire", "soul", clamped,
                    "A Soul Sigil opens.", 3_000L, 0);
            addRitualCharge(player, clamped, "Soul Sigil invocation");
            activateRitualSanctuary(sacredStructure.getLocation(), Math.max(1, clamped - 1),
                    "Soul Sigil invocation");
        } else {
            GptActions.stageDivineScene(player.getName(), "blessing", "soul", clamped,
                    "A Soul Sigil opens.");
        }
        spawnSpireWisps(location, Math.max(1, Math.min(2, clamped)), "Soul Sigil invoked by " + player.getName());

        String message = nearSpire ? "Your Sigil feeds the Spire." : "The Sigil answers your hand.";
        GptActions.whisperPlayer(player.getName(), message);
        MemoryStore.rememberGodMessage(player, message);
        noteSacredChange(String.format("%s invoked a Soul Sigil%s", player.getName(),
                nearSpire ? " near the Spire" : ""));
        displayRitualBossBar("Sigil invoked by " + player.getName());
        recordRitualMomentum(player, "Soul Sigil invocation", location);
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s invoked a Soul Sigil at intensity %d%s",
                player.getName(), clamped, nearSpire ? " near the Soul Spire" : "")));
        GameLoop.triggerSoon("Soul Sigil invoked", 15);
        return true;
    }

    private static boolean tryRecallToSoulSpire(Player player, int intensity) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null || !sacredStructure.isSacredAwakened()
                || sacredStructure.getLocation().getWorld() == null
                || !player.getWorld().equals(sacredStructure.getLocation().getWorld())) {
            return false;
        }
        Location spireLocation = sacredStructure.getLocation();
        if (player.getLocation().distanceSquared(spireLocation) < RITUAL_SIGIL_RECALL_MIN_DISTANCE
                * RITUAL_SIGIL_RECALL_MIN_DISTANCE) {
            return false;
        }

        Location destination = findSafeRecallLocation(spireLocation);
        if (destination == null) {
            player.sendActionBar(Component.text("The Soul Sigil cannot find safe ground at the Spire.",
                    NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.45f, 0.45f);
            EventLogger.addLoggable(new GenericEventLoggable(String.format(
                    "%s tried to recall to %s, but no safe destination was found",
                    player.getName(), SACRED_STRUCTURE_NAME)));
            GameLoop.triggerSoon("failed Soul Spire recall", 30);
            return false;
        }

        Location origin = player.getLocation().clone();
        pulseRecallOrigin(origin, intensity);
        player.teleport(destination);
        pulseRecallArrival(destination, intensity);

        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 14, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 12, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 6, 0));
        player.sendTitle("Recalled", "The Soul Spire pulls you home.", 5, 42, 12);
        player.giveExp(8 + intensity * 4);

        stageSacredScene("soul-recall:" + player.getUniqueId(), "spire", "soul", Math.max(2, intensity),
                "A Soul Sigil recalls a servant.", 3_000L, 0);
        addRitualCharge(player, Math.max(1, intensity - 1), "Soul Sigil recall");
        recordRitualMomentum(player, "Soul Sigil recall", destination);
        activateRitualSanctuary(spireLocation, Math.max(1, intensity - 1), "Soul Sigil recall");
        displayRitualBossBar("Recall: " + player.getName());
        noteSacredChange(String.format("%s recalled %s from %d blocks away",
                SACRED_STRUCTURE_NAME, player.getName(), (int) origin.distance(destination)));
        GptActions.whisperPlayer(player.getName(), "The Sigil has returned you to the Spire.");
        MemoryStore.rememberGodMessage(player, "The Sigil has returned you to the Spire.");
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s used a Soul Sigil to recall to %s from %d blocks away",
                player.getName(), SACRED_STRUCTURE_NAME, (int) origin.distance(destination))));
        GameLoop.triggerSoon("Soul Sigil recall", 10);
        return true;
    }

    private static Location findSafeRecallLocation(Location center) {
        if (center == null || center.getWorld() == null) {
            return null;
        }
        for (int radius = 2; radius <= RITUAL_SIGIL_RECALL_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Location candidate = center.clone().add(dx, 0, dz);
                    Location safe = findSafeStandingLocation(candidate);
                    if (safe != null) {
                        faceLocation(safe, center);
                        return safe;
                    }
                }
            }
        }
        Location fallback = findSafeStandingLocation(center.clone());
        if (fallback != null) {
            faceLocation(fallback, center);
        }
        return fallback;
    }

    private static Location findSafeStandingLocation(Location near) {
        World world = near.getWorld();
        if (world == null) {
            return null;
        }
        int x = near.getBlockX();
        int z = near.getBlockZ();
        int startY = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 2, near.getBlockY() + 3));
        int minY = Math.max(world.getMinHeight() + 1, near.getBlockY() - 8);
        int maxY = Math.min(world.getMaxHeight() - 2, near.getBlockY() + 8);

        for (int y = startY; y >= minY; y--) {
            Location candidate = new Location(world, x + 0.5, y, z + 0.5);
            if (isSafeStandingLocation(candidate)) {
                return candidate;
            }
        }
        for (int y = startY + 1; y <= maxY; y++) {
            Location candidate = new Location(world, x + 0.5, y, z + 0.5);
            if (isSafeStandingLocation(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isSafeStandingLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return isBreathableRecallBlock(feet)
                && isBreathableRecallBlock(head)
                && !ground.isEmpty()
                && !ground.isLiquid()
                && ground.getType() != Material.BEDROCK
                && ground.getType() != Material.CACTUS
                && ground.getType() != Material.MAGMA_BLOCK
                && ground.getType() != Material.CAMPFIRE
                && ground.getType() != Material.SOUL_CAMPFIRE;
    }

    private static boolean isBreathableRecallBlock(Block block) {
        return block.isEmpty()
                || (block.isPassable() && !block.isLiquid())
                || block.getType() == Material.COBWEB
                || block.getType() == Material.LADDER
                || block.getType() == Material.VINE;
    }

    private static void faceLocation(Location location, Location target) {
        Vector direction = target.toVector().subtract(location.toVector());
        if (direction.lengthSquared() <= 0.001) {
            return;
        }
        location.setDirection(direction);
    }

    private static void pulseRecallOrigin(Location origin, int intensity) {
        if (origin == null || origin.getWorld() == null) {
            return;
        }
        Location burst = origin.clone().add(0, 1.0, 0);
        origin.getWorld().spawnParticle(Particle.REVERSE_PORTAL, burst, 90 + intensity * 25,
                0.7, 0.9, 0.7, 0.04);
        origin.getWorld().spawnParticle(Particle.SOUL, burst, 45 + intensity * 12,
                0.45, 0.7, 0.45, 0.02);
        origin.getWorld().playSound(burst, Sound.ENTITY_ENDERMAN_TELEPORT, 0.85f, 0.75f);
        origin.getWorld().playSound(burst, Sound.PARTICLE_SOUL_ESCAPE, 0.75f, 1.35f);
    }

    private static void pulseRecallArrival(Location destination, int intensity) {
        if (destination == null || destination.getWorld() == null) {
            return;
        }
        Location burst = destination.clone().add(0, 1.0, 0);
        destination.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, burst, 120 + intensity * 45,
                1.0, 1.0, 1.0, 0.045);
        destination.getWorld().spawnParticle(Particle.END_ROD, burst, 65 + intensity * 20,
                0.7, 0.9, 0.7, 0.025);
        destination.getWorld().spawnParticle(Particle.FIREWORK, burst, 25 + intensity * 12,
                0.5, 0.6, 0.5, 0.02);
        destination.getWorld().playSound(burst, Sound.BLOCK_END_PORTAL_SPAWN, 0.65f, 1.65f);
        destination.getWorld().playSound(burst, Sound.BLOCK_BEACON_POWER_SELECT, 0.95f, 0.75f);
        destination.getWorld().strikeLightningEffect(destination);
    }

    private static int getSoulSigilIntensity(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return 0;
        }
        Integer intensity = stack.getItemMeta().getPersistentDataContainer()
                .get(soulSigilKey(), PersistentDataType.INTEGER);
        return intensity == null ? 0 : Math.max(1, Math.min(3, intensity));
    }

    private static void showSoulSigilRecallGuide(Player player, Structure sacredStructure) {
        if (player == null || sacredStructure == null || !sacredStructure.isSacredAwakened()
                || sacredStructure.getLocation().getWorld() == null
                || !player.getWorld().equals(sacredStructure.getLocation().getWorld())
                || getSoulSigilIntensity(player.getInventory().getItemInMainHand()) <= 0
                || player.getLocation().distanceSquared(sacredStructure.getLocation()) < RITUAL_SIGIL_RECALL_MIN_DISTANCE
                        * RITUAL_SIGIL_RECALL_MIN_DISTANCE
                || !sacredSceneReady("soul-sigil-guide:" + player.getUniqueId(), RITUAL_SIGIL_GUIDE_COOLDOWN_MS, 0)) {
            return;
        }

        int distance = sacredStructure.getDistanceToI(player.getLocation());
        player.sendActionBar(Component.text(
                String.format("Soul Sigil: right-click to recall to the Soul Spire (%d blocks).", distance),
                NamedTextColor.AQUA));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.28f, 1.55f);
        player.getWorld().spawnParticle(Particle.SOUL, player.getLocation().clone().add(0, 1.0, 0),
                10, 0.28, 0.32, 0.28, 0.006);
    }

    private static void consumeOneHeldItem(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return;
        }
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            return;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held);
    }

    private static void recordRitualMomentum(Player player, String action, Location sourceLocation) {
        if (player == null || !player.isOnline()) {
            return;
        }
        RitualMomentum momentum = ritualMomentum.computeIfAbsent(player.getUniqueId(), id -> new RitualMomentum());
        int streak;
        boolean milestoneReached = false;
        synchronized (momentum) {
            long now = System.currentTimeMillis();
            if (now - momentum.lastActionMs > RITUAL_MOMENTUM_WINDOW_MS) {
                momentum.streak = 0;
                momentum.lastMilestone = 0;
            }
            momentum.streak++;
            momentum.lastActionMs = now;
            streak = momentum.streak;
            if (streak >= 3 && streak % 3 == 0 && streak > momentum.lastMilestone) {
                momentum.lastMilestone = streak;
                milestoneReached = true;
            }
        }

        Location pulseLocation = sourceLocation == null ? player.getLocation() : sourceLocation;
        pulseRitualMomentum(player, streak, action, pulseLocation);
        recordRitualConvergence(player, action, pulseLocation);
        if (milestoneReached) {
            triggerRitualMomentumMilestone(player, streak, pulseLocation);
        }
    }

    private static void pulseRitualMomentum(Player player, int streak, String action, Location location) {
        player.sendActionBar(Component.text("Ritual momentum x" + streak + ": " + action, NamedTextColor.AQUA));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f,
                Math.min(1.9f, 0.65f + streak * 0.08f));
        if (location == null || location.getWorld() == null) {
            return;
        }
        Location center = location.clone().add(0, 0.8, 0);
        location.getWorld().spawnParticle(Particle.SOUL, center, 10 + Math.min(streak, 8) * 2,
                0.35, 0.35, 0.35, 0.012);
        if (streak >= 3) {
            location.getWorld().spawnParticle(Particle.END_ROD, center, 6 + Math.min(streak, 8),
                    0.25, 0.35, 0.25, 0.006);
        }
    }

    private static void recordRitualConvergence(Player player, String action, Location sourceLocation) {
        Structure sacredStructure = getSacredStructure();
        if (player == null || sourceLocation == null || sacredStructure == null
                || !isNearSacredStructure(sourceLocation, sacredStructure, RITUAL_BOSSBAR_RADIUS)) {
            return;
        }

        boolean triggerConvergence = false;
        int score;
        int contributorCount;
        synchronized (StructureManager.class) {
            long now = System.currentTimeMillis();
            if (now - ritualConvergenceLastActionMs > RITUAL_CONVERGENCE_WINDOW_MS) {
                ritualConvergenceScore = 0;
                ritualConvergenceContributors.clear();
            }
            ritualConvergenceLastActionMs = now;
            ritualConvergenceScore++;
            ritualConvergenceContributors.add(player.getUniqueId());
            score = ritualConvergenceScore;
            contributorCount = ritualConvergenceContributors.size();
            if (ritualConvergenceScore >= RITUAL_CONVERGENCE_THRESHOLD) {
                triggerConvergence = true;
                ritualConvergenceScore = 0;
                ritualConvergenceContributors.clear();
            }
        }

        displayRitualBossBar(String.format("Convergence %d/%d", Math.min(score, RITUAL_CONVERGENCE_THRESHOLD),
                RITUAL_CONVERGENCE_THRESHOLD));
        pulseRitualConvergence(sourceLocation, score);
        if (triggerConvergence) {
            triggerRitualConvergence(player, action, sacredStructure.getLocation(), contributorCount);
        }
    }

    private static void pulseRitualConvergence(Location location, int score) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        int visibleScore = Math.max(1, Math.min(RITUAL_CONVERGENCE_THRESHOLD, score));
        Location center = location.clone().add(0, 1.0, 0);
        location.getWorld().spawnParticle(Particle.ENCHANT, center, 8 + visibleScore * 4,
                0.45, 0.45, 0.45, 0.015);
        if (visibleScore >= RITUAL_CONVERGENCE_THRESHOLD - 1) {
            location.getWorld().spawnParticle(Particle.END_ROD, center, 14, 0.35, 0.35, 0.35, 0.01);
            location.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 1.35f);
        }
    }

    private static void triggerRitualConvergence(Player catalyst, String action, Location center,
            int contributorCount) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        int intensity = Math.max(2, Math.min(3, 1 + contributorCount));
        String message = contributorCount > 1
                ? "The Soul Spire unites the ritual circle."
                : "The Soul Spire binds the ritual rhythm.";

        noteSacredChange(String.format("%s converged after %s from %s with %d contributor%s",
                SACRED_STRUCTURE_NAME, action, catalyst.getName(), contributorCount,
                contributorCount == 1 ? "" : "s"));
        stageSacredScene("ritual-convergence:" + System.currentTimeMillis(), "spire", "soul", intensity,
                message, 0, 0);
        pulseRitualConvergenceBurst(center, intensity);
        activateRitualSanctuary(center, intensity, "ritual convergence");
        spawnSpireWisps(center, intensity, "ritual convergence");
        dropSoulSigilReward(center, intensity, contributorCount > 1 ? 2 : 1, "Soul Convergence");
        addRitualCharge(catalyst, intensity + 1, "ritual convergence");
        displayRitualBossBar("Convergence from " + catalyst.getName());

        int radius = RITUAL_BOSSBAR_RADIUS;
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) > radius * radius) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 24, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 24, intensity - 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 18, 0));
            player.giveExp(14 + intensity * 8);
            player.sendTitle("Soul Convergence", "The ritual circle answers.", 5, 45, 12);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.85f, 0.85f);
        }

        GptActions.whisperPlayer(catalyst.getName(), contributorCount > 1
                ? "Together, your circle has my attention."
                : "Your rhythm has forced the Spire to answer.");
        MemoryStore.rememberGodMessage(catalyst, contributorCount > 1
                ? "Together, your circle has my attention."
                : "Your rhythm has forced the Spire to answer.");
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s triggered Soul Convergence after %s with %d contributor%s",
                catalyst.getName(), action, contributorCount, contributorCount == 1 ? "" : "s")));
        GameLoop.triggerSoon("Soul Spire convergence", 10);
    }

    private static void pulseRitualConvergenceBurst(Location center, int intensity) {
        if (center.getWorld() == null) {
            return;
        }
        Location above = center.clone().add(0, 1.6, 0);
        center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, above, 160 + intensity * 70,
                2.2, 1.4, 2.2, 0.045);
        center.getWorld().spawnParticle(Particle.END_ROD, above, 90 + intensity * 35,
                1.5, 1.9, 1.5, 0.025);
        center.getWorld().spawnParticle(Particle.FIREWORK, above, 55 + intensity * 25,
                1.2, 1.0, 1.2, 0.03);
        center.getWorld().playSound(above, Sound.BLOCK_END_PORTAL_SPAWN, 0.8f, 1.45f);
        center.getWorld().playSound(above, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.9f, 0.8f);
        center.getWorld().strikeLightningEffect(center);
    }

    private static void triggerRitualMomentumMilestone(Player player, int streak, Location sourceLocation) {
        int intensity = Math.max(1, Math.min(3, streak / 3));
        Location center = sourceLocation == null ? player.getLocation() : sourceLocation.clone();
        String message = "Ritual momentum x" + streak;

        player.sendMessage(Component.text(message + " gathers around you.", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.85f, 0.75f + intensity * 0.18f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * (8 + intensity * 4), 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * (8 + intensity * 4), 0));
        player.giveExp(6 + intensity * 6);

        if (center.getWorld() != null) {
            Location burst = center.clone().add(0, 1.1, 0);
            center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, burst, 70 + intensity * 35,
                    0.75, 0.75, 0.75, 0.035);
            center.getWorld().spawnParticle(Particle.FIREWORK, burst, 16 + intensity * 10,
                    0.45, 0.45, 0.45, 0.02);
            center.getWorld().playSound(burst, Sound.BLOCK_BEACON_POWER_SELECT, 0.75f, 0.7f);
        }

        Structure sacredStructure = getSacredStructure();
        boolean nearSpire = sacredStructure != null && isNearSacredStructure(center, sacredStructure,
                RITUAL_BOSSBAR_RADIUS);
        if (nearSpire) {
            stageSacredScene("ritual-momentum:" + player.getUniqueId() + ":" + streak, "spire", "soul", intensity,
                    message + " gathers.", 2_500L, 0);
            addRitualCharge(player, intensity, message);
            displayRitualBossBar("Momentum x" + streak + " from " + player.getName());
            if (intensity >= 2) {
                activateRitualSanctuary(sacredStructure.getLocation(), intensity - 1, message);
            }
        } else {
            GptActions.stageDivineScene(player.getName(), "blessing", "soul", intensity,
                    message + " gathers.");
        }

        if (intensity >= 2) {
            dropSoulSigilReward(center, intensity, 1, "Ritual Momentum");
            spawnSpireWisps(center, Math.min(2, intensity), message);
        }
        GptActions.whisperPlayer(player.getName(), "Your ritual rhythm has been witnessed.");
        MemoryStore.rememberGodMessage(player, "Your ritual rhythm has been witnessed.");
        noteSacredChange(String.format("%s reached %s through %s", player.getName(), message,
                SACRED_STRUCTURE_NAME));
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s reached %s after repeated ritual actions", player.getName(), message)));
        GameLoop.triggerSoon("ritual momentum milestone", 15);
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
        if (intensity >= 3) {
            activateRitualSanctuary(sacredStructure.getLocation(), intensity, milestoneName + " milestone");
        }

        if (builder != null && builder.isOnline()) {
            builder.sendActionBar(Component.text(message + " +" + charge + " charge", NamedTextColor.AQUA));
            GptActions.whisperPlayer(builder.getName(), message);
            MemoryStore.rememberGodMessage(builder, message);
            addRitualCharge(builder, charge, "Spire build milestone: " + milestoneName);
            recordRitualMomentum(builder, "Spire build milestone: " + milestoneName,
                    sacredStructure.getLocation());
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
            entity.addScoreboardTag(SOUL_WISP_TAG);
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

    private static void collectSoulWisp(Player player, Entity wisp) {
        if (player == null || wisp == null || wisp.isDead()
                || !wisp.getScoreboardTags().contains(SOUL_WISP_TAG)) {
            return;
        }

        Location location = wisp.getLocation();
        wisp.removeScoreboardTag(SOUL_WISP_TAG);
        wisp.remove();

        if (location.getWorld() != null) {
            Location burst = location.clone().add(0, 0.45, 0);
            location.getWorld().spawnParticle(Particle.SOUL, burst, 46, 0.45, 0.45, 0.45, 0.02);
            location.getWorld().spawnParticle(Particle.END_ROD, burst, 26, 0.35, 0.35, 0.35, 0.02);
            location.getWorld().spawnParticle(Particle.FIREWORK, burst, 16, 0.25, 0.25, 0.25, 0.02);
            location.getWorld().playSound(burst, Sound.PARTICLE_SOUL_ESCAPE, 0.8f, 1.45f);
            location.getWorld().playSound(burst, Sound.ENTITY_PLAYER_LEVELUP, 0.55f, 1.65f);
        }

        int duration = 20 * (8 + Math.min(ritualSurgeCount, 8));
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, 0));
        if (ritualSurgeCount >= 2) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 5, 0));
        }
        int experience = 6 + Math.min(ritualSurgeCount * 2, 12);
        player.giveExp(experience);
        player.sendActionBar(Component.text("A Soul Wisp blesses you. +" + experience + " XP",
                NamedTextColor.AQUA));

        noteSacredChange(String.format("%s claimed a Soul Wisp from %s", player.getName(), SACRED_STRUCTURE_NAME));
        displayRitualBossBar("Wisp claimed by " + player.getName());
        recordRitualMomentum(player, "Soul Wisp claimed", location);
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s collected a temporary Soul Wisp and received a small blessing",
                player.getName())));
        GameLoop.triggerSoon("Soul Wisp collected", 20);
    }

    private static void collectNearbySoulWisps(Player player) {
        if (player == null || player.getWorld() == null) {
            return;
        }
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), SOUL_WISP_PICKUP_RADIUS,
                SOUL_WISP_PICKUP_RADIUS, SOUL_WISP_PICKUP_RADIUS)) {
            if (entity.getScoreboardTags().contains(SOUL_WISP_TAG)) {
                collectSoulWisp(player, entity);
                return;
            }
        }
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
        displayRitualBossBar("+" + amount + " charge from " + player.getName());
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

    private static void displayRitualBossBar(String pulseMessage) {
        Structure sacredStructure = getSacredStructure();
        if (sacredStructure == null) {
            hideRitualBossBar();
            return;
        }
        BossBar bar = getRitualBossBar();
        bar.setTitle(buildRitualBossBarTitle(sacredStructure, pulseMessage));
        bar.setProgress(Math.max(0.02d, Math.min(1.0d, ritualCharge / (double) RITUAL_CHARGE_THRESHOLD)));
        bar.setColor(getRitualBossBarColor(sacredStructure));

        for (Player player : List.copyOf(bar.getPlayers())) {
            if (!isRitualBossBarViewer(player, sacredStructure)) {
                bar.removePlayer(player);
            }
        }
        for (Player player : GPTGOD.SERVER.getOnlinePlayers()) {
            if (isRitualBossBarViewer(player, sacredStructure) && !bar.getPlayers().contains(player)) {
                bar.addPlayer(player);
            }
        }
    }

    private static void syncRitualBossBarViewer(Player player, Structure sacredStructure) {
        if (player == null) {
            return;
        }
        if (sacredStructure == null) {
            if (ritualBossBar != null) {
                ritualBossBar.removePlayer(player);
            }
            return;
        }
        if (isRitualBossBarViewer(player, sacredStructure)) {
            displayRitualBossBar(null);
        } else if (ritualBossBar != null) {
            ritualBossBar.removePlayer(player);
        }
    }

    private static boolean isRitualBossBarViewer(Player player, Structure sacredStructure) {
        return player != null
                && player.isOnline()
                && sacredStructure != null
                && sacredStructure.getLocation().getWorld() != null
                && player.getWorld().equals(sacredStructure.getLocation().getWorld())
                && player.getLocation().distanceSquared(sacredStructure.getLocation()) <= RITUAL_BOSSBAR_RADIUS
                        * RITUAL_BOSSBAR_RADIUS;
    }

    private static BossBar getRitualBossBar() {
        if (ritualBossBar == null) {
            ritualBossBar = GPTGOD.SERVER.createBossBar("Soul Spire", BarColor.BLUE, BarStyle.SEGMENTED_10);
            ritualBossBar.setVisible(true);
        }
        return ritualBossBar;
    }

    private static String buildRitualBossBarTitle(Structure sacredStructure, String pulseMessage) {
        String status;
        if (!sacredStructure.isSacredAwakened()) {
            List<String> missing = getMissingRitualComponentLabels(sacredStructure);
            status = missing.isEmpty()
                    ? "add ritual components nearby"
                    : "needs " + compactList(missing, 2);
        } else if (ritualCharge >= RITUAL_CHARGE_THRESHOLD - 2) {
            status = "near surge";
        } else if (ritualCharge <= 0) {
            status = "offer, ring, touch fire, or build higher";
        } else {
            status = "charging";
        }

        String title = String.format("Soul Spire %d/%d | %s",
                Math.min(ritualCharge, RITUAL_CHARGE_THRESHOLD), RITUAL_CHARGE_THRESHOLD, status);
        if (pulseMessage != null && !pulseMessage.isBlank()) {
            title = title + " | " + pulseMessage;
        }
        return title.length() <= 96 ? title : title.substring(0, 93) + "...";
    }

    private static BarColor getRitualBossBarColor(Structure sacredStructure) {
        if (!sacredStructure.isSacredAwakened()) {
            return BarColor.WHITE;
        }
        if (ritualCharge >= RITUAL_CHARGE_THRESHOLD - 2) {
            return BarColor.PURPLE;
        }
        if (ritualCharge >= RITUAL_CHARGE_THRESHOLD / 2) {
            return BarColor.GREEN;
        }
        return BarColor.BLUE;
    }

    private static void hideRitualBossBar() {
        if (ritualBossBar == null) {
            return;
        }
        ritualBossBar.removeAll();
        ritualBossBar = null;
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
        displayRitualBossBar("Surge #" + ritualSurgeCount);
        stageSacredScene("ritual-surge:" + ritualSurgeCount, "spire", "soul", intensity,
                intensity >= 3 ? "The Soul Spire erupts with power." : "The Soul Spire surges.",
                RITUAL_MAJOR_SCENE_COOLDOWN_MS, 0);
        pulseRitualSurge(center, intensity);
        blessNearbyRitualPlayers(center, intensity);
        dropRitualSurgeReward(center, intensity);
        spawnSpireWisps(center, intensity, "ritual surge");
        activateRitualSanctuary(center, intensity, "ritual surge #" + ritualSurgeCount);
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

    private static void activateRitualSanctuary(Location center, int intensity, String reason) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        cancelRitualSanctuary();
        int clamped = Math.max(1, Math.min(3, intensity));
        Location anchor = center.clone();
        int[] pulsesRemaining = { 10 + clamped * 4 };
        activeSanctuaryIntensity = clamped;
        activeSanctuaryReason = reason;
        activeSanctuaryExpiresAtMs = System.currentTimeMillis()
                + pulsesRemaining[0] * RITUAL_SANCTUARY_PULSE_PERIOD_TICKS * 50L;

        noteSacredChange(String.format("%s opened a temporary sanctuary after %s", SACRED_STRUCTURE_NAME, reason));
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s opened a temporary sanctuary aura at intensity %d after %s",
                SACRED_STRUCTURE_NAME, clamped, reason)));

        activeSanctuaryTask = GPTGOD.SERVER.getScheduler().runTaskTimer(JavaPlugin.getPlugin(GPTGOD.class), () -> {
            if (anchor.getWorld() == null || pulsesRemaining[0] <= 0) {
                finishRitualSanctuary(anchor, reason);
                return;
            }
            pulseRitualSanctuary(anchor, clamped, pulsesRemaining[0]);
            pulsesRemaining[0]--;
        }, 0L, RITUAL_SANCTUARY_PULSE_PERIOD_TICKS);
    }

    private static void triggerLastLightIntervention(Player player, Structure sacredStructure,
            EntityDamageEvent event) {
        event.setCancelled(true);

        double maxHealth = player.getMaxHealth();
        player.setHealth(Math.min(maxHealth, Math.max(player.getHealth(), Math.min(maxHealth, 6.0))));
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 12, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 18, 2));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 8, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 8, 1));

        Location location = player.getLocation();
        if (location.getWorld() != null) {
            Location burst = location.clone().add(0, 1.0, 0);
            location.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, burst, 80, 0.7, 0.9, 0.7, 0.04);
            location.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, burst, 90, 0.8, 0.9, 0.8, 0.04);
            location.getWorld().spawnParticle(Particle.END_ROD, burst, 45, 0.55, 0.7, 0.55, 0.02);
            location.getWorld().playSound(burst, Sound.ITEM_TOTEM_USE, 1.0f, 0.75f);
            location.getWorld().playSound(burst, Sound.BLOCK_BEACON_ACTIVATE, 0.9f, 1.35f);
            for (Entity entity : location.getWorld().getNearbyEntities(location, 9, 5, 9)) {
                if (entity instanceof Monster monster && !monster.isDead()) {
                    repelHostileFromSanctuary(location, monster, 3);
                }
            }
        }

        activateRitualSanctuary(sacredStructure.getLocation(), 2, "Last Light saved " + player.getName());
        spawnSpireWisps(player.getLocation(), 2, "Last Light saved " + player.getName());
        stageSacredScene("last-light:" + player.getUniqueId(), "blessing", "soul", 3,
                "The Soul Spire refuses this death.", 0, 0);
        displayRitualBossBar("Last Light saved " + player.getName());
        noteSacredChange(String.format("%s saved %s from death by Last Light after %s",
                SACRED_STRUCTURE_NAME, player.getName(), event.getCause().name().toLowerCase(Locale.ROOT)));

        GptActions.whisperPlayer(player.getName(), "Not yet. The Spire keeps you.");
        MemoryStore.rememberGodMessage(player, "Not yet. The Spire keeps you.");
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s invoked Last Light to save %s from lethal %s damage",
                SACRED_STRUCTURE_NAME, player.getName(), event.getCause().name().toLowerCase(Locale.ROOT))));
        GameLoop.triggerSoon("Soul Spire Last Light rescue", 10);
    }

    private static void pulseRitualSanctuary(Location center, int intensity, int pulsesRemaining) {
        if (center.getWorld() == null) {
            return;
        }
        int radius = RITUAL_SANCTUARY_BASE_RADIUS + intensity * 3;
        Location above = center.clone().add(0, 0.35, 0);
        for (int i = 0; i < 28; i++) {
            double angle = (Math.PI * 2 * i) / 28.0;
            Location ring = above.clone().add(Math.cos(angle) * radius, 0.05, Math.sin(angle) * radius);
            center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, ring, 2, 0.05, 0.05, 0.05, 0.004);
            if (i % 3 == 0) {
                center.getWorld().spawnParticle(Particle.END_ROD, ring.clone().add(0, 0.35, 0), 1,
                        0.04, 0.04, 0.04, 0.002);
            }
        }
        center.getWorld().spawnParticle(Particle.SOUL, center.clone().add(0, 1.2, 0),
                36 + intensity * 18, 1.6, 0.9, 1.6, 0.025);
        if (pulsesRemaining % 4 == 0) {
            center.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_AMBIENT, 0.65f, 0.75f);
        }

        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) > radius * radius) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 45, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 45, 0));
            if (intensity >= 2) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 45, 0));
            }
            if (pulsesRemaining % 5 == 0) {
                player.sendActionBar(Component.text("The Soul Spire sanctuary protects this ground.",
                        NamedTextColor.AQUA));
            }
        }

        int repelled = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, Math.max(5, radius / 2.0), radius)) {
            if (!(entity instanceof Monster monster) || monster.isDead()) {
                continue;
            }
            repelHostileFromSanctuary(center, monster, intensity);
            repelled++;
        }
        if (repelled > 0 && pulsesRemaining % 3 == 0) {
            center.getWorld().playSound(center, Sound.BLOCK_CONDUIT_ATTACK_TARGET, 0.6f, 1.25f);
        }
    }

    private static void repelHostileFromSanctuary(Location center, LivingEntity hostile, int intensity) {
        Location hostileLocation = hostile.getLocation();
        Vector away = hostileLocation.toVector().subtract(center.toVector());
        if (away.lengthSquared() < 0.01) {
            away = new Vector(1, 0, 0);
        }
        away.setY(0).normalize().multiply(0.45 + intensity * 0.16);
        away.setY(0.18 + intensity * 0.05);
        hostile.setVelocity(away);
        hostile.damage(0.8 + intensity * 0.55);
        hostileLocation.getWorld().spawnParticle(Particle.SOUL, hostileLocation.clone().add(0, 1.0, 0),
                8 + intensity * 4, 0.25, 0.45, 0.25, 0.015);
    }

    private static void finishRitualSanctuary(Location center, String reason) {
        cancelRitualSanctuary();
        if (center != null && center.getWorld() != null) {
            center.getWorld().spawnParticle(Particle.SOUL, center.clone().add(0, 1.0, 0),
                    48, 1.4, 0.7, 1.4, 0.02);
            center.getWorld().playSound(center, Sound.PARTICLE_SOUL_ESCAPE, 0.55f, 0.9f);
        }
        EventLogger.addLoggable(new GenericEventLoggable(String.format(
                "%s sanctuary faded after %s", SACRED_STRUCTURE_NAME, reason)));
    }

    private static void cancelRitualSanctuary() {
        if (activeSanctuaryTask == null) {
            return;
        }
        activeSanctuaryTask.cancel();
        activeSanctuaryTask = null;
        activeSanctuaryIntensity = 0;
        activeSanctuaryExpiresAtMs = 0L;
        activeSanctuaryReason = "";
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
        int clamped = Math.max(1, Math.min(3, intensity));
        int count = clamped >= 3 ? 2 : 1;
        dropSoulSigilReward(center, clamped, count, count > 1 ? "Soul Sigils" : "Soul Sigil");
    }

    private static void dropSoulSigilReward(Location center, int intensity, int count, String dropName) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        ItemStack stack = createSoulSigil(intensity, count);
        Item drop = center.getWorld().dropItem(center.clone().add(0, 2.4, 0), stack);
        drop.setGlowing(true);
        drop.setCustomNameVisible(true);
        drop.customName(Component.text(dropName, NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true));
        drop.setVelocity(new Vector(0, 0.36, 0));
    }

    private static ItemStack createSoulSigil(int intensity, int count) {
        int clamped = Math.max(1, Math.min(3, intensity));
        Material material = clamped >= 3 ? Material.ECHO_SHARD : Material.AMETHYST_SHARD;
        ItemStack stack = new ItemStack(material, Math.max(1, count));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Soul Sigil", NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true));
            meta.lore(List.of(
                    Component.text("Right-click to invoke a soul burst.", NamedTextColor.GRAY),
                    Component.text("Far away, it recalls you to the Soul Spire.", NamedTextColor.DARK_AQUA)));
            meta.getPersistentDataContainer().set(soulSigilKey(), PersistentDataType.INTEGER, clamped);
            stack.setItemMeta(meta);
        }
        return stack;
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

    private static NamespacedKey soulSigilKey() {
        return new NamespacedKey(JavaPlugin.getPlugin(GPTGOD.class), SOUL_SIGIL_KEY);
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
        hideRitualBossBar();
        cancelRitualSanctuary();
        structures = new ConcurrentHashMap<String, Structure>();
        sacredSceneCooldowns.clear();
        recentSacredChanges.clear();
        achievedSpireMilestones.clear();
        ritualMomentum.clear();
        ritualConvergenceContributors.clear();
        lastLightCooldowns.clear();
        ritualCharge = 0;
        ritualSurgeCount = 0;
        ritualConvergenceScore = 0;
        ritualConvergenceLastActionMs = 0L;
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
                if (structure.getSize() < 1) {
                    structures.remove(key);
                    if (SACRED_STRUCTURE_NAME.equals(key)) {
                        hideRitualBossBar();
                        cancelRitualSanctuary();
                    }
                }
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
