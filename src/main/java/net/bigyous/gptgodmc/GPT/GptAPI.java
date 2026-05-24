package net.bigyous.gptgodmc.GPT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.bigyous.gptgodmc.GPTGOD;
import net.bigyous.gptgodmc.GPT.Json.Candidate;
import net.bigyous.gptgodmc.GPT.Json.Content;
import net.bigyous.gptgodmc.GPT.Json.FunctionCallingConfig;
import net.bigyous.gptgodmc.GPT.Json.FunctionCall;
import net.bigyous.gptgodmc.GPT.Json.FunctionDeclaration;
import net.bigyous.gptgodmc.GPT.Json.FunctionResponse;
import net.bigyous.gptgodmc.GPT.Json.GenerateContentRequest;
import net.bigyous.gptgodmc.GPT.Json.GenerateContentResponse;
import net.bigyous.gptgodmc.GPT.Json.GptModel;
import net.bigyous.gptgodmc.GPT.Json.ModelSerializer;
import net.bigyous.gptgodmc.GPT.Json.ParameterExclusion;
import net.bigyous.gptgodmc.GPT.Json.Part;
import net.bigyous.gptgodmc.GPT.Json.Tool;
import net.bigyous.gptgodmc.GPT.Json.ToolConfig;
import net.bigyous.gptgodmc.utils.AsyncTaskQueue;
import net.bigyous.gptgodmc.utils.GPTUtils;
import net.bigyous.gptgodmc.GPT.Json.Content.Role;
import net.bigyous.gptgodmc.GPT.Json.FunctionCallingConfig.Mode;
import net.bigyous.gptgodmc.awareness.ActionOutcomeTracker;
import net.bigyous.gptgodmc.awareness.ActionOutcomeTracker.OutcomeResult;

public class GptAPI {
    private GsonBuilder gson = new GsonBuilder();

    private GptModel model;
    private ModelProvider provider;
    private GenerateContentRequest body;
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final String CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";
    // keep track of what index each type of context is stored at
    // should never have an index greater than contextHeight
    private Map<String, Integer> messageMap = new HashMap<String, Integer>();
    // the index that instructions end at and rolling context starts
    private int contextHeight = 0;
    // keep track of how many tokens are in total chat history
    private int totalTokens = 0;

    private volatile boolean isSending = false;
    private final AtomicInteger pendingRequests = new AtomicInteger(0);
    private static JavaPlugin plugin = JavaPlugin.getPlugin(GPTGOD.class);

    // handles the submission of the gpt content request
    // and ensures that each request is fired sequentially
    // without the call to the request blocking (call async)
    private AsyncTaskQueue<Map<String, FunctionDeclaration>> gptTasks = new AsyncTaskQueue<Map<String, FunctionDeclaration>>(
            (Map<String, FunctionDeclaration> funcs) -> {
                try {
                    // wait for previous sender to return
                    while (this.isSending) {
                        Thread.onSpinWait();
                    }
                    this.isSending = true;
                    executeModelRequest(funcs);
                } catch (Throwable e) {
                    GPTGOD.LOGGER.error("Unhandled error while preparing or sending GPT request", e);
                    markRequestFinished();
                }
            });

    public GptAPI(GptModel model, double temperature) {
        this(model, GPTModels.getMainProvider(), temperature);
    }

    public GptAPI(GptModel model, ModelProvider provider, double temperature) {
        Tool allTools = GptActions.GetAllTools();
        this.body = new GenerateContentRequest(allTools, temperature);
        initialize(model, provider);
    }

    public GptAPI(GptModel model) {
        this(model, GPTModels.getMainProvider());
    }

    public GptAPI(GptModel model, ModelProvider provider) {
        Tool allTools = GptActions.GetAllTools();
        this.body = new GenerateContentRequest(allTools);
        initialize(model, provider);
    }

    public GptAPI(GptModel model, Tool customTools, double tempurature) {
        this(model, GPTModels.getMainProvider(), customTools, tempurature);
    }

    public GptAPI(GptModel model, ModelProvider provider, Tool customTools, double tempurature) {
        this.body = new GenerateContentRequest(customTools, tempurature);
        initialize(model, provider);
    }

    public GptAPI(GptModel model, Tool customTools) {
        this(model, GPTModels.getMainProvider(), customTools);
    }

    public GptAPI(GptModel model, ModelProvider provider, Tool customTools) {
        this.body = new GenerateContentRequest(customTools);
        initialize(model, provider);
    }

    public GptAPI(GptModel model, GenerateContentRequest request) {
        this(model, GPTModels.getMainProvider(), request);
    }

    public GptAPI(GptModel model, ModelProvider provider, GenerateContentRequest request) {
        this.body = request;
        initialize(model, provider);
    }

