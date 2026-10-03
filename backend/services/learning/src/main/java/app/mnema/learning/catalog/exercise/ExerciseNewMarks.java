package app.mnema.learning.catalog.exercise;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The «Новое» mark of an exercise a generation approval just published: a row in {@code exercise_new_mark}, owned by the
 * catalog so that Study and the lists can read it without knowing where it came from (they never depend on generation).
 * An exercise is new while its row exists and {@code marked_at} is within {@code learning.exercise.new-mark-ttl} ({@code P7D});
 * the row is deleted when the owner opens the exercise or answers it in Study, and an expired row is purged by the Study
 * retention worker. The writers join the caller's transaction (the approval's, the attempt's).
 */
@Component
public class ExerciseNewMarks {
    private final JdbcClient jdbc;
    private final Duration ttl;

    public ExerciseNewMarks(JdbcClient jdbc, @Value("${learning.exercise.new-mark-ttl:P7D}") Duration ttl) {
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0) throw new IllegalArgumentException("Invalid new-mark ttl");
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    /** Marks a just-published exercise; a repeat (the exercise was re-published or the approval replayed) renews the mark. */
    public void mark(UUID owner, UUID deck, UUID exercise) {
        jdbc.sql("""
                INSERT INTO app_learning.exercise_new_mark(deck_id,exercise_id,owner_id,marked_at)
                VALUES (:deck,:exercise,:owner,statement_timestamp())
                ON CONFLICT (deck_id,exercise_id) DO UPDATE SET marked_at=EXCLUDED.marked_at
                """).param("deck", deck).param("exercise", exercise).param("owner", owner).update();
    }

    /** Deletes the mark (idempotent: no row is fine). */
    public void clear(UUID owner, UUID deck, UUID exercise) {
        jdbc.sql("DELETE FROM app_learning.exercise_new_mark WHERE deck_id=:deck AND exercise_id=:exercise AND owner_id=:owner")
                .param("deck", deck).param("exercise", exercise).param("owner", owner).update();
    }

    /** The subset of {@code exercises} that is new now (marked within the TTL). */
    public Set<UUID> fresh(UUID owner, UUID deck, Collection<UUID> exercises) {
        Set<UUID> fresh = new HashSet<>();
        if (exercises.isEmpty()) return fresh;
        jdbc.sql("""
                SELECT exercise_id FROM app_learning.exercise_new_mark
                 WHERE deck_id=:deck AND owner_id=:owner AND exercise_id IN (:exercises)
                   AND marked_at > statement_timestamp() - (:ttl * interval '1 second')
                """).param("deck", deck).param("owner", owner).param("exercises", exercises).param("ttl", ttl.toSeconds())
                .query(UUID.class).list().forEach(fresh::add);
        return fresh;
    }

    /** Deletes up to {@code limit} marks older than the TTL; returns how many (the retention worker repeats while it is full). */
    public int purgeExpired(int limit) {
        return jdbc.sql("""
                DELETE FROM app_learning.exercise_new_mark WHERE (deck_id,exercise_id) IN (
                    SELECT deck_id,exercise_id FROM app_learning.exercise_new_mark
                     WHERE marked_at <= statement_timestamp() - (:ttl * interval '1 second') ORDER BY marked_at LIMIT :limit)
                """).param("ttl", ttl.toSeconds()).param("limit", limit).update();
    }
}
