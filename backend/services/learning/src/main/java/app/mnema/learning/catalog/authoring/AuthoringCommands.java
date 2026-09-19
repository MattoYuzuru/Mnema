package app.mnema.learning.catalog.authoring;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

final class AuthoringCommands {
    private static final int MAX_DRAFT_REQUEST = 1_100_000;
    private static final int MAX_CAPTURE_REQUEST = 1_100_000;
    private static final ContentJsonReader DRAFT_JSON = new ContentJsonReader(MAX_DRAFT_REQUEST, 132, 250_600);
    private static final ContentJsonReader CAPTURE_JSON = new ContentJsonReader(MAX_CAPTURE_REQUEST, 132, 250_600);
    private static final ContentJsonReader CAPTURE_SMALL_JSON = new ContentJsonReader(65_536, 8, 64);
    private static final ContentJsonReader BOOLEAN_JSON = new ContentJsonReader(128, 2, 8);
    private static final CanonicalJsonHasher CANONICAL = new CanonicalJsonHasher();

    private AuthoringCommands() { }

    record DraftCreate(UUID commandId, UUID deckId, UUID memberKey, UUID baseRevisionId,
                       NativeDocument document) {
        ObjectNode envelope() {
            ObjectNode value = JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString());
            if (memberKey == null) value.putNull("memberKey"); else value.put("memberKey", memberKey.toString());
            if (baseRevisionId == null) value.putNull("baseRevisionId");
            else value.put("baseRevisionId", baseRevisionId.toString());
            value.set("document", document.toJson());
            return value;
        }
    }

    record DraftUpdate(UUID commandId, NativeDocument document) {
        ObjectNode envelope(UUID draftId, long expected) {
            ObjectNode value = JsonNodeFactory.instance.objectNode().put("draftId", draftId.toString())
                    .put("expectedVersion", Long.toString(expected));
            value.set("document", document.toJson());
            return value;
        }
    }

    record CaptureCreate(UUID commandId, UUID deckId, String source, String text) {
        ObjectNode envelope() {
            return JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString())
                    .put("source", source).put("text", text);
        }
    }

    record CaptureUpdate(String source, String text) { }
    record CaptureArchive(boolean archived) { }

    record CaptureConvert(UUID commandId, long expectedDeckVersion, UUID expectedDeckRevisionId,
                          Integer ordinal, NativeDocument document, ObjectNode envelope) {
        @Override public ObjectNode envelope() { return envelope.deepCopy(); }
    }

    static DraftCreate draftCreate(InputStream input) {
        JsonNode body = read(input, DRAFT_JSON, MAX_DRAFT_REQUEST);
        fields(body, Set.of("commandId", "deckId", "document"),
                Set.of("commandId", "deckId", "memberKey", "baseRevisionId", "document"));
        UUID member = body.has("memberKey") ? id(body, "memberKey") : null;
        UUID base = body.has("baseRevisionId") ? id(body, "baseRevisionId") : null;
        NativeValue nativeValue = nativeValue(body.path("document"));
        return new DraftCreate(command(body), id(body, "deckId"), member, base, nativeValue.document());
    }

    static DraftUpdate draftUpdate(InputStream input) {
        JsonNode body = read(input, DRAFT_JSON, MAX_DRAFT_REQUEST);
        fields(body, Set.of("commandId", "document"));
        NativeValue nativeValue = nativeValue(body.path("document"));
        return new DraftUpdate(command(body), nativeValue.document());
    }

    static CaptureCreate captureCreate(InputStream input) {
        JsonNode body = read(input, CAPTURE_SMALL_JSON, 65_536);
        fields(body, Set.of("commandId", "deckId", "source", "text"));
        return new CaptureCreate(command(body), id(body, "deckId"), text(body, "source", 2048),
                text(body, "text", 32_768));
    }

    static CaptureUpdate captureUpdate(InputStream input) {
        JsonNode body = read(input, CAPTURE_SMALL_JSON, 65_536);
        fields(body, Set.of("source", "text"));
        return new CaptureUpdate(text(body, "source", 2048), text(body, "text", 32_768));
    }

    static CaptureArchive captureArchive(InputStream input) {
        JsonNode body = read(input, BOOLEAN_JSON, 128);
        fields(body, Set.of("archived"));
        if (!body.path("archived").isBoolean()) throw new InvalidRequestException();
        return new CaptureArchive(body.path("archived").booleanValue());
    }

    static CaptureConvert captureConvert(InputStream input) {
        JsonNode body = read(input, CAPTURE_JSON, MAX_CAPTURE_REQUEST);
        fields(body, Set.of("commandId", "expectedDeckVersion", "expectedDeckRevisionId", "document"),
                Set.of("commandId", "expectedDeckVersion", "expectedDeckRevisionId", "ordinal", "document"));
        if (!body.path("expectedDeckVersion").isTextual()
                || !body.path("expectedDeckVersion").textValue().matches("0|[1-9][0-9]{0,18}")) {
            throw new InvalidRequestException();
        }
        long deckVersion;
        try {
            deckVersion = Long.parseLong(body.path("expectedDeckVersion").textValue());
            if (deckVersion == Long.MAX_VALUE) throw new InvalidRequestException();
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException();
        }
        Integer ordinal = null;
        if (body.has("ordinal")) {
            JsonNode value = body.path("ordinal");
            if (!value.isIntegralNumber() || !value.canConvertToInt()
                    || value.intValue() < 0 || value.intValue() >= 100_000) throw new InvalidRequestException();
            ordinal = value.intValue();
        }
        NativeValue nativeValue = nativeValue(body.path("document"));
        ObjectNode envelope = ((ObjectNode) body).deepCopy();
        envelope.remove("commandId");
        return new CaptureConvert(command(body), deckVersion, id(body, "expectedDeckRevisionId"),
                ordinal, nativeValue.document(), envelope);
    }

    private static JsonNode read(InputStream input, ContentJsonReader reader, int maxBytes) {
        try {
            return reader.read(input.readNBytes(maxBytes + 1));
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static NativeValue nativeValue(JsonNode value) {
        if (!value.isObject()) throw new InvalidRequestException();
        try {
            byte[] canonical = CANONICAL.canonicalBytes(value);
            return new NativeValue(new NativeDocumentReader().read(canonical));
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static UUID command(JsonNode body) {
        return AuthoringIds.command(text(body, "commandId", 36));
    }

    private static UUID id(JsonNode body, String name) {
        return AuthoringIds.entity(text(body, name, 36));
    }

    private static String text(JsonNode body, String name, int maxBytes) {
        JsonNode value = body.path(name);
        if (!value.isTextual() || value.textValue().isEmpty()
                || value.textValue().getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new InvalidRequestException();
        return value.textValue();
    }

    @SafeVarargs
    private static void fields(JsonNode node, Set<String>... alternatives) {
        if (!node.isObject()) throw new InvalidRequestException();
        Set<String> actual = node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (Set<String> candidate : alternatives) if (actual.equals(candidate)) return;
        throw new InvalidRequestException();
    }

    private record NativeValue(NativeDocument document) { }
}
