package app.mnema.learning.study.retention;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
class StudyRetentionRepository {
    private final JdbcClient jdbc;

    StudyRetentionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    int purgeRaw(Instant asOf, int limit) {
        return jdbc.sql("""
                DELETE FROM app_learning.study_raw_response raw
                 WHERE raw.attempt_id IN (
                       SELECT candidate.attempt_id FROM app_learning.study_raw_response candidate
                        WHERE candidate.expires_at<=:asOf
                        ORDER BY candidate.expires_at,candidate.attempt_id
                        LIMIT :limit FOR UPDATE SKIP LOCKED
                 )
                """).param("asOf", java.sql.Timestamp.from(asOf)).param("limit", limit).update();
    }

    int expireCompactOutcomes(Instant asOf, int limit) {
        return jdbc.sql("""
                UPDATE app_learning.study_attempt_tombstone receipt SET outcome=NULL
                 WHERE receipt.attempt_id IN (
                       SELECT candidate.attempt_id FROM app_learning.study_attempt_tombstone candidate
                        WHERE candidate.receipt_expires_at<=:asOf AND candidate.outcome IS NOT NULL
                        ORDER BY candidate.receipt_expires_at,candidate.attempt_id
                        LIMIT :limit FOR UPDATE SKIP LOCKED
                 )
                """).param("asOf", java.sql.Timestamp.from(asOf)).param("limit", limit).update();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()")
            .query(java.sql.Timestamp.class).single().toInstant(); }
}
