package net.bigyous.gptgodmc.utils;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Score;

import net.bigyous.gptgodmc.EventLogger;
import net.bigyous.gptgodmc.GameLoop;
import net.bigyous.gptgodmc.ServerInfoSummarizer;
import net.bigyous.gptgodmc.ServerInfoSummarizer.ObjectiveAssessment;
import net.bigyous.gptgodmc.ServerInfoSummarizer.ObjectiveProgressTier;
import net.bigyous.gptgodmc.GPT.GptActions;
import net.bigyous.gptgodmc.loggables.GenericEventLoggable;
import net.bigyous.gptgodmc.memory.MemoryStore;
import net.bigyous.gptgodmc.memory.PlayerMemory;

public class GptObjectiveTracker implements Runnable {
    private static final int OBJECTIVE_GRACE_CYCLES = 2;
    private static final int RECENT_PROGRESS_CYCLES = 2;
    public static final int CHECK_INTERVAL_TICKS = 200;
    private static final int DECAY_INTERVAL_CHECKS = 6;

    private final Score score;
    private final String objective;

    private int taskId;
    private int checksUntilDecay = DECAY_INTERVAL_CHECKS;

    public GptObjectiveTracker(Score score, String objective) {
        this.score = score;
        this.objective = objective;
    }

    public void setTaskId(int id) {
        this.taskId = id;
    }

    public void cancel() {
        Bukkit.getScheduler().cancelTask(taskId);
    }

    @Override
    public void run() {
        ObjectiveState state = inspectObjectiveState();

        if (state.completed) {
            GptActions.completeObjective(objective);
            EventLogger.addLoggable(new GenericEventLoggable("Objective completed by observed progress: " + objective));
            GameLoop.triggerSoon("objective completed", 10);
            Bukkit.getScheduler().cancelTask(taskId);
            return;
        }

        if (score.getScore() <= 1 && state.progressChanged) {
            score.setScore(Math.max(score.getScore(), state.readyOrBetter ? 3 : 2));
            EventLogger.addLoggable(new GenericEventLoggable("Objective progress extended: " + objective));
            GameLoop.triggerSoon("objective progress", 10);
            return;
        }

        if (score.getScore() < 1) {
            if (state.withinGrace || state.recentProgress) {
                score.setScore(state.readyOrBetter ? 3 : 2);
                return;
            }

            if (state.warningDue) {
                for (Player target : state.targets) {
                    GptActions.whisperPlayer(target.getName(), "Your task withers. Show progress now.");
                    MemoryStore.setObjectiveWarningStage(target, objective, 1);
                }
                EventLogger.addLoggable(new GenericEventLoggable("Objective warning issued: " + objective));
                GameLoop.triggerSoon("objective warning", 10);
                score.setScore(1);
                return;
            }

            score.resetScore();
            MemoryStore.recordObjectiveExpired(objective);
            GptActions.onObjectiveExpired(objective);
            EventLogger.addLoggable(new GenericEventLoggable("Objective expired: " + objective));
            Bukkit.getScheduler().cancelTask(taskId);
            return;
        }

        if (state.withinGrace || state.progressChanged) {
            return;
        }
        if (state.recentProgress && score.getScore() <= 2) {
            return;
        }

        checksUntilDecay--;
        if (checksUntilDecay <= 0) {
            score.setScore(score.getScore() - 1);
            checksUntilDecay = DECAY_INTERVAL_CHECKS;
        }
    }

    private ObjectiveState inspectObjectiveState() {
        int currentCycle = Math.max(1, GameLoop.getCycleCount());
        boolean withinGrace = false;
        boolean recentProgress = false;
        boolean progressChanged = false;
        boolean readyOrBetter = false;
        boolean completed = false;
        boolean warningDue = false;

        List<PlayerMemory> targets = MemoryStore.peekObjectiveTargets(objective);
        List<Player> onlineTargets = targets.stream()
                .map(memory -> Bukkit.getPlayerExact(memory.playerName))
                .filter(player -> player != null && player.isOnline())
                .toList();

        for (Player player : onlineTargets) {
            PlayerMemory memory = MemoryStore.get(player);
            ObjectiveAssessment assessment = ServerInfoSummarizer.assessObjectiveProgress(player, objective);
            int assignedCycle = memory.getObjectiveAssignedCycle(objective);
            if (assessment.tier() == ObjectiveProgressTier.COMPLETE) {
                completed = true;
            }

            if (assignedCycle > 0 && currentCycle - assignedCycle <= OBJECTIVE_GRACE_CYCLES) {
                withinGrace = true;
            }

            if (assessment.tier().isMeaningful()) {
                if (MemoryStore.noteObjectiveProgress(player, objective, assessment.summary())) {
                    progressChanged = true;
                }
            }

            int lastProgressCycle = memory.getObjectiveLastProgressCycle(objective);
            if (lastProgressCycle > 0 && currentCycle - lastProgressCycle <= RECENT_PROGRESS_CYCLES) {
                recentProgress = true;
            }

            if (assessment.tier() == ObjectiveProgressTier.READY || assessment.tier() == ObjectiveProgressTier.COMPLETE) {
                readyOrBetter = true;
            }

            if (!withinGrace && !recentProgress && memory.getObjectiveWarningStage(objective) < 1) {
                warningDue = true;
            }
        }

        return new ObjectiveState(withinGrace, recentProgress, progressChanged, readyOrBetter, completed, warningDue,
                onlineTargets);
    }

    private record ObjectiveState(boolean withinGrace, boolean recentProgress, boolean progressChanged,
            boolean readyOrBetter, boolean completed, boolean warningDue, List<Player> targets) {
    }
}
