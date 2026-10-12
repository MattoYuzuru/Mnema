package app.mnema.learning.community;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** The opaque keyset position in the anomaly list: dismissed or open, the detection time and the deck id of the last flag of the previous page. */
record AnomalyCursor(boolean dismissed, Instant detectedAt, UUID deckId) {
    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ((dismissed ? "d" : "o") + "/" + detectedAt.getEpochSecond() + "." + detectedAt.getNano() + "/" + deckId).getBytes(StandardCharsets.US_ASCII));
    }

    /** @return the cursor, or null for none; anything malformed (or not exactly what {@link #encode} writes) is the 400 of the house schema */
    static AnomalyCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 120 || !encoded.matches("[A-Za-z0-9_-]+")) throw new InvalidRequestException();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("/", -1);
            String[] time = parts.length == 3 ? parts[1].split("\\.", -1) : null;
            if (time == null || !(parts[0].equals("d") || parts[0].equals("o")) || time.length != 2 || !time[0].matches("-?[0-9]{1,12}") || !time[1].matches("[0-9]{1,9}")) {
                throw new InvalidRequestException();
            }
            AnomalyCursor cursor = new AnomalyCursor(parts[0].equals("d"), Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])), UUID.fromString(parts[2]));
            if (!cursor.encode().equals(encoded)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw new InvalidRequestException();
        }
    }
}
