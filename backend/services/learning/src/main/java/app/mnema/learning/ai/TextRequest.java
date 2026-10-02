package app.mnema.learning.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One text generation call.
 *
 * @param route the server-owned route that picks provider and model
 * @param segments ordered prompt segments; the cacheable ones must form a leading run so that the provider's prefix
 *                 cache sees byte-identical prefixes (stable layers first, volatile last)
 * @param output the contract the answer must satisfy
 * @param maxOutputTokens 1..65536
 * @param temperature 0..2
 * @param deadline the whole call, including backoff and fallback; at most one hour
 * @param userKey opaque, non-personal key made by {@link UserKeys}; sent to the provider for abuse isolation
 * @param listener optional; a non-null listener switches the call to streaming
 * @param stepId the generation step this call belongs to, null until generation steps exist
 * @param attempt the step attempt, at least 1
 */
public record TextRequest(AiRoute route, List<Segment> segments, OutputContract output, int maxOutputTokens,
                          double temperature, Duration deadline, OpaqueUserKey userKey, StreamListener listener,
                          UUID stepId, int attempt) {
    /** Prefix of the segment appended by {@link #withRepair}; the Stub uses it to recognize a repair request. */
    public static final String REPAIR_PREFIX = "<repair>";
    private static final int MAX_REPAIR_DETAIL = 2_000;

    public TextRequest {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(output, "output");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (segments.isEmpty()) throw new IllegalArgumentException("A request needs at least one segment");
        boolean volatileSeen = false;
        for (Segment segment : segments) {
            if (segment.cacheable() && volatileSeen) {
                throw new IllegalArgumentException("Cacheable segments must precede volatile ones");
            }
            volatileSeen |= !segment.cacheable();
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 65_536) throw new IllegalArgumentException("Invalid maxOutputTokens");
        if (!(temperature >= 0 && temperature <= 2)) throw new IllegalArgumentException("Invalid temperature");
        if (deadline == null || deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid deadline");
        }
        Objects.requireNonNull(userKey, "userKey");
        if (attempt < 1) throw new IllegalArgumentException("attempt starts at 1");
    }

    public enum Role { SYSTEM, USER }

    /** One prompt segment; {@code cacheable} marks the stable prefix. */
    public record Segment(Role role, String text, boolean cacheable) {
        public Segment {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(text, "text");
        }

        /** Lengths only: segments carry prompt text. */
        @Override
        public String toString() { return "Segment[role=" + role + ", chars=" + text.length() + ", cacheable=" + cacheable + "]"; }

        public static Segment system(String text, boolean cacheable) { return new Segment(Role.SYSTEM, text, cacheable); }

        public static Segment user(String text, boolean cacheable) { return new Segment(Role.USER, text, cacheable); }
    }

    /** No prompt text and no user key: segment sizes only. */
    @Override
    public String toString() {
        return "TextRequest[route=" + route + ", segments=" + segments + ", output=" + output + ", maxOutputTokens=" + maxOutputTokens
                + ", deadline=" + deadline + ", stepId=" + stepId + ", attempt=" + attempt + ", streaming=" + streaming() + "]";
    }

    public boolean streaming() { return listener != null; }

    /**
     * The same request with a volatile trailing segment that tells the model its previous answer was rejected. The
     * cacheable prefix is untouched, so a repair still hits the provider cache.
     */
    public TextRequest withRepair(String violations) {
        String detail = violations == null ? "" : violations.strip();
        if (detail.length() > MAX_REPAIR_DETAIL) detail = detail.substring(0, MAX_REPAIR_DETAIL);
        detail = detail.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        var repaired = new ArrayList<>(segments);
        repaired.add(Segment.user(REPAIR_PREFIX + "\nПредыдущий ответ не принят: " + detail
                + "\nОтветь заново с самого начала в том же формате, без пояснений.\n</repair>", false));
        return new TextRequest(route, repaired, output, maxOutputTokens, temperature, deadline, userKey, listener,
                stepId, attempt);
    }

    public TextRequest withListener(StreamListener other) {
        return new TextRequest(route, segments, output, maxOutputTokens, temperature, deadline, userKey, other,
                stepId, attempt);
    }

    public TextRequest withRoute(AiRoute other) {
        return new TextRequest(other, segments, output, maxOutputTokens, temperature, deadline, userKey, listener,
                stepId, attempt);
    }

    /** SHA-256 of everything that shapes the provider's answer, never of the user key; stored in the journal. */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, route.name());
            update(digest, output.name());
            update(digest, Integer.toString(maxOutputTokens));
            update(digest, Double.toString(temperature));
            for (Segment segment : segments) {
                update(digest, segment.role().name());
                update(digest, Boolean.toString(segment.cacheable()));
                update(digest, segment.text());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }
}
