package net.bigyous.gptgodmc.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DivineDebt {
    public enum Type {
        REWARD,
        PUNISHMENT
    }

    public enum Severity {
        MINOR,
        MODERATE,
        MAJOR
    }

    public enum Status {
        PENDING,
        SETTLED,
        EXPIRED
    }

    public Type type = Type.REWARD;
    public String reason = "";
    public String targetPlayer = "";
    public Severity severity = Severity.MINOR;
    public int createdAtCycle;
    public int dueByCycle;
    public Status status = Status.PENDING;
    public List<String> suggestedActions = new ArrayList<>();
    public int escalationStage;
    public String settlementSource = "";
    public int settledAtCycle = -1;
    public String outcomeNote = "";

    public DivineDebt() {
    }

    public DivineDebt(Type type, String reason, String targetPlayer, Severity severity, int createdAtCycle,
            int dueByCycle, List<String> suggestedActions) {
        this.type = type;
        this.reason = reason;
        this.targetPlayer = targetPlayer;
        this.severity = severity;
        this.createdAtCycle = createdAtCycle;
        this.dueByCycle = dueByCycle;
        if (suggestedActions != null) {
            this.suggestedActions.addAll(suggestedActions);
        }
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public boolean isOverdue(int currentCycle) {
        return isPending() && currentCycle > dueByCycle;
    }

    public int getPriorityScore(int currentCycle) {
        int score = switch (type) {
        case PUNISHMENT -> 200;
        case REWARD -> 100;
        };
        score += switch (severity) {
        case MINOR -> 10;
        case MODERATE -> 20;
        case MAJOR -> 30;
        };
        if (isOverdue(currentCycle)) {
            score += 1000 + Math.max(0, currentCycle - dueByCycle);
        }
        return score;
    }

    public void mergeSuggestions(List<String> additions) {
        if (additions == null) {
            return;
        }
        for (String action : additions) {
            if (action != null && !action.isBlank() && !suggestedActions.contains(action)) {
                suggestedActions.add(action);
            }
        }
    }

    public String getCompactSummary(int currentCycle) {
        String statusText = isOverdue(currentCycle) ? "overdue" : "pending";
        String severityText = severity.name().toLowerCase(Locale.ROOT);
        String reasonText = compact(reason, 60);
        return String.format("%s %s %s for %s", severityText, type.name().toLowerCase(Locale.ROOT), statusText,
                reasonText);
    }

    public String getSettlementSummary() {
        String prefix = switch (status) {
        case SETTLED -> "settled";
        case EXPIRED -> "expired";
        case PENDING -> "pending";
        };
        return String.format("%s %s for %s", prefix, type.name().toLowerCase(Locale.ROOT), compact(reason, 60));
    }

    private static String compact(String input, int maxLength) {
        if (input == null || input.isBlank()) {
            return "unknown reason";
        }
        String normalized = input.replaceAll("\\s+", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength - 3) + "..." : normalized;
    }
}
