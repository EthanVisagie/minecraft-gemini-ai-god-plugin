package net.bigyous.gptgodmc.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

public class PlayerMemory {
    private static final int HISTORY_LIMIT = 12;

    public String uuid;
    public String playerName;
    public int reputation;
    public int joinCount;
    public int deaths;
    public int combatEngagements;
    public int friendlyChats;
    public int hostileChats;
    public int praises;
    public int blasphemies;
    public int objectivesAssigned;
    public int objectivesCompleted;
    public int objectivesFailed;
    public String primaryTitle = "";
    public String lastSeen = "";
    public String lastNotableEvent = "";
    public String lastGodMessage = "";
    public List<String> titles = new ArrayList<>();
    public List<String> activeObjectives = new ArrayList<>();
    public List<String> objectiveHistory = new ArrayList<>();
    // Legacy fields kept so older memory files can migrate cleanly.
    public List<String> pendingRewards = new ArrayList<>();
    public List<String> pendingPunishments = new ArrayList<>();
    public List<DivineDebt> divineDebts = new ArrayList<>();
    public List<DivineDebt> recentSettledDebts = new ArrayList<>();
    public Map<String, Integer> offenseCounts = new LinkedHashMap<>();
    public Map<String, Integer> offenseWarnings = new LinkedHashMap<>();
    public Map<String, Integer> relationships = new LinkedHashMap<>();
    public Map<String, Integer> objectiveAssignedCycles = new LinkedHashMap<>();
    public Map<String, Integer> objectiveLastProgressCycles = new LinkedHashMap<>();
    public Map<String, Integer> objectiveWarningStages = new LinkedHashMap<>();
    public Map<String, String> objectiveProgressSnapshots = new LinkedHashMap<>();

    public PlayerMemory() {
    }

    public PlayerMemory(Player player) {
        this(player.getUniqueId(), player.getName());
    }

    public PlayerMemory(UUID uuid, String playerName) {
        this.uuid = uuid.toString();
        this.playerName = playerName;
    }

    public void syncPlayer(Player player) {
        this.playerName = player.getName();
    }

    public void addObjectiveHistory(String entry) {
        objectiveHistory.add(0, entry);
        while (objectiveHistory.size() > HISTORY_LIMIT) {
            objectiveHistory.remove(objectiveHistory.size() - 1);
        }
    }

    public void addActiveObjective(String objective) {
        if (!activeObjectives.contains(objective)) {
            activeObjectives.add(objective);
        }
    }

    public void removeActiveObjective(String objective) {
        activeObjectives.remove(objective);
    }

    public void trackObjectiveAssignment(String objective, int currentCycle) {
        addActiveObjective(objective);
        objectiveAssignedCycles.put(objective, currentCycle);
        objectiveLastProgressCycles.put(objective, currentCycle);
        objectiveWarningStages.put(objective, 0);
        objectiveProgressSnapshots.put(objective, "");
        trimObjectiveTracking();
    }

    public void clearObjectiveTracking(String objective) {
        removeActiveObjective(objective);
        objectiveAssignedCycles.remove(objective);
        objectiveLastProgressCycles.remove(objective);
        objectiveWarningStages.remove(objective);
        objectiveProgressSnapshots.remove(objective);
    }

    public int getObjectiveAssignedCycle(String objective) {
        return objectiveAssignedCycles.getOrDefault(objective, 0);
    }

    public int getObjectiveLastProgressCycle(String objective) {
        return objectiveLastProgressCycles.getOrDefault(objective, 0);
    }

    public int getObjectiveWarningStage(String objective) {
        return objectiveWarningStages.getOrDefault(objective, 0);
    }

    public void setObjectiveWarningStage(String objective, int stage) {
        if (objective == null || objective.isBlank()) {
            return;
        }
        objectiveWarningStages.put(objective, Math.max(0, stage));
    }

