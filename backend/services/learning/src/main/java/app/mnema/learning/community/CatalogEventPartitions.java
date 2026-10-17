package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.Slice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;

/** Retention runs even with both product switches disabled. All DDL names are generated dates or validated catalogue names. */
@Component
class CatalogEventPartitions implements JobHandler {
    static final String QUEUE = "catalog.events.partitions";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM", java.util.Locale.ROOT);
    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired CatalogEventPartitions(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    CatalogEventPartitions(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override public String queue() {
        return QUEUE;
    }

    List<String> names() {
        return jdbc.sql("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid=i.inhrelid
                WHERE i.inhparent='app_learning.catalog_event'::regclass
                ORDER BY c.relname
                """)
                .query(String.class).list();
    }

    @Override public Slice run(Job job) {
        jdbc.sql("SELECT set_config('lock_timeout','2s',true),set_config('statement_timeout','10s',true)").query().listOfRows();
        // Multiple worker instances and an operator's job cannot race CREATE/DROP.
        jdbc.sql("SELECT pg_advisory_xact_lock(7145670)").query().listOfRows();
        LocalDate month = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        LocalDate oldest = month.minusMonths(12);
        var names = new HashSet<>(names());
        int changes = 0;
        for (int i = -12; i <= 2; i++) {
            LocalDate start = month.plusMonths(i);
            String name = "catalog_event_" + MONTH.format(start);
            if (!names.contains(name)) {
                jdbc.sql("CREATE TABLE app_learning." + name + " PARTITION OF app_learning.catalog_event FOR VALUES FROM ('" + start + "T00:00:00Z') TO ('" + start.plusMonths(1)
                        + "T00:00:00Z')").update();
                changes++;
            }
        }

        for (String name : names) {
            if (!name.matches("catalog_event_[0-9]{6}")) throw new IllegalStateException("Unexpected event partition name");
            LocalDate start = LocalDate.parse(name.substring("catalog_event_".length()) + "01", DateTimeFormatter.BASIC_ISO_DATE);
            if (start.isBefore(oldest)) {
                jdbc.sql("DROP TABLE app_learning." + name).update();
                changes++;
            }
        }

        return Slice.done(changes);
    }
}
