package app.mnema.learning.study.restart;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class StudyRestartRepository {
    private static final UUID CONFIG = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
    private final JdbcClient jdbc;

    StudyRestartRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record State(UUID objectiveId, long learningEpoch, long rowVersion) { }

    boolean ownsDeck(UUID actor, UUID deck) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.deck WHERE owner_id=:actor AND deck_id=:deck)")
                .param("actor", actor).param("deck", deck).query(Boolean.class).single();
    }

    int currentMemberCount(UUID deck, List<UUID> members) {
        return jdbc.sql("""
                SELECT count(*) FROM app_learning.deck_head_item
                 WHERE deck_id=:deck AND member_key IN (:members)
                """).param("deck", deck).param("members", members).query(Integer.class).single();
    }

    List<UUID> objectives(UUID deck, List<UUID> members) {
        return jdbc.sql("""
                SELECT objective_id FROM app_learning.memory_objective
                 WHERE deck_id=:deck AND member_key IN (:members) ORDER BY objective_id
                """).param("deck", deck).param("members", members).query(UUID.class).list();
    }

    void ensureState(UUID actor, UUID deck, UUID objective, Instant now) {
        jdbc.sql("""
                INSERT INTO app_learning.study_policy_assignment(account_id,deck_id,objective_id,reducer_config_id,
                    assigned_at)
                VALUES (:actor,:deck,:objective,:config,:now)
                ON CONFLICT (account_id,deck_id,objective_id) DO NOTHING
                """).param("actor", actor).param("deck", deck).param("objective", objective).param("config", CONFIG)
                .param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                INSERT INTO app_learning.study_state(account_id,deck_id,objective_id,learning_epoch,level,
                    correct_streak,lapse_count,last_assessed_at,next_due,reducer_config_id,transition_sequence,
                    row_version,introduced_at,updated_at)
                VALUES (:actor,:deck,:objective,0,0,0,0,NULL,NULL,:config,0,0,:now,:now)
                ON CONFLICT (account_id,deck_id,objective_id) DO NOTHING
                """).param("actor", actor).param("deck", deck).param("objective", objective).param("config", CONFIG)
                .param("now", Timestamp.from(now)).update();
    }

    State lockState(UUID actor, UUID deck, UUID objective) {
        return jdbc.sql("""
                SELECT objective_id,learning_epoch,row_version FROM app_learning.study_state
                 WHERE account_id=:actor AND deck_id=:deck AND objective_id=:objective FOR UPDATE
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .query((row, ignored) -> new State(row.getObject("objective_id", UUID.class),
                        row.getLong("learning_epoch"), row.getLong("row_version"))).single();
    }

    void restart(UUID actor, UUID deck, UUID command, State state, Instant now) {
        int changed = jdbc.sql("""
                UPDATE app_learning.study_state SET learning_epoch=learning_epoch+1,level=0,correct_streak=0,
                    lapse_count=0,last_assessed_at=NULL,next_due=:now,transition_sequence=0,row_version=row_version+1,
                    updated_at=:now
                 WHERE account_id=:actor AND deck_id=:deck AND objective_id=:objective AND row_version=:version
                """).param("now", Timestamp.from(now)).param("actor", actor).param("deck", deck)
                .param("objective", state.objectiveId()).param("version", state.rowVersion()).update();
        if (changed != 1) throw new IllegalStateException("Study state changed while locked");
        jdbc.sql("""
                INSERT INTO app_learning.study_restart_audit(command_id,account_id,deck_id,objective_id,
                    prior_learning_epoch,new_learning_epoch,restarted_at)
                VALUES (:command,:actor,:deck,:objective,:prior,:next,:now)
                """).param("command", command).param("actor", actor).param("deck", deck)
                .param("objective", state.objectiveId()).param("prior", state.learningEpoch())
                .param("next", state.learningEpoch() + 1).param("now", Timestamp.from(now)).update();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()")
            .query(Timestamp.class).single().toInstant(); }
}
