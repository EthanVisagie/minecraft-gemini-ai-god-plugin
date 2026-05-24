package net.bigyous.gptgodmc.utils;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import net.bigyous.gptgodmc.GPTGOD;
import net.bigyous.gptgodmc.WorldManager;

public class CommandHelper {
    private static final String[] RAW_SAFE_PREFIXES = new String[] {
            "execute ",
            "title ",
            "tellraw ",
            "say ",
            "msg ",
            "teammsg ",
            "w ",
            "whisper ",
            "give ",
            "clear ",
            "effect ",
            "gamemode ",
            "xp ",
            "experience ",
            "recipe ",
            "advancement "
    };

    private static final String[] WORLD_CONTEXT_PREFIXES = new String[] {
            "particle ",
            "playsound ",
            "setblock ",
            "fill ",
            "clone ",
            "summon ",
            "spreadplayers ",
            "weather ",
            "time "
    };

    private static boolean startsWithAny(String command, String[] prefixes) {
        for (String prefix : prefixes) {
            if (command.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizePlayerFeedbackCommand(String command) {
        if (!command.startsWith("execute as ") || !command.contains(" at @s run ")) {
            return command;
        }

        String remainder = command.substring("execute as ".length());
        int atIndex = remainder.indexOf(" at @s run ");
        if (atIndex < 1) {
            return command;
        }

        String playerName = remainder.substring(0, atIndex).strip();
        String innerCommand = remainder.substring(atIndex + " at @s run ".length()).strip();

        if (innerCommand.startsWith("title @s ")) {
            innerCommand = "title " + playerName + innerCommand.substring("title @s".length());
        } else if (innerCommand.startsWith("tellraw @s ")) {
            innerCommand = "tellraw " + playerName + innerCommand.substring("tellraw @s".length());
        } else if (innerCommand.startsWith("effect give @s ")) {
            innerCommand = "effect give " + playerName + innerCommand.substring("effect give @s".length());
        }

        if (!innerCommand.contains("@s")) {
            return "execute at " + playerName + " run " + innerCommand;
        }

        return command;
    }

    private static boolean dispatch(String command, CommandSender console) {
        // can't let GPT turn off mob spawning
        if (command.contains("doMobSpawning")) {
            return false;
        }
        command = command.strip();
        if (command.isEmpty()) {
            return false;
        }
        command = command.charAt(0) == '/' ? command.substring(1) : command;
        command = sanitizePlayerFeedbackCommand(command);

        if (startsWithAny(command, RAW_SAFE_PREFIXES) || command.contains(" in ")) {
            return GPTGOD.SERVER.dispatchCommand(console, command);
        }

        if (command.contains("~") || command.contains("^")) {
            if (!(command.contains(" as ") || command.contains(" at "))) {
                command = String.format("execute as @r at @s in %s run %s", WorldManager.getDimensionName(), command);
            }
            return GPTGOD.SERVER.dispatchCommand(console, command);
        }

        if (startsWithAny(command, WORLD_CONTEXT_PREFIXES)) {
            return GPTGOD.SERVER.dispatchCommand(console,
                    String.format("execute in %s run %s", WorldManager.getDimensionName(), command));
        }

        return GPTGOD.SERVER.dispatchCommand(console, command);
    }

    public static String executeCommands(String[] commands) {
        ConsoleCommandSender console = GPTGOD.SERVER.getConsoleSender();
        int executed = 0;
        for (String command : commands) {
            if (!dispatch(command, console)) {
                return "error in command dispatch";
            }
            executed++;
        }
        return String.format("executed %d command(s)", executed);
    }

    public static String executeCommand(String command) {
        ConsoleCommandSender console = GPTGOD.SERVER.getConsoleSender();
        dispatch(command, console);
        return "executed 1 command";
    }
}
