package net.bigyous.gptgodmc.awareness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.bigyous.gptgodmc.GameLoop;
import net.bigyous.gptgodmc.StructureManager;

public class PlayerIntentTracker {
    private static final int MAX_SIGNALS_PER_PLAYER = 8;
    private static final int MAX_SIGNAL_AGE_CYCLES = 5;
    private static final Map<UUID, Deque<IntentSignal>> SIGNALS = new LinkedHashMap<>();

    public static synchronized void recordChat(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "what should", "what do", "what now", "help", "hint", "objective", "task",
                "am i done", "did i finish", "how do", "?")) {
            add(player, "asked for guidance", quoteBrief(message));
        } else if (containsAny(normalized, "confused", "don't understand", "dont understand", "what mean",
                "where", "lost")) {
            add(player, "seems confused", quoteBrief(message));
        } else if (containsAny(normalized, "i will", "i'll", "on it", "okay", "yes lord", "yes god")) {
            add(player, "accepting task", quoteBrief(message));
        } else if (containsAny(normalized, "won't", "wont", "refuse", "not doing", "never doing", "i refuse")) {
            add(player, "resisting task", quoteBrief(message));
        }
    }

    public static synchronized void recordPickup(Player player, ItemStack item) {
        if (player == null || item == null || item.isEmpty()) {
            return;
        }
        add(player, "gathering materials",
                String.format("picked up %s x%d", materialName(item.getType()), item.getAmount()));
    }

    public static synchronized void recordDrop(Player player, ItemStack item) {
        if (player == null || item == null || item.isEmpty()) {
            return;
        }
        add(player, "discarding or offering items",
                String.format("dropped %s x%d", materialName(item.getType()), item.getAmount()));
    }

    public static synchronized void recordCraft(Player player, ItemStack result) {
        if (player == null || result == null || result.isEmpty()) {
            return;
        }
        add(player, "crafting", String.format("crafted %s x%d", materialName(result.getType()), result.getAmount()));
    }

    public static synchronized void recordBlockPlace(Player player, Material material) {
        if (player == null || material == null) {
            return;
        }
        String location = StructureManager.getStructureProximityData(player.getLocation()) != null
                ? " near " + StructureManager.getStructureProximityData(player.getLocation()).getStructure()
                : "";
        add(player, "building", "placed " + materialName(material) + location);
    }

    public static synchronized void recordUse(Player player, Block block, ItemStack item) {
        if (player == null) {
            return;
        }
        if (block != null) {
            add(player, "interacting", "used " + materialName(block.getType()));
        } else if (item != null && !item.isEmpty()) {
            add(player, "using item", "used " + materialName(item.getType()));
        }
    }

    public static synchronized void recordEat(Player player, ItemStack item) {
        if (player == null || item == null || item.isEmpty()) {
            return;
        }
        add(player, "recovering", "ate " + materialName(item.getType()));
    }

    public static synchronized void recordDamage(Player player, double damage) {
        if (player == null || damage <= 0.0) {
            return;
        }
        add(player, "under threat", String.format("took %.1f damage", damage));
    }

    public static synchronized void recordAttack(Player player, Entity target) {
        if (player == null || target == null) {
            return;
        }
        add(player, target instanceof Player ? "fighting player" : "fighting mobs",
                "attacked " + target.getName());
    }

    public static synchronized void recordSleep(Player player) {
        if (player != null) {
            add(player, "seeking rest", "entered bed");
        }
    }

    public static synchronized String getSummary(Player player) {
        if (player == null) {
            return "Player Intent: unknown";
        }
        Deque<IntentSignal> signals = SIGNALS.get(player.getUniqueId());
        if (signals == null || signals.isEmpty()) {
            return "Player Intent: no recent intent signal";
        }
        prune(signals);
        if (signals.isEmpty()) {
            return "Player Intent: no recent intent signal";
        }

        List<String> parts = new ArrayList<>();
        signals.stream().limit(4).forEach(signal -> parts.add(signal.describe()));
        return "Player Intent: " + String.join(" | ", parts);
    }

    public static synchronized void reset() {
        SIGNALS.clear();
    }

    private static void add(Player player, String intent, String detail) {
        Deque<IntentSignal> signals = SIGNALS.computeIfAbsent(player.getUniqueId(), key -> new ArrayDeque<>());
        signals.addFirst(new IntentSignal(GameLoop.getCycleCount(), intent, detail));
        prune(signals);
        while (signals.size() > MAX_SIGNALS_PER_PLAYER) {
            signals.removeLast();
        }
    }

    private static void prune(Deque<IntentSignal> signals) {
        int currentCycle = Math.max(1, GameLoop.getCycleCount());
        while (!signals.isEmpty()
                && currentCycle - Math.max(1, signals.peekLast().cycle()) > MAX_SIGNAL_AGE_CYCLES) {
            signals.removeLast();
        }
    }

    private static String quoteBrief(String message) {
        String compact = message.replaceAll("\\s+", " ").trim();
        if (compact.length() > 80) {
            compact = compact.substring(0, 77) + "...";
        }
        return "\"" + compact + "\"";
    }

    private static String materialName(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private record IntentSignal(int cycle, String intent, String detail) {
        String describe() {
            int age = Math.max(0, GameLoop.getCycleCount() - cycle);
            return String.format("%s (%s, %d cycles ago)", intent, detail, age);
        }
    }
}
