package net.bigyous.gptgodmc.memory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.bigyous.gptgodmc.GameLoop;
import net.bigyous.gptgodmc.GPTGOD;

public class MemoryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Map<UUID, PlayerMemory> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<UUID>> OBJECTIVE_ASSIGNMENTS = new ConcurrentHashMap<>();
    private static final JavaPlugin PLUGIN = JavaPlugin.getPlugin(GPTGOD.class);
    private static final Path MEMORY_DIR = PLUGIN.getDataFolder().toPath().resolve("memory");

    public enum ChatTone {
        PRAISE,
        FRIENDLY,
        HOSTILE,
        BLASPHEMY,
        NEUTRAL
    }

    public static final class MemoryUpdate {
        private final PlayerMemory memory;
        private final String previousTitle;
        private final String newTitle;
        private final ChatTone chatTone;

        private MemoryUpdate(PlayerMemory memory, String previousTitle, String newTitle, ChatTone chatTone) {
            this.memory = memory;
            this.previousTitle = previousTitle;
            this.newTitle = newTitle;
            this.chatTone = chatTone;
        }

        public PlayerMemory getMemory() {
            return memory;
        }

        public String getPreviousTitle() {
            return previousTitle;
        }

        public String getNewTitle() {
            return newTitle;
        }

        public ChatTone getChatTone() {
            return chatTone;
        }

        public boolean titleChanged() {
            return !previousTitle.equals(newTitle);
        }
    }

    public static void init() {
        try {
            Files.createDirectories(MEMORY_DIR);
        } catch (IOException e) {
            GPTGOD.LOGGER.error("Failed to create memory directory", e);
            return;
        }

        try (var files = Files.list(MEMORY_DIR)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try (Reader reader = Files.newBufferedReader(path)) {
                    PlayerMemory memory = GSON.fromJson(reader, PlayerMemory.class);
                    if (memory == null || memory.uuid == null || memory.uuid.isBlank()) {
                        return;
                    }
                    CACHE.put(UUID.fromString(memory.uuid), memory);
                } catch (Exception e) {
                    GPTGOD.LOGGER.warn("Skipping unreadable memory file " + path.getFileName(), e);
                }
            });
        } catch (IOException e) {
            GPTGOD.LOGGER.error("Failed to load player memories", e);
        }
    }

    public static PlayerMemory get(Player player) {
        return get(player.getUniqueId(), player.getName());
    }

    public static PlayerMemory get(UUID uuid, String playerName) {
        PlayerMemory memory = CACHE.computeIfAbsent(uuid, key -> new PlayerMemory(uuid, playerName));
        memory.playerName = playerName;
        memory.migrateLegacyDebts(getCurrentCycle());
        return memory;
    }

    public static Collection<PlayerMemory> getLoadedMemories() {
        return List.copyOf(CACHE.values());
    }

    public static MemoryUpdate recordJoin(Player player) {
        PlayerMemory memory = get(player);
        String previousTitle = memory.primaryTitle;
        memory.joinCount++;
        memory.lastSeen = now();
        memory.lastNotableEvent = "Joined the island";
        refreshTitles(memory);
        save(memory);
        return new MemoryUpdate(memory, previousTitle, memory.primaryTitle, ChatTone.NEUTRAL);
    }

    public static MemoryUpdate recordQuit(Player player) {
        PlayerMemory memory = get(player);
        String previousTitle = memory.primaryTitle;
        memory.lastSeen = now();
        save(memory);
        return new MemoryUpdate(memory, previousTitle, memory.primaryTitle, ChatTone.NEUTRAL);
    }

    public static MemoryUpdate recordDeath(Player player) {
        PlayerMemory memory = get(player);
        String previousTitle = memory.primaryTitle;
        memory.deaths++;
        memory.reputation -= memory.deaths >= 3 ? 1 : 0;
        memory.lastSeen = now();
        memory.lastNotableEvent = "Died in God's domain";
        refreshTitles(memory);
        save(memory);
        return new MemoryUpdate(memory, previousTitle, memory.primaryTitle, ChatTone.NEUTRAL);
    }

    public static MemoryUpdate recordChat(Player player, String message) {
        PlayerMemory memory = get(player);
        String previousTitle = memory.primaryTitle;
        ChatTone tone = classifyChat(message);
        adjustMentionedRelationships(memory, message, tone);
        switch (tone) {
        case PRAISE -> {
            memory.praises++;
            memory.friendlyChats++;
            memory.reputation += 2;
            memory.lastNotableEvent = "Praised the god";
        }
        case FRIENDLY -> {
            memory.friendlyChats++;
            memory.reputation += 1;
            memory.lastNotableEvent = "Spoke respectfully";
        }
        case HOSTILE -> {
            memory.hostileChats++;
            memory.recordOffense("hostility");
            memory.reputation -= 1;
            memory.lastNotableEvent = "Spoke with hostility";
        }
        case BLASPHEMY -> {
            memory.blasphemies++;
            memory.hostileChats++;
            memory.recordOffense("blasphemy");
            memory.reputation -= 3;
            memory.lastNotableEvent = "Committed blasphemy";
        }
        case NEUTRAL -> memory.lastNotableEvent = "Spoke in the world";
        }
        memory.lastSeen = now();
        refreshTitles(memory);
        save(memory);
        return new MemoryUpdate(memory, previousTitle, memory.primaryTitle, tone);
    }

    public static MemoryUpdate recordCombat(Player attacker, String targetName, boolean targetIsPlayer) {
        PlayerMemory memory = get(attacker);
        String previousTitle = memory.primaryTitle;
        memory.combatEngagements++;
        memory.reputation += targetIsPlayer ? 1 : 0;
        if (targetIsPlayer) {
            memory.recordOffense("violence");
            memory.adjustRelationship(targetName, -2);
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null) {
                PlayerMemory targetMemory = get(target);
                targetMemory.adjustRelationship(attacker.getName(), -1);
                save(targetMemory);
            }
        }
        memory.lastSeen = now();
        memory.lastNotableEvent = targetIsPlayer ? "Attacked another player" : "Attacked " + targetName;
        refreshTitles(memory);
        save(memory);
        return new MemoryUpdate(memory, previousTitle, memory.primaryTitle, ChatTone.NEUTRAL);
    }

    public static void recordKill(Player killer, Player victim) {
        if (killer == null || victim == null) {
            return;
        }
        PlayerMemory killerMemory = get(killer);
        PlayerMemory victimMemory = get(victim);
        killerMemory.recordOffense("murder");
        killerMemory.adjustRelationship(victim.getName(), -4);
        victimMemory.adjustRelationship(killer.getName(), -5);
        killerMemory.lastNotableEvent = "Killed " + victim.getName();
        victimMemory.lastNotableEvent = "Killed by " + killer.getName();
        save(killerMemory);
        save(victimMemory);
    }

    public static List<MemoryUpdate> recordObjectiveAssigned(String objective) {
        List<MemoryUpdate> updates = new ArrayList<>();
        List<PlayerMemory> targets = resolveObjectiveTargets(objective, false);
        OBJECTIVE_ASSIGNMENTS.put(objective,
                targets.stream().map(memory -> UUID.fromString(memory.uuid)).sorted(Comparator.comparing(UUID::toString))
                        .toList());
        for (PlayerMemory memory : targets) {
            String previousTitle = memory.primaryTitle;
            memory.objectivesAssigned++;
            memory.trackObjectiveAssignment(objective, getCurrentCycle());
            memory.addObjectiveHistory("Assigned: " + objective);
            memory.lastNotableEvent = "Received objective " + objective;
            refreshTitles(memory);
            save(memory);
            updates.add(new MemoryUpdate(memory, previousTitle, memory.primaryTitle, ChatTone.NEUTRAL));
        }
        return updates;
    }

    public static List<MemoryUpdate> recordObjectiveRetired(String objective, String replacementObjective) {
        List<MemoryUpdate> updates = new ArrayList<>();
        for (PlayerMemory memory : resolveObjectiveTargets(objective, true)) {
            String previousTitle = memory.primaryTitle;
            memory.clearObjectiveTracking(objective);
            if (replacementObjective != null && !replacementObjective.isBlank()) {
                memory.addObjectiveHistory("Retired: " + objective + " -> " + replacementObjective);
                memory.lastNotableEvent = "Objective advanced to " + replacementObjective;
            } else {
                memory.addObjectiveHistory("Retired: " + objective);
                memory.lastNotableEvent = "Objective retired";
            }
            refreshTitles(memory);
            save(memory);
            updates.add(new MemoryUpdate(memory, previousTitle, memory.primaryTitle, ChatTone.NEUTRAL));
        }
        return updates;
    }

    public static List<MemoryUpdate> recordObjectiveCompleted(String objective) {
        List<MemoryUpdate> updates = new ArrayList<>();
        for (PlayerMemory memory : resolveObjectiveTargets(objective, true)) {
            String previousTitle = memory.primaryTitle;
            memory.objectivesCompleted++;
            memory.reputation += 4;
            memory.clearObjectiveTracking(objective);
            memory.addObjectiveHistory("Completed: " + objective);
            createDebt(memory, DivineDebt.Type.REWARD, "Reward owed for: " + objective,
                    DivineDebt.Severity.MODERATE, List.of("giveItem", "summonSupplyChest", "revive"), 2);
            memory.lastNotableEvent = "Completed objective " + objective;
            refreshTitles(memory);
            save(memory);
            updates.add(new MemoryUpdate(memory, previousTitle, memory.primaryTitle, ChatTone.NEUTRAL));
        }
        return updates;
    }

    public static List<MemoryUpdate> recordObjectiveExpired(String objective) {
        List<MemoryUpdate> updates = new ArrayList<>();
        for (PlayerMemory memory : resolveObjectiveTargets(objective, true)) {
            String previousTitle = memory.primaryTitle;
            memory.objectivesFailed++;
            memory.recordOffense("neglect");
            memory.reputation -= 2;
            memory.clearObjectiveTracking(objective);
            memory.addObjectiveHistory("Failed: " + objective);
            createDebt(memory, DivineDebt.Type.PUNISHMENT, "Punishment owed for: " + objective,
                    DivineDebt.Severity.MINOR, List.of("whisper", "decree", "announce", "command"), 2);
            memory.lastNotableEvent = "Failed objective " + objective;
            refreshTitles(memory);
            save(memory);
            updates.add(new MemoryUpdate(memory, previousTitle, memory.primaryTitle, ChatTone.NEUTRAL));
        }
        return updates;
    }

    public static void rememberGodMessage(Player player, String message) {
        PlayerMemory memory = get(player);
        memory.lastGodMessage = message;
        trackDivineCommitment(memory, message);
        memory.lastSeen = now();
        save(memory);
    }

    public static void rememberGodMessage(String playerName, String message) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            rememberGodMessage(online, message);
            return;
        }

        CACHE.values().stream()
                .filter(memory -> memory.playerName.equalsIgnoreCase(playerName))
                .findFirst()
                .ifPresent(memory -> {
                    memory.lastGodMessage = message;
                    trackDivineCommitment(memory, message);
                    memory.lastSeen = now();
                    save(memory);
                });
    }

    public static void rememberAnnouncedGodMessage(String message) {
        List<PlayerMemory> targets = resolveMentionedPlayers(message);
        for (PlayerMemory memory : targets) {
            memory.lastGodMessage = message;
            trackDivineCommitment(memory, message);
            memory.lastSeen = now();
            save(memory);
        }
    }

    public static void recordRewardGranted(String playerName, String source) {
        updateDebtResolution(playerName, true, source);
    }

    public static void recordPunishmentDelivered(String playerName, String source) {
        updateDebtResolution(playerName, false, source);
    }

    public static void markOffenseWarned(Player player, String offenseKey) {
        if (player == null || offenseKey == null || offenseKey.isBlank()) {
            return;
        }
        PlayerMemory memory = get(player);
        memory.markWarned(offenseKey);
        save(memory);
    }

    public static void createPunishmentDebt(String playerName, String reason, DivineDebt.Severity severity,
            List<String> suggestedActions, int dueOffset) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        Player online = Bukkit.getPlayerExact(playerName);
        PlayerMemory memory = online != null ? get(online)
                : CACHE.values().stream().filter(candidate -> candidate.playerName.equalsIgnoreCase(playerName))
                        .findFirst().orElse(null);
        if (memory == null) {
            return;
        }
        createDebt(memory, DivineDebt.Type.PUNISHMENT, reason, severity, suggestedActions, dueOffset);
        save(memory);
    }

    public static void createRewardDebt(String playerName, String reason, DivineDebt.Severity severity,
            List<String> suggestedActions, int dueOffset) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        Player online = Bukkit.getPlayerExact(playerName);
        PlayerMemory memory = online != null ? get(online)
                : CACHE.values().stream().filter(candidate -> candidate.playerName.equalsIgnoreCase(playerName))
                        .findFirst().orElse(null);
        if (memory == null) {
            return;
        }
        createDebt(memory, DivineDebt.Type.REWARD, reason, severity, suggestedActions, dueOffset);
        save(memory);
    }

    public static void recordCommandOutcome(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        DivineDebt.Type type = null;
        if (containsAny(normalized, "bless", "gift", "grace", "comfort", "restore", "heal", "favor")) {
            type = DivineDebt.Type.REWARD;
        } else if (containsAny(normalized, "curse", "wrath", "shadow", "doom", "rot", "pain", "blood", "suffer",
                "punish", "judgment", "judge")) {
            type = DivineDebt.Type.PUNISHMENT;
        }
        if (type == null) {
            return;
        }
        for (PlayerMemory memory : resolveMentionedPlayers(prompt)) {
            if (type == DivineDebt.Type.REWARD) {
                settleHighestDebt(memory, DivineDebt.Type.REWARD, "command", "Divine command enacted");
            } else {
                settleHighestDebt(memory, DivineDebt.Type.PUNISHMENT, "command", "Divine curse enacted");
            }
        }
    }

    public static void onCycleTick(int currentCycle) {
        for (PlayerMemory memory : CACHE.values()) {
            boolean changed = processDebtEscalation(memory, currentCycle);
            if (changed) {
                save(memory);
            }
        }
    }

    public static String getPromptSummary(Player player) {
        PlayerMemory memory = get(player);
        return memory.getPromptSummary(getCurrentCycle());
    }

    public static String getUnresolvedDebtSummary(Player player) {
        return get(player).getPendingDebtSummary(getCurrentCycle());
    }

    public static String getHighestPrioritySettlement(Player player) {
        DivineDebt debt = get(player).getHighestPriorityDebt(getCurrentCycle());
        if (debt == null) {
            return "none";
        }
        String actions = debt.suggestedActions.isEmpty() ? "improvise" : String.join("/", debt.suggestedActions);
        return String.format("%s via %s", debt.getCompactSummary(getCurrentCycle()), actions);
    }

    public static String getRecentSettledDebtSummary(Player player) {
        return get(player).getSettledDebtSummary();
    }

    public static String getGlobalUnresolvedDebtSummary() {
        List<String> summaries = Bukkit.getOnlinePlayers().stream()
                .map(MemoryStore::get)
                .map(memory -> {
                    DivineDebt debt = memory.getHighestPriorityDebt(getCurrentCycle());
                    if (debt == null) {
                        return null;
                    }
                    return memory.playerName + ": " + debt.getCompactSummary(getCurrentCycle());
                })
                .filter(summary -> summary != null)
                .limit(4)
                .toList();
        if (summaries.isEmpty()) {
            return "NONE";
        }
        return String.join(" | ", summaries);
    }

    public static String getHighestPrioritySettlementDirective() {
        DivineDebtSelection selection = selectHighestPriorityDebt();
        if (selection == null) {
            return "NONE";
        }
        String actions = selection.debt.suggestedActions.isEmpty() ? "improvise"
                : String.join("/", selection.debt.suggestedActions);
        return String.format("%s must settle %s using %s", selection.memory.playerName,
                selection.debt.getCompactSummary(getCurrentCycle()), actions);
    }

    public static String getRecentSettledDebtSummary() {
        List<String> settled = Bukkit.getOnlinePlayers().stream()
                .map(MemoryStore::get)
                .flatMap(memory -> memory.recentSettledDebts.stream().limit(1)
                        .map(debt -> memory.playerName + ": " + debt.getSettlementSummary()))
                .limit(4)
                .toList();
        if (settled.isEmpty()) {
            return "NONE";
        }
        return String.join(" | ", settled);
    }

    public static List<PlayerMemory> peekObjectiveTargets(String objective) {
        return new ArrayList<>(resolveObjectiveTargets(objective, false));
    }

    public static boolean noteObjectiveProgress(Player player, String objective, String progressSnapshot) {
        if (player == null || objective == null || objective.isBlank()) {
            return false;
        }
        PlayerMemory memory = get(player);
        boolean changed = memory.noteObjectiveProgress(objective, getCurrentCycle(), progressSnapshot);
        if (changed) {
            memory.lastNotableEvent = "Worked toward objective " + objective;
            save(memory);
        }
        return changed;
    }

    public static void setObjectiveWarningStage(Player player, String objective, int warningStage) {
        if (player == null || objective == null || objective.isBlank()) {
            return;
        }
        PlayerMemory memory = get(player);
        memory.setObjectiveWarningStage(objective, warningStage);
        save(memory);
    }

    private static void updateDebtResolution(String playerName, boolean reward, String source) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }

        List<PlayerMemory> targets = CACHE.values().stream()
                .filter(memory -> memory.playerName.equalsIgnoreCase(playerName))
                .toList();
        if (targets.isEmpty()) {
            Player online = Bukkit.getPlayerExact(playerName);
            if (online != null) {
                targets = List.of(get(online));
            }
        }

        for (PlayerMemory memory : targets) {
            DivineDebt.Type type = reward ? DivineDebt.Type.REWARD : DivineDebt.Type.PUNISHMENT;
            if (settleHighestDebt(memory, type, source,
                    reward ? "Received divine reward via " + source : "Suffered divine punishment via " + source)) {
                save(memory);
            }
        }
    }

    private static void trackDivineCommitment(PlayerMemory memory, String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "reward", "bless", "gift", "favor", "grant", "grant you", "i will give",
                "i shall give", "you will be rewarded")) {
            createDebt(memory, DivineDebt.Type.REWARD, summarizeCommitment(message),
                    containsAny(normalized, "great", "abundance", "riches") ? DivineDebt.Severity.MAJOR
                            : DivineDebt.Severity.MINOR,
                    List.of("giveItem", "summonSupplyChest", "whisper"), 2);
        }
        if (containsAny(normalized, "punish", "smite", "wrath", "judge", "suffer", "face my", "or face",
                "i will strike", "i shall strike", "pay the price")) {
            createDebt(memory, DivineDebt.Type.PUNISHMENT, summarizeCommitment(message),
                    containsAny(normalized, "smite", "wrath", "pay the price") ? DivineDebt.Severity.MAJOR
                            : DivineDebt.Severity.MINOR,
                    List.of("decree", "smite", "spawnEntity", "command"), 1);
        }
    }

    private static String summarizeCommitment(String message) {
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 100 ? compact.substring(0, 97) + "..." : compact;
    }

    private static List<PlayerMemory> resolveMentionedPlayers(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        List<PlayerMemory> mentioned = CACHE.values().stream()
                .filter(memory -> normalized.contains(memory.playerName.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(memory -> memory.playerName))
                .collect(Collectors.toList());
        if (!mentioned.isEmpty()) {
            return mentioned;
        }
        return List.of();
    }

    private static List<PlayerMemory> resolveObjectiveTargets(String objective, boolean consumeAssignment) {
        List<UUID> assigned = consumeAssignment ? OBJECTIVE_ASSIGNMENTS.remove(objective) : OBJECTIVE_ASSIGNMENTS.get(objective);
        if (assigned != null && !assigned.isEmpty()) {
            return assigned.stream().map(uuid -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    return get(online);
                }
                PlayerMemory memory = CACHE.get(uuid);
                return memory == null ? null : memory;
            }).filter(memory -> memory != null).sorted(Comparator.comparing(memory -> memory.playerName)).toList();
        }

        List<PlayerMemory> activeTargets = CACHE.values().stream()
                .filter(memory -> memory.activeObjectives.contains(objective))
                .sorted(Comparator.comparing(memory -> memory.playerName)).toList();
        if (!activeTargets.isEmpty()) {
            return activeTargets;
        }

        String normalizedObjective = objective.toLowerCase(Locale.ROOT);
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        List<PlayerMemory> targeted = online.stream()
                .filter(player -> normalizedObjective.contains(player.getName().toLowerCase(Locale.ROOT)))
                .map(MemoryStore::get)
                .sorted(Comparator.comparing(memory -> memory.playerName))
                .toList();

        if (!targeted.isEmpty()) {
            return new ArrayList<>(targeted);
        }

        if (online.size() == 1) {
            return new ArrayList<>(List.of(get(online.iterator().next())));
        }

        return online.stream().map(MemoryStore::get).sorted(Comparator.comparing(memory -> memory.playerName))
                .toList();
    }

    private static void refreshTitles(PlayerMemory memory) {
        List<String> titles = new ArrayList<>();
        if (memory.reputation >= 12) {
            titles.add("Favored");
        }
        if (memory.objectivesCompleted >= 3) {
            titles.add("Faithful");
        }
        if (memory.combatEngagements >= 6) {
            titles.add("Blooded");
        }
        if (memory.deaths >= 6) {
            titles.add("Doomed");
        }
        if (memory.blasphemies >= 3 || memory.reputation <= -10) {
            titles.add("Blasphemer");
        }

        memory.titles = titles;
        if (titles.contains("Blasphemer")) {
            memory.primaryTitle = "Blasphemer";
        } else if (titles.contains("Favored")) {
            memory.primaryTitle = "Favored";
        } else if (titles.contains("Faithful")) {
            memory.primaryTitle = "Faithful";
        } else if (titles.contains("Blooded")) {
            memory.primaryTitle = "Blooded";
        } else if (titles.contains("Doomed")) {
            memory.primaryTitle = "Doomed";
        } else {
            memory.primaryTitle = "";
        }
    }

    private static ChatTone classifyChat(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        boolean mentionsGod = normalized.contains("god") || normalized.contains("lord") || normalized.contains("divine")
                || normalized.contains("machine") || normalized.contains("am ");
        boolean praise = containsAny(normalized, "praise", "bless", "forgive", "thank you", "thanks", "holy", "grace");
        boolean hostility = containsAny(normalized, "hate", "stupid", "idiot", "shut up", "damn", "fuck", "trash",
                "sucks", "loser", "pathetic", "moron", "dumb", "cringe");
        boolean blasphemy = mentionsGod && (containsAny(normalized, "fake", "false", "not my", "useless", "weak",
                "liar", "fraud", "worthless", "nothing", "coward")
                || hostility);
        boolean friendly = containsAny(normalized, "please", "sorry", "welcome", "friend", "love", "peace")
                || (mentionsGod && praise);

        if (blasphemy) {
            return ChatTone.BLASPHEMY;
        }
        if (mentionsGod && praise) {
            return ChatTone.PRAISE;
        }
        if (hostility) {
            return ChatTone.HOSTILE;
        }
        if (friendly) {
            return ChatTone.FRIENDLY;
        }
        return ChatTone.NEUTRAL;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void adjustMentionedRelationships(PlayerMemory speaker, String message, ChatTone tone) {
        String normalized = message.toLowerCase(Locale.ROOT);
        int delta = switch (tone) {
        case PRAISE, FRIENDLY -> 1;
        case HOSTILE, BLASPHEMY -> -1;
        case NEUTRAL -> 0;
        };
        if (delta == 0) {
            return;
        }

        CACHE.values().stream()
                .filter(other -> !other.playerName.equalsIgnoreCase(speaker.playerName))
                .filter(other -> normalized.contains(other.playerName.toLowerCase(Locale.ROOT)))
                .forEach(other -> speaker.adjustRelationship(other.playerName, delta));
    }

    private static String now() {
        return FORMATTER.format(Instant.now());
    }

    private static DivineDebt createDebt(PlayerMemory memory, DivineDebt.Type type, String reason,
            DivineDebt.Severity severity, List<String> suggestedActions, int dueOffset) {
        DivineDebt debt = new DivineDebt(type, reason, memory.playerName, severity, getCurrentCycle(),
                Math.max(getCurrentCycle() + dueOffset, 1), suggestedActions);
        return memory.addDebt(debt);
    }

    private static boolean settleHighestDebt(PlayerMemory memory, DivineDebt.Type type, String source, String note) {
        DivineDebt debt = memory.getHighestPriorityDebt(type, getCurrentCycle());
        if (debt == null) {
            return false;
        }
        memory.settleDebt(debt, source, getCurrentCycle(), note);
        memory.addObjectiveHistory((type == DivineDebt.Type.REWARD ? "Reward settled: " : "Punishment settled: ")
                + debt.reason);
        memory.lastNotableEvent = note;
        return true;
    }

    private static boolean processDebtEscalation(PlayerMemory memory, int currentCycle) {
        boolean changed = false;
        for (DivineDebt debt : memory.divineDebts) {
            if (!debt.isPending() || !debt.isOverdue(currentCycle)) {
                continue;
            }
            changed = true;
            if (debt.type == DivineDebt.Type.REWARD) {
                if (debt.escalationStage == 0) {
                    debt.escalationStage = 1;
                    debt.dueByCycle = currentCycle + 1;
                    debt.mergeSuggestions(List.of("whisper", "giveItem"));
                    memory.addObjectiveHistory("Reward reminder due: " + debt.reason);
                    memory.lastNotableEvent = "Still owes reward to " + memory.playerName;
                } else if (debt.escalationStage == 1) {
                    debt.escalationStage = 2;
                    debt.severity = DivineDebt.Severity.MINOR;
                    debt.dueByCycle = currentCycle + 1;
                    debt.mergeSuggestions(List.of("giveItem", "whisper"));
                    memory.addObjectiveHistory("Reward reduced to consolation: " + debt.reason);
                    memory.lastNotableEvent = "Reward reduced to consolation";
                } else {
                    memory.expireDebt(debt, currentCycle, "Reward expired after repeated delay");
                    memory.addObjectiveHistory("Reward expired: " + debt.reason);
                    memory.lastNotableEvent = "A promised reward was forfeited";
                }
            } else {
                if (debt.escalationStage == 0) {
                    debt.escalationStage = 1;
                    if (debt.severity == DivineDebt.Severity.MINOR) {
                        debt.severity = DivineDebt.Severity.MODERATE;
                    }
                    debt.dueByCycle = currentCycle + 1;
                    debt.mergeSuggestions(List.of("decree", "announce"));
                    memory.addObjectiveHistory("Judgment warning issued: " + debt.reason);
                    memory.lastNotableEvent = "Judgment warning remains unresolved";
                } else if (debt.escalationStage == 1) {
                    debt.escalationStage = 2;
                    debt.severity = DivineDebt.Severity.MAJOR;
                    debt.dueByCycle = currentCycle + 1;
                    debt.mergeSuggestions(List.of("decree", "smite", "spawnEntity", "command"));
                    memory.addObjectiveHistory("Judgment escalated: " + debt.reason);
                    memory.lastNotableEvent = "Judgment escalated against " + memory.playerName;
                } else {
                    debt.dueByCycle = currentCycle + 1;
                    debt.mergeSuggestions(List.of("smite", "spawnEntity", "command"));
                    memory.lastNotableEvent = "Overdue divine punishment demands action";
                }
            }
        }
        return changed;
    }

    private static DivineDebtSelection selectHighestPriorityDebt() {
        return Bukkit.getOnlinePlayers().stream()
                .map(MemoryStore::get)
                .map(memory -> {
                    DivineDebt debt = memory.getHighestPriorityDebt(getCurrentCycle());
                    if (debt == null) {
                        return null;
                    }
                    return new DivineDebtSelection(memory, debt);
                })
                .filter(selection -> selection != null)
                .max((left, right) -> Integer.compare(left.debt.getPriorityScore(getCurrentCycle()),
                        right.debt.getPriorityScore(getCurrentCycle())))
                .orElse(null);
    }

    private static int getCurrentCycle() {
        return Math.max(1, GameLoop.getCycleCount());
    }

    private record DivineDebtSelection(PlayerMemory memory, DivineDebt debt) {
    }

    private static void save(PlayerMemory memory) {
        try {
            Files.createDirectories(MEMORY_DIR);
            Path path = MEMORY_DIR.resolve(memory.uuid + ".json");
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(memory, writer);
            }
        } catch (IOException e) {
            GPTGOD.LOGGER.error("Failed to save memory for " + memory.playerName, e);
        }
    }
}
