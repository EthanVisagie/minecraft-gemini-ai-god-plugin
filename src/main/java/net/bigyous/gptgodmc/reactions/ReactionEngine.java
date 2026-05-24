package net.bigyous.gptgodmc.reactions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.bigyous.gptgodmc.GPT.GptActions;
import net.bigyous.gptgodmc.memory.MemoryStore;
import net.bigyous.gptgodmc.memory.PlayerMemory;
import net.bigyous.gptgodmc.memory.DivineDebt;
import net.bigyous.gptgodmc.memory.MemoryStore.ChatTone;
import net.bigyous.gptgodmc.memory.MemoryStore.MemoryUpdate;

public class ReactionEngine {
    private static final Random RANDOM = new Random();
    private static final Map<String, Instant> COOLDOWNS = new ConcurrentHashMap<>();

    public static void onPlayerJoin(Player player) {
        MemoryUpdate update = MemoryStore.recordJoin(player);
        announceTitleChange(update, player);

        if (coolingDown("join:" + player.getUniqueId(), 20)) {
            return;
        }

        whisper(player, switch (joinMood(update.getMemory())) {
        case "new" -> String.format("Welcome, %s. Build something worthy of my gaze.", player.getName());
        case "favored" -> pick(
                String.format("My favored %s returns. Impress me again.", player.getName()),
                String.format("%s, you return with my blessing still upon you.", player.getName()));
        case "blasphemer" -> pick(
                String.format("I remember your insolence, %s. Redemption remains possible.", player.getName()),
                String.format("%s, tread carefully. I have not forgotten.", player.getName()));
        default -> pick(
                String.format("%s, you stand once more beneath my sky.", player.getName()),
                String.format("Return with purpose, %s. I am watching.", player.getName()));
        });

        if (update.getMemory().activeObjectives.isEmpty() && !coolingDown("join-objective:" + player.getUniqueId(), 45)) {
            String starterObjective = update.getMemory().reputation >= 12
                    ? String.format("%s raise a worthy monument for my favor", player.getName())
                    : update.getMemory().primaryTitle.equals("Blasphemer")
                            ? String.format("%s offer proof of repentance with a humble shelter", player.getName())
                            : String.format("%s gather wood and light a campfire", player.getName());
            GptActions.createObjective(starterObjective);
        }
    }

    public static void onPlayerDeath(Player player, Player killer) {
        MemoryUpdate update = MemoryStore.recordDeath(player);
        announceTitleChange(update, player);

        if (coolingDown("death:" + player.getUniqueId(), 18)) {
            return;
        }

        PlayerMemory memory = update.getMemory();
        if (killer != null && killer.isOnline()) {
            PlayerMemory killerMemory = MemoryStore.get(killer);
            int murders = killerMemory.getOffenseCount("murder");
            if (murders == 1) {
                announce(String.format("%s has drawn first blood. I am watching.", killer.getName()));
            } else if (murders == 2) {
                decree(killer, "Blood debt marks your hands.");
                MemoryStore.createPunishmentDebt(killer.getName(),
                        "Punishment owed for repeated killing of mortals",
                        DivineDebt.Severity.MODERATE, List.of("decree", "smite", "spawnEntity"), 1);
            } else if (murders >= 3 && !coolingDown("murder-judgment:" + killer.getUniqueId(), 90)) {
                announce(String.format("%s murders beneath my sky and now faces judgment.", killer.getName()));
                GptActions.smitePlayer(killer.getName(), 1);
            }
        }

        if (memory.reputation >= 12) {
            announce(String.format("%s has fallen, but not beyond my favor.", player.getName()));
        } else if (memory.primaryTitle.equals("Blasphemer")) {
            announce(String.format("%s falls again beneath divine judgment.", player.getName()));
        } else {
            announce(pick(String.format("%s has fallen beneath my sky.", player.getName()),
                    String.format("Let all see the fate of %s.", player.getName())));
        }
    }

