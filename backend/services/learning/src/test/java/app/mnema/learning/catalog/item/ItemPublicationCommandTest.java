package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemPublicationCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void sharedFixtureIsTheExecutableCreateAndOrderedSaveWireContract() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/items/publication.json"))) root = root.getParent();
        JsonNode fixture = JSON.readTree(Files.readString(root.resolve("contracts/items/publication.json")));
        ItemPublicationCommand command = ItemPublicationCommand.readCreate(bytes(fixture.path("create").toString()));
        assertThat(command.commandId().toString()).isEqualTo(fixture.path("create").path("commandId").textValue());
        assertThat(((ItemPublicationCommand.Create) command.changes().getFirst()).ordinal()).isZero();
        ItemPublicationCommand save = ItemPublicationCommand.readSave(bytes(fixture.path("save").toString()), UUID.randomUUID());
        var structural = ((ItemPublicationCommand.Save) save.changes().getFirst()).edits();
        assertThat(structural).containsExactly(new app.mnema.learning.catalog.content.storage.NativeStructuralEdit.Insert(
                UUID.fromString("00000000-0000-4000-8000-000000000004"),
                UUID.fromString("00000000-0000-4000-8000-000000000001"), 1));
    }

    @Test
    void parsesStrictNativeCreateAndStructuralSaveWithoutLosingOpaqueContent() {
        UUID deckRevision = UUID.randomUUID();
        ObjectNode create = base(deckRevision);
        ObjectNode document = document();
        document.path("root").path("content").get(0).withObject("attrs")
                .put("privateFuture", "<script>data only</script>");
        ((ObjectNode) document.path("root").path("content").get(0)).put("type", "future_block").put("version", 7);
        create.set("document", document);
        ItemPublicationCommand parsed = ItemPublicationCommand.readCreate(bytes(create.toString()));
        assertThat(parsed.changes()).singleElement().isInstanceOf(ItemPublicationCommand.Create.class);
        assertThat(((ItemPublicationCommand.Create) parsed.changes().getFirst()).document().toJson().toString())
                .contains("privateFuture", "<script>data only</script>");

        UUID member = UUID.randomUUID();
        ObjectNode save = base(deckRevision).put("expectedItemRevisionId", UUID.randomUUID().toString())
                .put("expectedOrdinal", 0);
        save.set("document", document);
        save.putArray("edits").addObject().put("type", "delete").put("nodeId", UUID.randomUUID().toString());
        assertThat(((ItemPublicationCommand.Save) ItemPublicationCommand.readSave(bytes(save.toString()), member)
                .changes().getFirst()).edits()).hasSize(1);
    }

    @Test
    void rejectsDuplicateKeysUnknownFieldsBadNativeAndDuplicateBulkMembers() {
        UUID command = UUID.randomUUID(), revision = UUID.randomUUID(), member = UUID.randomUUID();
        String duplicate = "{\"commandId\":\"" + command + "\",\"commandId\":\"" + command
                + "\",\"expectedDeckRevisionId\":\"" + revision + "\",\"document\":" + document() + "}";
        assertThatThrownBy(() -> ItemPublicationCommand.readCreate(bytes(duplicate)))
                .isInstanceOf(InvalidRequestException.class);
        ObjectNode unknown = base(revision); unknown.set("document", document()); unknown.put("private", true);
        assertThatThrownBy(() -> ItemPublicationCommand.readCreate(bytes(unknown.toString())))
                .isInstanceOf(InvalidRequestException.class);
        ObjectNode invalid = base(revision); invalid.set("document", document());
        ((ObjectNode) invalid.path("document").path("root")).put("id", "00000000-0000-1000-8000-000000000001");
        assertThatThrownBy(() -> ItemPublicationCommand.readCreate(bytes(invalid.toString())))
                .isInstanceOf(InvalidRequestException.class);

        ObjectNode bulk = base(revision);
        var changes = bulk.putArray("changes");
        for (int i = 0; i < 2; i++) {
            ObjectNode change = changes.addObject().put("operation", "create").put("memberKey", member.toString());
            change.set("document", document());
        }
        assertThatThrownBy(() -> ItemPublicationCommand.readBulk(bytes(bulk.toString())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsMoreThanOneHundredChangesAndOversizedCommand() {
        ObjectNode bulk = base(UUID.randomUUID());
        var changes = bulk.putArray("changes");
        for (int i = 0; i <= ItemPublicationCommand.MAX_CHANGES; i++) {
            changes.addObject().put("operation", "reorder").put("memberKey", UUID.randomUUID().toString())
                    .put("expectedItemRevisionId", UUID.randomUUID().toString())
                    .put("expectedOrdinal", 0).put("ordinal", 0);
        }
        assertThatThrownBy(() -> ItemPublicationCommand.readBulk(bytes(bulk.toString())))
                .isInstanceOf(InvalidRequestException.class);
        byte[] oversized = new byte[ItemPublicationCommand.MAX_REQUEST_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        assertThatThrownBy(() -> ItemPublicationCommand.readCreate(new ByteArrayInputStream(oversized)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void parsesBoundedOrderedEditsAndRejectsLegacyEmptyDuplicateAndOversizedForms() {
        UUID revision = UUID.randomUUID();
        ObjectNode save = base(revision).put("expectedItemRevisionId", UUID.randomUUID().toString())
                .put("expectedOrdinal", 0);
        save.set("document", document());
        UUID inserted = UUID.randomUUID(), parent = UUID.randomUUID(), moved = UUID.randomUUID();
        var edits = save.putArray("edits");
        edits.addObject().put("type", "insert").put("nodeId", inserted.toString())
                .put("parentId", parent.toString()).put("childIndex", 1);
        edits.addObject().put("type", "move").put("nodeId", moved.toString())
                .put("parentId", parent.toString()).put("childIndex", 0);
        ItemPublicationCommand.Save parsed = (ItemPublicationCommand.Save) ItemPublicationCommand
                .readSave(bytes(save.toString()), UUID.randomUUID()).changes().getFirst();
        assertThat(parsed.edits()).containsExactly(
                new app.mnema.learning.catalog.content.storage.NativeStructuralEdit.Insert(inserted, parent, 1),
                new app.mnema.learning.catalog.content.storage.NativeStructuralEdit.Move(moved, parent, 0));

        ObjectNode legacy = save.deepCopy();
        legacy.remove("edits");
        legacy.putObject("edit").put("type", "delete").put("nodeId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> ItemPublicationCommand.readSave(bytes(legacy.toString()), UUID.randomUUID()))
                .isInstanceOf(InvalidRequestException.class);

        ObjectNode empty = save.deepCopy(); empty.putArray("edits");
        assertThatThrownBy(() -> ItemPublicationCommand.readSave(bytes(empty.toString()), UUID.randomUUID()))
                .isInstanceOf(InvalidRequestException.class);

        ObjectNode duplicate = save.deepCopy();
        duplicate.withArray("edits").add(duplicate.path("edits").get(0).deepCopy());
        assertThatThrownBy(() -> ItemPublicationCommand.readSave(bytes(duplicate.toString()), UUID.randomUUID()))
                .isInstanceOf(InvalidRequestException.class);

        ObjectNode oversized = save.deepCopy();
        oversized.putArray("edits");
        for (int index = 0; index <= ItemPublicationCommand.MAX_EDITS; index++) {
            oversized.withArray("edits").addObject().put("type", "delete")
                    .put("nodeId", UUID.randomUUID().toString());
        }
        assertThatThrownBy(() -> ItemPublicationCommand.readSave(bytes(oversized.toString()), UUID.randomUUID()))
                .isInstanceOf(InvalidRequestException.class);
    }

    private static ObjectNode base(UUID revision) {
        return JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", revision.toString());
    }

    private static ObjectNode document() {
        ObjectNode root = JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode paragraph = root.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs"); paragraph.putArray("content");
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1); document.set("root", root);
        return document;
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
