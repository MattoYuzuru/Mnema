package app.mnema.learning.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The read side of the rank generations for the catalogue routes (Community/2 and /6): which generation is current, whether a cursor's generation is still kept, and the RAW audience
 * counts of a page of cards. The display rule is {@link AudienceCounts}; nothing here is ever sent to a client as it is.
 *
 * <p>{@code deck_rank} has a row for every {@code public_deck} row of the generation, listed or not (the author's page shows the counts of a deck below the threshold too); a deck that
 * entered the catalogue after the generation was built has none, which a caller reads as 0 and 0. The browse queries read {@code deck_rank} directly with
 * {@code WHERE generation = $g AND listed ...} so that the partial indexes of {@code V68} serve them.
 */
@Repository
class CatalogRanks {
    /** The raw counts of one deck: {@code added} = distinct non-owner accounts with a direct copy, {@code learningNow} = those with a completed scheduled session in the last 30 days. */
    record Counts(int added, int learningNow) {
        /** A deck the generation does not know. */
        public static final Counts NONE = new Counts(0, 0);
    }

    private final JdbcClient jdbc;

    CatalogRanks(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The newest COMPLETED generation; empty before the first job has finished. */
    @Transactional(readOnly = true)
    OptionalLong current() {
        return jdbc.sql("SELECT generation FROM app_learning.rank_generation WHERE completed_at IS NOT NULL ORDER BY generation DESC LIMIT 1")
                .query(Long.class).optional().map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }

    /** Whether the generation of a cursor is still kept (completed and not past its retention): the cursor of a deleted one is {@code 412 VERSION_CONFLICT}. */
    @Transactional(readOnly = true)
    boolean kept(long generation) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.rank_generation WHERE generation = :generation AND completed_at IS NOT NULL)")
                .param("generation", generation).query(Boolean.class).single();
    }

    /** The raw counts of the given decks in {@code generation}; a deck without a row is absent from the map (read it as {@link Counts#NONE}). */
    @Transactional(readOnly = true)
    Map<UUID, Counts> counts(long generation, Collection<UUID> decks) {
        Map<UUID, Counts> result = new HashMap<>();
        if (decks.isEmpty()) return result;
        jdbc.sql("SELECT deck_id, added_count, learning_now_count FROM app_learning.deck_rank WHERE generation = :generation AND deck_id IN (:decks)")
                .param("generation", generation).param("decks", decks)
                .query((row, ignored) -> result.put(row.getObject("deck_id", UUID.class), new Counts(row.getInt("added_count"), row.getInt("learning_now_count")))).list();
        return result;
    }
}
