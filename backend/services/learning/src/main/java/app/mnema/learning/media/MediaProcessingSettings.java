package app.mnema.learning.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/** Local worker controls; upload admission is configured independently in MediaUploadSettings. */
@Component
final class MediaProcessingSettings {
    private static final Pattern IMAGE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:/@-]{0,255}");

    final boolean enabled;
    final Path workRoot;
    final String dockerBinary;
    final String image;
    final Duration workerTimeout;
    final Duration lease;
    final Duration heartbeat;
    final Duration retryBase;
    final Duration retryMaximum;
    final int maxAttempts;
    final int maxParallel;
    final long maxAudioDurationMs;
    final long maxVideoDurationMs;

    MediaProcessingSettings(
            @Value("${learning.media.processing.enabled:false}") boolean enabled,
            @Value("${learning.media.processing.work-root:}") String workRoot,
            @Value("${learning.media.processing.docker-binary:docker}") String dockerBinary,
            @Value("${learning.media.processing.image:mnema-media-worker:local}") String image,
            @Value("${learning.media.processing.worker-timeout:PT30M}") Duration workerTimeout,
            @Value("${learning.media.processing.lease:PT2M}") Duration lease,
            @Value("${learning.media.processing.heartbeat:PT30S}") Duration heartbeat,
            @Value("${learning.media.processing.retry-base:PT1M}") Duration retryBase,
            @Value("${learning.media.processing.retry-maximum:PT30M}") Duration retryMaximum,
            @Value("${learning.media.processing.max-attempts:5}") int maxAttempts,
            @Value("${learning.media.processing.max-parallel:2}") int maxParallel,
            @Value("${learning.media.processing.max-audio-duration:PT1H}") Duration maxAudioDuration,
            @Value("${learning.media.processing.max-video-duration:PT5M}") Duration maxVideoDuration) {
        Path root = Path.of(workRoot.isBlank()
                ? Path.of(System.getProperty("user.home"), ".mnema", "media-processing").toString()
                : workRoot).toAbsolutePath().normalize();
        if (dockerBinary.isBlank() || dockerBinary.startsWith("-") || dockerBinary.contains("\u0000")
                || !IMAGE.matcher(image).matches() || workerTimeout.compareTo(Duration.ofMinutes(1)) < 0
                || workerTimeout.compareTo(Duration.ofMinutes(30)) > 0
                || lease.compareTo(Duration.ofMinutes(1)) < 0 || lease.compareTo(Duration.ofMinutes(15)) > 0
                || heartbeat.compareTo(Duration.ofSeconds(5)) < 0
                || heartbeat.multipliedBy(2).compareTo(lease) >= 0
                || retryBase.compareTo(Duration.ofSeconds(10)) < 0
                || retryMaximum.compareTo(retryBase) < 0 || retryMaximum.compareTo(Duration.ofHours(2)) > 0
                || maxAttempts < 1 || maxAttempts > 10 || maxParallel < 1 || maxParallel > 4
                || maxAudioDuration.isNegative() || maxAudioDuration.isZero()
                || maxAudioDuration.compareTo(Duration.ofHours(1)) > 0
                || maxVideoDuration.isNegative() || maxVideoDuration.isZero()
                || maxVideoDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid media processing policy");
        }
        this.enabled = enabled;
        this.workRoot = root;
        this.dockerBinary = dockerBinary;
        this.image = image;
        this.workerTimeout = workerTimeout;
        this.lease = lease;
        this.heartbeat = heartbeat;
        this.retryBase = retryBase;
        this.retryMaximum = retryMaximum;
        this.maxAttempts = maxAttempts;
        this.maxParallel = maxParallel;
        this.maxAudioDurationMs = maxAudioDuration.toMillis();
        this.maxVideoDurationMs = maxVideoDuration.toMillis();
    }

    Duration delay(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration delay = retryBase.multipliedBy(multiplier);
        return delay.compareTo(retryMaximum) > 0 ? retryMaximum : delay;
    }

    long maxDurationMs(String kind) {
        return switch (kind) {
            case "audio" -> maxAudioDurationMs;
            case "video" -> maxVideoDurationMs;
            case "image" -> 0;
            default -> throw new IllegalArgumentException("Unknown media kind");
        };
    }
}