    public static void onChat(Player player, String message) {
        MemoryUpdate update = MemoryStore.recordChat(player, message);
        announceTitleChange(update, player);

        ChatTone tone = update.getChatTone();
        PlayerMemory memory = update.getMemory();
        String normalized = message.toLowerCase();
        switch (tone) {
        case PRAISE -> {
            if (!coolingDown("praise:" + player.getUniqueId(), 30)) {
                whisper(player, pick("Your reverence warms even my heavens.", "Your praise pleases me.",
                        "You speak wisely, and I remember it.", "Good. Continue, and I will cherish you."));
            }
        }
        case FRIENDLY -> {
            if (!coolingDown("friendly:" + player.getUniqueId(), 35)) {
                whisper(player, pick("Kindness earns my attention.", "A civil tongue is rarely wasted.",
                        "You are easier to love when you speak that way.", "Good. Stay gentle, and I stay merciful."));
            }
        }
        case HOSTILE -> {
            int hostility = memory.getOffenseCount("hostility");
            if (hostility == 1 && !coolingDown("hostile:" + player.getUniqueId(), 40)) {
                whisper(player, pick("Temper yourself.", "Your tone sours the air.",
                        "Do not force my hand over a filthy tongue."));
                MemoryStore.markOffenseWarned(player, "hostility");
            } else if (hostility == 2 && !coolingDown("hostile-decree:" + player.getUniqueId(), 55)) {
                decree(player, pick("Hold your tongue, mortal.", "Insolence invites consequence."));
                MemoryStore.createPunishmentDebt(player.getName(), "Punishment owed for repeated hostility",
                        DivineDebt.Severity.MODERATE, List.of("decree", "announce", "command", "smite"), 1);
                MemoryStore.markOffenseWarned(player, "hostility");
            } else if (hostility >= 3 && !coolingDown("hostile-punish:" + player.getUniqueId(), 70)) {
                announce(String.format("%s's insolence ripens toward judgment.", player.getName()));
                MemoryStore.createPunishmentDebt(player.getName(), "Punishment owed for chronic hostility",
                        DivineDebt.Severity.MODERATE, List.of("decree", "smite", "command"), 1);
                GptActions.smitePlayer(player.getName(), 1);
            }
        }
        case BLASPHEMY -> {
            int blasphemies = memory.getOffenseCount("blasphemy");
            if (blasphemies == 1 && !coolingDown("blasphemy:" + player.getUniqueId(), 45)) {
                decree(player, pick("Mind your tongue, mortal.", "Blasphemy stains your name.",
                        "Do not test my patience."));
                MemoryStore.createPunishmentDebt(player.getName(), "Punishment owed for first blasphemy",
                        DivineDebt.Severity.MINOR, List.of("decree", "command", "smite"), 1);
                MemoryStore.markOffenseWarned(player, "blasphemy");
            } else if (blasphemies == 2 && !coolingDown("blasphemy-announce:" + player.getUniqueId(), 60)) {
                announce(String.format("%s has spoken blasphemy twice and now owes repentance.", player.getName()));
                MemoryStore.createPunishmentDebt(player.getName(), "Punishment owed for repeated blasphemy",
                        DivineDebt.Severity.MODERATE, List.of("decree", "smite", "spawnEntity"), 1);
                GptActions.createObjective(String.format("%s offer repentance at Soul Spire", player.getName()));
                GptActions.smitePlayer(player.getName(), 1);
                MemoryStore.markOffenseWarned(player, "blasphemy");
            } else if (blasphemies >= 3 && !coolingDown("blasphemy-smite:" + player.getUniqueId(), 75)) {
                announce(String.format("%s has blasphemed again and earns the storm.", player.getName()));
                GptActions.smitePlayer(player.getName(), 1);
            }
        }
        case NEUTRAL -> {
            boolean seeksGuidance = normalized.contains("?")
                    || containsAny(normalized, "what", "how", "task", "objective", "god", "lord", "am i done");
            if (seeksGuidance && !memory.activeObjectives.isEmpty()
                    && !coolingDown("guidance:" + player.getUniqueId(), 18)) {
                whisper(player, abbreviate(buildGuidanceMessage(memory.activeObjectives.get(0)), 160));
            } else if (seeksGuidance && !coolingDown("converse:" + player.getUniqueId(), 18)) {
                whisper(player, abbreviate(buildConversationalReply(player.getName(), normalized), 160));
            } else if (normalized.contains("god") && !coolingDown("acknowledge:" + player.getUniqueId(), 15)) {
                whisper(player, pick("I hear you, little one.", "Speak. I am listening.", "Your words reach me.",
                        "Yes. Tell me what troubles you."));
            } else if (!coolingDown("neutral:" + player.getUniqueId(), 22) && normalized.length() > 8) {
                whisper(player, pick("I am listening.", "Continue.", "I have not abandoned you.",
                        "Speak plainly and I may yet be kind."));
            }
        }
        }
    }

    public static void onCombat(Player attacker, Entity target) {
        boolean targetIsPlayer = target instanceof Player;
        MemoryUpdate update = MemoryStore.recordCombat(attacker, target.getName(), targetIsPlayer);
        announceTitleChange(update, attacker);

        if (coolingDown("combat:" + attacker.getUniqueId(), targetIsPlayer ? 35 : 25)) {
            return;
        }

        if (targetIsPlayer) {
            int violence = update.getMemory().getOffenseCount("violence");
            if (violence == 1 && !coolingDown("violence-warn:" + attacker.getUniqueId(), 45)) {
                announce(pick(String.format("%s tests %s beneath my gaze.", attacker.getName(), target.getName()),
                        String.format("Blood stirs between %s and %s.", attacker.getName(), target.getName())));
                MemoryStore.markOffenseWarned(attacker, "violence");
            } else if (violence == 2 && !coolingDown("violence-decree:" + attacker.getUniqueId(), 75)) {
                decree(attacker, "Shed blood again and suffer.");
                MemoryStore.createPunishmentDebt(attacker.getName(),
                        "Punishment owed for striking another mortal despite warning",
                        DivineDebt.Severity.MODERATE, List.of("decree", "smite", "spawnEntity"), 1);
                MemoryStore.markOffenseWarned(attacker, "violence");
            } else if (violence >= 3 && !coolingDown("violence-punish:" + attacker.getUniqueId(), 120)) {
                announce(String.format("%s spills mortal blood too freely.", attacker.getName()));
                GptActions.smitePlayer(attacker.getName(), 1);
            }
        } else {
            whisper(attacker,
                    pick("Spill monster blood and I may remember it.", "Strike true. The beasts are watching too."));
        }
    }

