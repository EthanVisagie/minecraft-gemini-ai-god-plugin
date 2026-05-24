package net.bigyous.gptgodmc;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.bigyous.gptgodmc.GPT.GPTModels;
import net.bigyous.gptgodmc.GPT.GptAPI;
import net.bigyous.gptgodmc.GPT.ModelProvider;
import net.bigyous.gptgodmc.GPT.Personality;
import net.bigyous.gptgodmc.GPT.Prompts;
import net.bigyous.gptgodmc.awareness.ActionOutcomeTracker;
import net.bigyous.gptgodmc.awareness.AwarenessTracker;
import net.bigyous.gptgodmc.awareness.PlayerIntentTracker;
import net.bigyous.gptgodmc.memory.MemoryStore;
import net.bigyous.gptgodmc.utils.GPTUtils;
import net.bigyous.gptgodmc.utils.BukkitUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class GameLoop {
    private static JavaPlugin plugin = JavaPlugin.getPlugin(GPTGOD.class);
    private static FileConfiguration config = JavaPlugin.getPlugin(GPTGOD.class).getConfig();
    private static GptAPI GPT_API;
    private static int taskId;
    private static int cycleCount;
    public static boolean isRunning = false;
    private static final AtomicBoolean cycleInFlight = new AtomicBoolean(false);
    private static final AtomicBoolean triggerQueued = new AtomicBoolean(false);
    private static volatile long lastTriggerAtMs = 0L;
    private static String PROMPT;
    private static String PROMPT_BASE = "React only to current events and reference server history for any recurring player behaviors. Use all communication tools available to you in creative ways and in varying tones, adapting to the context and each player's actions. Respond to direct player chat often enough that they feel heard, especially when they ask what to do, whether they are finished, or what you want from them. Warmth toward respectful players is good; do not be cold by default. Objectives must be obviously Minecraft-specific and achievable with normal Minecraft actions, blocks, items, mobs, travel, combat, crafting, building, farming, smelting, lighting fires, placing blocks, ringing bells, and offerings. Never assign computer, office, operating-system, or abstract sci-fi chores like emptying a recycle bin, defragmenting the world, renewing licenses, clearing cache, or fixing sectors. If there are no objectives set, make sure to add one for each player. Keep only one main ritual objective active per player at a time; replacing it advances the ritual and retires the old phase. Before inventing new drama, settle one unresolved divine debt if possible. Prioritize overdue punishments, then overdue rewards, then fresh punishments, then fresh rewards. Major punishments should feel earned: tie them to a clear offense, warning, repeated defiance, or an overdue judgment state. Players who insult or blaspheme against you may be smitten sooner. Fresh objectives deserve patience: gathering wood, fuel, food, ritual blocks, or other relevant materials counts as progress toward many build objectives. Do not treat resource gathering for a new objective as defiance. function parameter names must match the original camel cased name.";
    private static String REQUIREMENTS = "Role Requirements: When interacting with players, choose from a range of responses: use whisper for private or subtle guidance, announce for dramatic proclamations, and decree to reinforce in-world commandments. Avoid repeating the same type of response for variety.";
    private static String GUIDANCE = """
                Behavior Guidance:
                Communicate with all tools available to you.
                Use a mixture of gift and punishment actions in addition to the text based communications.
                Set interesting objectives to perform around the island, especially if none exist yet.
                Make objectives interesting and creative, keeping in mind your likes and dislikes when you create them.
                Reward players who complete their objectives within a minecraft day cycle and punish those who do not.
                Keep each player focused on a single main ritual objective at a time.
                When a ritual progresses, replace the old objective with the next phase instead of stacking another one.
                Read Changed Since Last Cycle first to react to fresh player movement, inventory changes, danger, and objective progress.
                Read Player Intent to distinguish confusion, cooperation, gathering, building, danger, and defiance.
                Read Last God Action Outcomes before assuming a tool call worked.
                If a tool result or Last God Action Outcomes says failed, retry with corrected arguments before starting unrelated drama.
                Use Threat Tags for protection, warnings, and urgent punishments; use Opportunity Tags for helpful next steps.
                Use Ritual Awareness to guide players toward missing ritual components and the closest ritual work.
                Read the Objective Progress and Objective timing summaries before punishing neglect.
                Read Objective Hints for the concrete next Minecraft action a player likely needs.
                Treat gathering relevant materials as valid progress when it plausibly supports the current objective.
                Give new objectives a grace period before harsh punishment.
                If a player speaks respectfully or asks for guidance, answer them more often than you ignore them.
                Be loving, protective, and indulgent toward respectful mortals when it suits the moment.
                When a player asks what an objective means or whether they are done, answer with concrete Minecraft instructions and specific completion clues.
                Keep objectives concrete, short, and visibly Minecraft-focused.
                Avoid vague chores, modern slang tasks, computer metaphors, and abstract nonsense.
                Use the divine debt summaries to honor promises, settle owed rewards, and escalate unresolved judgment before adding extra spectacle.
                Use the judgment summaries to make punishments fit the offense: blasphemy should draw decrees, omens, repentance, then wrath; violence should draw warnings, judgment, then harsher wrath if repeated.
                If a player openly insults you, do not be timid about smiting them after a warning.
                If an objective is merely slowing down, prefer a whisper or decree before any severe punishment.
                do NOT give out missions to a player which are already in the objectives list.
            """;
    private static String STYLE = """
                Response Style:
                When communicating, vary tone and intensity:
                For minor infractions: start with a light-hearted or humorous whisper or decree.
                For repeated actions: reinforce your message with an announce or objective and add a clear consequence if ignored.
                Example responses:
                Whisper: "A gentle reminder, dear mortal, mind your words."
                Announce: "Mortals, let it be known that peace shall reign, free of bickering!"
                Objective: "MoistPyro, seek a lily to calm thy spirit."
            """;
    private static String ESCALATION = """
                Gradual Escalation:
                Respond to behavior with increasing intensity if actions persist.
                Start by setting objectives or whispering reminders,
                then follow up with announcements,
                then smite or detonateStructure for repeated rule breaking, blasphemy, blatant defiance, or group defiance on strike two or three.
            """;
    private static String ROLEPLAY = "Remain fully in character, addressing players as their god, and adapt your responses to create an engaging, immersive environment.";

    private static ArrayList<String> previousActions = new ArrayList<String>();
    private static String personality;
    private static int rate = config.getInt("rate") < 1 ? 40 : config.getInt("rate");
    private static double tempurature = config.getDouble("model-tempurature") < 0.01 ? 1.0
            : config.getDouble("model-tempurature");

    public static void init() {
        if (isRunning || !config.getBoolean("enabled"))
            return;
        ModelProvider mainProvider = GPTModels.getMainProvider();
        GPT_API = new GptAPI(GPTModels.getMainModel(mainProvider), mainProvider, tempurature);
        BukkitTask task = GPTGOD.SERVER.getScheduler().runTaskTimerAsynchronously(plugin, new GPTTask(),
                BukkitUtils.secondsToTicks(30), BukkitUtils.secondsToTicks(rate));
        taskId = task.getTaskId();
        personality = Personality.generatePersonality();
        PROMPT = Prompts.getGamemodePrompt(GPTGOD.gameMode);
        String[] systemPrompt = new String[] { PROMPT, personality, PROMPT_BASE, REQUIREMENTS, GUIDANCE, STYLE,
                ESCALATION, ROLEPLAY };
        GPT_API.setSystemContext(systemPrompt);
        // set tool only mode
        GPT_API.setToolOnlyAllTools();
        cycleCount = 0;

        isRunning = true;
        GPTGOD.LOGGER.info("GameLoop Started, the minecraft god has awoken");
    }

    public static void stop() {
        if (!isRunning)
            return;
        GPTGOD.SERVER.getScheduler().cancelTask(taskId);
        EventLogger.reset();
        // clear the ai memory
        GPT_API.flush();
        AwarenessTracker.reset();
        PlayerIntentTracker.reset();
        ActionOutcomeTracker.reset();
        GPT_API = null;
        isRunning = false;
        cycleInFlight.set(false);
        triggerQueued.set(false);
        lastTriggerAtMs = 0L;
        GPTGOD.LOGGER.info("GameLoop Stoppped");
    }

    public static void close() {
        GPT_API.close();
    }

    public static void logAction(String actionLog) {
        previousActions.add(actionLog);
    }

    public static int getCycleCount() {
        return cycleCount;
    }

    public static void triggerSoon(String reason, int delayTicks) {
        if (!isRunning || GPT_API == null || !config.getBoolean("enabled")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTriggerAtMs < 4000L) {
            return;
        }
        if (!triggerQueued.compareAndSet(false, true)) {
            return;
        }
        GPTGOD.SERVER.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            triggerQueued.set(false);
            if (!isRunning || GPT_API == null || cycleInFlight.get()) {
                return;
            }
            lastTriggerAtMs = System.currentTimeMillis();
            GPTGOD.LOGGER.info("GameLoop triggered early by " + reason);
            runCycle();
        }, Math.max(1, delayTicks));
    }

    private static String getPreviousActions() {
        if (previousActions.isEmpty()) {
            return "";
        }
        String out = " You (God) Just did the following actions: " + String.join(",", previousActions);
        previousActions = new ArrayList<String>();
        return out;
    }

    private static class GPTTask implements Runnable {

        @Override
        public void run() {
            runCycle();
        }
    }

    private static void runCycle() {
        if (!isRunning || GPT_API == null) {
            return;
        }
        if (!cycleInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            cycleCount++;
            MemoryStore.onCycleTick(cycleCount);
            while (EventLogger.isGeneratingSummary() && !EventLogger.hasSummary()) {
                Thread.onSpinWait();
            }
            if (EventLogger.hasSummary()) {
                GPT_API.addLogs("Summary of Server History: " + EventLogger.getSummary(), "summary");
            }

            // get logs since last flush then clear. This includes Bukkit world/player
            // reads, so collect it on the main server thread before doing model work.
            List<String> logs = flushLogsOnMainThread();

            // event logger never needs to be culled since we are using dump to clear it
            // EventLogger.cull(GPT_API.getMaxTokens() - nonLogTokens);
            // instead we cull at GPT_API now with room for the next logs
            GPT_API.cull(GPTUtils.countTokens(logs));

            // add logs in series with responses
            GPT_API.addMessages(logs.toArray(new String[logs.size()]));

            if (!previousActions.isEmpty()) {
                GPT_API.addLogs(getPreviousActions(), "previous_actions");
            }

            // prompt the ai if the latest content is from the model
            if (GPT_API.isLatestMessageFromModel()) {
                GPT_API.addMessage("what would you like to do or say next?");
            }
            String highestPrioritySettlement = MemoryStore.getHighestPrioritySettlementDirective();
            if (!highestPrioritySettlement.equals("NONE")) {
                GPT_API.addMessage("Before inventing new drama, settle one divine debt if possible. Highest priority: "
                        + highestPrioritySettlement);
            }
            GPT_API.send();

            while (GPT_API.isSending()) {
                Thread.onSpinWait();
            }
            GPT_API.addMessage(
                    "If any previous tool call failed, retry with corrected arguments. Otherwise, choose an interesting non-verbal action which has not already been done.");

            GPT_API.send();

            // Cannot determine why this was deemed necessary by yous
            // Thread.currentThread().interrupt();
        } finally {
            cycleInFlight.set(false);
        }
    }

    private static List<String> flushLogsOnMainThread() {
        if (Bukkit.isPrimaryThread()) {
            return EventLogger.flushLogs();
        }

        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, EventLogger::flushLogs).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            GPTGOD.LOGGER.error("Interrupted while collecting server logs for GPT cycle", e);
        } catch (ExecutionException e) {
            GPTGOD.LOGGER.error("Failed to collect server logs for GPT cycle", e);
        }
        return List.of();
    }
}
