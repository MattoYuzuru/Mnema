package app.mnema.learning.catalog.content;

import app.mnema.learning.catalog.content.storage.NativeSnapshotDecoder;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Authorized callers supply the pinned item revision; the cache is private, durable and rebuildable. */
@Service
public class ItemPreviews {
    private final JdbcClient jdbc;
    private final NativeStorageBatches batches;

    public ItemPreviews(JdbcClient jdbc, ImmutableStorage storage) {
        this.jdbc = jdbc;
        this.batches = new NativeStorageBatches(storage);
    }

    public String title(UUID deck, UUID member, UUID revision, UUID scope, UUID root) {
        var existing = jdbc.sql("SELECT title FROM app_learning.item_preview WHERE deck_id=:deck AND member_key=:member AND revision_id=:revision")
                .param("deck", deck).param("member", member).param("revision", revision).query(String.class).optional();
        if (existing.isPresent()) return existing.get();
        var decoder = new NativeSnapshotDecoder(new ObjectRef(scope, root));
        while (!decoder.isComplete()) batches.readNext(decoder);
        String title = NativeDocumentPreview.title(decoder.snapshot().document());
        jdbc.sql("""
                INSERT INTO app_learning.item_preview(deck_id,member_key,revision_id,title)
                VALUES (:deck,:member,:revision,:title) ON CONFLICT DO NOTHING
                """).param("deck", deck).param("member", member).param("revision", revision).param("title", title).update();
        return title;
    }
}
