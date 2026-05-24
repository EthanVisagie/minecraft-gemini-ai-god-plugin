package net.bigyous.gptgodmc.GPT;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import net.bigyous.gptgodmc.EventLogger;
import net.bigyous.gptgodmc.GPTGOD;
import net.bigyous.gptgodmc.Structure;
import net.bigyous.gptgodmc.StructureManager;
import net.bigyous.gptgodmc.WorldManager;
import net.bigyous.gptgodmc.awareness.ActionOutcomeTracker;
import net.bigyous.gptgodmc.GPT.Json.FunctionDeclaration;
import net.bigyous.gptgodmc.GPT.Json.Schema;
import net.bigyous.gptgodmc.GPT.Json.Tool;
import net.bigyous.gptgodmc.interfaces.SimpFunction;
import net.bigyous.gptgodmc.loggables.GPTActionLoggable;
import net.bigyous.gptgodmc.memory.MemoryStore;
import net.bigyous.gptgodmc.memory.PlayerMemory;
import net.bigyous.gptgodmc.utils.BukkitUtils;
import net.bigyous.gptgodmc.utils.GptObjectiveTracker;
import net.bigyous.gptgodmc.utils.ImageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

public class GptActions {
        private int tokens = -1;
        private static Gson gson = new Gson();
        private static JavaPlugin plugin = JavaPlugin.getPlugin(GPTGOD.class);
        private static Boolean useTts = plugin.getConfig().getBoolean("tts");
        private static final Map<String, GptObjectiveTracker> objectiveTrackers = new ConcurrentHashMap<>();
        private static final Map<String, String> objectiveEntries = new ConcurrentHashMap<>();
        private static final Map<String, String> objectiveTeams = new ConcurrentHashMap<>();
        private static final Map<UUID, TrialState> activeTrials = new ConcurrentHashMap<>();
        private static final Map<UUID, UUID> trialEntityIds = new ConcurrentHashMap<>();
        private static final ChatColor[] HIDDEN_COLORS = Arrays.stream(ChatColor.values())
                        .filter(color -> color != ChatColor.RESET)
                        .toArray(ChatColor[]::new);

        private static final class TrialState {
                private final UUID id;
                private final UUID playerId;
                private final String playerName;
                private final String theme;
                private final int intensity;
                private final Set<UUID> remainingEntityIds = ConcurrentHashMap.newKeySet();
                private int totalEntities;

                private TrialState(Player player, String theme, int intensity) {
                        this.id = UUID.randomUUID();
                        this.playerId = player.getUniqueId();
                        this.playerName = player.getName();
                        this.theme = normalizeTheme(theme);
                        this.intensity = clampIntensity(intensity);
                }
        }

        private static void recordActionSuccess(String action, String detail) {
                ActionOutcomeTracker.success(action, detail);
        }

        private static void recordActionFailure(String action, String detail) {
                ActionOutcomeTracker.failure(action, detail);
        }

        private static Location resolveTargetLocation(String targetName) {
                if (targetName == null || targetName.isBlank()) {
                        return null;
                }
                Player player = GPTGOD.SERVER.getPlayer(targetName);
                if (player != null) {
                        return player.getLocation();
                }
                Structure structure = StructureManager.getStructure(targetName);
                return structure == null ? null : structure.getLocation();
        }

        private static int clampIntensity(int intensity) {
                return Math.max(1, Math.min(3, intensity));
        }

        private static String normalizeTheme(String theme) {
                if (theme == null || theme.isBlank()) {
                        return "divine";
                }
                String normalized = theme.toLowerCase(Locale.ROOT).trim();
                if (containsAny(normalized, "wrath", "curse", "punish", "storm", "judg")) {
                        return "wrath";
                }
                if (containsAny(normalized, "soul", "spirit", "spire", "ritual")) {
                        return "soul";
                }
                if (containsAny(normalized, "fire", "flame", "ash")) {
                        return "fire";
                }
                if (containsAny(normalized, "reward", "bless", "holy", "grace", "gift")) {
                        return "blessing";
                }
                if (containsAny(normalized, "void", "shadow", "dark")) {
                        return "void";
                }
                return "divine";
        }

