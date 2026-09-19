package app.mnema.learning.catalog.authoring;

import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringCommandsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void acceptsNewAndExistingDraftContextsWithValidatedNativeDocuments() {
        ObjectNode fresh = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("deckId", UUID.randomUUID().toString());
        fresh.set("document", document("текст"));
        assertThat(AuthoringCommands.draftCreate(bytes(fresh)).memberKey()).isNull();

        ObjectNode existing = fresh.deepCopy().put("commandId", UUID.randomUUID().toString())
                .put("memberKey", UUID.randomUUID().toString()).put("baseRevisionId", UUID.randomUUID().toString());
        assertThat(AuthoringCommands.draftCreate(bytes(existing)).baseRevisionId()).isNotNull();
    }

    @Test
    void rejectsAmbiguousDraftContextDuplicateKeysAndUnsupportedShape() {
        ObjectNode missingBase = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("deckId", UUID.randomUUID().toString()).put("memberKey", UUID.randomUUID().toString());
        missingBase.set("document", document("x"));
        assertThatThrownBy(() -> AuthoringCommands.draftCreate(bytes(missingBase)))
                .isInstanceOf(InvalidRequestException.class);
        String duplicate = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"commandId\":\""
                + UUID.randomUUID() + "\",\"deckId\":\"" + UUID.randomUUID() + "\",\"document\":"
                + document("x") + "}";
        assertThatThrownBy(() -> AuthoringCommands.draftCreate(stream(duplicate)))
                .isInstanceOf(InvalidRequestException.class);
        ObjectNode legacy = document("x");
        legacy.put("formatVersion", 2);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        body.set("document", legacy);
        assertThatThrownBy(() -> AuthoringCommands.draftUpdate(bytes(body)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void enforcesCaptureTextAndStrictConversionPreconditions() {
        ObjectNode empty = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("deckId", UUID.randomUUID().toString()).put("source", "lesson").put("text", "");
        assertThatThrownBy(() -> AuthoringCommands.captureCreate(bytes(empty)))
                .isInstanceOf(InvalidRequestException.class);
        ObjectNode large = empty.put("text", "ж".repeat(16_385));
        assertThatThrownBy(() -> AuthoringCommands.captureCreate(bytes(large)))
                .isInstanceOf(InvalidRequestException.class);

        ObjectNode conversion = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckVersion", "01").put("expectedDeckRevisionId", UUID.randomUUID().toString());
        conversion.set("document", document("x"));
        assertThatThrownBy(() -> AuthoringCommands.captureConvert(bytes(conversion)))
                .isInstanceOf(InvalidRequestException.class);
        conversion.put("expectedDeckVersion", "0").put("ordinal", 100_000);
        assertThatThrownBy(() -> AuthoringCommands.captureConvert(bytes(conversion)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void cursorAndVersionHeadersAreCanonicalAndBounded() {
        assertThat(AuthoringCursor.pageSize(null)).isEqualTo(20);
        assertThatThrownBy(() -> AuthoringCursor.pageSize("101")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> AuthoringCursor.decode("not+url-safe"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> AuthoringCursor.decode("YQ"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> AuthoringPrecondition.read(java.util.Collections.enumeration(java.util.List.of("0"))))
                .isInstanceOf(InvalidRequestException.class);
    }

    static ObjectNode document(String text) {
        ObjectNode root = JSON.createObjectNode().put("id", UUID.randomUUID().toString())
                .put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode paragraph = root.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode value = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        value.putObject("attrs").put("text", text); value.putArray("content");
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1); document.set("root", root);
        return document;
    }

    static ByteArrayInputStream bytes(com.fasterxml.jackson.databind.JsonNode value) {
        return stream(value.toString());
    }

    static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
