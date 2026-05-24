package net.bigyous.gptgodmc.GPT.Json;

import com.google.gson.JsonObject;

import net.bigyous.gptgodmc.utils.GPTUtils;

public class FunctionResponse {
    private String name;
    private JsonObject response;
    private transient String callId;

    public FunctionResponse(String name, JsonObject response) {
        this.name = name;
        this.response = response;
    }

    public FunctionResponse(String name, JsonObject response, String callId) {
        this.name = name;
        this.response = response;
        this.callId = callId;
    }

    public String getName() {
        return name;
    }

    public JsonObject getResponse() {
        return response;
    }

    public String getCallId() {
        return callId;
    }

    public int calculateFunctionTokens() {
        return GPTUtils.countTokens(name) + GPTUtils.countTokens(response.toString());
    }
}
