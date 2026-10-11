package app.mnema.learning.update.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The pins of a plan and the opaque cursor of its pages. {@code planVersion} is {@code sha256:} and the SHA-256 of the copy's head, the source's published revision, the base and a
 * digest of the decisions, so any change of the three heads or of one decision is a different plan; the apply command recomputes and compares it. A cursor embeds the plan version
 * it was cut from: with a different version it is stale (409), and a cursor of another copy or a malformed one is a 400.
 */
public final class PlanVersion {
    static final String PREFIX = "sha256:";

    private PlanVersion() { }

    /** The version of a plan with these pins (public for the apply command, which re-checks it inside the final transaction of the publication, Updates/4). */
    public static String of(UUID head, UUID published, UUID base, List<Decision> decisions) {
        StringBuilder rows = new StringBuilder();
        for (Decision decision : decisions) {
            rows.append(decision.kind()).append('|').append(decision.key()).append('|').append(decision.verdict()).append('|')
                    .append(decision.resolvedAbsent() ? "absent" : String.valueOf(decision.resolvedRevisionId())).append('|')
                    .append(decision.pendingBaseAbsent() ? "absent" : String.valueOf(decision.pendingBaseRevisionId())).append('\n');
        }
        return PREFIX + sha256(head + "|" + published + "|" + base + "|" + sha256(rows.toString()));
    }

    public static boolean valid(String version) {
        return version != null && version.length() == PREFIX.length() + 64 && version.startsWith(PREFIX) && version.substring(PREFIX.length()).matches("[0-9a-f]{64}");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", failure);
        }
    }

    /** A page position: the copy, the plan it was cut from and the index of the first unit of the page. */
    record Cursor(UUID deckId, String planVersion, int offset) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((deckId + "/" + planVersion.substring(PREFIX.length()) + "/" + offset).getBytes(StandardCharsets.US_ASCII));
        }

        /** {@code null} for no cursor; any malformed or foreign cursor is an {@link app.mnema.learning.platform.api.InvalidRequestException}. */
        static Cursor decode(String encoded, UUID deck) {
            if (encoded == null) return null;
            if (encoded.isEmpty() || encoded.length() > 200 || !encoded.matches("[A-Za-z0-9_-]+")) throw new app.mnema.learning.platform.api.InvalidRequestException();
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("/", -1);
                if (parts.length != 3 || !parts[1].matches("[0-9a-f]{64}") || !parts[2].matches("0|[1-9][0-9]{0,6}")) throw new app.mnema.learning.platform.api.InvalidRequestException();
                Cursor cursor = new Cursor(UUID.fromString(parts[0]), PREFIX + parts[1], Integer.parseInt(parts[2]));
                if (!cursor.encode().equals(encoded) || !cursor.deckId().equals(deck)) throw new app.mnema.learning.platform.api.InvalidRequestException();
                return cursor;
            } catch (IllegalArgumentException failure) {
                throw new app.mnema.learning.platform.api.InvalidRequestException();
            }
        }
    }
}