    private void initialize(GptModel model, ModelProvider provider) {
        this.model = model;
        this.provider = provider;
        Tool[] tools = body.getTools();
        if (tools != null && tools.length > 0) {
            for (Tool tool : tools) {
                totalTokens += GPTUtils.calculateToolTokens(tool);
            }
        }
        if (body.getSystemInstruction() != null) {
            totalTokens += body.getSystemInstruction().countTokens();
        }
        gson.registerTypeAdapter(GptModel.class, new ModelSerializer());
        gson.setExclusionStrategies(new ParameterExclusion());

        if (totalTokens > this.getMaxTokens()) {
            throw new RuntimeException(
                    "system instruction alone is more than gpt-model-token-limit. Please increase it to some value higher than "
                            + this.getMaxTokens());
        }
    }

    // remove and return the oldest chat history
    // excepting any entires under the contextHeight
    public Content popOldestContent() {
        return this.body.removeMessage(contextHeight);
    }

    // clear all chat messages except context
    public void flush() {
        // pop until null
        try {
            while (popOldestContent() != null) {
            }
        } catch (IndexOutOfBoundsException e) {
        }
    }

    // remove and return the oldest chat history until we are within the token limit
    // excepting any entires under the contextHeight
    // if provided, nextTokenLength ensures that there is room for the next token
    // addition
    public void cull(int nextTokenLength) {
        // get the configured token maximum for this model
        // and set the goal to that minus the headroom needed for our next prompt
        int tokenLimit = this.getMaxTokens() - nextTokenLength;

        if (totalTokens > tokenLimit) {
            GPTGOD.LOGGER.info("running cull operation from " + totalTokens + " down to " + tokenLimit);
        }

        while (totalTokens > tokenLimit && this.body.getMessagesSize() > contextHeight) {
            Content oldest = this.popOldestContent();
            totalTokens -= oldest.countTokens();
        }

        if (totalTokens > tokenLimit) {
            GPTGOD.LOGGER.warn("GPT token count " + totalTokens + " is greater than maximum of " + tokenLimit);
        }
    }

    public void cull() {
        cull(0);
    }

    // push a message to the index at the current stack height
    // then increase the context stack height
    // and return the height
    private int pushContextStack(String context) {
        GPTGOD.LOGGER.info("Pushing context stack height up one from " + contextHeight + " to " + (contextHeight + 1));
        // get current stack height then increment
        int insertedAtIndex = contextHeight++;
        // increment token count of message history
        totalTokens += GPTUtils.countTokens(context);
        this.body.addMessage(Content.Role.user, context, insertedAtIndex);
        return insertedAtIndex;
    }

    private int pushContextStack(List<String> context) {
        GPTGOD.LOGGER.info("Pushing context stack height up one from " + contextHeight + " to " + (contextHeight + 1));
        // get current stack height then increment
        int insertedAtIndex = contextHeight++;
        // increment token count of message history
        totalTokens += GPTUtils.countTokens(context);
        this.body.addMessage(Content.Role.user, context, insertedAtIndex);
        return insertedAtIndex;
    }

    // replace message content at index and update total running token count
    private void replaceMessage(int index, String message) {
        int oldMsgTokens = this.body.getMessage(index).countTokens();
        int newMessageTokens = GPTUtils.countTokens(message);
        // replace the message
        this.body.replaceMessage(index, message);
        // update the token total with the difference between the old and new message
        totalTokens += (newMessageTokens - oldMsgTokens);
    }

    // replace message content at index and update total running token count
    private void replaceMessage(int index, List<String> message) {
        int oldMsgTokens = this.body.getMessage(index).countTokens();
        int newMessageTokens = GPTUtils.countTokens(message);
        // replace the message
        this.body.replaceMessage(index, message);
        // update the token total with the difference between the old and new message
        totalTokens += (newMessageTokens - oldMsgTokens);
    }

    public GptAPI addContext(String context, String name) {
        if (this.messageMap.containsKey(name)) {
            this.replaceMessage(messageMap.get(name), context);
            return this;
        }
        // push message to context stack then add its index to the message map
        // also increments totalTokens
        this.messageMap.put(name, pushContextStack(context));
        return this;
    }

    public GptAPI addFileWithContext(String context, String fileMimeType, String fileUri) {
        this.body.addFileWithPrompt(context, fileMimeType, fileUri);
        totalTokens += GPTUtils.countTokens(context);
        return this;
    }

    public GptAPI addFilesWithContext(String context, GoogleFile[] files) {
        this.body.addFilesWithPrompt(context, files);
        totalTokens += GPTUtils.countTokens(context);
        return this;
    }

