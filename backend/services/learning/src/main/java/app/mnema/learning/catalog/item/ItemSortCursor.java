package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset position of the {@code sort=exerciseCount} list: the last returned {@code (exerciseCount, ordinal)}. It is bound
 * to the Deck revision, like {@link ItemCursor}, and the leading sort name makes a cursor of another sort unreadable
 * here (and an ordinal cursor unreadable by {@link ItemCursor}), so the two orders can never be mixed.
 */
record ItemSortCursor(UUID deckRevisionId, int exerciseCount, int ordinal) {
    private static final String SORT = "exerciseCount";

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (SORT + "/" + deckRevisionId + "/" + exerciseCount + "/" + ordinal).getBytes(StandardCharsets.US_ASCII));
    }

    static ItemSortCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 128 || !encoded.matches("[A-Za-z0-9_-]+")) throw new InvalidRequestException();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("/", -1);
            if (parts.length != 4 || !parts[0].equals(SORT) || !parts[2].matches("0|[1-9][0-9]{0,7}")
                    || !parts[3].matches("0|[1-9][0-9]{0,4}")) throw new InvalidRequestException();
            int ordinal = Integer.parseInt(parts[3]);
            if (ordinal >= ItemService.MAX_MEMBERS) throw new InvalidRequestException();
            ItemSortCursor result = new ItemSortCursor(ItemIds.entity(parts[1]), Integer.parseInt(parts[2]), ordinal);
            if (!result.encode().equals(encoded)) throw new InvalidRequestException();
            return result;
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }
}
