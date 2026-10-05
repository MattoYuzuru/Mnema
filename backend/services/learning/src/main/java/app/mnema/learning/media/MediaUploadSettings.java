package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The upload policy is deliberately separate from Study limits and verified-blob retention. */
@Component
public final class MediaUploadSettings {
    private static final long MAX_SINGLE_REQUEST = 5_000_000_000L;
    private static final long MAX_S3_OBJECT = 5L * 1024 * 1024 * 1024 * 1024;
    private static final Map<String, Set<String>> TYPES = Map.of(
            "image", Set.of("image/jpeg", "image/png", "image/webp", "image/gif"),
            "audio", Set.of("audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/webm",
                    "audio/webm;codecs=opus"),
            "video", Set.of("video/mp4", "video/quicktime", "video/webm",
                    "video/webm;codecs=vp8,opus", "video/webm;codecs=vp9,opus"));

    final URI endpoint;
    final String region;
    final String bucket;
    final String accessKey;
    final String secretKey;
    final long maxImageBytes;
    final long maxAudioBytes;
    final long maxVideoBytes;
    final long maxReservedBytes;
    final int maxActiveUploads;
    final long multipartThreshold;
    final long partSize;
    final Duration urlTtl;
    final Duration sessionTtl;
    final Duration storageTimeout;
    final Duration finalizeLease;
    final Duration cleanupGrace;

    public MediaUploadSettings(
            @Value("${learning.media.upload.endpoint:https://storage.yandexcloud.net}") URI endpoint,
            @Value("${learning.media.upload.region:ru-central1}") String region,
            @Value("${learning.media.upload.bucket:}") String bucket,
            @Value("${learning.media.upload.access-key:}") String accessKey,
            @Value("${learning.media.upload.secret-key:}") String secretKey,
            @Value("${learning.media.upload.allow-loopback-http:false}") boolean loopback,
            @Value("${learning.media.upload.max-image-bytes:67108864}") long maxImageBytes,
            @Value("${learning.media.upload.max-audio-bytes:536870912}") long maxAudioBytes,
            @Value("${learning.media.upload.max-video-bytes:4294967296}") long maxVideoBytes,
            @Value("${learning.media.upload.max-reserved-bytes:8589934592}") long maxReservedBytes,
            @Value("${learning.media.upload.max-active-uploads:3}") int maxActiveUploads,
            @Value("${learning.media.upload.multipart-threshold:104857600}") long multipartThreshold,
            @Value("${learning.media.upload.part-size:16777216}") long partSize,
            @Value("${learning.media.upload.url-ttl:PT15M}") Duration urlTtl,
            @Value("${learning.media.upload.session-ttl:PT24H}") Duration sessionTtl,
            @Value("${learning.media.upload.storage-timeout:PT10M}") Duration storageTimeout,
            @Value("${learning.media.upload.finalize-lease:PT15M}") Duration finalizeLease,
            @Value("${learning.media.upload.cleanup-grace:PT15M}") Duration cleanupGrace) {
        Objects.requireNonNull(endpoint, "Media upload endpoint");
        Objects.requireNonNull(urlTtl, "Media upload URL TTL");
        Objects.requireNonNull(sessionTtl, "Media upload session TTL");
        Objects.requireNonNull(storageTimeout, "Media upload storage timeout");
        Objects.requireNonNull(finalizeLease, "Media upload finalize lease");
        Objects.requireNonNull(cleanupGrace, "Media upload cleanup grace");
        boolean plainEndpoint = endpoint.getHost() != null && endpoint.getRawUserInfo() == null
                && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null
                && (endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty()
                    || endpoint.getRawPath().equals("/"));
        boolean localHttp = loopback && "http".equals(endpoint.getScheme()) && plainEndpoint
                && Set.of("localhost", "127.0.0.1", "::1").contains(endpoint.getHost());
        if ((!"https".equals(endpoint.getScheme()) || !plainEndpoint) && !localHttp) {
            throw new IllegalArgumentException("Media upload endpoint requires HTTPS");
        }
        if (region.isBlank() || maxImageBytes < 1 || maxAudioBytes < 1 || maxVideoBytes < 1
                || maxImageBytes > MAX_S3_OBJECT || maxAudioBytes > MAX_S3_OBJECT
                || maxVideoBytes > MAX_S3_OBJECT
                || maxReservedBytes < Math.max(maxVideoBytes, Math.max(maxAudioBytes, maxImageBytes))
                || maxActiveUploads < 1 || maxActiveUploads > 100
                || multipartThreshold < 5_242_880 || multipartThreshold > maxVideoBytes
                || multipartThreshold > MAX_SINGLE_REQUEST
                || partSize < 5_242_880 || partSize > MAX_SINGLE_REQUEST
                || Math.ceilDiv(maxImageBytes, partSize) > 10_000
                || Math.ceilDiv(maxAudioBytes, partSize) > 10_000
                || Math.ceilDiv(maxVideoBytes, partSize) > 10_000
                || urlTtl.isNegative() || urlTtl.isZero() || urlTtl.compareTo(Duration.ofHours(1)) > 0
                || sessionTtl.compareTo(urlTtl) <= 0 || sessionTtl.compareTo(Duration.ofDays(7)) > 0
                || storageTimeout.compareTo(Duration.ofMinutes(1)) < 0
                || storageTimeout.compareTo(Duration.ofMinutes(30)) > 0
                || finalizeLease.compareTo(storageTimeout) <= 0
                || finalizeLease.compareTo(Duration.ofHours(1)) > 0
                || cleanupGrace.isNegative() || cleanupGrace.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("Invalid media upload policy");
        }
        this.endpoint = endpoint;
        this.region = region;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.maxImageBytes = maxImageBytes;
        this.maxAudioBytes = maxAudioBytes;
        this.maxVideoBytes = maxVideoBytes;
        this.maxReservedBytes = maxReservedBytes;
        this.maxActiveUploads = maxActiveUploads;
        this.multipartThreshold = multipartThreshold;
        this.partSize = partSize;
        this.urlTtl = urlTtl;
        this.sessionTtl = sessionTtl;
        this.storageTimeout = storageTimeout;
        this.finalizeLease = finalizeLease;
        this.cleanupGrace = cleanupGrace;
    }

