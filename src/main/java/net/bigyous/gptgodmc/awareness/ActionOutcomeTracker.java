package net.bigyous.gptgodmc.awareness;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Comparator;

import net.bigyous.gptgodmc.GameLoop;

public class ActionOutcomeTracker {
    private static final int MAX_OUTCOMES = 10;
    private static final int MAX_AGE_CYCLES = 4;
    private static final Deque<ActionOutcome> OUTCOMES = new ArrayDeque<>();
    private static long nextSequence = 1L;

    public static synchronized void success(String action, String detail) {
        add("succeeded", action, detail);
    }

    public static synchronized void failure(String action, String detail) {
        add("failed", action, detail);
    }

    public static synchronized String getSummary() {
        prune();
        if (OUTCOMES.isEmpty()) {
            return "Last God Action Outcomes: none";
        }
        List<String> summaries = OUTCOMES.stream()
                .limit(6)
                .map(ActionOutcome::describe)
                .toList();
        return "Last God Action Outcomes: " + String.join(" | ", summaries);
    }

    public static synchronized long mark() {
        return nextSequence;
    }

    public static synchronized List<OutcomeResult> getSince(long cursor) {
        return OUTCOMES.stream()
                .filter(outcome -> outcome.sequence() >= cursor)
                .sorted(Comparator.comparingLong(ActionOutcome::sequence))
                .map(outcome -> new OutcomeResult(outcome.status(), outcome.action(), outcome.detail()))
                .toList();
    }

    public static synchronized void reset() {
        OUTCOMES.clear();
        nextSequence = 1L;
    }

    private static void add(String status, String action, String detail) {
        OUTCOMES.addFirst(new ActionOutcome(nextSequence++, GameLoop.getCycleCount(), status, action, compact(detail)));
        prune();
        while (OUTCOMES.size() > MAX_OUTCOMES) {
            OUTCOMES.removeLast();
        }
    }

    private static String compact(String detail) {
        if (detail == null || detail.isBlank()) {
            return "no detail";
        }
        String normalized = detail.replaceAll("\\s+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 157) + "..." : normalized;
    }

    private static void prune() {
        int currentCycle = Math.max(1, GameLoop.getCycleCount());
        while (!OUTCOMES.isEmpty()
                && currentCycle - Math.max(1, OUTCOMES.peekLast().cycle()) > MAX_AGE_CYCLES) {
            OUTCOMES.removeLast();
        }
    }

    public record OutcomeResult(String status, String action, String detail) {
        public boolean failed() {
            return "failed".equals(status);
        }

        public String describe() {
            return String.format("%s %s: %s", action, status, detail);
        }
    }

    private record ActionOutcome(long sequence, int cycle, String status, String action, String detail) {
        String describe() {
            int age = Math.max(0, GameLoop.getCycleCount() - cycle);
            return String.format("%s %s: %s (%d cycles ago)", action, status, detail, age);
        }
    }
}