    // sets the system direction parameter
    public GptAPI setSystemContext(String context) {
        Content oldInstruction = this.body.getSystemInstruction();
        int newTokenCount = GPTUtils.countTokens(context);
        if (oldInstruction == null) {
            this.totalTokens += newTokenCount;
        } else {
            int oldCount = oldInstruction.countTokens();
            this.totalTokens += (newTokenCount - oldCount);
        }
        this.body.setSystemInstruction(context);
        return this;
    }

    public GptAPI setSystemContext(String[] context) {
        Content oldInstruction = this.body.getSystemInstruction();
        int newTokenCount = GPTUtils.countTokens(context);
        if (oldInstruction == null) {
            this.totalTokens += newTokenCount;
        } else {
            int oldCount = oldInstruction.countTokens();
            this.totalTokens += (newTokenCount - oldCount);
        }
        this.body.setSystemInstruction(context);
        return this;
    }

    public GptAPI setTools(Tool tools) {
        Tool[] oldTools = this.body.getTools();
        int newTokenCount = GPTUtils.calculateToolTokens(tools);
        if (oldTools == null) {
            this.totalTokens += newTokenCount;
        } else {
            int oldCount = 0;
            for (Tool oldTool : oldTools) {
                oldCount += GPTUtils.calculateToolTokens(oldTool);
            }
            this.totalTokens += (newTokenCount - oldCount);
        }
        this.body.setTools(tools);
        return this;
    }

    // adds server logs context
    // same as addContext but takes in a list of events
    public GptAPI addLogs(List<String> Logs, String name) {
        if (this.messageMap.containsKey(name)) {
            this.replaceMessage(messageMap.get(name), Logs);
            return this;
        }
        this.messageMap.put(name, pushContextStack(Logs));
        return this;
    }

    // this is just an alias really
    public GptAPI addLogs(String Logs, String name) {
        this.addContext(Logs, name);
        return this;
    }

    public GptAPI addResponse(Content responseContent) {
        GPTGOD.LOGGER.info("Adding response " + gson.create().toJson(responseContent));
        this.body.addMessage(responseContent);
        this.totalTokens += responseContent.countTokens();
        return this;
    }

    public GptAPI addContent(Content content) {
        GPTGOD.LOGGER.info("Adding content " + gson.create().toJson(content));
        this.body.addMessage(content);
        this.totalTokens += content.countTokens();
        return this;
    }

    public GptAPI addMessage(String message) {
        GPTGOD.LOGGER.info("Adding prompt to get response: " + message);
        this.body.addMessage(Role.user, message);
        this.totalTokens += GPTUtils.countTokens(message);
        return this;
    }

    public GptAPI addMessages(String[] messages) {
        // GPTGOD.LOGGER.info("Adding prompt to get response: " + String.join("\n",
        // messages) );
        this.body.addMessage(Role.user, messages);
        this.totalTokens += GPTUtils.countTokens(messages);
        return this;
    }

    public GptAPI setToolChoice(String tool_choice) {
        this.body.setToolConfig(new ToolConfig(new String[] { tool_choice }));
        return this;
    }

    public GptAPI setToolOnlyAllTools() {
        ArrayList<String> toolNames = new ArrayList<>();
        // add all toolNames to required tools
        for (Tool tool : this.body.getTools()) {
            for (FunctionDeclaration func : tool.getFunctions()) {
                toolNames.add(func.getName());
            }
        }
        this.body.setToolConfig(new ToolConfig(Mode.ANY, toolNames.toArray(new String[toolNames.size()])));
        return this;
    }

    // public void removeLastMessage() {
    // this.body.removeLastMessage();
    // }

    public int getMaxTokens() {
        return model.getTokenLimit();
    }

    public String getModelName() {
        return model.getName();
    }

    public boolean isLatestMessageFromModel() {
        return this.body.isLatestMessageFromModel();
    }

    public void send(Map<String, FunctionDeclaration> functions) {
        pendingRequests.incrementAndGet();
        try {
            gptTasks.insert(functions);
        } catch (RuntimeException e) {
            markRequestFinished();
            throw e;
        }
    }

    public void send() {
        this.send(GptActions.getFunctionMap());
    }

    public boolean isSending() {
        return pendingRequests.get() > 0 || isSending;
    }

    private void markRequestFinished() {
        this.isSending = false;
        pendingRequests.updateAndGet(count -> Math.max(0, count - 1));
    }

