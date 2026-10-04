package app.mnema.learning.ai;

import java.util.Optional;

/**
 * Deterministic speech for local runs and CI: no network, no key; it exists only with {@code learning.ai.provider=stub}. The clip is a sine tone whose
 * pitch depends on the voice (female higher) and whose length follows the text (about 60 ms a character, at most 6 s), so the same text and voice are
 * the same bytes and a different voice is a different clip. Two markers in the text simulate failures: {@code [[stub:tts-down]]} (the provider is
 * unavailable) and {@code [[stub:tts-garbage]]} (bytes the media pipeline rejects).
 */
final class StubSpeechSynthesis implements SpeechSynthesis {
    static final String PROVIDER = "stub";
    static final String DOWN = "[[stub:tts-down]]";
    static final String GARBAGE = "[[stub:tts-garbage]]";
    private static final int RATE = 16_000;

    @Override
    public Optional<Identity> identity(String lang, String voice) { return Optional.of(new Identity(PROVIDER, "stub-tts", "1", "wav", voice)); }

    @Override
    public AiResult<Audio> synthesize(Request request) {
        if (request.text().contains(DOWN)) return AiResult.failed(new AiFailure.Transient("stub_down"));
        Identity identity = new Identity(PROVIDER, "stub-tts", "1", "wav", request.voice());
        if (request.text().contains(GARBAGE)) {
            return AiResult.ok(new Audio("this is not audio".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "audio/wav", request.text().length(), 1_000, identity, 0L));
        }
        int millis = Math.min(6_000, Math.max(300, request.text().length() * 60));
        double pitch = request.voice().equals("female") ? 440.0 : 220.0;
        short[] samples = new short[RATE * millis / 1000];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = (short) (Math.sin(2 * Math.PI * pitch * index / RATE) * 8_000);
        }
        return AiResult.ok(new Audio(Wav.write(samples, RATE), "audio/wav", request.text().length(), millis, identity, 0L));
    }
}