    boolean configured() {
        return !bucket.isBlank() && !accessKey.isBlank() && !secretKey.isBlank();
    }

    /** Safe browser preflight values; upload validation below remains authoritative. */
    public ClientPolicy clientPolicy() {
        return new ClientPolicy(maxImageBytes, maxAudioBytes, maxVideoBytes);
    }

    public record ClientPolicy(long maxImageBytes, long maxAudioBytes, long maxVideoBytes) { }

    /**
     * As {@link #validate} for bytes the server itself obtained: a synthesised clip may also be a PCM WAV (Gemini speech returns nothing else). The
     * browser allowlist stays as it was; the media worker accepts a WAV source only for an asset of origin {@code generated}.
     */
    void validateGenerated(String kind, String mime, long length) {
        validate(kind, "audio".equals(kind) && "audio/wav".equals(mime) ? "audio/mpeg" : mime, length);
    }

    void validate(String kind, String mime, long length) {
        if (kind == null || mime == null || !TYPES.getOrDefault(kind, Set.of()).contains(mime)
                || length < 1) throw new InvalidRequestException();
        long limit = switch (kind) {
            case "image" -> maxImageBytes;
            case "audio" -> maxAudioBytes;
            case "video" -> maxVideoBytes;
            default -> throw new InvalidRequestException();
        };
        if (length > limit) throw new ResourceLimitExceededException();
    }

    boolean multipart(long length) { return length >= multipartThreshold; }

    int partCount(long length) { return Math.toIntExact((length + partSize - 1) / partSize); }

    long expectedPartLength(long length, int part) {
        return Math.min(partSize, length - (long) (part - 1) * partSize);
    }
}