    private void executeModelRequest(Map<String, FunctionDeclaration> functions) {
        JsonElement requestJson = buildRequestBodyJson();
        String requestBody = gson.create().toJson(requestJson);
        GPTGOD.LOGGER.info("POSTING " + gson.setPrettyPrinting().create().toJson(redactForLog(requestJson)));

        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpPost post = new HttpPost(getRequestUrl());
            post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            if (provider == ModelProvider.OPENAI && !OpenAIAuth.applyBearerAuth(post)) {
                GPTGOD.LOGGER.warn(OpenAIAuth.missingApiKeyMessage() + " Skipping OpenAI model request.");
                markRequestFinished();
                return;
            }
            if (provider == ModelProvider.CODEX && !CodexAuth.applyBearerAuth(post)) {
                GPTGOD.LOGGER.warn(CodexAuth.missingAuthMessage() + " Skipping Codex model request.");
                markRequestFinished();
                return;
            }

            post.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));
            GPTGOD.LOGGER.info("Making " + provider.name().toLowerCase() + " POST request");

            HttpResponse response = client.execute(post);
            String out = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            EntityUtils.consume(response.getEntity());
            GPTGOD.LOGGER.info("recieved response from " + provider.name().toLowerCase() + ": " + out);

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < 200 || statusCode >= 300) {
                GPTGOD.LOGGER.warn(String.format("%s API call failed with status %s: %s", provider.name(), statusCode,
                        out));
                markRequestFinished();
                return;
            }

            if (provider == ModelProvider.OPENAI) {
                processOpenAIResponse(out, functions);
            } else if (provider == ModelProvider.CODEX) {
                processOpenAIResponse(out, functions);
            } else {
                processGeminiResponse(out, functions);
            }

            // after everything finishes, executing the request is finished
            Bukkit.getScheduler().runTaskLater(JavaPlugin.getPlugin(GPTGOD.class), () -> {
                markRequestFinished();
            }, 20);
        } catch (IOException e) {
            GPTGOD.LOGGER.error("There was an error making a request to GPT", e);
            markRequestFinished();
        }
    }

    private String getRequestUrl() {
        if (provider == ModelProvider.OPENAI) {
            return OPENAI_RESPONSES_URL;
        }
        if (provider == ModelProvider.CODEX) {
            return CODEX_RESPONSES_URL;
        }

        FileConfiguration config = JavaPlugin.getPlugin(GPTGOD.class).getConfig();
        return GEMINI_BASE_URL + model.getName() + ":generateContent" + "?key=" + config.getString("geminiKey");
    }

    private JsonElement buildRequestBodyJson() {
        if (provider.usesOpenAIResponsesFormat()) {
            return buildOpenAIRequestBody();
        }
        return gson.create().toJsonTree(body);
    }

    private JsonObject buildOpenAIRequestBody() {
        JsonObject request = new JsonObject();
        request.addProperty("model", model.getName());
        request.addProperty("store", false);
        if (provider == ModelProvider.CODEX) {
            request.addProperty("stream", true);
        }

        String instructions = contentToText(body.getSystemInstruction());
        if (!instructions.isBlank()) {
            request.addProperty("instructions", instructions);
        }

        if (provider != ModelProvider.CODEX && body.getGenerationConfig() != null) {
            request.addProperty("temperature", body.getGenerationConfig().getTemperature());
        }

        addOpenAIReasoningConfig(request);

        request.add("input", buildOpenAIInput());

        JsonArray tools = buildOpenAITools();
        if (tools.size() > 0) {
            request.add("tools", tools);
            applyOpenAIToolChoice(request);
        }

        return request;
    }

    private void addOpenAIReasoningConfig(JsonObject request) {
        String effort = getOpenAIReasoningEffort();
        if (effort == null || effort.isBlank()) {
            return;
        }

        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", effort);
        request.add("reasoning", reasoning);
    }

    private String getOpenAIReasoningEffort() {
        FileConfiguration config = JavaPlugin.getPlugin(GPTGOD.class).getConfig();
        String effort = config.getString("openai-reasoning-effort");
        if (effort == null || effort.isBlank()) {
            effort = config.getString("thinking-effort");
        }
        return effort == null ? null : effort.trim().toLowerCase();
    }

    private JsonArray buildOpenAIInput() {
        JsonArray input = new JsonArray();
        for (Content content : body.getContents()) {
            appendOpenAIContent(input, content);
        }
        return input;
    }

    private void appendOpenAIContent(JsonArray input, Content content) {
        String role = content.getRole() == Role.model ? "assistant" : "user";
        JsonArray messageContent = new JsonArray();

        for (Part part : content.getParts()) {
            if (part.getOpenAIResponseItem() != null) {
                flushOpenAIMessageContent(input, role, messageContent);
                input.add(part.getOpenAIResponseItem());
                continue;
            }

            if (part.getText() != null) {
                addOpenAITextContent(messageContent, part.getText());
                continue;
            }

            if (part.getFileDataUri() != null) {
                addOpenAIFileContent(messageContent, part);
                continue;
            }

            flushOpenAIMessageContent(input, role, messageContent);

            FunctionCall functionCall = part.getFunctionCall();
            if (functionCall != null) {
                appendOpenAIFunctionCall(input, functionCall, role);
                continue;
            }

            FunctionResponse functionResponse = part.getFunctionResponse();
            if (functionResponse != null) {
                appendOpenAIFunctionResponse(input, functionResponse);
            }
        }

        flushOpenAIMessageContent(input, role, messageContent);
    }

    private void appendOpenAIFunctionCall(JsonArray input, FunctionCall functionCall, String fallbackRole) {
        if (functionCall.getCallId() == null || functionCall.getCallId().isBlank()) {
            addOpenAIMessage(input, fallbackRole,
                    "Example function call " + functionCall.getName() + " with arguments "
                            + functionCall.getArguments());
            return;
        }

        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call");
        item.addProperty("call_id", functionCall.getCallId());
        item.addProperty("name", functionCall.getName());
        item.addProperty("arguments", functionCall.getArguments().toString());
        input.add(item);
    }

    private void appendOpenAIFunctionResponse(JsonArray input, FunctionResponse functionResponse) {
        if (functionResponse.getCallId() == null || functionResponse.getCallId().isBlank()) {
            addOpenAIMessage(input, "user",
                    "Function " + functionResponse.getName() + " returned " + functionResponse.getResponse());
            return;
        }

        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call_output");
        item.addProperty("call_id", functionResponse.getCallId());
        item.addProperty("output", functionResponse.getResponse().toString());
        input.add(item);
    }

    private void flushOpenAIMessageContent(JsonArray input, String role, JsonArray messageContent) {
        if (messageContent.size() > 0) {
            addOpenAIMessage(input, role, messageContent);
        }
        while (messageContent.size() > 0) {
            messageContent.remove(0);
        }
    }

    private void addOpenAIMessage(JsonArray input, String role, JsonArray content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", content.deepCopy());
        input.add(message);
    }

    private void addOpenAIMessage(JsonArray input, String role, String text) {
        JsonArray content = new JsonArray();
        addOpenAITextContent(content, text);
        addOpenAIMessage(input, role, content);
    }

    private void addOpenAITextContent(JsonArray content, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "input_text");
        textContent.addProperty("text", text);
        content.add(textContent);
    }

    private void addOpenAIFileContent(JsonArray content, Part part) {
        String uri = part.getFileDataUri();
        String mimeType = part.getFileDataMimeType();
        if (uri == null || uri.isBlank()) {
            return;
        }

        if (mimeType != null && mimeType.startsWith("image/")) {
            JsonObject imageContent = new JsonObject();
            imageContent.addProperty("type", "input_image");
            imageContent.addProperty("image_url", uri);
            imageContent.addProperty("detail", "high");
            content.add(imageContent);
        }
    }

    private JsonArray buildOpenAITools() {
        JsonArray openAITools = new JsonArray();
        Tool[] tools = body.getTools();
        if (tools == null) {
            return openAITools;
        }

        for (Tool tool : tools) {
            for (FunctionDeclaration function : tool.getFunctions()) {
                JsonObject openAITool = new JsonObject();
                openAITool.addProperty("type", "function");
                openAITool.addProperty("name", function.getName());
                openAITool.addProperty("description", function.getDescription());
                openAITool.add("parameters", toOpenAISchema(function.getParameters()));
                openAITools.add(openAITool);
            }
        }
        return openAITools;
    }

    private JsonObject toOpenAISchema(net.bigyous.gptgodmc.GPT.Json.Schema schema) {
        JsonObject out = new JsonObject();
        out.addProperty("type", toOpenAIType(schema.getType()));

        if (schema.getDescription() != null && !schema.getDescription().isBlank()) {
            out.addProperty("description", schema.getDescription());
        }

        if (schema.getEnumValues() != null && !schema.getEnumValues().isEmpty()) {
            JsonArray enumValues = new JsonArray();
            for (String value : schema.getEnumValues()) {
                enumValues.add(value);
            }
            out.add("enum", enumValues);
        }

        if (schema.getItems() != null) {
            out.add("items", toOpenAISchema(schema.getItems()));
        }

        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (Map.Entry<String, net.bigyous.gptgodmc.GPT.Json.Schema> property : schema.getProperties()
                    .entrySet()) {
                properties.add(property.getKey(), toOpenAISchema(property.getValue()));
            }
            out.add("properties", properties);
            out.addProperty("additionalProperties", false);
        }

        if (schema.getRequiredFields() != null && !schema.getRequiredFields().isEmpty()) {
            JsonArray required = new JsonArray();
            for (String field : schema.getRequiredFields()) {
                required.add(field);
            }
            out.add("required", required);
        }

        return out;
    }

    private String toOpenAIType(net.bigyous.gptgodmc.GPT.Json.Schema.Type type) {
        return switch (type) {
        case STRING -> "string";
        case NUMBER -> "number";
        case INTEGER -> "integer";
        case BOOLEAN -> "boolean";
        case ARRAY -> "array";
        case OBJECT -> "object";
        default -> "object";
        };
    }

    private void applyOpenAIToolChoice(JsonObject request) {
        ToolConfig toolConfig = body.getToolConfig();
        if (toolConfig == null || toolConfig.getFunctionCallingConfig() == null) {
            return;
        }

        FunctionCallingConfig functionConfig = toolConfig.getFunctionCallingConfig();
        Mode mode = functionConfig.getMode();
        String[] allowedFunctionNames = functionConfig.getAllowedFunctionNames();

        if (mode == Mode.NONE) {
            request.addProperty("tool_choice", "none");
            return;
        }

        if (mode == Mode.AUTO) {
            request.addProperty("tool_choice", "auto");
            return;
        }

        if (mode == Mode.ANY && allowedFunctionNames != null && allowedFunctionNames.length == 1) {
            JsonObject toolChoice = new JsonObject();
            toolChoice.addProperty("type", "function");
            toolChoice.addProperty("name", allowedFunctionNames[0]);
            request.add("tool_choice", toolChoice);
            return;
        }

        if (mode == Mode.ANY) {
            request.addProperty("tool_choice", "required");
        }
    }

    private String contentToText(Content content) {
        if (content == null || content.getParts() == null) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        for (Part part : content.getParts()) {
            if (part.getText() != null) {
                appendText(out, part.getText());
            }
        }
        return out.toString();
    }

    private void appendText(StringBuilder out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (out.length() > 0) {
            out.append("\n");
        }
        out.append(text);
    }

    // DEBUG method
    public void checkRequestBody() {
        GPTGOD.LOGGER.info("POSTING " + gson.setPrettyPrinting().create().toJson(redactForLog(buildRequestBodyJson())));
    }

    private JsonElement redactForLog(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return element;
        }

        if (element.isJsonArray()) {
            JsonArray redacted = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                redacted.add(redactForLog(child));
            }
            return redacted;
        }

        if (!element.isJsonObject()) {
            return element.deepCopy();
        }

        JsonObject redacted = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if ("image_url".equals(key) || "file_data".equals(key)) {
                redacted.addProperty(key, "[redacted file data]");
            } else {
                redacted.add(key, redactForLog(entry.getValue()));
            }
        }
        return redacted;
    }

    private void processOpenAIResponse(String response, Map<String, FunctionDeclaration> functions) {
        response = normalizeOpenAIResponsePayload(response);
        JsonObject responseObject;
        try {
            responseObject = JsonParser.parseString(response).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            GPTGOD.LOGGER.error("error loading OpenAI response: invalid response payload", e);
            return;
        }

        if (responseObject.has("error") && responseObject.get("error").isJsonObject()) {
            GPTGOD.LOGGER.error("error loading OpenAI response: " + responseObject.get("error").toString());
            return;
        }

        JsonObject usage = getObject(responseObject, "usage");
        if (usage != null) {
            int inputTokenCount = getInt(usage, "input_tokens", getInt(usage, "prompt_tokens", 0));
            if (inputTokenCount > 0) {
                GPTGOD.LOGGER.info("setting GPT token count to " + inputTokenCount);
                this.totalTokens = inputTokenCount;
            }
        }

        JsonArray output = getArray(responseObject, "output");
        if (output == null || output.isEmpty()) {
            GPTGOD.LOGGER.warn("OpenAI response did not contain any output items");
            return;
        }

        ArrayList<Part> parts = new ArrayList<>();
        for (JsonElement itemElement : output) {
            if (!itemElement.isJsonObject()) {
                continue;
            }
            JsonObject item = itemElement.getAsJsonObject();
            String type = getString(item, "type");

            if ("message".equals(type)) {
                appendOpenAIMessageParts(parts, item);
            } else if ("function_call".equals(type)) {
                String name = getString(item, "name");
                String callId = getString(item, "call_id");
                JsonObject args = parseOpenAIArguments(getString(item, "arguments"));
                if (name != null && !name.isBlank()) {
                    parts.add(new Part(new FunctionCall(name, args, callId)));
                }
            } else if ("reasoning".equals(type)) {
                parts.add(new Part(item.deepCopy()));
            }
        }

        if (parts.isEmpty()) {
            GPTGOD.LOGGER.warn("OpenAI response did not contain usable message or function call output");
            return;
        }

        Content cont = new Content(Role.model, parts);
        this.addResponse(cont);

        ArrayList<Part> functionResponses = new ArrayList<>();
        for (Part call : parts) {
            FunctionCall func = call.getFunctionCall();
            if (func == null) {
                continue;
            }
            FunctionDeclaration declaration = functions.get(func.getName());
            JsonObject result = new JsonObject();

            if (declaration == null) {
                GPTGOD.LOGGER.warn("OpenAI tried to call unknown function " + func.getName());
                result.addProperty("status", "error");
                result.addProperty("message", "Unknown function: " + func.getName());
                functionResponses.add(new Part(new FunctionResponse(func.getName(), result, func.getCallId())));
                continue;
            }

            result = executeFunctionCall(func, declaration);
            functionResponses.add(new Part(new FunctionResponse(func.getName(), result, func.getCallId())));
        }

        if (!functionResponses.isEmpty()) {
            this.addContent(new Content(Role.user, functionResponses));
        }
    }

    private String normalizeOpenAIResponsePayload(String response) {
        if (response == null || !response.lines().anyMatch(line -> line.startsWith("data:"))) {
            return response;
        }

        JsonObject fallbackResponse = null;
        JsonArray fallbackOutput = new JsonArray();
        for (String line : response.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }

            String data = trimmed.substring("data:".length()).trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }

            JsonObject event;
            try {
                JsonElement parsed = JsonParser.parseString(data);
                if (!parsed.isJsonObject()) {
                    continue;
                }
                event = parsed.getAsJsonObject();
            } catch (JsonSyntaxException e) {
                GPTGOD.LOGGER.warn("Skipping invalid streamed OpenAI event: " + data);
                continue;
            }

            JsonObject completedResponse = getObject(event, "response");
            if (completedResponse != null && hasResponseOutput(completedResponse)) {
                return completedResponse.toString();
            }

            JsonObject item = getObject(event, "item");
            if (isCompletedOpenAIOutputItemEvent(event) && item != null && hasOpenAIOutputItemType(item)) {
                fallbackOutput.add(item.deepCopy());
            }
            if (fallbackResponse == null) {
                fallbackResponse = getObject(event, "response");
            }
        }

        if (fallbackResponse == null) {
            fallbackResponse = new JsonObject();
        } else {
            fallbackResponse = fallbackResponse.deepCopy();
        }
        JsonArray existingOutput = getArray(fallbackResponse, "output");
        if ((existingOutput == null || existingOutput.isEmpty()) && fallbackOutput.size() > 0) {
            fallbackResponse.add("output", fallbackOutput);
        }
        return fallbackResponse.toString();
    }

    private boolean isCompletedOpenAIOutputItemEvent(JsonObject event) {
        String eventType = getString(event, "type");
        if (!"response.output_item.done".equals(eventType)) {
            return false;
        }
        JsonObject item = getObject(event, "item");
        return item != null && "completed".equals(getString(item, "status"));
    }

    private boolean hasResponseOutput(JsonObject response) {
        JsonArray output = getArray(response, "output");
        return output != null && !output.isEmpty();
    }

    private boolean hasOpenAIOutputItemType(JsonObject item) {
        String type = getString(item, "type");
        return "message".equals(type) || "function_call".equals(type) || "reasoning".equals(type);
    }

    private void appendOpenAIMessageParts(ArrayList<Part> parts, JsonObject item) {
        JsonArray content = getArray(item, "content");
        if (content == null) {
            return;
        }

        for (JsonElement contentElement : content) {
            if (!contentElement.isJsonObject()) {
                continue;
            }
            JsonObject contentItem = contentElement.getAsJsonObject();
            String type = getString(contentItem, "type");
            if ("output_text".equals(type)) {
                String text = getString(contentItem, "text");
                if (text != null && !text.isBlank()) {
                    parts.add(new Part(text));
                }
            } else if ("refusal".equals(type)) {
                String refusal = getString(contentItem, "refusal");
                if (refusal != null && !refusal.isBlank()) {
                    parts.add(new Part("Model refusal: " + refusal));
                }
            }
        }
    }

    private JsonObject parseOpenAIArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new JsonObject();
        }

        try {
            JsonElement parsed = JsonParser.parseString(arguments);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            JsonObject wrapper = new JsonObject();
            wrapper.add("value", parsed);
            return wrapper;
        } catch (JsonSyntaxException e) {
            GPTGOD.LOGGER.warn("OpenAI returned invalid function arguments JSON: " + arguments);
            return new JsonObject();
        }
    }

    private JsonObject getObject(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private JsonArray getArray(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private String getString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && !element.isJsonNull() ? element.getAsString() : null;
    }

    private int getInt(JsonObject object, String name, int defaultValue) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : defaultValue;
    }

    private void processGeminiResponse(String response, Map<String, FunctionDeclaration> functions) {
        // shadow gson builder with gson
        Gson gson = this.gson.create();

        GenerateContentResponse responseObject = gson.fromJson(response, GenerateContentResponse.class);
        if (responseObject == null) {
            GPTGOD.LOGGER.error("error loading gemini response: empty response payload");
            return;
        }

        if (responseObject.isError()) {
            GPTGOD.LOGGER.error("error loading gemini response: " + responseObject.getError().toString());
            return;
        }

        // overwrite our rough guess of a total with the actual token total from the
        // last request
        int promptTokenCount = responseObject.getUsageMetadata().getPromptTokenCount();
        if (promptTokenCount > 0) {
            GPTGOD.LOGGER.info("setting GPT token count to " + promptTokenCount);
            this.totalTokens = promptTokenCount;
        }

        Candidate[] cands = responseObject.getCandidates();
        if (cands == null || cands.length < 1 || cands[0] == null) {
            GPTGOD.LOGGER.warn("Gemini response did not contain any candidates");
            return;
        }

        Content cont = cands[0].getContent();
        if (cont == null) {
            GPTGOD.LOGGER.warn("Gemini candidate did not contain content");
            return;
        }

        ArrayList<Part> parts = cont.getParts();
        if (parts == null) {
            return;
        }

        // add non null candidates to response history for multi-turn
        this.addResponse(cont); // THIS COMES BACK IN A NON-DETERMINISTIC ORDER!!! TODO FIX

        ArrayList<Part> functionResponses = new ArrayList<>();
        for (Part call : parts) {
            FunctionCall func = call.getFunctionCall();
            if (func == null) {
                continue;
            }
            FunctionDeclaration declaration = functions.get(func.getName());
            JsonObject result = new JsonObject();

            if (declaration == null) {
                GPTGOD.LOGGER.warn("Gemini tried to call unknown function " + func.getName());
                result.addProperty("status", "error");
                result.addProperty("message", "Unknown function: " + func.getName());
                functionResponses.add(new Part(new FunctionResponse(func.getName(), result)));
                continue;
            }

            result = executeFunctionCall(func, declaration);
            functionResponses.add(new Part(new FunctionResponse(func.getName(), result)));
        }

        if (!functionResponses.isEmpty()) {
            this.addContent(new Content(Role.user, functionResponses));
        }

    }

    private JsonObject executeFunctionCall(FunctionCall func, FunctionDeclaration declaration) {
        JsonObject result = new JsonObject();
        result.addProperty("function", func.getName());

        long outcomeCursor = ActionOutcomeTracker.mark();
        try {
            runFunctionOnMainThread(func, declaration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            GPTGOD.LOGGER.error("Interrupted while executing GPT function " + func.getName(), e);
            result.addProperty("status", "failed");
            result.addProperty("message", "Interrupted while executing " + func.getName());
            result.addProperty("retryGuidance", "Try again later or choose another action.");
            return result;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            GPTGOD.LOGGER.error("GPT function " + func.getName() + " failed while executing", cause);
            result.addProperty("status", "failed");
            result.addProperty("message", "Exception while executing " + func.getName() + ": " + cause.getMessage());
            result.addProperty("retryGuidance", "Correct the arguments or choose a safer alternative action.");
            return result;
        } catch (RuntimeException e) {
            GPTGOD.LOGGER.error("GPT function " + func.getName() + " failed before execution", e);
            result.addProperty("status", "failed");
            result.addProperty("message", "Exception while executing " + func.getName() + ": " + e.getMessage());
            result.addProperty("retryGuidance", "Correct the arguments or choose a safer alternative action.");
            return result;
        }

        List<OutcomeResult> outcomes = ActionOutcomeTracker.getSince(outcomeCursor);
        if (outcomes.isEmpty()) {
            result.addProperty("status", "unknown");
            result.addProperty("message", "The function ran but did not report a server outcome.");
            result.addProperty("retryGuidance", "Check Last God Action Outcomes before assuming it worked.");
            return result;
        }

        boolean failed = outcomes.stream().anyMatch(OutcomeResult::failed);
        result.addProperty("status", failed ? "failed" : "succeeded");
        result.addProperty("message", outcomes.stream()
                .map(OutcomeResult::describe)
                .reduce((left, right) -> left + " | " + right)
                .orElse("No server outcome detail"));
        if (failed) {
            result.addProperty("retryGuidance",
                    "Retry with corrected player, structure, item, entity, or block names before moving on.");
        }
        return result;
    }

    private void runFunctionOnMainThread(FunctionCall func, FunctionDeclaration declaration)
            throws InterruptedException, ExecutionException {
        Runnable runner = () -> {
            GPTGOD.LOGGER.info(
                    "Trying to execute function " + func.getName() + " from map with args: " + func.getArguments());
            declaration.runFunction(func.getArguments());
        };

        if (Bukkit.isPrimaryThread()) {
            runner.run();
            return;
        }

        Future<Void> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            runner.run();
            return null;
        });
        future.get();
    }

    // cleanup
    public void close() {
        gptTasks.close();
    }
}
