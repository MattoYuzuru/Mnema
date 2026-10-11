package app.mnema.learning.library;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.UUID;

/**
 * The projection {@code published_media} (V55, Share/9): the assets that the MATERIALS of the published revision of a deck reference, with the number of
 * media references ({@code content_media_ref} rows) that point at each ({@code ref_count > 0}). Only materials: the public view shows exercises as plain-text
 * prompts, and an exercise pin does not say whether an asset is part of the question or of the answer, so exercise media is never offered to a guest (a copy's
 * owner reaches it through the {@code COPY} grant of the heads). The projection is written only here, by the publication command, in the transaction that moves
 * the published pointer, and read only by the media authorization ({@code MediaCatalog}, one primary-key probe).
 *
 * <p>The set is kept incrementally from the journal. {@link #advance} is given the sequence of the previously published revision ({@code null}: the first
 * publication) and the sequence of the new head, which the command holds locked, so the journal cannot move. A first publication REBUILDS the deck's rows from
 * the head. Afterwards the delta is {@code Σ (references of the head revision − references of the revision the first change in the range replaced)} over the
 * materials changed in the range, so the cost is O(references of the changed materials), not O(deck). A downgrade to PRIVATE does not touch the rows: the
 * pointer is kept and the access level decides who may use them.
 *
 * <p>Lineage rows are immutable, so the references of an old revision are still there to subtract. A change whose replaced revision is {@code NULL} (the
 * material was created inside the range) subtracts nothing; a material removed inside the range has no head and adds nothing. A reorder replaces nothing (it
 * names the unchanged revision as the replaced one), so its delta is zero.
 *
 * <p><b>Self-check and repair.</b> After a published head the stored count of an asset must equal the number of references to it in the head's materials, so
 * after every incremental step the touched assets are compared with that recount (an index probe per asset), and a decrement that found no row is counted
 * too. Any disagreement, an over-count included, REBUILDS the deck's rows from the head inside the same transaction, so the published set is exact whatever
 * went wrong before; the caller learns how many disagreements there were, to log the deck and count them.
 */
@Repository
public class PublishedMediaRepository {
    /** The delta of a republication: the head's references of the materials changed in {@code (from, to]} minus those of the revisions they replaced. */
    private static final String RANGE_DELTA = """
            WITH changed_items AS (
                SELECT DISTINCT ON (c.member_key) c.member_key, c.previous_revision_id AS old_revision
                  FROM app_learning.deck_item_change c
                 WHERE c.deck_id = :deck AND c.deck_sequence > :from AND c.deck_sequence <= :to
                 ORDER BY c.member_key, c.deck_sequence, c.change_ordinal
            ), delta AS (
                SELECT asset_id, sum(n)::integer AS delta FROM (
                    SELECT r.asset_id, 1 AS n
                      FROM changed_items c
                      JOIN app_learning.deck_head_item h ON h.deck_id = :deck AND h.member_key = c.member_key
                      JOIN app_learning.content_media_ref r ON r.reuse_scope_id = h.reuse_scope_id AND r.member_key = h.member_key AND r.revision_id = h.revision_id
                    UNION ALL
                    SELECT r.asset_id, -1
                      FROM changed_items c
                      JOIN app_learning.content_media_ref r ON r.reuse_scope_id = (SELECT d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = :deck)
                       AND r.member_key = c.member_key AND r.revision_id = c.old_revision
                ) refs GROUP BY asset_id HAVING sum(n) <> 0
            )""";

    /** The delta of a first publication or a rebuild: every reference of the head's materials. */
    private static final String HEAD_DELTA = """
            WITH delta AS (
                SELECT r.asset_id, count(*)::integer AS delta
                  FROM app_learning.deck_head_item h
                  JOIN app_learning.content_media_ref r ON r.reuse_scope_id = h.reuse_scope_id AND r.member_key = h.member_key AND r.revision_id = h.revision_id
                 WHERE h.deck_id = :deck
                 GROUP BY r.asset_id
            )""";