        private static Particle primaryParticle(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> Particle.ELECTRIC_SPARK;
                case "soul" -> Particle.SOUL_FIRE_FLAME;
                case "fire" -> Particle.FLAME;
                case "void" -> Particle.PORTAL;
                case "blessing" -> Particle.FIREWORK;
                default -> Particle.END_ROD;
                };
        }

        private static Particle secondaryParticle(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> Particle.WITCH;
                case "soul" -> Particle.SOUL;
                case "fire" -> Particle.CAMPFIRE_COSY_SMOKE;
                case "void" -> Particle.REVERSE_PORTAL;
                case "blessing" -> Particle.ENCHANT;
                default -> Particle.WAX_OFF;
                };
        }

        private static Sound primarySound(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> Sound.ENTITY_LIGHTNING_BOLT_THUNDER;
                case "soul" -> Sound.PARTICLE_SOUL_ESCAPE;
                case "fire" -> Sound.ITEM_FIRECHARGE_USE;
                case "void" -> Sound.ENTITY_ENDERMAN_TELEPORT;
                case "blessing" -> Sound.ENTITY_PLAYER_LEVELUP;
                default -> Sound.BLOCK_BEACON_ACTIVATE;
                };
        }

        private static Material ritualMaterial(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> Material.REDSTONE_BLOCK;
                case "soul" -> Material.CRYING_OBSIDIAN;
                case "fire" -> Material.MAGMA_BLOCK;
                case "void" -> Material.OBSIDIAN;
                case "blessing" -> Material.GLOWSTONE;
                default -> Material.AMETHYST_BLOCK;
                };
        }

        private static Material pillarMaterial(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> Material.RED_STAINED_GLASS;
                case "soul" -> Material.LIGHT_BLUE_STAINED_GLASS;
                case "fire" -> Material.ORANGE_STAINED_GLASS;
                case "void" -> Material.PURPLE_STAINED_GLASS;
                case "blessing" -> Material.YELLOW_STAINED_GLASS;
                default -> Material.WHITE_STAINED_GLASS;
                };
        }

        private static Material pillarCoreMaterial(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> Material.REDSTONE_LAMP;
                case "soul" -> Material.SEA_LANTERN;
                case "fire" -> Material.SHROOMLIGHT;
                case "void" -> Material.CRYING_OBSIDIAN;
                case "blessing" -> Material.GLOWSTONE;
                default -> Material.AMETHYST_BLOCK;
                };
        }

        private static Color primaryColor(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> Color.RED;
                case "soul" -> Color.AQUA;
                case "fire" -> Color.ORANGE;
                case "void" -> Color.PURPLE;
                case "blessing" -> Color.YELLOW;
                default -> Color.WHITE;
                };
        }

        private static Color secondaryColor(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> Color.BLACK;
                case "soul" -> Color.TEAL;
                case "fire" -> Color.RED;
                case "void" -> Color.NAVY;
                case "blessing" -> Color.LIME;
                default -> Color.SILVER;
                };
        }

        private static ChatColor titleColor(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> ChatColor.RED;
                case "soul" -> ChatColor.AQUA;
                case "fire" -> ChatColor.GOLD;
                case "void" -> ChatColor.DARK_PURPLE;
                case "blessing" -> ChatColor.YELLOW;
                default -> ChatColor.LIGHT_PURPLE;
                };
        }

        private static EntityType trialEntity(String theme) {
                return switch (normalizeTheme(theme)) {
                case "wrath" -> EntityType.HUSK;
                case "soul" -> EntityType.STRAY;
                case "fire" -> EntityType.MAGMA_CUBE;
                case "void" -> EntityType.ENDERMAN;
                default -> EntityType.ZOMBIE;
                };
        }

        private static Schema themeSchema(String description) {
                Schema schema = new Schema(Schema.Type.STRING, description);
                schema.setEnumValues(Arrays.asList("divine", "blessing", "soul", "fire", "wrath", "void"));
                return schema;
        }

        private static Schema intensitySchema() {
                return new Schema(Schema.Type.INTEGER, "spectacle intensity from 1 to 3; use 1 for subtle, 2 for major moments, 3 for rare climaxes");
        }

        private static Schema atmosphereSchema() {
                Schema schema = new Schema(Schema.Type.STRING,
                                "island atmosphere mood: dawn, storm, night, soul, clear, or eclipse");
                schema.setEnumValues(Arrays.asList("dawn", "storm", "night", "soul", "clear", "eclipse"));
                return schema;
        }

        private static Schema sceneSchema() {
                Schema schema = new Schema(Schema.Type.STRING,
                                "curated divine scene: arrival, objective, reward, judgment, trial, spire, or celebration");
                schema.setEnumValues(Arrays.asList("arrival", "objective", "reward", "judgment", "trial", "spire",
                                "celebration"));
                return schema;
        }

        private static void scheduleDivinePulses(Location center, String theme, int intensity) {
                Location base = center.clone();
                int clamped = clampIntensity(intensity);
                int pulses = 3 + clamped;
                for (int step = 0; step < pulses; step++) {
                        int pulse = step;
                        Bukkit.getScheduler().runTaskLater(plugin, () -> spawnDivinePulse(base.clone(), theme, clamped, pulse),
                                        pulse * 8L);
                }
        }

        private static void spawnDivinePulse(Location center, String theme, int intensity, int pulse) {
                World world = center.getWorld();
                if (world == null) {
                        return;
                }

                Location above = center.clone().add(0, 1.2 + (pulse * 0.08), 0);
                double radius = 1.8 + intensity + (pulse * 0.35);
                Particle primary = primaryParticle(theme);
                Particle secondary = secondaryParticle(theme);
                world.playSound(above, primarySound(theme), 0.75f + intensity * 0.25f, 0.7f + pulse * 0.08f);
                world.spawnParticle(primary, above, 45 + intensity * 35, radius / 3, 0.8 + intensity * 0.25,
                                radius / 3, 0.03);
                world.spawnParticle(secondary, above, 20 + intensity * 20, radius / 4, 0.5, radius / 4, 0.01);

                for (int i = 0; i < 48; i++) {
                        double angle = (Math.PI * 2 * i) / 48.0;
                        Location ring = above.clone().add(Math.cos(angle) * radius, Math.sin(pulse * 0.6) * 0.25,
                                        Math.sin(angle) * radius);
                        world.spawnParticle(primary, ring, 1, 0.02, 0.02, 0.02, 0.0);
                }

                if (pulse == 0 && (intensity >= 3 || normalizeTheme(theme).equals("wrath"))) {
                        world.strikeLightningEffect(center);
                }
        }

        private static void spawnFireworkBurst(Location center, String theme, int intensity) {
                World world = center.getWorld();
                if (world == null) {
                        return;
                }
                int count = clampIntensity(intensity) + 1;
                for (int i = 0; i < count; i++) {
                        Location launch = center.clone().add((Math.random() - 0.5) * 4, 1.5 + i * 0.4,
                                        (Math.random() - 0.5) * 4);
                        Firework firework = world.spawn(launch, Firework.class);
                        FireworkMeta meta = firework.getFireworkMeta();
                        meta.addEffect(FireworkEffect.builder()
                                        .with(FireworkEffect.Type.BALL_LARGE)
                                        .withColor(primaryColor(theme))
                                        .withFade(secondaryColor(theme))
                                        .trail(true)
                                        .flicker(true)
                                        .build());
                        meta.setPower(1);
                        firework.setFireworkMeta(meta);
                }
        }

        private static void sendDivineTitle(Player player, String title, String subtitle, String theme) {
                String safeTitle = compactTitle(title, 32);
                String safeSubtitle = compactTitle(subtitle, 64);
                ChatColor color = titleColor(theme);
                player.sendTitle(color + safeTitle, ChatColor.GRAY + safeSubtitle, 10, 55, 20);
        }

        private static String compactTitle(String text, int maxLength) {
                if (text == null || text.isBlank()) {
                        return "";
                }
                String normalized = text.replaceAll("\\s+", " ").trim();
                return normalized.length() > maxLength ? normalized.substring(0, maxLength - 3) + "..." : normalized;
        }

        private static List<Player> playersNear(Location center, double radius) {
                World world = center.getWorld();
                if (world == null) {
                        return List.of();
                }
                double maxDistance = radius * radius;
                return world.getPlayers().stream()
                                .filter(player -> player.getLocation().distanceSquared(center) <= maxDistance)
                                .toList();
        }

        private static String sceneTitle(String sceneType) {
                String scene = normalizeScene(sceneType);
                return switch (scene) {
                case "arrival" -> "The Heavens Open";
                case "objective" -> "A Task Is Given";
                case "reward" -> "Favor Descends";
                case "judgment" -> "Judgment Falls";
                case "trial" -> "Trial Summoned";
                case "spire" -> "The Spire Answers";
                case "celebration" -> "The Island Rejoices";
                default -> "Divine Sign";
                };
        }

        private static String sceneSubtitle(String sceneType, String message) {
                if (message != null && !message.isBlank()) {
                        return message;
                }
                String scene = normalizeScene(sceneType);
                return switch (scene) {
                case "arrival" -> "A presence gathers above the island.";
                case "objective" -> "The heavens name your work.";
                case "reward" -> "Grace takes visible form.";
                case "judgment" -> "The air remembers your offense.";
                case "trial" -> "Stand and prove thy worth.";
                case "spire" -> "Soul-fire stirs in the stones.";
                case "celebration" -> "Your deed echoes beyond the shore.";
                default -> "The world bends for a moment.";
                };
        }

        private static String normalizeScene(String sceneType) {
                if (sceneType == null || sceneType.isBlank()) {
                        return "celebration";
                }
                String normalized = sceneType.toLowerCase(Locale.ROOT).trim();
                if (containsAny(normalized, "arriv", "appear", "presence")) {
                        return "arrival";
                }
                if (containsAny(normalized, "objective", "quest", "task", "mission")) {
                        return "objective";
                }
                if (containsAny(normalized, "reward", "gift", "favor", "bless")) {
                        return "reward";
                }
                if (containsAny(normalized, "judge", "judgment", "punish", "wrath", "curse")) {
                        return "judgment";
                }
                if (containsAny(normalized, "trial", "challenge", "test")) {
                        return "trial";
                }
                if (containsAny(normalized, "spire", "soul", "ritual")) {
                        return "spire";
                }
                return "celebration";
        }

        private static void applyAtmosphere(World world, String mood) {
                if (world == null) {
                        return;
                }
                String normalized = mood == null ? "clear" : mood.toLowerCase(Locale.ROOT).trim();
                if (containsAny(normalized, "storm", "wrath", "thunder", "judg")) {
                        world.setStorm(true);
                        world.setThundering(true);
                        world.setTime(16000);
                } else if (containsAny(normalized, "dawn", "bless", "holy", "reward", "clear")) {
                        world.setStorm(false);
                        world.setThundering(false);
                        world.setTime(23000);
                } else if (containsAny(normalized, "night", "void", "eclipse", "dark")) {
                        world.setStorm(false);
                        world.setThundering(false);
                        world.setTime(18000);
                } else if (containsAny(normalized, "soul", "ritual", "spire", "dusk")) {
                        world.setStorm(false);
                        world.setThundering(false);
                        world.setTime(12500);
                } else {
                        world.setStorm(false);
                        world.setThundering(false);
                }
        }

        private static String sceneMood(String sceneType, String theme) {
                String scene = normalizeScene(sceneType);
                if (scene.equals("judgment")) {
                        return "storm";
                }
                if (scene.equals("spire") || normalizeTheme(theme).equals("soul")) {
                        return "soul";
                }
                if (scene.equals("trial") || normalizeTheme(theme).equals("void")) {
                        return "night";
                }
                if (scene.equals("reward") || scene.equals("celebration") || normalizeTheme(theme).equals("blessing")) {
                        return "dawn";
                }
                return "clear";
        }

        private static void summonTemporaryLightPillar(Location center, String theme, int intensity, int durationSeconds) {
                World world = center.getWorld();
                if (world == null) {
                        return;
                }
                Block base = findSurfaceBlock(center, 0, 0);
                if (base == null) {
                        base = center.getBlock();
                }
                List<BlockState> originalStates = new ArrayList<>();
                int height = 5 + clampIntensity(intensity) * 2;
                for (int y = 0; y < height; y++) {
                        Block block = world.getBlockAt(base.getX(), base.getY() + y, base.getZ());
                        if (!block.isPassable() || block.isLiquid()) {
                                continue;
                        }
                        originalStates.add(block.getState());
                        block.setType(y % 3 == 0 ? pillarCoreMaterial(theme) : pillarMaterial(theme), false);
                }
                int[][] feet = new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                for (int[] foot : feet) {
                        Block block = findSurfaceBlock(base.getLocation(), foot[0], foot[1]);
                        if (block != null && block.isPassable() && !block.isLiquid()) {
                                originalStates.add(block.getState());
                                block.setType(pillarMaterial(theme), false);
                        }
                }
                restoreBlocksLater(originalStates, durationSeconds);
        }

        private static int spawnTrialEntities(Player player, String theme, int intensity) {
                EntityType type = trialEntity(theme);
                int count = Math.min(4, clampIntensity(intensity) + 1);
                TrialState trial = new TrialState(player, theme, intensity);
                int spawned = 0;
                for (int i = 0; i < count; i++) {
                        double angle = (Math.PI * 2 * i) / count;
                        Location candidate = player.getLocation().clone().add(Math.cos(angle) * 7, 0,
                                        Math.sin(angle) * 7);
                        Location safe = BukkitUtils.getSafeLocation(candidate, true, 24);
                        Location spawn = safe == null ? candidate : safe;
                        Entity entity = player.getWorld().spawnEntity(spawn, type, true);
                        entity.setGlowing(true);
                        entity.customName(Component.text("Trial of " + theme, titleColor(theme) == ChatColor.RED
                                        ? NamedTextColor.RED
                                        : NamedTextColor.LIGHT_PURPLE));
                        entity.setCustomNameVisible(true);
                        trial.remainingEntityIds.add(entity.getUniqueId());
                        trialEntityIds.put(entity.getUniqueId(), trial.id);
                        spawned++;
                }
                if (spawned > 0) {
                        trial.totalEntities = spawned;
                        activeTrials.put(trial.id, trial);
                        scheduleTrialExpiry(trial.id, 90 + trial.intensity * 30);
                }
                return spawned;
        }

        private static void scheduleTrialExpiry(UUID trialId, int seconds) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        TrialState trial = activeTrials.remove(trialId);
                        if (trial == null) {
                                return;
                        }
                        int remaining = trial.remainingEntityIds.size();
                        clearTrialEntities(trial, true);
                        Player player = GPTGOD.SERVER.getPlayer(trial.playerId);
                        if (player != null && player.isOnline()) {
                                sendDivineTitle(player, "Trial Faded", "The heavens withdraw their test.", "void");
                        }
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("divine trial for %s expired with %d/%d enemies remaining",
                                                        trial.playerName, remaining, trial.totalEntities)));
                }, Math.max(30, seconds) * 20L);
        }

        private static void clearTrialEntities(TrialState trial, boolean removeLiveEntities) {
                for (UUID entityId : Set.copyOf(trial.remainingEntityIds)) {
                        trialEntityIds.remove(entityId);
                        if (removeLiveEntities) {
                                Entity entity = Bukkit.getEntity(entityId);
                                if (entity != null && !entity.isDead()) {
                                        entity.remove();
                                }
                        }
                }
                trial.remainingEntityIds.clear();
        }

        private static Material trialRewardMaterial(TrialState trial) {
                if (trial.intensity >= 3) {
                        return normalizeTheme(trial.theme).equals("wrath") ? Material.ENCHANTED_GOLDEN_APPLE
                                        : Material.DIAMOND;
                }
                if (trial.intensity == 2) {
                        return normalizeTheme(trial.theme).equals("soul") ? Material.ECHO_SHARD : Material.GOLDEN_APPLE;
                }
                return Material.EMERALD;
        }

        private static int trialRewardCount(TrialState trial) {
                return trial.intensity >= 3 ? 2 : trial.intensity == 2 ? 1 : 3;
        }

        private static void completeTrial(TrialState trial, Player rewardPlayer, Player killer) {
                clearTrialEntities(trial, false);
                Player recipient = rewardPlayer != null && rewardPlayer.isOnline() ? rewardPlayer : killer;
                if (recipient == null || !recipient.isOnline()) {
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        "divine trial completed, but no online recipient could receive the reward"));
                        return;
                }

                String recipientName = recipient.getName();
                stageDivineScene(recipientName, "reward", "blessing", Math.max(2, trial.intensity),
                                "Trial conquered.");
                recipient.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * (8 + trial.intensity * 4),
                                Math.max(0, trial.intensity - 1)));
                recipient.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                                20 * (12 + trial.intensity * 4), 0));

                JsonObject reward = new JsonObject();
                Material rewardMaterial = trialRewardMaterial(trial);
                reward.addProperty("playerName", recipientName);
                reward.addProperty("itemId", rewardMaterial.name().toLowerCase(Locale.ROOT));
                reward.addProperty("count", trialRewardCount(trial));
                reward.addProperty("displayName", "Victor's " + rewardMaterial.name().toLowerCase(Locale.ROOT)
                                .replace('_', ' '));
                dropDivineReward.run(reward);
                EventLogger.addLoggable(new GPTActionLoggable(String.format(
                                "%s conquered a %s divine trial by defeating %d enemies",
                                recipientName, trial.theme, trial.totalEntities)));
                recordActionSuccess("trialCompleted", "rewarded " + recipientName + " for conquering a divine trial");
        }

        private static Block findSurfaceBlock(Location origin, int dx, int dz) {
                World world = origin.getWorld();
                if (world == null) {
                        return null;
                }
                int x = origin.getBlockX() + dx;
                int z = origin.getBlockZ() + dz;
                int high = Math.min(world.getMaxHeight() - 2, origin.getBlockY() + 5);
                int low = Math.max(world.getMinHeight() + 1, origin.getBlockY() - 6);
                for (int y = high; y >= low; y--) {
                        Block ground = world.getBlockAt(x, y - 1, z);
                        Block target = world.getBlockAt(x, y, z);
                        if (!ground.isPassable() && target.isPassable() && !target.isLiquid()) {
                                return target;
                        }
                }
                return null;
        }

        private static void restoreBlocksLater(List<BlockState> states, int durationSeconds) {
                if (states.isEmpty()) {
                        return;
                }
                int ticks = Math.max(5, Math.min(60, durationSeconds)) * 20;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        for (BlockState state : states) {
                                state.update(true, false);
                        }
                }, ticks);
        }

        private static void pulseObjectiveTargets(List<PlayerMemory> targets, String theme, int intensity, String title,
                        String subtitle) {
                for (PlayerMemory memory : targets) {
                        Player player = GPTGOD.SERVER.getPlayerExact(memory.playerName);
                        if (player != null) {
                                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 0.7f, 1.1f);
                                sendDivineTitle(player, title, subtitle, theme);
                                scheduleDivinePulses(player.getLocation(), theme, intensity);
                        }
                }
        }

        private static void pulseSacredStructure(String theme, int intensity) {
                Structure sacredStructure = StructureManager.getSacredStructure();
                if (sacredStructure != null) {
                        scheduleDivinePulses(sacredStructure.getLocation(), theme, intensity);
                        spawnFireworkBurst(sacredStructure.getLocation(), theme, intensity);
                }
        }

        private static void staticWhisper(String playerName, String message) {
                Player player = GPTGOD.SERVER.getPlayerExact(playerName);
                if (player == null) {
                        recordActionFailure("whisper", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to whisper \"%s\" to %s (player not online)", message,
                                                        playerName)));
                        return;
                }
                player.sendRichMessage("<i>You hear something whisper to you...</i>");
                player.sendMessage(message);
                MemoryStore.rememberGodMessage(playerName, message);
                maybeSettleBlessingDebt(playerName, message);
                if (useTts) {
                        Speechify.makeSpeech(message, player);
                }
                EventLogger.addLoggable(
                                new GPTActionLoggable(String.format("whispered \"%s\" to %s", message, playerName)));
                recordActionSuccess("whisper", "sent to " + playerName);
        }

        private static void staticAnnounce(String message) {
                GPTGOD.SERVER.broadcast(Component.text("A Loud voice bellows from the heavens", NamedTextColor.YELLOW)
                                .decoration(TextDecoration.BOLD, true));
                GPTGOD.SERVER.broadcast(Component.text(message, NamedTextColor.LIGHT_PURPLE)
                                .decoration(TextDecoration.BOLD, true));
                MemoryStore.rememberAnnouncedGodMessage(message);
                if (useTts) {
                        Speechify.makeSpeech(message, null);
                }
                EventLogger.addLoggable(new GPTActionLoggable(String.format("announced \"%s\"", message)));
                recordActionSuccess("announce", "broadcast message");
        }

        private static String getObjectiveKey(String objective) {
                return objective.length() > 45 ? objective.substring(0, 44) : objective;
        }

        private static String sanitizeObjective(String objective) {
                if (objective == null) {
                        return "";
                }
                String trimmed = objective.trim();
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (containsAny(lower, "recycle bin", "defragment", "sector", "cache", "license", "desktop", "folder",
                                "computer", "disk", "file", "email", "keyboard", "mouse")) {
                        if (containsAny(lower, "stone", "spire")) {
                                return rewriteObjectivePrefix(trimmed, "Rebuild the spire with stone.");
                        }
                        if (containsAny(lower, "empty", "clear")) {
                                return rewriteObjectivePrefix(trimmed, "Throw unwanted items into lava.");
                        }
                        return rewriteObjectivePrefix(trimmed, "Gather stone and raise a small shrine.");
                }
                return trimmed;
        }

        private static String rewriteObjectivePrefix(String original, String replacementBody) {
                int colon = original.indexOf(':');
                if (colon > 0) {
                        return original.substring(0, colon + 1) + " " + replacementBody;
                }
                return replacementBody;
        }

        private static String getObjectiveEntry(String objective) {
                return objectiveEntries.computeIfAbsent(objective, key -> {
                        int index = objectiveEntries.size();
                        ChatColor first = HIDDEN_COLORS[index % HIDDEN_COLORS.length];
                        ChatColor second = HIDDEN_COLORS[(index / HIDDEN_COLORS.length) % HIDDEN_COLORS.length];
                        return first.toString() + second.toString() + ChatColor.RESET;
                });
        }

        private static String getObjectiveTeamName(String objective) {
                return objectiveTeams.computeIfAbsent(objective,
                                key -> "obj" + Integer.toHexString(Math.abs(key.hashCode())));
        }

        private static void applyObjectiveDisplay(String objective) {
                String entry = getObjectiveEntry(objective);
                String teamName = getObjectiveTeamName(objective);
                Team team = GPTGOD.SCOREBOARD.getTeam(teamName);
                if (team == null) {
                        team = GPTGOD.SCOREBOARD.registerNewTeam(teamName);
                }
                if (!team.hasEntry(entry)) {
                        team.addEntry(entry);
                }
                String prefix = objective.substring(0, Math.min(64, objective.length()));
                String suffix = objective.length() > 64 ? objective.substring(64, Math.min(128, objective.length()))
                                : "";
                team.prefix(Component.text(prefix));
                team.suffix(suffix.isBlank() ? Component.empty() : Component.text(suffix));
        }

        private static void clearObjectiveDisplay(String objective) {
                String entry = objectiveEntries.remove(objective);
                String teamName = objectiveTeams.remove(objective);
                if (entry != null) {
                        GPTGOD.GPT_OBJECTIVES.getScore(entry).resetScore();
                }
                if (teamName != null) {
                        Team team = GPTGOD.SCOREBOARD.getTeam(teamName);
                        if (team != null) {
                                team.unregister();
                        }
                }
        }

        private static void refreshObjectiveDisplay() {
                long objectiveEntries = GPTGOD.SCOREBOARD.getEntries().stream()
                                .filter(entry -> GPTGOD.SERVER.getPlayer(entry) == null)
                                .count();
                if (objectiveEntries < 1) {
                        GPTGOD.GPT_OBJECTIVES.setDisplaySlot(null);
                } else if (GPTGOD.GPT_OBJECTIVES.getDisplaySlot() == null) {
                        GPTGOD.GPT_OBJECTIVES.setDisplaySlot(DisplaySlot.SIDEBAR);
                }
        }

        private static void retireObjective(String objective, String replacementObjective) {
                clearObjectiveDisplay(objective);
                GptObjectiveTracker tracker = objectiveTrackers.remove(objective);
                if (tracker != null) {
                        tracker.cancel();
                }
                MemoryStore.recordObjectiveRetired(objective, replacementObjective);
                refreshObjectiveDisplay();
                if (replacementObjective != null && !replacementObjective.isBlank()) {
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("retired objective %s for next ritual phase %s", objective,
                                                        replacementObjective)));
                } else {
                        EventLogger.addLoggable(
                                        new GPTActionLoggable(String.format("retired objective %s", objective)));
                }
        }
        // in hindsight, I should have used an interface or abstract class to do this
        // but oh well...

        private static SimpFunction<JsonObject> whisper = (JsonObject args) -> {
                TypeToken<Map<String, String>> mapType = new TypeToken<Map<String, String>>() {
                };
                Map<String, String> argsMap = gson.fromJson(args, mapType);
                String message = argsMap.get("message");
                String playerName = argsMap.get("playerName");
                if (playerName == null) {
                        playerName = argsMap.get("player_name");
                }

                staticWhisper(playerName, message);
                return;
        };

        private static SimpFunction<JsonObject> announce = (JsonObject args) -> {
                String message = gson.fromJson(args.get("message"), String.class);
                staticAnnounce(message);
        };
        private static SimpFunction<JsonObject> giveItem = (JsonObject argObject) -> {
                String playerName = gson.fromJson(argObject.get("playerName"), String.class);
                String itemId = gson.fromJson(argObject.get("itemId"), String.class);
                int count = gson.fromJson(argObject.get("count"), Integer.class);
                // executeCommand(String.format("/give %s %s %d", playerName, itemId, count));
                Material material = Material.matchMaterial(itemId);
                if (material == null) {
                        recordActionFailure("giveItem", "invalid material: " + itemId);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to give %s to %s (invalid material)", itemId,
                                                        playerName)));
                        return;
                }
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("giveItem", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to give %s to %s (player not online)", itemId,
                                                        playerName)));
                        return;
                }
                player.getInventory().addItem(new ItemStack(material, count));
                player.sendRichMessage(String.format("<i>A %s appeared in your inventory</i>", itemId));
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                scheduleDivinePulses(player.getLocation(), "blessing", 1);
                MemoryStore.recordRewardGranted(playerName, "giveItem");
                EventLogger.addLoggable(
                                new GPTActionLoggable(String.format("gave %d %s to %s", count, itemId, playerName)));
                recordActionSuccess("giveItem", String.format("gave %d %s to %s", count, itemId, playerName));
        };
        private static SimpFunction<JsonObject> command = (JsonObject args) -> {
                String prompt = gson.fromJson(args.get("prompt"), String.class);
                GenerateCommands.generate(prompt);
                MemoryStore.recordCommandOutcome(prompt);
                EventLogger.addLoggable(new GPTActionLoggable(String.format("commanded \"%s\" to happen", prompt)));
                recordActionSuccess("command", "queued command generation: " + prompt);
        };
        private static SimpFunction<JsonObject> smite = (JsonObject argObject) -> {
                String playerName = gson.fromJson(argObject.get("playerName"), String.class);
                int power = gson.fromJson(argObject.get("power"), Integer.class);
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("smite", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to smite %s (player not online)", playerName)));
                        return;
                }
                for (int i = 0; i < power; i++) {
                        WorldManager.getCurrentWorld().strikeLightning(player.getLocation());
                }
                MemoryStore.recordPunishmentDelivered(playerName, "smite");
                EventLogger.addLoggable(new GPTActionLoggable(String.format("smited %s", playerName)));
                recordActionSuccess("smite", String.format("struck %s with power %d", playerName, power));
        };
        private static SimpFunction<JsonObject> spawnEntity = (JsonObject argObject) -> {
                String position = gson.fromJson(argObject.get("position"), String.class);
                String entityName = gson.fromJson(argObject.get("entity"), String.class);
                int count = gson.fromJson(argObject.get("count"), Integer.class);
                String customName = gson.fromJson(argObject.get("customName"), String.class);
                Player targetPlayer = GPTGOD.SERVER.getPlayer(position);
                if (!StructureManager.hasStructure(position) && targetPlayer == null) {
                        recordActionFailure("spawnEntity", "unknown player or structure: " + position);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to summon %s near %s (unknown target)", entityName,
                                                        position)));
                        return;
                }
                Location location = StructureManager.hasStructure(position)
                                ? StructureManager.getStructure(position).getLocation()
                                : targetPlayer.getLocation();
                // try to get a safe location for the spawn
                Location safeLocation = BukkitUtils.getSafeLocation(location, true, 256);
                // if the safeLocation is not null, then use it instead of location
                location = safeLocation == null ? location : safeLocation;

                EntityType type = EntityType.fromName(entityName);
                if (type == null) {
                        recordActionFailure("spawnEntity", "invalid entity type: " + entityName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to summon invalid entity %s near %s", entityName,
                                                        position)));
                        return;
                }
                for (int i = 0; i < count; i++) {
                        double r = Math.random() / Math.nextDown(1.0);
                        double offset = 0 * (1.0 - 1) + 3 * r;
                        Entity ent = WorldManager.getCurrentWorld().spawnEntity(location
                                        .offset(offset - i, 0, offset + i).toLocation(WorldManager.getCurrentWorld()),
                                        type, true);
                        TextComponent nameComponent = customName != null
                                        ? PlainTextComponentSerializer.plainText().deserialize(String.format("%s%s",
                                                        customName, i > 0 ? " " + String.valueOf(i) : ""))
                                        : null;
                        ent.customName(nameComponent);
                }
                if (GPTGOD.SERVER.getPlayerExact(position) != null && isHostileEntity(type)) {
                        MemoryStore.recordPunishmentDelivered(position, "hostile spawn");
                }
                EventLogger.addLoggable(new GPTActionLoggable(String.format("summoned %d %s%s near %s", count,
                                entityName, customName != null ? String.format(" named: %s,", customName) : "",
                                position)));
                recordActionSuccess("spawnEntity", String.format("summoned %d %s near %s", count, entityName, position));
        };
        private static SimpFunction<JsonObject> summonSupplyChest = (JsonObject argObject) -> {
                TypeToken<List<String>> stringArrayType = new TypeToken<List<String>>() {
                };
                String playerName = gson.fromJson(argObject.get("playerName"), String.class);
                List<String> itemNames = gson.fromJson(argObject.get("items"), stringArrayType);
                if (itemNames == null || itemNames.isEmpty()) {
                        recordActionFailure("summonSupplyChest", "no items requested");
                        EventLogger.addLoggable(new GPTActionLoggable("failed to summon supply chest (no items requested)"));
                        return;
                }
                boolean fullStacks = gson.fromJson(argObject.get("fullStacks"), Boolean.class) != null
                                ? gson.fromJson(argObject.get("fullStacks"), Boolean.class)
                                : false;
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("summonSupplyChest", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to summon supply chest for %s (player not online)",
                                                        playerName)));
                        return;
                }
                int invalidItems = 0;
                List<ItemStack> items = itemNames.stream().map((String itemName) -> {
                        Material mat = Material.matchMaterial(itemName);
                        if (mat == null) {
                                return new ItemStack(Material.COBWEB);
                        }
                        return new ItemStack(mat, fullStacks ? mat.getMaxStackSize() : 1);
                }).toList();
                for (String itemName : itemNames) {
                        if (Material.matchMaterial(itemName) == null) {
                                invalidItems++;
                        }
                }
                Location playerLoc = player.getLocation();
                Block currentBlock = WorldManager.getCurrentWorld()
                                .getBlockAt(playerLoc
                                                .offset(playerLoc.getDirection().getBlockX() + 1, 0,
                                                                playerLoc.getDirection().getBlockZ() + 1)
                                                .toLocation(null));
                currentBlock.setType(Material.CHEST);
                Chest chest = (Chest) currentBlock.getState();
                chest.getBlockInventory().addItem(items.toArray(new ItemStack[itemNames.size()]));
                chest.open();
                WorldManager.getCurrentWorld().spawnParticle(Particle.WAX_OFF, chest.getLocation().toCenterLocation(),
                                100, 2, 3, 2);
                WorldManager.getCurrentWorld().playSound(chest.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 0.65f);
                scheduleDivinePulses(chest.getLocation(), "blessing", 1);
                MemoryStore.recordRewardGranted(playerName, "summonSupplyChest");
                EventLogger.addLoggable(
                                new GPTActionLoggable(String.format("summoned a chest with: %s inside next to %s",
                                                String.join(", ", itemNames), playerName)));
                recordActionSuccess("summonSupplyChest",
                                String.format("chest placed near %s with %d requested items%s", playerName,
                                                itemNames.size(),
                                                invalidItems > 0 ? ", " + invalidItems + " invalid item substitutions"
                                                                : ""));

        };
        private static SimpFunction<JsonObject> transformStructure = (JsonObject argObject) -> {
                String structure = gson.fromJson(argObject.get("structure"), String.class);
                String blockType = gson.fromJson(argObject.get("block"), String.class);
                Structure structureObj = StructureManager.getStructure(structure);
                Material replacement = Material.matchMaterial(blockType);
                // blocktypes that we don't want god to accidentally transform
                List<Material> protectedBlockTypes = Arrays.asList(Material.CHEST, Material.ENDER_CHEST,
                                Material.TRAPPED_CHEST, Material.FURNACE, Material.CRAFTING_TABLE,
                                Material.BLAST_FURNACE, Material.ENDER_CHEST, Material.ARMOR_STAND, Material.CAULDRON,
                                Material.BREWING_STAND, Material.CARTOGRAPHY_TABLE, Material.FLETCHING_TABLE,
                                Material.SMOKER, Material.BARREL, Material.GRINDSTONE, Material.COMPOSTER,
                                Material.SMITHING_TABLE, Material.STONECUTTER, Material.BELL, Material.LANTERN,
                                Material.SOUL_LANTERN, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SHROOMLIGHT,
                                Material.PLAYER_HEAD, Material.PIGLIN_HEAD, Material.DRAGON_HEAD, Material.CREEPER_HEAD,
                                Material.ZOMBIE_HEAD, Material.WITHER_SKELETON_SKULL, Material.SKELETON_SKULL,
                                // all doors as of 1.21.1
                                Material.IRON_DOOR, Material.BIRCH_DOOR, Material.DARK_OAK_DOOR, Material.ACACIA_DOOR,
                                Material.OAK_DOOR, Material.BAMBOO_DOOR, Material.CHERRY_DOOR, Material.COPPER_DOOR,
                                Material.JUNGLE_DOOR, Material.SPRUCE_DOOR, Material.WARPED_DOOR, Material.CRIMSON_DOOR,
                                Material.MANGROVE_DOOR,
                                // all buttons as of 1.21.1
                                Material.OAK_BUTTON, Material.BIRCH_BUTTON, Material.STONE_BUTTON,
                                Material.ACACIA_BUTTON, Material.BAMBOO_BUTTON, Material.BAMBOO_BUTTON,
                                Material.CHERRY_BUTTON, Material.JUNGLE_BUTTON, Material.SPRUCE_BUTTON,
                                Material.WARPED_BUTTON, Material.CRIMSON_BUTTON, Material.DARK_OAK_BUTTON,
                                Material.MANGROVE_BUTTON, Material.POLISHED_BLACKSTONE_BUTTON,
                                // all trapdoors as of 1.21.1
                                Material.IRON_TRAPDOOR, Material.BIRCH_TRAPDOOR, Material.DARK_OAK_TRAPDOOR,
                                Material.ACACIA_TRAPDOOR, Material.OAK_TRAPDOOR, Material.BAMBOO_TRAPDOOR,
                                Material.CHERRY_TRAPDOOR, Material.COPPER_TRAPDOOR, Material.JUNGLE_TRAPDOOR,
                                Material.SPRUCE_TRAPDOOR, Material.WARPED_TRAPDOOR, Material.CRIMSON_TRAPDOOR,
                                Material.MANGROVE_TRAPDOOR,
                                // all pressure plates as of 1.21.1
                                Material.OAK_PRESSURE_PLATE, Material.BIRCH_PRESSURE_PLATE,
                                Material.STONE_PRESSURE_PLATE, Material.ACACIA_PRESSURE_PLATE,
                                Material.BAMBOO_PRESSURE_PLATE, Material.CHERRY_PRESSURE_PLATE,
                                Material.JUNGLE_PRESSURE_PLATE, Material.SPRUCE_PRESSURE_PLATE,
                                Material.WARPED_PRESSURE_PLATE, Material.CRIMSON_PRESSURE_PLATE,
                                Material.DARK_OAK_PRESSURE_PLATE, Material.MANGROVE_PRESSURE_PLATE,
                                Material.HEAVY_WEIGHTED_PRESSURE_PLATE, Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
                                Material.POLISHED_BLACKSTONE_PRESSURE_PLATE, Material.LEVER);
                if (structureObj == null) {
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        "tried to transform non existant structure \"" + structure + "\""));
                        recordActionFailure("transformStructure", "unknown structure: " + structure);
                        return;
                }
                if (replacement == null) {
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to transform %s to invalid block %s", structure,
                                                        blockType)));
                        recordActionFailure("transformStructure", "invalid block: " + blockType);
                        return;
                }
                structureObj.getBlocks().forEach((Block b) -> {
                        if (!protectedBlockTypes.contains(b.getType())) {
                                b.setType(replacement);
                        }
                });
                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("turned all the blocks in Structure %s to %s", structure, blockType)));
                recordActionSuccess("transformStructure",
                                String.format("transformed unprotected blocks in %s to %s", structure, blockType));

        };
        private static SimpFunction<JsonObject> revive = (JsonObject args) -> {
                TypeToken<Map<String, String>> mapType = new TypeToken<Map<String, String>>() {
                };
                Map<String, String> argsMap = gson.fromJson(args, mapType);
                String playerName = argsMap.get("playerName");
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("revive", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to revive %s (player not online)", playerName)));
                        return;
                }
                if (player.getGameMode().equals(GameMode.SURVIVAL)) {
                        recordActionFailure("revive", playerName + " is already alive in survival");
                        return;
                }
                Location spawn = player.getRespawnLocation() != null ? player.getRespawnLocation()
                                : WorldManager.getCurrentWorld().getSpawnLocation();
                if (!BukkitUtils.safeTeleport(player, spawn)) {
                        // fallback to regular if it fails for now
                        player.teleport(spawn);
                }
                player.setGameMode(GameMode.SURVIVAL);
                MemoryStore.recordRewardGranted(playerName, "revive");
                EventLogger.addLoggable(new GPTActionLoggable(String.format("revived %s", playerName)));
                recordActionSuccess("revive", "returned " + playerName + " to survival");
        };
        private static SimpFunction<JsonObject> teleport = (JsonObject args) -> {
                TypeToken<Map<String, String>> mapType = new TypeToken<Map<String, String>>() {
                };
                Map<String, String> argsMap = gson.fromJson(args, mapType);
                String playerName = argsMap.get("playerName");
                String destName = argsMap.get("destination");
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("teleport", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to teleport %s (player not online)", playerName)));
                        return;
                }
                Player destinationPlayer = GPTGOD.SERVER.getPlayer(destName);
                if (!StructureManager.hasStructure(destName) && destinationPlayer == null) {
                        recordActionFailure("teleport", "unknown destination: " + destName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to teleport %s to %s (unknown destination)", playerName,
                                                        destName)));
                        return;
                }
                Location destination = StructureManager.hasStructure(destName)
                                ? StructureManager.getStructure(destName).getLocation()
                                : destinationPlayer.getLocation();

                BukkitUtils.safeTeleport(player, destination);
                EventLogger.addLoggable(
                                new GPTActionLoggable(String.format("teleported %s to %s", playerName, destName)));
                recordActionSuccess("teleport", String.format("moved %s to %s", playerName, destName));
        };
        private static SimpFunction<JsonObject> setObjective = (JsonObject args) -> {
                String objective = sanitizeObjective(gson.fromJson(args.get("objective"), String.class));
                List<PlayerMemory> targets = MemoryStore.peekObjectiveTargets(objective);
                Set<String> objectivesToRetire = new LinkedHashSet<>();
                for (PlayerMemory memory : targets) {
                        for (String activeObjective : memory.activeObjectives) {
                                if (!activeObjective.equals(objective)) {
                                        objectivesToRetire.add(activeObjective);
                                }
                        }
                }

                for (String activeObjective : objectivesToRetire) {
                        retireObjective(activeObjective, objective);
                }

                applyObjectiveDisplay(objective);
                Score score = GPTGOD.GPT_OBJECTIVES.getScore(getObjectiveEntry(objective));
                score.setScore(plugin.getConfig().getInt("objectiveDecay"));
                // decrement the score by one every minute until the score reaches zero
                GptObjectiveTracker existingTracker = objectiveTrackers.remove(objective);
                if (existingTracker != null) {
                        existingTracker.cancel();
                }
                GptObjectiveTracker tracker = new GptObjectiveTracker(score, objective);
                tracker.setTaskId(Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, tracker, 0,
                                GptObjectiveTracker.CHECK_INTERVAL_TICKS));
                objectiveTrackers.put(objective, tracker);
                if (GPTGOD.GPT_OBJECTIVES.getDisplaySlot() == null)
                        GPTGOD.GPT_OBJECTIVES.setDisplaySlot(DisplaySlot.SIDEBAR);
                MemoryStore.recordObjectiveAssigned(objective);
                pulseObjectiveTargets(targets, "soul", 1, "A Task Is Given", "The heavens turn their gaze.");
                EventLogger.addLoggable(new GPTActionLoggable(String.format("set objective %s", objective)));
                recordActionSuccess("setObjective", "active objective: " + objective);

        };
        private static SimpFunction<JsonObject> clearObjective = (JsonObject args) -> {
                String objective = gson.fromJson(args.get("objective"), String.class);
                List<PlayerMemory> targets = MemoryStore.peekObjectiveTargets(objective);
                clearObjectiveDisplay(objective);
                GptObjectiveTracker tracker = objectiveTrackers.remove(objective);
                if (tracker != null) {
                        tracker.cancel();
                }
                MemoryStore.recordObjectiveCompleted(objective);
                pulseObjectiveTargets(targets, "blessing", 2, "Task Fulfilled", "Favor gathers in the air.");
                pulseSacredStructure("soul", 2);
                refreshObjectiveDisplay();
                EventLogger.addLoggable(
                                new GPTActionLoggable(String.format("declared objective %s as completed", objective)));
                recordActionSuccess("clearObjective", "completed objective: " + objective);

        };
        private static SimpFunction<JsonObject> decreeMessage = (JsonObject args) -> {
                String name = gson.fromJson(args.get("playerName"), String.class);
                String message = gson.fromJson(args.get("message"), String.class);
                Player player = GPTGOD.SERVER.getPlayer(name);
                if (player == null) {
                        recordActionFailure("decree", "player not online: " + name);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to decree \"%s\" to %s (player not online)", message,
                                                        name)));
                        return;
                }

                Entity ent = player.getWorld().spawnEntity(player.getLocation(), EntityType.ARMOR_STAND);
                ent.customName(Component.text(message).decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
                                .color(TextColor.color(255, 45, 45)));
                // teehee
                ent.setCustomNameVisible(true);
                ent.setInvisible(true);
                ent.setGravity(false);
                ent.setInvulnerable(true);
                ent.setVisualFire(true);
                ent.setGlowing(true);
                MemoryStore.rememberGodMessage(name, message);
                MemoryStore.recordPunishmentDelivered(name, "decree");
                EventLogger.addLoggable(new GPTActionLoggable(String.format("decreed \"%s\" to %s", message, name)));
                recordActionSuccess("decree", "displayed decree to " + name);
        };
        private static SimpFunction<JsonObject> detonateStructure = (JsonObject argObject) -> {
                String structure = gson.fromJson(argObject.get("structure"), String.class);
                boolean setFire = gson.fromJson(argObject.get("setFire"), Boolean.class);
                int power = gson.fromJson(argObject.get("power"), Integer.class);
                Structure structureObj = StructureManager.getStructure(structure);
                if(structureObj == null) {
                        // tell god to stop being stupid
                        EventLogger.addLoggable(new GPTActionLoggable(String.format("failed to detonate invalid structure name %s (no such structure found)", structure)));
                        recordActionFailure("detonateStructure", "unknown structure: " + structure);
                        return;
                }
                structureObj.getLocation().createExplosion(power, setFire, true);
                EventLogger.addLoggable(new GPTActionLoggable(String.format("detonated Structure: %s", structure)));
                recordActionSuccess("detonateStructure",
                                String.format("exploded %s with power %d fire=%s", structure, power, setFire));
        };
        // private static Function<JsonObject> lookThroughPlayerEyes = (JsonObject args)
        // -> {
        // String playerName = gson.fromJson(args.get("playerName"), String.class);
        // Player player = GPTGOD.SERVER.getPlayer(playerName);
        // ImageUtils.takePicture(player);
        // };
        private static SimpFunction<JsonObject> lookAtStructure = (JsonObject args) -> {
                String structureName = gson.fromJson(args.get("structureName"), String.class);
                if (structureName == null) {
                        GPTGOD.LOGGER.warn("gptGod called lookAtStructure with null structureName");
                        recordActionFailure("lookAtStructure", "missing structureName");
                        return;
                }
                Structure structure = StructureManager.getStructure(structureName);
                if (structure == null) {
                        GPTGOD.LOGGER.warn("gptGod called lookAtStructure with non existant structure name "
                                        + structureName);
                        recordActionFailure("lookAtStructure", "unknown structure: " + structureName);
                        return;
                }
                ImageUtils.takePicture(structure, structureName);
                recordActionSuccess("lookAtStructure", "requested render for " + structureName);
        };
        private static SimpFunction<JsonObject> divineOmen = (JsonObject args) -> {
                String target = gson.fromJson(args.get("target"), String.class);
                String theme = normalizeTheme(gson.fromJson(args.get("theme"), String.class));
                int intensity = clampIntensity(gson.fromJson(args.get("intensity"), Integer.class));
                Location location = resolveTargetLocation(target);
                if (location == null) {
                        recordActionFailure("divineOmen", "unknown player or structure: " + target);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to manifest %s omen at %s (unknown target)", theme,
                                                        target)));
                        return;
                }

                World world = location.getWorld();
                if (world == null) {
                        recordActionFailure("divineOmen", "target has no world: " + target);
                        return;
                }

                if (theme.equals("wrath") && intensity >= 2) {
                        world.setStorm(true);
                        world.setThundering(true);
                }
                scheduleDivinePulses(location, theme, intensity);
                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("manifested a %s omen around %s", theme, target)));
                recordActionSuccess("divineOmen",
                                String.format("manifested %s omen at %s with intensity %d", theme, target, intensity));
        };
        private static SimpFunction<JsonObject> blessPlayer = (JsonObject args) -> {
                String playerName = gson.fromJson(args.get("playerName"), String.class);
                String blessing = normalizeTheme(gson.fromJson(args.get("blessing"), String.class));
                int intensity = clampIntensity(gson.fromJson(args.get("intensity"), Integer.class));
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("blessPlayer", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to bless %s (player not online)", playerName)));
                        return;
                }

                int duration = 20 * (25 + intensity * 20);
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, duration, intensity - 1));
                if (blessing.equals("fire")) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, duration, 0));
                } else if (blessing.equals("soul") || blessing.equals("void")) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, 0));
                } else {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, duration, intensity - 1));
                }

                player.sendMessage(Component.text("A blessing settles upon you.", NamedTextColor.GOLD));
                scheduleDivinePulses(player.getLocation(), blessing, intensity);
                MemoryStore.recordRewardGranted(playerName, "divine blessing");
                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("blessed %s with a %s sign", playerName, blessing)));
                recordActionSuccess("blessPlayer",
                                String.format("blessed %s with %s intensity %d", playerName, blessing, intensity));
        };
        private static SimpFunction<JsonObject> cursePlayer = (JsonObject args) -> {
                String playerName = gson.fromJson(args.get("playerName"), String.class);
                String curse = normalizeTheme(gson.fromJson(args.get("curse"), String.class));
                int intensity = clampIntensity(gson.fromJson(args.get("intensity"), Integer.class));
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("cursePlayer", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to curse %s (player not online)", playerName)));
                        return;
                }

                int duration = 20 * (12 + intensity * 10);
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, Math.max(0, intensity - 1)));
                if (curse.equals("void")) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, duration, 0));
                } else if (curse.equals("wrath") && intensity >= 2) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, duration, 0));
                } else if (curse.equals("soul") && intensity >= 2) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20 * 3, 0));
                }

                player.sendMessage(Component.text("A judgment grips your bones.", NamedTextColor.RED));
                scheduleDivinePulses(player.getLocation(), curse.equals("divine") ? "wrath" : curse, intensity);
                MemoryStore.recordPunishmentDelivered(playerName, "divine curse");
                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("cursed %s with a %s sign", playerName, curse)));
                recordActionSuccess("cursePlayer",
                                String.format("cursed %s with %s intensity %d", playerName, curse, intensity));
        };
        private static SimpFunction<JsonObject> summonRitualCircle = (JsonObject args) -> {
                String target = gson.fromJson(args.get("target"), String.class);
                String theme = normalizeTheme(gson.fromJson(args.get("theme"), String.class));
                int durationSeconds = gson.fromJson(args.get("durationSeconds"), Integer.class);
                Location location = resolveTargetLocation(target);
                if (location == null) {
                        recordActionFailure("summonRitualCircle", "unknown player or structure: " + target);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to summon ritual circle at %s (unknown target)", target)));
                        return;
                }

                Material material = ritualMaterial(theme);
                List<BlockState> originalStates = new ArrayList<>();
                int placed = 0;
                int radius = 4;
                for (int i = 0; i < 12; i++) {
                        double angle = (Math.PI * 2 * i) / 12.0;
                        int dx = (int) Math.round(Math.cos(angle) * radius);
                        int dz = (int) Math.round(Math.sin(angle) * radius);
                        Block block = findSurfaceBlock(location, dx, dz);
                        if (block == null) {
                                continue;
                        }
                        originalStates.add(block.getState());
                        block.setType(material, false);
                        placed++;
                }

                if (placed < 3) {
                        recordActionFailure("summonRitualCircle", "not enough safe surface blocks around: " + target);
                        for (BlockState state : originalStates) {
                                state.update(true, false);
                        }
                        return;
                }

                restoreBlocksLater(originalStates, durationSeconds);
                scheduleDivinePulses(location, theme, 3);
                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("summoned a temporary %s ritual circle at %s", theme, target)));
                recordActionSuccess("summonRitualCircle",
                                String.format("placed %d temporary %s blocks around %s for %d seconds", placed,
                                                material.name().toLowerCase(Locale.ROOT), target,
                                                Math.max(5, Math.min(60, durationSeconds))));
        };
        private static SimpFunction<JsonObject> dropDivineReward = (JsonObject args) -> {
                String playerName = gson.fromJson(args.get("playerName"), String.class);
                String itemId = gson.fromJson(args.get("itemId"), String.class);
                int count = gson.fromJson(args.get("count"), Integer.class);
                String displayName = gson.fromJson(args.get("displayName"), String.class);
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("dropDivineReward", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to drop reward for %s (player not online)", playerName)));
                        return;
                }

                Material material = Material.matchMaterial(itemId);
                if (material == null) {
                        recordActionFailure("dropDivineReward", "invalid material: " + itemId);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to drop invalid reward %s for %s", itemId, playerName)));
                        return;
                }

                int safeCount = Math.max(1, Math.min(material.getMaxStackSize(), count));
                ItemStack stack = new ItemStack(material, safeCount);
                if (displayName != null && !displayName.isBlank()) {
                        ItemMeta meta = stack.getItemMeta();
                        if (meta != null) {
                                meta.displayName(Component.text(displayName, NamedTextColor.GOLD)
                                                .decoration(TextDecoration.BOLD, true));
                                stack.setItemMeta(meta);
                        }
                }

                Location dropLocation = player.getLocation().clone().add(0, 2.5, 0);
                Item dropped = player.getWorld().dropItem(dropLocation, stack);
                dropped.setGlowing(true);
                dropped.setCustomNameVisible(displayName != null && !displayName.isBlank());
                if (displayName != null && !displayName.isBlank()) {
                        dropped.customName(Component.text(displayName, NamedTextColor.GOLD)
                                        .decoration(TextDecoration.BOLD, true));
                }
                dropped.setVelocity(new Vector(0, 0.25, 0));
                scheduleDivinePulses(player.getLocation(), "blessing", 2);
                MemoryStore.recordRewardGranted(playerName, "visible divine reward");
                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("dropped %d %s as a visible divine reward for %s", safeCount, itemId,
                                                playerName)));
                recordActionSuccess("dropDivineReward",
                                String.format("dropped %d %s for %s", safeCount, itemId, playerName));
        };
        private static SimpFunction<JsonObject> divineTitle = (JsonObject args) -> {
                String playerName = gson.fromJson(args.get("playerName"), String.class);
                String title = gson.fromJson(args.get("title"), String.class);
                String subtitle = gson.fromJson(args.get("subtitle"), String.class);
                String theme = normalizeTheme(gson.fromJson(args.get("theme"), String.class));
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("divineTitle", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to show divine title to %s (player not online)",
                                                        playerName)));
                        return;
                }

                sendDivineTitle(player, title, subtitle, theme);
                player.playSound(player.getLocation(), primarySound(theme), 1.0f, 0.85f);
                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("showed divine title \"%s\" to %s", compactTitle(title, 32),
                                                playerName)));
                recordActionSuccess("divineTitle", "displayed title to " + playerName);
        };
        private static SimpFunction<JsonObject> fireworkShow = (JsonObject args) -> {
                String target = gson.fromJson(args.get("target"), String.class);
                String theme = normalizeTheme(gson.fromJson(args.get("theme"), String.class));
                int intensity = clampIntensity(gson.fromJson(args.get("intensity"), Integer.class));
                Location location = resolveTargetLocation(target);
                if (location == null) {
                        recordActionFailure("fireworkShow", "unknown player or structure: " + target);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to launch %s fireworks at %s (unknown target)", theme,
                                                        target)));
                        return;
                }

                spawnFireworkBurst(location, theme, intensity);
                scheduleDivinePulses(location, theme, Math.max(1, intensity - 1));
                EventLogger.addLoggable(
                                new GPTActionLoggable(String.format("launched %s fireworks at %s", theme, target)));
                recordActionSuccess("fireworkShow",
                                String.format("launched %s fireworks at %s intensity %d", theme, target, intensity));
        };
        private static SimpFunction<JsonObject> setDivineAtmosphere = (JsonObject args) -> {
                String mood = gson.fromJson(args.get("mood"), String.class);
                String normalized = mood == null ? "clear" : mood.toLowerCase(Locale.ROOT).trim();
                World world = WorldManager.getCurrentWorld();
                if (world == null) {
                        recordActionFailure("setDivineAtmosphere", "no current world loaded");
                        return;
                }

                applyAtmosphere(world, normalized);
                for (Player player : world.getPlayers()) {
                        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 0.65f);
                        scheduleDivinePulses(player.getLocation(), normalizeTheme(normalized), 1);
                }
                EventLogger.addLoggable(new GPTActionLoggable("changed the island atmosphere to " + normalized));
                recordActionSuccess("setDivineAtmosphere", "set island atmosphere to " + normalized);
        };
        private static SimpFunction<JsonObject> summonTrial = (JsonObject args) -> {
                String playerName = gson.fromJson(args.get("playerName"), String.class);
                String theme = normalizeTheme(gson.fromJson(args.get("theme"), String.class));
                int intensity = clampIntensity(gson.fromJson(args.get("intensity"), Integer.class));
                Player player = GPTGOD.SERVER.getPlayer(playerName);
                if (player == null) {
                        recordActionFailure("summonTrial", "player not online: " + playerName);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to summon trial for %s (player not online)", playerName)));
                        return;
                }

                int spawned = spawnTrialEntities(player, theme, intensity);
                sendDivineTitle(player, "Trial Summoned", "Stand and prove thy worth.", theme);
                scheduleDivinePulses(player.getLocation(), theme, intensity);
                MemoryStore.createRewardDebt(playerName, "Reward owed if the divine trial is survived",
                                net.bigyous.gptgodmc.memory.DivineDebt.Severity.MODERATE,
                                List.of("dropDivineReward", "blessPlayer", "summonSupplyChest"), 2);
                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("summoned a %s trial of %d %s near %s", theme, spawned,
                                                trialEntity(theme).name().toLowerCase(Locale.ROOT), playerName)));
                recordActionSuccess("summonTrial",
                                String.format("summoned %d %s trial entities near %s", spawned,
                                                trialEntity(theme).name().toLowerCase(Locale.ROOT), playerName));
        };
        private static SimpFunction<JsonObject> divineScene = (JsonObject args) -> {
                String target = gson.fromJson(args.get("target"), String.class);
                String sceneType = normalizeScene(gson.fromJson(args.get("sceneType"), String.class));
                String theme = normalizeTheme(gson.fromJson(args.get("theme"), String.class));
                int intensity = clampIntensity(gson.fromJson(args.get("intensity"), Integer.class));
                String message = gson.fromJson(args.get("message"), String.class);
                Location location = resolveTargetLocation(target);
                if (location == null) {
                        recordActionFailure("divineScene", "unknown player or structure: " + target);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("failed to stage %s scene at %s (unknown target)", sceneType,
                                                        target)));
                        return;
                }

                World world = location.getWorld();
                if (world == null) {
                        recordActionFailure("divineScene", "target has no world: " + target);
                        return;
                }

                if (sceneType.equals("judgment")) {
                        theme = "wrath";
                } else if (sceneType.equals("reward") || sceneType.equals("celebration")) {
                        theme = "blessing";
                } else if (sceneType.equals("spire")) {
                        theme = "soul";
                }

                applyAtmosphere(world, sceneMood(sceneType, theme));
                summonTemporaryLightPillar(location, theme, intensity, 12 + intensity * 8);
                scheduleDivinePulses(location, theme, intensity);

                if (intensity >= 2 || containsAny(sceneType, "reward", "celebration", "spire")) {
                        spawnFireworkBurst(location, theme, intensity);
                }
                if (sceneType.equals("judgment") || intensity >= 3) {
                        world.strikeLightningEffect(location);
                }

                Set<Player> viewers = new LinkedHashSet<>(playersNear(location, 96 + intensity * 24));
                Player targetPlayer = GPTGOD.SERVER.getPlayer(target);
                if (targetPlayer != null) {
                        viewers.add(targetPlayer);
                }
                if (viewers.isEmpty()) {
                        viewers.addAll(world.getPlayers());
                }

                String title = sceneTitle(sceneType);
                String subtitle = sceneSubtitle(sceneType, message);
                for (Player viewer : viewers) {
                        sendDivineTitle(viewer, title, subtitle, theme);
                        viewer.playSound(viewer.getLocation(), primarySound(theme), 1.0f, 0.75f);
                }

                if (sceneType.equals("trial") && targetPlayer != null) {
                        int spawned = spawnTrialEntities(targetPlayer, theme, intensity);
                        MemoryStore.createRewardDebt(targetPlayer.getName(), "Reward owed if the divine trial is survived",
                                        net.bigyous.gptgodmc.memory.DivineDebt.Severity.MODERATE,
                                        List.of("dropDivineReward", "blessPlayer", "summonSupplyChest"), 2);
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("included %d trial entities in divine scene for %s", spawned,
                                                        targetPlayer.getName())));
                }

                EventLogger.addLoggable(new GPTActionLoggable(
                                String.format("staged a %s %s divine scene at %s", theme, sceneType, target)));
                recordActionSuccess("divineScene",
                                String.format("staged %s scene at %s with theme %s intensity %d", sceneType, target,
                                                theme, intensity));
        };
        private static Map<String, FunctionDeclaration> functionMap = Map.ofEntries(
                        Map.entry("decree", new FunctionDeclaration("decree",
                                        "display a heavenly decree in front of a specific player in the world. Use only to communicate displeasure in some action. Use no more than 12 words in the message.",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of the player to send the decree to"),
                                                        "message",
                                                        new Schema(Schema.Type.STRING, "the message of this decree"))),
                                        decreeMessage)),
                        Map.entry("whisper", new FunctionDeclaration("whisper",
                                        "privately send a message to a player. Avoid repeating things that have already been said. Keep messages short, concise, and no more than 100 characters.",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of the player to privately send to"),
                                                        "message", new Schema(Schema.Type.STRING, "the message"))),
                                        whisper)),
                        Map.entry("announce", new FunctionDeclaration("announce",
                                        "brodcast a message to all players. Avoid repeating things that have already been said. Keep messages short, concise, and no more than 100 characters.",
                                        new Schema(Map.of("message", new Schema(Schema.Type.STRING, "the message"))),
                                        announce)),
                        Map.entry("divineScene", new FunctionDeclaration("divineScene",
                                        "stage a curated multi-step divine scene in one call: atmosphere, titles, sound, particles, fireworks, and a temporary light pillar. Use this for major moments when separate spectacle calls would be too slow.",
                                        new Schema(Map.of("target",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of an online player or structure such as Soul Spire"),
                                                        "sceneType", sceneSchema(),
                                                        "theme", themeSchema(
                                                                        "scene theme: divine, blessing, soul, fire, wrath, or void"),
                                                        "intensity", intensitySchema(),
                                                        "message",
                                                        new Schema(Schema.Type.STRING,
                                                                        "short subtitle or reason shown to nearby players"))),
                                        divineScene)),
                        Map.entry("divineOmen", new FunctionDeclaration("divineOmen",
                                        "create a dramatic but safe visual omen around a player or structure using particles, sound, and optional lightning effects. Use before major rewards, warnings, objectives, or Soul Spire moments.",
                                        new Schema(Map.of("target",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of an online player or structure such as Soul Spire"),
                                                        "theme", themeSchema(
                                                                        "visual theme: divine, blessing, soul, fire, wrath, or void"),
                                                        "intensity", intensitySchema())),
                                        divineOmen)),
                        Map.entry("blessPlayer", new FunctionDeclaration("blessPlayer",
                                        "bless a player with visible particles, sound, glow, and helpful temporary potion effects. Use for earned rewards and protection.",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING, "name of the player to bless"),
                                                        "blessing", themeSchema(
                                                                        "blessing theme: divine, blessing, soul, fire, or void"),
                                                        "intensity", intensitySchema())),
                                        blessPlayer)),
                        Map.entry("cursePlayer", new FunctionDeclaration("cursePlayer",
                                        "curse a player with theatrical non-lethal judgment: particles, sound, glow, and short negative effects. Use as a warning before harsher punishment.",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING, "name of the player to curse"),
                                                        "curse", themeSchema(
                                                                        "curse theme: wrath, void, soul, fire, or divine"),
                                                        "intensity", intensitySchema())),
                                        cursePlayer)),
                        Map.entry("summonRitualCircle", new FunctionDeclaration("summonRitualCircle",
                                        "summon a temporary glowing ritual circle around a player or structure. It restores the original blocks automatically; best for Soul Spire ceremonies, completed objectives, and climactic divine signs.",
                                        new Schema(Map.of("target",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of an online player or structure such as Soul Spire"),
                                                        "theme", themeSchema(
                                                                        "circle theme: divine, blessing, soul, fire, wrath, or void"),
                                                        "durationSeconds",
                                                        new Schema(Schema.Type.INTEGER,
                                                                        "duration from 5 to 60 seconds"))),
                                        summonRitualCircle)),
                        Map.entry("dropDivineReward", new FunctionDeclaration("dropDivineReward",
                                        "drop a glowing named reward item visibly above a player instead of silently adding it to inventory. Use for memorable rewards.",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of the player receiving the reward"),
                                                        "itemId",
                                                        new Schema(Schema.Type.STRING,
                                                                        "minecraft item id, for example diamond or cooked_beef"),
                                                        "count",
                                                        new Schema(Schema.Type.INTEGER, "amount to drop, capped to one stack"),
                                                        "displayName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "short dramatic name shown above the glowing item"))),
                                        dropDivineReward)),
                        Map.entry("divineTitle", new FunctionDeclaration("divineTitle",
                                        "show a dramatic full-screen title and subtitle to a player. Use for objective reveals, judgments, blessings, and climactic Soul Spire moments.",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of the player who should see the title"),
                                                        "title",
                                                        new Schema(Schema.Type.STRING,
                                                                        "short title, ideally under 32 characters"),
                                                        "subtitle",
                                                        new Schema(Schema.Type.STRING,
                                                                        "short subtitle, ideally under 64 characters"),
                                                        "theme", themeSchema(
                                                                        "title theme: divine, blessing, soul, fire, wrath, or void"))),
                                        divineTitle)),
                        Map.entry("fireworkShow", new FunctionDeclaration("fireworkShow",
                                        "launch a themed firework burst at a player or structure. Use to celebrate completions, rewards, arrivals, and ritual milestones.",
                                        new Schema(Map.of("target",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of an online player or structure such as Soul Spire"),
                                                        "theme", themeSchema(
                                                                        "firework theme: divine, blessing, soul, fire, wrath, or void"),
                                                        "intensity", intensitySchema())),
                                        fireworkShow)),
                        Map.entry("setDivineAtmosphere", new FunctionDeclaration("setDivineAtmosphere",
                                        "change the island's weather and time as divine stagecraft. Use sparingly to make a major scene feel different.",
                                        new Schema(Map.of("mood", atmosphereSchema())),
                                        setDivineAtmosphere)),
                        Map.entry("summonTrial", new FunctionDeclaration("summonTrial",
                                        "summon a small bounded combat trial near one player with named glowing enemies. Use for fun challenges, judgment, or proving worth; intensity controls 2-4 mobs.",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of the player to challenge"),
                                                        "theme", themeSchema(
                                                                        "trial theme: divine, soul, fire, wrath, or void"),
                                                        "intensity", intensitySchema())),
                                        summonTrial)),
                        Map.entry("giveItem", new FunctionDeclaration("giveItem", "give a player any amount of an item",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING, "name of the Player"), "itemId",
                                                        new Schema(Schema.Type.STRING,
                                                                        "the name of the minecraft item"),
                                                        "count",
                                                        new Schema(Schema.Type.INTEGER, "amount of the item"))),
                                        giveItem)),
                        Map.entry("command", new FunctionDeclaration("command",
                                        "Describe a series of events you would like to take place, taking into consideration the limitations of minecraft",
                                        new Schema(Collections.singletonMap("prompt",
                                                        new Schema(Schema.Type.STRING,
                                                                        "a description of what will happen"))),
                                        command)),
                        Map.entry("smite", new FunctionDeclaration("smite",
                                        "Strike a player down with lightning, reserve this punishment for repeat offenders",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING, "the player's name"), "power",
                                                        new Schema(Schema.Type.INTEGER,
                                                                        "the strength of this smiting"))),
                                        smite)),
                        Map.entry("transformStructure", new FunctionDeclaration("transformStructure",
                                        "replace all the blocks in a structure with any block",
                                        new Schema(Map.of("structure",
                                                        new Schema(Schema.Type.STRING, "name of the structure"),
                                                        "block",
                                                        new Schema(Schema.Type.STRING,
                                                                        "The name of the minecraft block"))),
                                        transformStructure)),
                        Map.entry("spawnEntity", new FunctionDeclaration("spawnEntity",
                                        "spawn any minecraft entity next to a player or structure",
                                        new Schema(Map.of("position",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of the Player or Structure"),
                                                        "entity",
                                                        new Schema(Schema.Type.STRING,
                                                                        "the name of the minecraft entity name will be underscore deliminated eg. \"mushroom_cow\""),
                                                        "count",
                                                        new Schema(Schema.Type.INTEGER,
                                                                        "the amount of the entity that will be spawned"),
                                                        "customName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "(optional) custom name that will be given to the spawned entities, set to null to leave entities unnamed"))),
                                        spawnEntity)),
                        Map.entry("summonSupplyChest", new FunctionDeclaration("summonSupplyChest",
                                        "spawn chest full of items for use in a project next to a player",
                                        new Schema(Map.of("items", new Schema(Schema.Type.ARRAY,
                                                        "names of the minecraft items you would like to put in the chest, each item takes up one of 8 slots",
                                                        Schema.Type.STRING), "fullStacks",
                                                        new Schema(Schema.Type.BOOLEAN,
                                                                        "put the maximum stack size of each item?"),
                                                        "playerName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "The name of the player that will recieve this chest"))),
                                        summonSupplyChest)),
                        Map.entry("revive", new FunctionDeclaration("revive", "bring a player back from the dead",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING, "The name of the player"))),
                                        revive)),
                        Map.entry("teleport", new FunctionDeclaration("teleport",
                                        "teleport a player to another player or a structure",
                                        new Schema(Map.of("playerName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of the player to be teleported"),
                                                        "destination",
                                                        new Schema(Schema.Type.STRING,
                                                                        "The name of the player or Structure the player will be sent to"))),
                                        teleport)),
                        Map.entry("setObjective", new FunctionDeclaration("setObjective",
                                        "set an objective for players to complete. base this off of the behaviors observed in the logs. objectives can't be longer than 45 characters",
                                        new Schema(Map.of("objective", new Schema(Schema.Type.STRING,
                                                        "the objective to set, if it's for a specific player, be sure to include their name"))),
                                        setObjective)),
                        Map.entry("clearObjective",
                                        new FunctionDeclaration("clearObjective",
                                                        "set an objective as complete. Follow this up with a reward",
                                                        new Schema(Map.of("objective", new Schema(Schema.Type.STRING,
                                                                        "the objective to mark as complete"))),
                                                        clearObjective)),
                        Map.entry("detonateStructure", new FunctionDeclaration("detonateStructure",
                                        "cause an explosion at a Structure",
                                        new Schema(Map.of("structure",
                                                        new Schema(Schema.Type.STRING,
                                                                        "name of the structure (not a player name)"),
                                                        "setFire",
                                                        new Schema(Schema.Type.BOOLEAN,
                                                                        "will this explosion cause fires?"),
                                                        "power",
                                                        new Schema(Schema.Type.INTEGER,
                                                                        "the strength of this explosion where 4 is the strength of TNT"))),
                                        detonateStructure)),
                        Map.entry("lookAtStructure", new FunctionDeclaration("lookAtStructure",
                                        "request to view what a specific structure looks like. A render request will be sent off, and the resulting image will later be sent to the vision api to describe what it sees for you.",
                                        new Schema(Map.of("structureName",
                                                        new Schema(Schema.Type.STRING,
                                                                        "the exact name of the structure to look at"))),
                                        lookAtStructure)));
        // private static Map<String, FunctionDeclaration> speechFunctionMap = new
        // HashMap<>(functionMap);
        // private static Map<String, FunctionDeclaration> actionFunctionMap = new
        // HashMap<>(functionMap);

        private static Tool tools;
        // private static Tool[] actionTools;
        // private static Tool[] speechTools;
        // private static final List<String> speechActionKeys =
        // Arrays.asList("announce", "whisper", "setObjective", "clearObjective",
        // "decree");
        // private static final List<String> persistentActionKeys =
        // Arrays.asList("command");

        // todo: experiment with wrapping a list of functions in a single tool for
        // google
        public static Tool wrapFunctions(Map<String, FunctionDeclaration> functions) {
                FunctionDeclaration[] funcList = functions.values().toArray(new FunctionDeclaration[functions.size()]);
                Tool toolList = new Tool(funcList);
                return toolList;
        }

        public static Tool GetAllTools() {
                if (tools != null) {
                        return tools;
                }
                tools = wrapFunctions(functionMap);
                return tools;
        }

        public static Map<String, FunctionDeclaration> getFunctionMap() {
                return functionMap;
        }

        public static void whisperPlayer(String playerName, String message) {
                staticWhisper(playerName, message);
        }

        public static void announceMessage(String message) {
                staticAnnounce(message);
        }

        public static void decreePlayer(String playerName, String message) {
                JsonObject args = new JsonObject();
                args.addProperty("playerName", playerName);
                args.addProperty("message", message);
                decreeMessage.run(args);
        }

        public static void createObjective(String objective) {
                JsonObject args = new JsonObject();
                args.addProperty("objective", objective);
                setObjective.run(args);
        }

        public static void completeObjective(String objective) {
                JsonObject args = new JsonObject();
                args.addProperty("objective", objective);
                clearObjective.run(args);
        }

        public static void smitePlayer(String playerName, int power) {
                JsonObject args = new JsonObject();
                args.addProperty("playerName", playerName);
                args.addProperty("power", power);
                smite.run(args);
        }

        public static void spawnEntityNear(String position, String entity, int count, String customName) {
                JsonObject args = new JsonObject();
                args.addProperty("position", position);
                args.addProperty("entity", entity);
                args.addProperty("count", count);
                if (customName != null) {
                        args.addProperty("customName", customName);
                }
                spawnEntity.run(args);
        }

        public static void stageDivineScene(String target, String sceneType, String theme, int intensity, String message) {
                JsonObject args = new JsonObject();
                args.addProperty("target", target);
                args.addProperty("sceneType", sceneType);
                args.addProperty("theme", theme);
                args.addProperty("intensity", intensity);
                args.addProperty("message", message == null ? "" : message);
                divineScene.run(args);
        }

        public static boolean onTrialEntityDeath(Entity entity, Player killer) {
                if (entity == null) {
                        return false;
                }
                UUID trialId = trialEntityIds.remove(entity.getUniqueId());
                if (trialId == null) {
                        return false;
                }

                TrialState trial = activeTrials.get(trialId);
                if (trial == null) {
                        return false;
                }
                trial.remainingEntityIds.remove(entity.getUniqueId());
                scheduleDivinePulses(entity.getLocation(), trial.theme, 1);

                Player trialPlayer = GPTGOD.SERVER.getPlayer(trial.playerId);
                Player viewer = trialPlayer != null && trialPlayer.isOnline() ? trialPlayer : killer;
                if (!trial.remainingEntityIds.isEmpty()) {
                        if (viewer != null && viewer.isOnline()) {
                                viewer.playSound(viewer.getLocation(), Sound.ENTITY_WITHER_HURT, 0.55f, 1.25f);
                                if (trial.remainingEntityIds.size() == 1) {
                                        sendDivineTitle(viewer, "One Remains", "Finish the trial.", trial.theme);
                                }
                        }
                        return true;
                }

                activeTrials.remove(trialId);
                completeTrial(trial, trialPlayer, killer);
                return true;
        }

        public static boolean onTrialPlayerDeath(Player player) {
                if (player == null) {
                        return false;
                }
                boolean failed = false;
                for (TrialState trial : List.copyOf(activeTrials.values())) {
                        if (!trial.playerId.equals(player.getUniqueId())) {
                                continue;
                        }
                        activeTrials.remove(trial.id);
                        clearTrialEntities(trial, true);
                        failed = true;
                        EventLogger.addLoggable(new GPTActionLoggable(
                                        String.format("%s failed a %s divine trial by dying", player.getName(),
                                                        trial.theme)));
                        recordActionFailure("trialFailed", player.getName() + " died during a divine trial");
                }
                if (failed) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (player.isOnline()) {
                                        sendDivineTitle(player, "Trial Failed", "The test remembers your fall.", "wrath");
                                }
                        }, 40L);
                }
                return failed;
        }

        public static void onObjectiveExpired(String objective) {
                objectiveTrackers.remove(objective);
                clearObjectiveDisplay(objective);
                refreshObjectiveDisplay();
        }

        public static void resetObjectiveDisplayState() {
                for (String objective : Set.copyOf(objectiveEntries.keySet())) {
                        clearObjectiveDisplay(objective);
                }
                objectiveTrackers.clear();
                refreshObjectiveDisplay();
        }

        public static void restoreObjectiveDisplayState() {
                for (String objective : Set.copyOf(objectiveEntries.keySet())) {
                        clearObjectiveDisplay(objective);
                }
                for (GptObjectiveTracker tracker : objectiveTrackers.values()) {
                        tracker.cancel();
                }
                objectiveTrackers.clear();

                Set<String> activeObjectives = new LinkedHashSet<>();
                for (PlayerMemory memory : MemoryStore.getLoadedMemories()) {
                        activeObjectives.addAll(memory.activeObjectives);
                }

                for (String objective : activeObjectives) {
                        applyObjectiveDisplay(objective);
                        Score score = GPTGOD.GPT_OBJECTIVES.getScore(getObjectiveEntry(objective));
                        score.setScore(plugin.getConfig().getInt("objectiveDecay"));
                        GptObjectiveTracker tracker = new GptObjectiveTracker(score, objective);
                        tracker.setTaskId(Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, tracker, 0,
                                        GptObjectiveTracker.CHECK_INTERVAL_TICKS));
                        objectiveTrackers.put(objective, tracker);
                }
                refreshObjectiveDisplay();
        }

        // public static Tool[] GetActionTools() {
        // if (actionTools == null || actionTools[0] == null) {
        // actionFunctionMap.keySet().removeAll(speechActionKeys);
        // actionFunctionMap.keySet().removeAll(persistentActionKeys);
        // actionTools = wrapFunctions(actionFunctionMap);
        // }
        // Tool[] newTools = GPTUtils.randomToolSubset(actionTools, 3);
        // Tool[] persistentTools = persistentActionKeys.stream().map(key -> {
        // return new Tool(functionMap.get(key));
        // }).toArray(Tool[]::new);
        // // I could do this nicer, but I don't feel like it
        // newTools = GPTUtils.concatWithArrayCopy(newTools, persistentTools);
        // return newTools;
        // }

        // public static Tool[] GetSpeechTools() {
        // if (speechTools != null && speechTools[0] != null) {
        // return speechTools;
        // }
        // speechFunctionMap.keySet().retainAll(speechActionKeys);
        // speechTools = wrapFunctions(speechFunctionMap);
        // return speechTools;
        // }

        public static int run(String functionName, JsonObject jsonArgs) {
                GPTGOD.LOGGER.info(String.format("running function \"%s\" with json arguments \"%s\"", functionName,
                                jsonArgs.toString()));
                Bukkit.getScheduler().runTask(plugin, () -> {
                        functionMap.get(functionName).runFunction(jsonArgs);
                });
                return 1;
        }

        private int calculateFunctionTokens() {
                int sum = 0;
                for (FunctionDeclaration function : functionMap.values()) {
                        sum += function.calculateFunctionTokens();
                }
                return sum;
        }

        public int getTokens() {
                if (tokens >= 0) {
                        return tokens;
                }
                return calculateFunctionTokens();
        }

        private static void maybeSettleBlessingDebt(String playerName, String message) {
                if (playerName == null || message == null) {
                        return;
                }
                String normalized = message.toLowerCase(Locale.ROOT);
                if (containsAny(normalized, "bless", "blessing", "favor", "favored", "grace", "reward", "gift",
                                "chosen", "faithful")) {
                        MemoryStore.recordRewardGranted(playerName, "blessing whisper");
                }
        }

        private static boolean isHostileEntity(EntityType type) {
                if (type == null) {
                        return false;
                }
                return switch (type) {
                case ZOMBIE, HUSK, DROWNED, SKELETON, STRAY, WITHER_SKELETON, CREEPER, SPIDER, CAVE_SPIDER,
                                ENDERMAN, BLAZE, GHAST, PHANTOM, PILLAGER, VINDICATOR, VEX, EVOKER, RAVAGER,
                                WITCH, SLIME, MAGMA_CUBE, WARDEN, HOGLIN, ZOGLIN, PIGLIN_BRUTE -> true;
                default -> false;
                };
        }

        private static boolean containsAny(String haystack, String... needles) {
                for (String needle : needles) {
                        if (haystack.contains(needle)) {
                                return true;
                        }
                }
                return false;
        }

}
