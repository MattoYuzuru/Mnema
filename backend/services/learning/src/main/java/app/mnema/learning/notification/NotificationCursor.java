package app.mnema.learning.notification;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Keyset location of the next page, not an authorization capability. {@code D} continues the newest-first listing
 * towards older notifications, {@code A} continues a catch-up listing towards newer ones.
 */
record NotificationCursor(boolean ascending, long seq) {
    private static final String SEQ = "0|[1-9][0-9]{0,17}";

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ((ascending ? "A" : "D") + seq).getBytes(StandardCharsets.US_ASCII));
    }

    static NotificationCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 64 || !encoded.matches("[A-Za-z0-9_-]+")) {
            throw new InvalidRequestException();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
            if (value.length() < 2 || (value.charAt(0) != 'A' && value.charAt(0) != 'D')) throw new InvalidRequestException();
            NotificationCursor cursor = new NotificationCursor(value.charAt(0) == 'A', parseSeq(value.substring(1)));
            if (!cursor.encode().equals(encoded)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** A decimal sequence number as the wire carries it: no sign, no leading zeros. */
    static long parseSeq(String value) {
        if (value == null || !value.matches(SEQ)) throw new InvalidRequestException();
        return Long.parseLong(value);
    }

    static int pageSize(String value) {
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]{0,2}")) throw new InvalidRequestException();
        int size = Integer.parseInt(value);
        if (size > 100) throw new InvalidRequestException();
        return size;
    }
}
