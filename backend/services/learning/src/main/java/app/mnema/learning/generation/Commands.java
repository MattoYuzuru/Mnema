package app.mnema.learning.generation;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict request-body reading shared by the generation commands: bounded, duplicate-free JSON with exact field sets. */
final class Commands {
    /** Request bodies are at most 64 KiB and none of them carries a document. */
    static final int MAX_BODY_BYTES = 65_536;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_BODY_BYTES, 10, 4_096);

    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]{0,17}");
    private static final Pattern QUOTED = Pattern.compile("\"(0|[1-9][0-9]{0,17})\"");

    private Commands() { }

    /** @throws InvalidRequestException a body that is not one strict JSON object within the bound */
    static JsonNode read(byte[] raw) {
        try {
            return READER.read(raw);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** The body must have exactly the {@code required} fields and may add {@code optional} ones; others are a 400. */
    static void fields(JsonNode body, Set<String> required, Set<String> optional) {
        for (String name : body.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) throw new InvalidRequestException();
        }
        for (String name : required) {
            if (!body.has(name)) throw new InvalidRequestException();
        }
    }

    /** A canonical lowercase UUIDv4 or UUIDv7 command identifier. */
    static UUID commandId(JsonNode body) {
        return uuid(body, "commandId", true);
    }

    static UUID uuid(JsonNode body, String name, boolean command) {
        JsonNode node = body.get(name);
        if (node == null || !node.isString()) throw new InvalidRequestException();
        try {
            UUID id = UUID.fromString(node.stringValue());
            if (!id.toString().equals(node.stringValue())) throw new InvalidRequestException();
            return command ? UuidPolicy.requireCommandId(id) : UuidPolicy.requireEntityId(id, name);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** A decimal-string version of a body field (no sign, no leading zeros, at most 18 digits). */
    static long version(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || !node.isString() || !DECIMAL.matcher(node.stringValue()).matches()) throw new InvalidRequestException();
        return Long.parseLong(node.stringValue());
    }

    /** An entity id of a body field. */
    static UUID entity(JsonNode body, String name) {
        return uuid(body, name, false);
    }

    /**
     * The one quoted decimal of an {@code If-Match} header: missing is {@code 428}, anything else malformed (a list, a weak
     * validator, a wildcard, a leading zero) is {@code 400}.
     */
    static long ifMatch(List<String> headers) {
        if (headers == null || headers.isEmpty()) throw new VersionPreconditionRequiredException();
        if (headers.size() != 1 || !QUOTED.matcher(headers.getFirst()).matches()) throw new InvalidRequestException();
        String value = headers.getFirst();
        return Long.parseLong(value.substring(1, value.length() - 1));
    }

    /** The raw header as the receipt envelope records it: a replay is the same bytes, a different header is another command. */
    static String raw(List<String> headers) {
        return headers == null || headers.isEmpty() ? null : String.join(",", headers);
    }

    /**
     * A command identifier derived from a parent command and a name, so the catalog commands an approval or a hand-off
     * issues are the same commands when the request is repeated. The contract writes {@code uuidv5(commandId, artifactId)};
     * a version-5 value is not a legal command id of this platform ({@link UuidPolicy#requireCommandId}), so the same kind of
     * name-based hash (SHA-256 of parent and name) is shaped as a version-4 UUID.
     */
    static UUID derive(UUID parent, String name) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((parent + "/" + name).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
