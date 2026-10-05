package app.mnema.learning.ai;

import java.util.Optional;

/**
 * Deterministic transcription for local runs and CI: no network, no key, no look at the audio; it exists only with {@code learning.ai.provider=stub}.
 * The answer is a fixed sentence («Тестовая расшифровка голосового ввода.», or «Тестовый устный ответ.» for a spoken Study answer), the metered seconds
 * are the recorder's declared duration rounded up. A harness scripts the answer with the {@code X-Stub-Transcript} header of the speech input (percent
 * encoded UTF-8), which only this Stub ever reads ({@link Transcription.Request#script()}): an empty script is a clip without speech, the marker
 * {@code [[stub:stt-down]]} a provider that is unavailable, {@code [[stub:stt-garbled]]} (removed from the text) a low-confidence answer,
 * {@code [[stub:stt-unsupported]]} a clip the provider cannot decode and {@code [[stub:stt-slow]]} a provider that takes {@value #SLOW_MILLIS} ms.
 * The processing region is {@code RU}: nothing leaves the process.
 */
final class StubTranscription implements Transcription {
    static final String DOWN = "[[stub:stt-down]]";
    static final String GARBLED = "[[stub:stt-garbled]]";
    static final String UNSUPPORTED = "[[stub:stt-unsupported]]";
    static final String SLOW = "[[stub:stt-slow]]";
    static final long SLOW_MILLIS = 2_000;
    static final String DICTATION = "Тестовая расшифровка голосового ввода.";
    static final String ANSWER = "Тестовый устный ответ.";

    @Override
    public Optional<Region> region(String lang) { return Optional.of(Region.RU); }

    @Override
    public boolean scriptable() { return true; }

    @Override
    public AiResult<Transcript> transcribe(Request request) {
        String script = request.script();
        if (script != null && script.contains(DOWN)) return AiResult.failed(new AiFailure.Transient("stub_down"));
        if (script != null && script.contains(UNSUPPORTED)) return AiResult.failed(new AiFailure.InvalidOutput("unsupported_audio"));
        if (script != null && script.contains(SLOW)) {
            try {
                Thread.sleep(Math.min(SLOW_MILLIS, request.deadline().toMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return AiResult.failed(new AiFailure.Transient("interrupted"));
            }
        }
        String text = script == null ? request.purpose() == Purpose.STUDY_ANSWER ? ANSWER : DICTATION
                : script.replace(GARBLED, "").replace(SLOW, "").strip();
        boolean garbled = script != null && script.contains(GARBLED) && !text.isEmpty();
        return AiResult.ok(new Transcript(text, (request.declaredMs() + 999) / 1000, request.lang() == null ? "ru" : request.lang(), garbled));
    }
}
