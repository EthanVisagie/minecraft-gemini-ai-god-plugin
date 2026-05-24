package net.bigyous.gptgodmc.GPT;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.bigyous.gptgodmc.GPTGOD;
import net.bigyous.gptgodmc.GPT.Json.GptModel;

public class GPTModels {
    private static FileConfiguration config = JavaPlugin.getPlugin(GPTGOD.class).getConfig();
    public static final GptModel GPT_4o = new GptModel("gpt-4o", 100000);
    public static final GptModel GPT_4o_mini = new GptModel("gpt-4o-mini", 85000);
    private static final String DEFAULT_OPENAI_MAIN_MODEL = "gpt-5.5";
    private static final String DEFAULT_OPENAI_SECONDARY_MODEL = "gpt-4.1-mini";
    private static final String DEFAULT_OPENAI_VISION_MODEL = "gpt-5.5";
    private static final String DEFAULT_GEMINI_MAIN_MODEL = "gemini-1.5-flash";
    private static final String DEFAULT_GEMINI_SECONDARY_MODEL = "gemini-1.5-flash-8b";
    private static final int DEFAULT_OPENAI_TOKEN_LIMIT = 1050000;

    public static ModelProvider getMainProvider() {
        return ModelProvider.fromConfig(config, "model-provider", ModelProvider.CODEX);
    }

    public static ModelProvider getSecondaryProvider() {
        return ModelProvider.fromConfig(config, "secondary-model-provider", ModelProvider.GEMINI);
    }

    public static ModelProvider getVisionProvider() {
        return ModelProvider.fromConfig(config, "vision-model-provider", ModelProvider.CODEX);
    }

    public static GptModel getMainModel() {
        return getMainModel(getMainProvider());
    }

    public static GptModel getMainModel(ModelProvider provider) {
        String modelName;
        if (config.isSet("model-name")) {
            modelName = config.getString("model-name");
        } else if (config.isSet("use-full-model")) {
            // for passivity
            modelName = config.getBoolean("use-full-model") ? "gemini-1.5-pro" : "gemini-1.5-flash";
        } else {
            throw new RuntimeException("Please set a value for model-name or use-full-model.");
        }
        modelName = normalizeModelName(provider, modelName, DEFAULT_OPENAI_MAIN_MODEL, DEFAULT_GEMINI_MAIN_MODEL);

        int tokenLimit;

        if (config.isSet("gpt-model-token-limit")) {
            tokenLimit = config.getInt("gpt-model-token-limit");
        } else {
            tokenLimit = getDefaultTokenLimit(provider, modelName, "gpt-model-token-limit");
        }
        return new GptModel(modelName, tokenLimit);
    }

    public static GptModel getSecondaryModel() {
        return getSecondaryModel(getSecondaryProvider());
    }

    public static GptModel getSecondaryModel(ModelProvider provider) {
        String modelName;
        if (config.isSet("secondary-model-name")) {
            modelName = config.getString("secondary-model-name");
        } else {
            // for passivity
            modelName = provider.usesOpenAIResponsesFormat() ? DEFAULT_OPENAI_SECONDARY_MODEL
                    : DEFAULT_GEMINI_SECONDARY_MODEL;
        }
        modelName = normalizeModelName(provider, modelName, DEFAULT_OPENAI_SECONDARY_MODEL, DEFAULT_GEMINI_SECONDARY_MODEL);

        int tokenLimit;

        if (config.isSet("gpt-secondary-token-limit")) {
            tokenLimit = config.getInt("gpt-secondary-token-limit");
        } else {
            tokenLimit = getDefaultTokenLimit(provider, modelName, "gpt-secondary-token-limit");
        }
        return new GptModel(modelName, tokenLimit);
    }

    public static GptModel getVisionModel(ModelProvider provider) {
        String modelName;
        if (config.isSet("vision-model-name")) {
            modelName = config.getString("vision-model-name");
        } else if (provider.usesOpenAIResponsesFormat() && config.isSet("model-name")) {
            modelName = config.getString("model-name");
        } else {
            modelName = provider.usesOpenAIResponsesFormat() ? DEFAULT_OPENAI_VISION_MODEL
                    : DEFAULT_GEMINI_SECONDARY_MODEL;
        }
        modelName = normalizeModelName(provider, modelName, DEFAULT_OPENAI_VISION_MODEL, DEFAULT_GEMINI_SECONDARY_MODEL);

        int tokenLimit;
        if (config.isSet("gpt-vision-token-limit")) {
            tokenLimit = config.getInt("gpt-vision-token-limit");
        } else {
            tokenLimit = getDefaultTokenLimit(provider, modelName, "gpt-vision-token-limit");
        }
        return new GptModel(modelName, tokenLimit);
    }

    private static String normalizeModelName(ModelProvider provider, String modelName, String openAIDefault,
            String geminiDefault) {
        if (modelName == null || modelName.isBlank()) {
            return provider.usesOpenAIResponsesFormat() ? openAIDefault : geminiDefault;
        }
        if (provider.usesOpenAIResponsesFormat() && modelName.startsWith("gemini-")) {
            GPTGOD.LOGGER.warn(String.format("OpenAI provider cannot use Gemini model '%s'; using '%s'", modelName,
                    openAIDefault));
            return openAIDefault;
        }
        if (provider == ModelProvider.GEMINI && modelName.startsWith("gpt-")) {
            GPTGOD.LOGGER.warn(String.format("Gemini provider cannot use OpenAI model '%s'; using '%s'", modelName,
                    geminiDefault));
            return geminiDefault;
        }
        return modelName;
    }

    private static int getDefaultTokenLimit(ModelProvider provider, String modelName, String configKey) {
        if (provider.usesOpenAIResponsesFormat()) {
            return switch (modelName) {
            case "gpt-5.5", "gpt-5.5-2026-04-23" -> 1050000;
            case "gpt-5.5-pro" -> 1050000;
            case "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano" -> 1000000;
            case "gpt-4o", "gpt-4o-mini" -> 128000;
            default -> {
                GPTGOD.LOGGER.warn(String.format(
                        "Could not automatically determine token limit for OpenAI model %s. Using %s; set %s to tune it.",
                        modelName, DEFAULT_OPENAI_TOKEN_LIMIT, configKey));
                yield DEFAULT_OPENAI_TOKEN_LIMIT;
            }
            };
        }

        return switch (modelName) {
        case "gemini-1.5-pro", "gemini-1.5-pro-002" -> 2000000;
        case "gemini-1.5-flash" -> 850000;
        case "gemini-1.5-flash-8b" -> 127500; // model 8b is remarkably cheap at less than 128k prompt length
        default -> throw new RuntimeException(String.format(
                "Could not automatically determine token limit for %s. Please set %s in the config.", modelName,
                configKey));
        };
    }
}
