package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * The strict, bounded bodies of the access routes: exactly the documented fields, no unknown or missing field, no coercion. Unicode that JSON cannot carry
 * is rejected by {@link ContentJsonReader}. A body is never echoed in an error (the redeem body holds a secret).
 */
final class AccessCommands {
    static final int MAX_REQUEST_BYTES = 1024;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_REQUEST_BYTES, 4, 16);

    private AccessCommands() { }

    /** {@code POST /decks/{id}/invites}: {@code maxUses} and {@code expiresInDays}, each an integer or null. */
    record InviteSpec(Integer maxUses, Integer expiresInDays) { }

    static InviteSpec invite(InputStream input) {
        JsonNode body = read(input, Set.of("maxUses", "expiresInDays"));
        return new InviteSpec(optionalInt(body.path("maxUses")), optionalInt(body.path("expiresInDays")));
    }

    /** {@code POST /invites:redeem}: {@code token}, any text (a malformed token is the same 404 as an unknown one, decided by the service). */
    static String token(InputStream input) {
        return text(read(input, Set.of("token")).path("token"));
    }

    /** {@code POST /decks/{id}/grants}: {@code username}. */
    static String username(InputStream input) {
        return text(read(input, Set.of("username")).path("username"));
    }

    private static JsonNode read(InputStream input, Set<String> fields) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
            if (!body.isObject() || body.size() != fields.size() || body.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
                throw new InvalidRequestException();
            }
            return body;
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static String text(JsonNode value) {
        if (!value.isString()) throw new InvalidRequestException();
        return value.stringValue(null);
    }

    private static Integer optionalInt(JsonNode value) {
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new InvalidRequestException();
        return value.intValue();
    }
}
