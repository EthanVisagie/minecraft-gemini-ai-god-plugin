package net.bigyous.gptgodmc.GPT;

import org.bukkit.configuration.file.FileConfiguration;

import net.bigyous.gptgodmc.GPTGOD;

public enum ModelProvider {
    GEMINI,
    OPENAI,
    CODEX;

    public boolean usesOpenAIResponsesFormat() {
        return this == OPENAI || this == CODEX;
    }

    public static ModelProvider fromConfig(FileConfiguration config, String key, ModelProvider defaultProvider) {
        String configured = config.getString(key);
        if (configured == null || configured.isBlank()) {
            return defaultProvider;
        }

        String normalized = configured.trim().toLowerCase();
        return switch (normalized) {
        case "openai" -> OPENAI;
        case "codex", "gpt-auth", "chatgpt", "chatgpt-oauth" -> CODEX;
        case "gemini", "google" -> GEMINI;
        default -> {
            GPTGOD.LOGGER.warn(String.format("Unknown model provider '%s' for %s; using %s", configured, key,
                    defaultProvider.name().toLowerCase()));
            yield defaultProvider;
        }
        };
    }
}
