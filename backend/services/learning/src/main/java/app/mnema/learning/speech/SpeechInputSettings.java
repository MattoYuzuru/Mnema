package app.mnema.learning.speech;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code learning.speech.*}: speech inputs (#298, {@code contracts/speech}).
 *
 * @param rateLimit how many inputs one account may send in {@code rateWindow} (the 21st is {@code 429 RATE_LIMITED})
 * @param rateWindow the window of the rate limit
 * @param ttl how long a row (the text and the metadata, never the audio) lives after its creation; the learner polls and copies the text within it
 * @param deadline how long one input may take from its creation to a terminal state; a later one is failed {@code UNAVAILABLE}
 * @param sweepInterval how often the worker claims queued inputs it was not woken for, fails the overdue ones and purges the expired ones
 * @param pollAfterMs the polling interval the {@code 202} suggests to the client
 * @param maxHints how many deck terms may accompany a clip as recognition hints
 * @param consentVersion the version of the consent text; a new version asks every account again
 */
@ConfigurationProperties("learning.speech")
public record SpeechInputSettings(@DefaultValue("20") int rateLimit, @DefaultValue("PT10M") Duration rateWindow, @DefaultValue("PT15M") Duration ttl,
                                  @DefaultValue("PT30S") Duration deadline, @DefaultValue("PT2S") Duration sweepInterval,
                                  @DefaultValue("400") int pollAfterMs, @DefaultValue("60") int maxHints,
                                  @DefaultValue("speech-2026-10") String consentVersion) {
    /** The contract's cap of one recording, in bytes. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    @ConstructorBinding
    public SpeechInputSettings {
        if (rateLimit < 1 || rateLimit > 1_000) throw new IllegalArgumentException("Invalid learning.speech.rate-limit");
        if (rateWindow == null || rateWindow.compareTo(Duration.ofSeconds(10)) < 0 || rateWindow.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid learning.speech.rate-window");
        }
        if (ttl == null || ttl.compareTo(Duration.ofMinutes(1)) < 0 || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Invalid learning.speech.ttl");
        }
        if (deadline == null || deadline.compareTo(Duration.ofSeconds(1)) < 0 || deadline.compareTo(ttl) > 0) {
            throw new IllegalArgumentException("Invalid learning.speech.deadline");
        }
        if (sweepInterval == null || sweepInterval.isNegative() || sweepInterval.isZero() || sweepInterval.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid learning.speech.sweep-interval");
        }
        if (pollAfterMs < 100 || pollAfterMs > 5_000) throw new IllegalArgumentException("Invalid learning.speech.poll-after-ms");
        if (maxHints < 0 || maxHints > 200) throw new IllegalArgumentException("Invalid learning.speech.max-hints");
        if (consentVersion == null || !consentVersion.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Invalid learning.speech.consent-version");
    }

    /** Defaults, for code that builds the pieces without Spring binding. */
    public static SpeechInputSettings defaults() {
        return new SpeechInputSettings(20, Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofSeconds(2), 400, 60, "speech-2026-10");
    }
}
