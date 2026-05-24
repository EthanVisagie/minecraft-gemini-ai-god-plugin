package net.bigyous.gptgodmc.GPT.Json;

import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonObject;

import net.bigyous.gptgodmc.utils.GPTUtils;

// a message data part. Can only be one of the types
// https://ai.google.dev/api/caching#Part
public class Part {
    @SerializedName("text")
    String text;

    // @SerializedName("inlineData")
    // private Blob inlineData;

    @SerializedName("functionCall")
    private FunctionCall functionCall;

    @SerializedName("functionResponse")
    private FunctionResponse functionResponse;

    @SerializedName("fileData")
    private FileData fileData;

    @SerializedName("thoughtSignature")
    private String thoughtSignature;

    private transient JsonObject openAIResponseItem;

    // @SerializedName("executableCode")
    // private ExecutableCode executableCode;

    // @SerializedName("codeExecutionResult")
    // private CodeExecutionResult codeExecutionResult;

    public String getText() {
        return text;
    }

    public FunctionCall getFunctionCall() {
        return functionCall;
    }

    public FunctionResponse getFunctionResponse() {
        return functionResponse;
    }

    public String getThoughtSignature() {
        return thoughtSignature;
    }

    public String getFileDataMimeType() {
        return fileData == null ? null : fileData.getMimeType();
    }

    public String getFileDataUri() {
        return fileData == null ? null : fileData.getFileUri();
    }

    public JsonObject getOpenAIResponseItem() {
        return openAIResponseItem;
    }

    // constructors for the various union types
    public Part(String text) {
        this.text = text;
    }

    public Part(FileData fileData) {
        this.fileData = fileData;
    }

    public Part(FunctionCall function) {
        this.functionCall = function;
    }

    public Part(FunctionResponse functionResponse) {
        this.functionResponse = functionResponse;
    }

    public Part(JsonObject openAIResponseItem) {
        this.openAIResponseItem = openAIResponseItem;
    }

    // calculates and returns the token count of this part
    public int countTokens() {
        int count = 0;
        if (text != null) {
            count += GPTUtils.countTokens(text);
        } else if (functionCall != null) {
            count += functionCall.calculateFunctionTokens();
        } else if (functionResponse != null) {
            count += functionResponse.calculateFunctionTokens();
        } else if (fileData != null) {
            count += fileData.countTokens();
        } else if (openAIResponseItem != null) {
            count += GPTUtils.countTokens(openAIResponseItem.toString());
        }

        if (thoughtSignature != null) {
            count += GPTUtils.countTokens(thoughtSignature);
        }

        return count;
    }
}

class FileData {
    private String mimeType;
    private String fileUri;

    // used
    private transient int tokenCount;

    public FileData(String mimeType, String fileUri) {
        this.mimeType = mimeType;
        this.fileUri = fileUri;
    }

    // https://ai.google.dev/gemini-api/docs/tokens?lang=python#multimodal-tokens
    public int countTokens() {
        if (this.mimeType.startsWith("image")) {
            // images have a fixed token count of 258 on gemini
            return 258;
        }

        // if all else fails return 256 just in case
        return 256;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getFileUri() {
        return fileUri;
    }

    public int getTokenCount() {
        return tokenCount;
    }

}
