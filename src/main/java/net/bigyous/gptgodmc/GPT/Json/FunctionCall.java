package net.bigyous.gptgodmc.GPT.Json;

import com.google.gson.JsonObject;

import net.bigyous.gptgodmc.utils.GPTUtils;

public class FunctionCall {
    private String name;
    private JsonObject args;
    private transient String callId;

    public FunctionCall(String name, JsonObject args) {
        this.name = name;
        this.args = args;
    }

    public FunctionCall(String name, JsonObject args, String callId) {
        this.name = name;
        this.args = args;
        this.callId = callId;
    }

    public JsonObject getArguments() {
        return args;
    }

    public String getName() {
        return name;
    }

    public String getCallId() {
        return callId;
    }

    public int calculateFunctionTokens() {
        // todo: this calculates the arguments in json form at the moment so it might be
        // over estimating the token count
        return GPTUtils.countTokens(name) + GPTUtils.countTokens(args.toString());
    }
}
