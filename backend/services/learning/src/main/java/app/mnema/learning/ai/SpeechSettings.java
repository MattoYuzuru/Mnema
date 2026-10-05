package app.mnema.learning.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code learning.ai.tts.*}: speech synthesis (#297). The routes are {@code learning.ai.routes.tts} and {@code tts-ru}, the providers
 * {@code learning.ai.providers.google} and {@code yandex}; this holds what is neither: the prompt style, the voices an abstract voice maps to,
 * the SpeechKit folder and price, the cache lifetime and the text bound. Changing {@code version} (or a voice) changes every cache key.
 *
 * @param cacheTtl a cache entry unused for this long is evicted by the sweep (the media GC then reclaims its blobs)
 * @param maxText the longest text of one clip, in characters (the MBM bound of {@code ::audio})
 * @param version the prompt/synthesis version, a part of the cache key
 * @param style a short style instruction sent with the text; it must never carry personal data
 * @param yandexFolderId the Yandex Cloud folder SpeechKit bills to (an identifier, not a secret)
 * @param yandexRubPerMillionChars the SpeechKit price in roubles incl. VAT per one million characters
 * @param callTimeout the longest one provider call may take
 * @param lease how long a cache entry being synthesised is reserved for one step before another may take it over (shorter than the step deadline, so waiters behind a crashed worker can take over and still finish)
 */
@ConfigurationProperties("learning.ai.tts")
public record SpeechSettings(@DefaultValue("P180D") Duration cacheTtl, @DefaultValue("600") int maxText, @DefaultValue("v1") String version,
                             @DefaultValue("Read clearly for a language learner") String style,
                             @DefaultValue("Kore") String googleFemale, @DefaultValue("Charon") String googleMale,
                             @DefaultValue("alena") String yandexFemale, @DefaultValue("filipp") String yandexMale,
                             @DefaultValue("") String yandexFolderId, @DefaultValue("1342") BigDecimal yandexRubPerMillionChars,
                             @DefaultValue("PT45S") Duration callTimeout, @DefaultValue("PT75S") Duration lease) {
    private static final String NAME = "[A-Za-z0-9_-]{1,40}";

    @ConstructorBinding
    public SpeechSettings {
        if (cacheTtl == null || cacheTtl.compareTo(Duration.ofDays(1)) < 0 || cacheTtl.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("learning.ai.tts.cache-ttl must be between one day and ten years");
        }
        if (maxText < 1 || maxText > 600) throw new IllegalArgumentException("learning.ai.tts.max-text must be 1..600");
        if (version == null || !version.matches("[a-z0-9.]{1,16}")) throw new IllegalArgumentException("Invalid learning.ai.tts.version");
        if (style == null || style.isBlank() || style.length() > 200) throw new IllegalArgumentException("Invalid learning.ai.tts.style");
        for (String voice : new String[] {googleFemale, googleMale, yandexFemale, yandexMale}) {
            if (voice == null || !voice.matches(NAME)) throw new IllegalArgumentException("Invalid learning.ai.tts voice");
        }
        yandexFolderId = yandexFolderId == null ? "" : yandexFolderId.strip();
        if (!yandexFolderId.isEmpty() && !yandexFolderId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid learning.ai.tts.yandex-folder-id");
        }
        if (yandexRubPerMillionChars == null || yandexRubPerMillionChars.signum() < 0) {
            throw new IllegalArgumentException("Invalid learning.ai.tts.yandex-rub-per-million-chars");
        }
        if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero() || callTimeout.compareTo(Duration.ofMinutes(2)) > 0
                || lease == null || lease.compareTo(Duration.ofSeconds(30)) < 0 || lease.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Invalid learning.ai.tts timeouts");
        }
    }

    /** Defaults, for code that builds the pieces without Spring binding. */
    public static SpeechSettings defaults() {
        return new SpeechSettings(Duration.ofDays(180), 600, "v1", "Read clearly for a language learner", "Kore", "Charon", "alena", "filipp", "",
                BigDecimal.valueOf(1342), Duration.ofSeconds(45), Duration.ofSeconds(75));
    }
}
