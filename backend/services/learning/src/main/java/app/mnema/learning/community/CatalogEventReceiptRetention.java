package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.Slice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.node.JsonNodeFactory;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/** Only disposable telemetry receipts expire; other command scopes retain their existing semantics. */
@Component
class CatalogEventReceiptRetention implements JobHandler {
    static final String QUEUE = "catalog.events.receipts";
    static final int BATCH = 1000;
    static final Duration RETENTION = Duration.ofDays(7);
    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired CatalogEventReceiptRetention(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    CatalogEventReceiptRetention(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override public String queue() {
        return QUEUE;
    }

    @Override public Slice run(Job job) {
        int count = jdbc.sql("""
                WITH expired AS (SELECT command_id
                FROM app_learning.command_receipt
                WHERE command_scope='community.events'
                AND created_at<:before
                ORDER BY created_at,command_id
                LIMIT :batch FOR UPDATE SKIP LOCKED) DELETE
                FROM app_learning.command_receipt c USING expired e
                WHERE c.command_id=e.command_id
                """)
                .param("before", Timestamp.from(clock.instant().minus(RETENTION))).param("batch", BATCH).update();
        int snapshots = jdbc.sql("""
                WITH expired AS (SELECT snapshot_id
                FROM app_learning.recommendation_snapshot
                WHERE created_at<:before
                ORDER BY created_at,snapshot_id
                LIMIT :batch FOR UPDATE SKIP LOCKED) DELETE
                FROM app_learning.recommendation_snapshot s USING expired e
                WHERE s.snapshot_id=e.snapshot_id
                """)
                .param("before", Timestamp.from(clock.instant().minus(Duration.ofHours(24)))).param("batch", BATCH).update();
        long progress = job.progressCount() + count + snapshots;
        return count == BATCH || snapshots == BATCH ? Slice.proceed(JsonNodeFactory.instance.objectNode(), progress) : Slice.done(progress);
    }
}
