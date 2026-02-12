package app.mnema.ai.provider.gemini;

public record GeminiSpeechRequest(
        String model,
        String input,
        String voice,
        String responseMimeType
) {
}