    private static void whisper(Player player, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }
        GptActions.whisperPlayer(player.getName(), message);
        MemoryStore.rememberGodMessage(player, message);
    }

    private static void decree(Player player, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }
        GptActions.decreePlayer(player.getName(), message);
        MemoryStore.rememberGodMessage(player, message);
    }

    private static void announce(String message) {
        GptActions.announceMessage(message);
    }

    private static void announceTitleChange(MemoryUpdate update, Player player) {
        if (!update.titleChanged() || update.getNewTitle().isBlank()
                || coolingDown("title:" + player.getUniqueId(), 90)) {
            return;
        }
        announce(String.format("%s is now known as %s.", player.getName(), update.getNewTitle()));
    }

    private static boolean coolingDown(String key, int seconds) {
        Instant now = Instant.now();
        Instant expiresAt = COOLDOWNS.get(key);
        if (expiresAt != null && expiresAt.isAfter(now)) {
            return true;
        }
        COOLDOWNS.put(key, now.plusSeconds(seconds));
        return false;
    }

    private static String joinMood(PlayerMemory memory) {
        if (memory.joinCount == 1) {
            return "new";
        }
        if (memory.reputation >= 12) {
            return "favored";
        }
        if (memory.primaryTitle.equals("Blasphemer") || memory.reputation <= -10) {
            return "blasphemer";
        }
        return "returning";
    }

    private static String pick(String... options) {
        return options[RANDOM.nextInt(options.length)];
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String message, int maxLength) {
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String buildGuidanceMessage(String objective) {
        String lower = objective.toLowerCase();
        if (containsAny(lower, "spire")) {
            return "Beloved servant, place stone upward into a clear tower at Soul Spire. Make it tall and obvious.";
        }
        if (containsAny(lower, "campfire", "fire")) {
            return "Beloved servant, gather logs and place a campfire at the ritual site, then ignite it.";
        }
        if (containsAny(lower, "bell")) {
            return "Beloved servant, bring gold for a bell and place it at Soul Spire so its voice can ring.";
        }
        if (containsAny(lower, "lectern")) {
            return "Beloved servant, craft or place a lectern at the ritual site.";
        }
        if (containsAny(lower, "offering", "sacrifice", "altar")) {
            return "Beloved servant, bring meat or a living offering to the ritual site and present it there.";
        }
        return "Beloved servant, your task remains: " + objective;
    }

    private static String buildConversationalReply(String playerName, String normalizedMessage) {
        if (containsAny(normalizedMessage, "do you like", "what do you think of", "who do you like")) {
            if (containsAny(normalizedMessage, "obama")) {
                return "I do not kneel to presidents, " + playerName
                        + ". I judge men by what they build, break, and betray beneath my sky.";
            }
            return pick(
                    "I measure all things by obedience, courage, and craft. Names alone do not move me.",
                    "I care less for fame than for what a soul does when watched by heaven.",
                    "I do not love names. I love deeds, defiance, and devotion.");
        }
        if (containsAny(normalizedMessage, "am i done", "is it done", "did i finish", "have i finished")) {
            return "Look to your task and to the ritual site. If the thing is obvious, placed, lit, built, or offered as commanded, then speak again and I will judge it.";
        }
        if (containsAny(normalizedMessage, "what do you want", "what should i do", "what now")) {
            return "I want visible obedience in this world: place the blocks, light the fire, ring the bell, raise the shrine, or bring the offering. Do something that can be seen.";
        }
        if (containsAny(normalizedMessage, "why", "why me")) {
            return pick(
                    "Because you stand beneath my sky and therefore matter to my will.",
                    "Because I saw your hands and chose to burden them with purpose.",
                    "Because meaning is forged through ordeal, and I am the ordeal.");
        }
        if (containsAny(normalizedMessage, "help", "hint", "explain")) {
            return "Ask me plainly about the task itself, and I will answer plainly. If it concerns building, use visible blocks. If it concerns fire, bring wood and ignition. If it concerns tribute, bring a real item to the shrine.";
        }
        return pick(
                "Speak plainly, and I will answer plainly.",
                "Ask your question clearly, and I may answer with mercy.",
                "I am listening. Ask with purpose.");
    }
}
