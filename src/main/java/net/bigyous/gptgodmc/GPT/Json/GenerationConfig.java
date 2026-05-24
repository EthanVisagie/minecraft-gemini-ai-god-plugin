package net.bigyous.gptgodmc.GPT.Json;

public class GenerationConfig {
    // String[] stopSequences;
    // String responseMimeType;// ex application/json
    // Schema responseSchema;
    // int candidateCount;
    // int maxOutputTokens;
    double temperature = 1.0;
    // double topP;
    // int topK;
    // double presencePenalty;

    public GenerationConfig(double temp) {
        this.temperature = temp;
    }

    public double getTemperature() {
        return temperature;
    }
}
