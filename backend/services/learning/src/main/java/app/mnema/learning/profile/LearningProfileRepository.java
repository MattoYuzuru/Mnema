package app.mnema.learning.profile;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of {@code learning_profile}: one row per owner, written by the owner's own answer only.
 *
 * <p>TODO(account-deletion task; owner: Learning's account-purge epic): include this owner-linked goal and answer timestamp
 * in the purge and backup-retention inventory. Learning has no account purge path yet; see "Retention" in the Learning guide
 * and the launch checklist before public activation. The goal is never sent to an AI provider.
 */
@Repository
class LearningProfileRepository {
    /** {@code goal} is null for a skip. */
    record Row(LearningGoal goal, Instant answeredAt) { }

    private final JdbcClient jdbc;

    LearningProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Row> find(UUID owner) {
        return jdbc.sql("SELECT goal,answered_at FROM app_learning.learning_profile WHERE owner_id=:owner")
                .param("owner", owner)
                .query((row, number) -> new Row(row.getString("goal") == null ? null : LearningGoal.valueOf(row.getString("goal")),
                        row.getTimestamp("answered_at").toInstant()))
                .optional();
    }

    /** Stores the answer, replacing an earlier one (the owner may change it). */
    void upsert(UUID owner, LearningGoal goal, Instant now) {
        jdbc.sql("INSERT INTO app_learning.learning_profile(owner_id,goal,answered_at) VALUES (:owner,:goal,:now) "
                        + "ON CONFLICT (owner_id) DO UPDATE SET goal=EXCLUDED.goal, answered_at=EXCLUDED.answered_at")
                .param("owner", owner).param("goal", goal == null ? null : goal.name(), java.sql.Types.VARCHAR)
                .param("now", Timestamp.from(now)).update();
    }
}
