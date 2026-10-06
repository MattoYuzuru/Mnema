package app.mnema.learning.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code learning.ai.stt.*}: speech-to-text (#298). The routes are {@code learning.ai.routes.stt} and {@code stt-ru}, the providers
 * {@code learning.ai.providers.selfhost} (an OpenAI-compatible container) and {@code google}; this holds what is neither.
 *
 * @param callTimeout the longest one provider call may take (the whole input has {@code learning.speech.deadline})
 * @param minAvgLogprob a self-hosted answer whose mean segment {@code avg_logprob} is below this is {@code garbled} (Whisper's own heuristic is -1)
 * @param maxNoSpeechProb a self-hosted answer with text whose strongest segment {@code no_speech_prob} is above this is {@code garbled}
 * @param geminiPrompt the instruction sent with the audio to a Gemini model that is not the dedicated transcription model
 */
@ConfigurationProperties("learning.ai.stt")
public record SttSettings(@DefaultValue("PT25S") Duration callTimeout, @DefaultValue("-1.0") double minAvgLogprob,
                          @DefaultValue("0.6") double maxNoSpeechProb,
                          @DefaultValue("Transcribe this audio verbatim, in the language that is spoken. Do not translate, summarise or answer it. "
                                  + "Output only the transcript text. If there is no speech, output nothing.") String geminiPrompt) {
    @ConstructorBinding
    public SttSettings {
        if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero() || callTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Invalid learning.ai.stt.call-timeout");
        }
        if (minAvgLogprob > 0 || minAvgLogprob < -10) throw new IllegalArgumentException("Invalid learning.ai.stt.min-avg-logprob");
        if (maxNoSpeechProb < 0 || maxNoSpeechProb > 1) throw new IllegalArgumentException("Invalid learning.ai.stt.max-no-speech-prob");
        if (geminiPrompt == null || geminiPrompt.isBlank() || geminiPrompt.length() > 400) throw new IllegalArgumentException("Invalid learning.ai.stt.gemini-prompt");
    }

    /** Defaults, for code that builds the pieces without Spring binding. */
    public static SttSettings defaults() {
        return new SttSettings(Duration.ofSeconds(25), -1.0, 0.6, "Transcribe this audio verbatim, in the language that is spoken. Do not translate, "
                + "summarise or answer it. Output only the transcript text. If there is no speech, output nothing.");
    }
}
