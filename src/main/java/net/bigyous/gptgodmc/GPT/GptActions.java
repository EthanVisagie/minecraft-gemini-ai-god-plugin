package net.bigyous.gptgodmc.GPT;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Team;

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
        private static final ChatColor[] HIDDEN_COLORS = Arrays.stream(ChatColor.values())
                        .filter(color -> color != ChatColor.RESET)
                        .toArray(ChatColor[]::new);

        private static void recordActionSuccess(String action, String detail) {
                ActionOutcomeTracker.success(action, detail);
        }

        private static void recordActionFailure(String action, String detail) {
                ActionOutcomeTracker.failure(action, detail);
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
                EventLogger.addLoggable(new GPTActionLoggable(String.format("set objective %s", objective)));
                recordActionSuccess("setObjective", "active objective: " + objective);

        };
        private static SimpFunction<JsonObject> clearObjective = (JsonObject args) -> {
                String objective = gson.fromJson(args.get("objective"), String.class);
                clearObjectiveDisplay(objective);
                GptObjectiveTracker tracker = objectiveTrackers.remove(objective);
                if (tracker != null) {
                        tracker.cancel();
                }
                MemoryStore.recordObjectiveCompleted(objective);
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