    public boolean noteObjectiveProgress(String objective, int currentCycle, String snapshot) {
        if (objective == null || objective.isBlank() || snapshot == null || snapshot.isBlank()) {
            return false;
        }
        String previousSnapshot = objectiveProgressSnapshots.getOrDefault(objective, "");
        if (snapshot.equals(previousSnapshot)) {
            return false;
        }
        objectiveProgressSnapshots.put(objective, snapshot);
        objectiveLastProgressCycles.put(objective, currentCycle);
        objectiveWarningStages.put(objective, 0);
        return true;
    }

    public void migrateLegacyDebts(int currentCycle) {
        if (pendingRewards != null) {
            for (String reward : new ArrayList<>(pendingRewards)) {
                addDebt(new DivineDebt(DivineDebt.Type.REWARD, reward, playerName, DivineDebt.Severity.MODERATE,
                        currentCycle, currentCycle + 2, List.of("giveItem", "summonSupplyChest", "whisper")));
            }
            pendingRewards.clear();
        }
        if (pendingPunishments != null) {
            for (String punishment : new ArrayList<>(pendingPunishments)) {
                addDebt(new DivineDebt(DivineDebt.Type.PUNISHMENT, punishment, playerName,
                        DivineDebt.Severity.MODERATE, currentCycle, currentCycle + 1,
                        List.of("decree", "smite", "spawnEntity")));
            }
            pendingPunishments.clear();
        }
    }

    public DivineDebt addDebt(DivineDebt debt) {
        if (debt == null || debt.reason == null || debt.reason.isBlank()) {
            return null;
        }
        DivineDebt existing = divineDebts.stream()
                .filter(candidate -> candidate.isPending())
                .filter(candidate -> candidate.type == debt.type)
                .filter(candidate -> candidate.reason.equalsIgnoreCase(debt.reason))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (severityRank(debt.severity) > severityRank(existing.severity)) {
                existing.severity = debt.severity;
            }
            if (existing.dueByCycle <= 0 || (debt.dueByCycle > 0 && debt.dueByCycle < existing.dueByCycle)) {
                existing.dueByCycle = debt.dueByCycle;
            }
            existing.mergeSuggestions(debt.suggestedActions);
            return existing;
        }

