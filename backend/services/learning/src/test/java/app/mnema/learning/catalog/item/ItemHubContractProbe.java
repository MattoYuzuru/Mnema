package app.mnema.learning.catalog.item;

import tools.jackson.databind.JsonNode;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/** Feeds the {@code hub.json} command examples to the package-private production parsers. */
public final class ItemHubContractProbe {
    private ItemHubContractProbe() { }

    public static void accept(JsonNode hub) {
        JsonNode exemplar = hub.path("exemplar");
        ExemplarCommand set = ExemplarCommand.read(bytes(exemplar.path("command")));
        assertThat(set.exemplar()).isTrue();
        assertThat(ExemplarCommand.read(bytes(exemplar.path("unsetCommand"))).exemplar()).isFalse();
        assertThat(set.expectedItemRevisionId().toString()).isEqualTo(exemplar.path("acknowledgement").path("itemRevisionId").stringValue(null));

        JsonNode bulk = hub.path("bulkDelete");
        BulkDeleteCommand explicit = BulkDeleteCommand.read(bytes(bulk.path("command")));
        assertThat(explicit.selection().itemIds()).hasSize(bulk.path("completed").path("requested").intValue());
        assertThat(explicit.commandId().toString()).isEqualTo(bulk.path("completed").path("commandId").stringValue(null));
        BulkDeleteCommand all = BulkDeleteCommand.read(bytes(bulk.path("commandAllInDeck")));
        assertThat(all.selection().allInDeck()).isTrue();
        assertThat(all.selection().except()).hasSize(1);
        assertThat(all.commandId().toString()).isEqualTo(bulk.path("partial").path("commandId").stringValue(null));
        BulkSelection preview = BulkSelection.read(bulk.path("previewBody"));
        assertThat(preview.itemIds()).isEqualTo(explicit.selection().itemIds());
        assertThat(bulk.path("previewResponse").path("materialCount").intValue()).isEqualTo(preview.itemIds().size());
        assertThat(bulk.path("previewResponse").has("affectedExerciseCount")).isTrue();
        assertThat(bulk.path("previewResponse").has("exerciseCount")).isFalse();
    }
}
