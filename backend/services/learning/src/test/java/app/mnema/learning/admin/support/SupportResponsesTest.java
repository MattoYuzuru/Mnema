package app.mnema.learning.admin.support;

import app.mnema.learning.platform.json.ContentJsonReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportResponsesTest {
    @Test void sharedFixturesPreserveLargeIdentifiersAndKeepNotesPrivate() throws IOException {
        JsonNode fixture = fixture();
        SupportResponses.validate(fixture.path("tickets"), false, false);
        SupportResponses.validate(fixture.path("conversation"), false, true);
        SupportResponses.validate(fixture.path("acknowledgement"), true, false);
        assertThat(fixture.path("tickets").path("entries").get(0).path("userId").stringValue()).isEqualTo("9007199254740995");
        assertThat(fixture.path("conversation").path("messages").get(1).path("direction").stringValue()).isEqualTo("note");
    }

    @Test void unexpectedSecretsInvalidStatusOrCredentialsCannotCrossTheProjection() throws IOException {
        JsonNode fixture = fixture();
        var conversation = (ObjectNode) fixture.path("conversation").deepCopy();
        ((ObjectNode) conversation.path("messages").get(0).path("attachment")).put("file_id", "private-reference");
        assertThatThrownBy(() -> SupportResponses.validate(conversation, false, true)).isInstanceOf(SupportUnavailableException.class);
        var list = (ObjectNode) fixture.path("tickets").deepCopy();
        ((ObjectNode) list.path("entries").get(0)).put("status", "draft");
        assertThatThrownBy(() -> SupportResponses.validate(list, false, false)).isInstanceOf(SupportUnavailableException.class);
    }

    @Test void unknownAttachmentSizeIsPreservedAndKnownSizesRemainBounded() throws IOException {
        var conversation = (ObjectNode) fixture().path("conversation").deepCopy();
        var attachment = (ObjectNode) conversation.path("messages").get(0).path("attachment");
        attachment.putNull("size").put("name", "").put("mimeType", "");
        SupportResponses.validate(conversation, false, true);
        assertThat(attachment.path("size").isNull()).isTrue();
        for (long invalidSize : new long[]{-1, 20 * 1024 * 1024L + 1, Long.MAX_VALUE}) {
            attachment.put("size", invalidSize);
            assertThatThrownBy(() -> SupportResponses.validate(conversation, false, true)).isInstanceOf(SupportUnavailableException.class);
        }
        attachment.put("size", "12");
        assertThatThrownBy(() -> SupportResponses.validate(conversation, false, true)).isInstanceOf(SupportUnavailableException.class);
        attachment.putNull("size").put("file_id", "private-reference");
        assertThatThrownBy(() -> SupportResponses.validate(conversation, false, true)).isInstanceOf(SupportUnavailableException.class);
        attachment.remove("file_id");
        attachment.putNull("name");
        assertThatThrownBy(() -> SupportResponses.validate(conversation, false, true)).isInstanceOf(SupportUnavailableException.class);
        attachment.put("name", "").putNull("mimeType");
        assertThatThrownBy(() -> SupportResponses.validate(conversation, false, true)).isInstanceOf(SupportUnavailableException.class);
    }

    static JsonNode fixture() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/admin/support.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Missing admin support fixture");
        return new ContentJsonReader(16_384, 8, 1000).read(Files.readAllBytes(root.resolve("contracts/admin/support.json")));
    }
}
