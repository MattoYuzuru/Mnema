package app.mnema.learning.catalog.content;

import app.mnema.learning.catalog.content.storage.NativeSnapshotDecoder;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Title cache of a lineage revision. Callers authorize the revision (it is the reading deck's head or journal entry, see
 * {@code ItemRevisionVisibility}) and pass its identity {@code (scope, member, revision)}; the content root is always
 * taken from the {@code item_revision} row, never from the caller. The cache is private, durable and rebuildable, and
 * keyed by the lineage, so decks that share a scope share one preview per revision.
 */
@Service
public class ItemPreviews {
    private final JdbcClient jdbc;
    private final NativeStorageBatches batches;

    public ItemPreviews(JdbcClient jdbc, ImmutableStorage storage) {
        this.jdbc = jdbc;
        this.batches = new NativeStorageBatches(storage);
    }

    public String title(UUID scope, UUID member, UUID revision) {
        var existing = jdbc.sql("SELECT title FROM app_learning.item_preview WHERE reuse_scope_id=:scope AND member_key=:member AND revision_id=:revision")
                .param("scope", scope).param("member", member).param("revision", revision).query(String.class).optional();
        if (existing.isPresent()) return existing.get();
        UUID root = jdbc.sql("SELECT content_root_id FROM app_learning.item_revision "
                        + "WHERE reuse_scope_id=:scope AND member_key=:member AND revision_id=:revision")
                .param("scope", scope).param("member", member).param("revision", revision).query(UUID.class).optional()
                .orElseThrow(ResourceNotFoundException::new);
        String title = derive(scope, root);
        jdbc.sql("""
                INSERT INTO app_learning.item_preview(reuse_scope_id,member_key,revision_id,title)
                VALUES (:scope,:member,:revision,:title) ON CONFLICT DO NOTHING
                """).param("scope", scope).param("member", member).param("revision", revision).param("title", title).update();
        return title;
    }

    /** The title of the document stored under a content root, read-only: nothing is cached (the batch reader of the public routes stores its misses itself). */
    public String derive(UUID scope, UUID contentRoot) {
        var decoder = new NativeSnapshotDecoder(new ObjectRef(scope, contentRoot));
        while (!decoder.isComplete()) batches.readNext(decoder);
        return NativeDocumentPreview.title(decoder.snapshot().document());
    }
}
