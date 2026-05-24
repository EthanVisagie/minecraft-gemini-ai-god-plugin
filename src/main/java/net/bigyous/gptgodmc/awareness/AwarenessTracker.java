package net.bigyous.gptgodmc.awareness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.bigyous.gptgodmc.GameLoop;

public class AwarenessTracker {
    private static final Map<UUID, PlayerAwarenessSnapshot> previousSnapshots = new LinkedHashMap<>();
    private static int cachedCycle = Integer.MIN_VALUE;
    private static String cachedSummary = "Changed Since Last Cycle: no baseline yet";

    public static synchronized String compileChangeSummary(Collection<? extends Player> players) {
        int cycle = GameLoop.getCycleCount();
        if (cycle == cachedCycle) {
            return cachedSummary;
        }

        Map<UUID, PlayerAwarenessSnapshot> currentSnapshots = new LinkedHashMap<>();
        List<PlayerAwarenessSnapshot> current = players.stream()
                .map(PlayerAwarenessSnapshot::from)
                .sorted(Comparator.comparing(PlayerAwarenessSnapshot::playerName))
                .toList();
        for (PlayerAwarenessSnapshot snapshot : current) {
            currentSnapshots.put(snapshot.uuid(), snapshot);
        }

        List<String> changes = new ArrayList<>(current.stream()
                .map(snapshot -> snapshot.describeChangeFrom(previousSnapshots.get(snapshot.uuid())))
                .filter(change -> change != null && !change.isBlank())
                .toList());
        for (PlayerAwarenessSnapshot previous : previousSnapshots.values()) {
            if (!currentSnapshots.containsKey(previous.uuid())) {
                changes.add(previous.playerName() + " left the world.");
            }
        }

        StringBuilder sb = new StringBuilder("Changed Since Last Cycle:");
        if (changes.isEmpty() && previousSnapshots.isEmpty()) {
            sb.append(" baseline established");
        } else if (changes.isEmpty()) {
            sb.append(" no meaningful player changes");
        } else {
            sb.append("\n");
            for (String change : changes) {
                sb.append("- ").append(change).append("\n");
            }
            trimTrailingNewline(sb);
        }

        previousSnapshots.clear();
        previousSnapshots.putAll(currentSnapshots);
        cachedCycle = cycle;
        cachedSummary = sb.toString();
        return cachedSummary;
    }

    public static synchronized void reset() {
        previousSnapshots.clear();
        cachedCycle = Integer.MIN_VALUE;
        cachedSummary = "Changed Since Last Cycle: no baseline yet";
    }

    private static void trimTrailingNewline(StringBuilder sb) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
    }
}