        divineDebts.add(0, debt);
        while (divineDebts.size() > 12) {
            divineDebts.remove(divineDebts.size() - 1);
        }
        return debt;
    }

    public void addPendingReward(String reward) {
        addDebt(new DivineDebt(DivineDebt.Type.REWARD, reward, playerName, DivineDebt.Severity.MODERATE, 0, 0,
                List.of("giveItem", "summonSupplyChest", "whisper")));
    }

    public void addPendingPunishment(String punishment) {
        addDebt(new DivineDebt(DivineDebt.Type.PUNISHMENT, punishment, playerName, DivineDebt.Severity.MODERATE, 0, 0,
                List.of("decree", "smite", "spawnEntity")));
    }

    public void clearPendingReward(String reward) {
        divineDebts.removeIf(debt -> debt.isPending() && debt.type == DivineDebt.Type.REWARD
                && debt.reason.equalsIgnoreCase(reward));
    }

    public void clearPendingPunishment(String punishment) {
        divineDebts.removeIf(debt -> debt.isPending() && debt.type == DivineDebt.Type.PUNISHMENT
                && debt.reason.equalsIgnoreCase(punishment));
    }

    public DivineDebt getHighestPriorityDebt(int currentCycle) {
        return divineDebts.stream()
                .filter(DivineDebt::isPending)
                .sorted((left, right) -> Integer.compare(right.getPriorityScore(currentCycle),
                        left.getPriorityScore(currentCycle)))
                .findFirst()
                .orElse(null);
    }

    public DivineDebt getHighestPriorityDebt(DivineDebt.Type type, int currentCycle) {
        return divineDebts.stream()
                .filter(DivineDebt::isPending)
                .filter(debt -> debt.type == type)
                .sorted((left, right) -> Integer.compare(right.getPriorityScore(currentCycle),
                        left.getPriorityScore(currentCycle)))
                .findFirst()
                .orElse(null);
    }

    public void settleDebt(DivineDebt debt, String source, int currentCycle, String note) {
        if (debt == null) {
            return;
        }
        debt.status = DivineDebt.Status.SETTLED;
        debt.settlementSource = source;
        debt.settledAtCycle = currentCycle;
        debt.outcomeNote = note;
        recentSettledDebts.add(0, debt);
        trimSettledDebtHistory();
    }

    public void expireDebt(DivineDebt debt, int currentCycle, String note) {
        if (debt == null) {
            return;
        }
        debt.status = DivineDebt.Status.EXPIRED;
        debt.settledAtCycle = currentCycle;
        debt.outcomeNote = note;
        recentSettledDebts.add(0, debt);
        trimSettledDebtHistory();
    }

    private void trimSettledDebtHistory() {
        while (recentSettledDebts.size() > 8) {
            recentSettledDebts.remove(recentSettledDebts.size() - 1);
        }
    }

    public String getPendingDebtSummary(int currentCycle) {
        List<String> summary = divineDebts.stream()
                .filter(DivineDebt::isPending)
                .sorted((left, right) -> Integer.compare(right.getPriorityScore(currentCycle),
                        left.getPriorityScore(currentCycle)))
                .limit(3)
                .map(debt -> debt.getCompactSummary(currentCycle))
                .toList();
        if (summary.isEmpty()) {
            return "none";
        }
        return String.join(" | ", summary);
    }

    public String getSettledDebtSummary() {
        List<String> summary = recentSettledDebts.stream()
                .limit(2)
                .map(DivineDebt::getSettlementSummary)
                .toList();
        if (summary.isEmpty()) {
            return "none";
        }
        return String.join(" | ", summary);
    }

    public int recordOffense(String offenseKey) {
        if (offenseKey == null || offenseKey.isBlank()) {
            return 0;
        }
        int next = offenseCounts.getOrDefault(offenseKey, 0) + 1;
        offenseCounts.put(offenseKey, next);
        while (offenseCounts.size() > 8) {
            String weakest = offenseCounts.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (weakest == null) {
                break;
            }
            offenseCounts.remove(weakest);
            offenseWarnings.remove(weakest);
        }
        return next;
    }

    public int getOffenseCount(String offenseKey) {
        return offenseCounts.getOrDefault(offenseKey, 0);
    }

    public boolean warningDue(String offenseKey) {
        return offenseWarnings.getOrDefault(offenseKey, 0) < getOffenseCount(offenseKey);
    }

    public void markWarned(String offenseKey) {
        offenseWarnings.put(offenseKey, getOffenseCount(offenseKey));
    }

    public String getJudgmentSummary() {
        if (offenseCounts.isEmpty()) {
            return "none";
        }
        List<String> summary = offenseCounts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(3)
                .map(entry -> {
                    String state = warningDue(entry.getKey()) ? "unwarned" : "warned";
                    return entry.getKey() + " x" + entry.getValue() + " " + state;
                })
                .toList();
        return String.join(" | ", summary);
    }

    public void adjustRelationship(String otherPlayer, int delta) {
        if (otherPlayer == null || otherPlayer.isBlank() || otherPlayer.equalsIgnoreCase(playerName)) {
            return;
        }
        int nextValue = relationships.getOrDefault(otherPlayer, 0) + delta;
        if (nextValue == 0) {
            relationships.remove(otherPlayer);
            return;
        }
        relationships.put(otherPlayer, nextValue);
        while (relationships.size() > 8) {
            String weakest = relationships.entrySet().stream()
                    .min((left, right) -> Integer.compare(Math.abs(left.getValue()), Math.abs(right.getValue())))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (weakest == null) {
                break;
            }
            relationships.remove(weakest);
        }
    }

    public String getRelationshipSummary() {
        if (relationships.isEmpty()) {
            return "none";
        }
        List<String> summary = relationships.entrySet().stream()
                .sorted((left, right) -> Integer.compare(Math.abs(right.getValue()), Math.abs(left.getValue())))
                .limit(3)
                .map(entry -> {
                    if (entry.getValue() >= 3) {
                        return entry.getKey() + " ally";
                    }
                    if (entry.getValue() <= -3) {
                        return entry.getKey() + " rival";
                    }
                    return entry.getKey() + " tense";
                })
                .toList();
        return String.join(" | ", summary);
    }

    public String getPromptSummary(int currentCycle) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Reputation %d", reputation));
        if (!primaryTitle.isBlank()) {
            sb.append(String.format(", Title %s", primaryTitle));
        }
        if (!activeObjectives.isEmpty()) {
            sb.append(String.format(", Active objectives %s", String.join(" | ", activeObjectives)));
        }
        if (!objectiveHistory.isEmpty()) {
            sb.append(String.format(", Recent history %s",
                    String.join(" | ", objectiveHistory.subList(0, Math.min(3, objectiveHistory.size())))));
        }
        DivineDebt highestDebt = getHighestPriorityDebt(currentCycle);
        if (highestDebt != null) {
            sb.append(String.format(", Highest divine debt %s", highestDebt.getCompactSummary(currentCycle)));
        }
        String pendingDebtSummary = getPendingDebtSummary(currentCycle);
        if (!pendingDebtSummary.equals("none")) {
            sb.append(String.format(", Unresolved divine debts %s", pendingDebtSummary));
        }
        String settledDebtSummary = getSettledDebtSummary();
        if (!settledDebtSummary.equals("none")) {
            sb.append(String.format(", Recent settled debts %s", settledDebtSummary));
        }
        if (!relationships.isEmpty()) {
            sb.append(String.format(", Relationships %s", getRelationshipSummary()));
        }
        if (!offenseCounts.isEmpty()) {
            sb.append(String.format(", Judgment state %s", getJudgmentSummary()));
        }
        String objectiveTimingSummary = getObjectiveTimingSummary(currentCycle);
        if (!objectiveTimingSummary.equals("none")) {
            sb.append(String.format(", Objective timing %s", objectiveTimingSummary));
        }
        if (!lastNotableEvent.isBlank()) {
            sb.append(String.format(", Last notable event %s", lastNotableEvent));
        }
        return sb.toString();
    }

    public String getObjectiveTimingSummary(int currentCycle) {
        if (activeObjectives.isEmpty()) {
            return "none";
        }
        List<String> summary = activeObjectives.stream()
                .limit(2)
                .map(objective -> {
                    int assignedCycle = getObjectiveAssignedCycle(objective);
                    int lastProgressCycle = getObjectiveLastProgressCycle(objective);
                    int age = assignedCycle > 0 ? Math.max(0, currentCycle - assignedCycle) : 0;
                    int sinceProgress = lastProgressCycle > 0 ? Math.max(0, currentCycle - lastProgressCycle) : age;
                    int warningStage = getObjectiveWarningStage(objective);
                    String timing = String.format("%s age %d, progress %d ago", objective, age, sinceProgress);
                    if (warningStage > 0) {
                        timing += ", warned x" + warningStage;
                    }
                    return timing;
                })
                .toList();
        return summary.isEmpty() ? "none" : String.join(" | ", summary);
    }

    private int severityRank(DivineDebt.Severity severity) {
        return switch (severity) {
        case MINOR -> 1;
        case MODERATE -> 2;
        case MAJOR -> 3;
        };
    }

    private void trimObjectiveTracking() {
        objectiveAssignedCycles.keySet().retainAll(activeObjectives);
        objectiveLastProgressCycles.keySet().retainAll(activeObjectives);
        objectiveWarningStages.keySet().retainAll(activeObjectives);
        objectiveProgressSnapshots.keySet().retainAll(activeObjectives);
    }
}
