package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

record ExerciseCursor(UUID deckRevisionId, int nextOrdinal) {
    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (deckRevisionId + ":" + nextOrdinal).getBytes(StandardCharsets.US_ASCII));
    }

    static ExerciseCursor decode(String value) {
        if (value == null) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int split = decoded.indexOf(':');
            UUID revision = UUID.fromString(decoded.substring(0, split));
            int ordinal = Integer.parseInt(decoded.substring(split + 1));
            if (ordinal < 0 || ordinal > 100_000) throw new IllegalArgumentException();
            return new ExerciseCursor(revision, ordinal);
        } catch (RuntimeException exception) { throw new InvalidRequestException(); }
    }

    static int pageSize(String value) {
        if (value == null) return 20;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 100) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException exception) { throw new InvalidRequestException(); }
    }
}
