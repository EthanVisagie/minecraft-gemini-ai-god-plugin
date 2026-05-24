package net.bigyous.gptgodmc.GPT;

import java.util.ArrayList;
import java.util.List;

public class CommandInsightTracker {
    private static final int LIMIT = 5;
    private static final List<String> RECENT = new ArrayList<>();

    public static synchronized void recordOutcome(String prompt, String outcome, boolean success) {
        String compactPrompt = compact(prompt, 80);
        String compactOutcome = compact(outcome, 120);
        String summary = String.format("%s: %s -> %s", success ? "SUCCESS" : "FAILURE", compactPrompt, compactOutcome);
        RECENT.add(0, summary);
        while (RECENT.size() > LIMIT) {
            RECENT.remove(RECENT.size() - 1);
        }
    }

    public static synchronized String getSummary() {
        if (RECENT.isEmpty()) {
            return "NONE";
        }
        return String.join(" | ", RECENT.subList(0, Math.min(3, RECENT.size())));
    }

    private static String compact(String input, int maxLength) {
        if (input == null || input.isBlank()) {
            return "none";
        }
        String normalized = input.replaceAll("\\s+", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength - 3) + "..." : normalized;
    }
}