    /**
     * Applies {@code delta(asset_id, delta)} to the deck's rows in one statement: a row that stays positive is updated, one that would reach zero is deleted,
     * a new asset is inserted. The result is the number of deltas that matched nothing (a decrement of a row that does not exist) and the assets the delta
     * touched.
     */
    private static final String APPLY = """
            , changed AS (
                UPDATE app_learning.published_media pm SET ref_count = pm.ref_count + d.delta
                  FROM delta d WHERE pm.deck_id = :deck AND pm.asset_id = d.asset_id AND pm.ref_count + d.delta > 0
                RETURNING pm.asset_id
            ), removed AS (
                DELETE FROM app_learning.published_media pm USING delta d
                 WHERE pm.deck_id = :deck AND pm.asset_id = d.asset_id AND pm.ref_count + d.delta <= 0
                RETURNING pm.asset_id
            ), added AS (
                INSERT INTO app_learning.published_media(deck_id, asset_id, ref_count)
                SELECT :deck, d.asset_id, d.delta FROM delta d
                 WHERE d.delta > 0 AND NOT EXISTS (SELECT 1 FROM app_learning.published_media pm WHERE pm.deck_id = :deck AND pm.asset_id = d.asset_id)
                RETURNING asset_id
            )
            SELECT (SELECT count(*) FROM delta) - (SELECT count(*) FROM changed) - (SELECT count(*) FROM removed) - (SELECT count(*) FROM added) AS unmatched,
                   (SELECT COALESCE(array_agg(asset_id), CAST('{}' AS uuid[])) FROM delta) AS touched""";

    /** The assets among {@code :assets} whose stored count is not the number of references to them in the head's materials (absent counts as zero). */
    private static final String DISAGREEING = """
            SELECT count(*)::integer
              FROM unnest(CAST(:assets AS uuid[])) AS t(asset_id)
             WHERE COALESCE((SELECT pm.ref_count FROM app_learning.published_media pm WHERE pm.deck_id = :deck AND pm.asset_id = t.asset_id), 0)
                <> (SELECT count(*) FROM app_learning.content_media_ref r
                      JOIN app_learning.deck_head_item h ON h.deck_id = :deck AND h.reuse_scope_id = r.reuse_scope_id AND h.member_key = r.member_key AND h.revision_id = r.revision_id
                     WHERE r.asset_id = t.asset_id)""";

    private record Applied(int unmatched, UUID[] touched) { }

    private final JdbcClient jdbc;

    PublishedMediaRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Moves the deck's set from the previously published sequence to the head's. It runs in the transaction of the publication command (the publication
     * row is locked there and the head is share-locked, so neither the pointer nor the journal can move).
     *
     * @param previousSequence the sequence of the previously published revision; {@code null} for the first publication
     * @param headSequence the sequence of the head that is being published
     * @return how many disagreements were found and repaired by a rebuild from the head (0 unless the set had drifted from the journal)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int advance(UUID deck, Long previousSequence, long headSequence) {
        if (previousSequence == null) {
            rebuild(deck);
            return 0;
        }
        if (previousSequence >= headSequence) return 0;
        Applied applied = jdbc.sql(RANGE_DELTA + APPLY).param("deck", deck).param("from", previousSequence).param("to", headSequence)
                .query((row, ignored) -> new Applied(row.getInt("unmatched"), uuids(row.getArray("touched")))).single();
        int disagreeing = applied.touched().length == 0 ? 0
                : jdbc.sql(DISAGREEING).param("assets", applied.touched()).param("deck", deck).query(Integer.class).single();
        int found = applied.unmatched() + disagreeing;
        if (found != 0) rebuild(deck);
        return found;
    }

    /** Replaces the deck's rows by the references of the head's materials: exact by construction. */
    int rebuild(UUID deck) {
        jdbc.sql("DELETE FROM app_learning.published_media WHERE deck_id = :deck").param("deck", deck).update();
        return jdbc.sql(HEAD_DELTA + APPLY).param("deck", deck).query((row, ignored) -> row.getInt("unmatched")).single();
    }

    private static UUID[] uuids(java.sql.Array array) {
        try {
            return array == null ? new UUID[0] : (UUID[]) array.getArray();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
