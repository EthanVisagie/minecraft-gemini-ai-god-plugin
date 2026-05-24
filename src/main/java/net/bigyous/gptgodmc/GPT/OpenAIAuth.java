package net.bigyous.gptgodmc.GPT;

import java.net.http.HttpRequest;
import java.util.Optional;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpPost;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.bigyous.gptgodmc.GPTGOD;

public final class OpenAIAuth {
    private static final String[] CONFIG_KEYS = {
            "openAiKey",
            "openai-api-key",
            "codex-api-key"
    };
    private static final String[] ENV_KEYS = {
            "OPENAI_API_KEY",
            "CODEX_API_KEY"
    };

    private OpenAIAuth() {
    }

    public static Optional<String> getApiKey() {
        FileConfiguration config = JavaPlugin.getPlugin(GPTGOD.class).getConfig();
        for (String key : CONFIG_KEYS) {
            Optional<String> value = nonBlank(config.getString(key));
            if (value.isPresent()) {
                return value;
            }
        }

        for (String key : ENV_KEYS) {
            Optional<String> value = nonBlank(System.getenv(key));
            if (value.isPresent()) {
                return value;
            }
        }

        return Optional.empty();
    }

    public static boolean hasApiKey() {
        return getApiKey().isPresent();
    }

    public static boolean applyBearerAuth(HttpPost request) {
        Optional<String> apiKey = getApiKey();
        apiKey.ifPresent(key -> request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key));
        return apiKey.isPresent();
    }

    public static boolean applyBearerAuth(HttpRequest.Builder request) {
        Optional<String> apiKey = getApiKey();
        apiKey.ifPresent(key -> request.header("Authorization", "Bearer " + key));
        return apiKey.isPresent();
    }

    public static String missingApiKeyMessage() {
        return "OpenAI API key is not configured. Set openAiKey, openai-api-key, or codex-api-key in config.yml, or set OPENAI_API_KEY or CODEX_API_KEY in the server environment.";
    }

    private static Optional<String> nonBlank(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }
}
