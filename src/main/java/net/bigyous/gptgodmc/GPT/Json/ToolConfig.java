package net.bigyous.gptgodmc.GPT.Json;

public class ToolConfig {
    FunctionCallingConfig functionCallingConfig;

    public ToolConfig(String[] allowedFunctionNames) {
        this.functionCallingConfig = new FunctionCallingConfig(allowedFunctionNames);
    }

    public ToolConfig(FunctionCallingConfig.Mode mode, String[] allowedFunctionNames) {
        this.functionCallingConfig = new FunctionCallingConfig(mode, allowedFunctionNames);
    }

    public FunctionCallingConfig getFunctionCallingConfig() {
        return functionCallingConfig;
    }
}
