package app.mnema.ai.provider.grok;

public record GrokSpeechRequest(
        String model,
        String input,
        String voice,
        String responseFormat
) {
}
