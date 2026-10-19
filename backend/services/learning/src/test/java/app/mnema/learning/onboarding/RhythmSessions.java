package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.StudyFixtures;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Study sessions for the rhythm tests: rows of {@code study_session} cloned from one real session, with the status, mode and {@code local_study_date} the test chooses, so that a
 * week of the account is exactly what the test says and never depends on the clock of the JVM. A REPLAY row needs a session of its own account to replay (the foreign key), which
 * {@link #anchor} gives it in a year no test looks at.
 */
final class RhythmSessions {
    /** A Monday long before any week a test uses: the session a REPLAY row replays. */
    private static final LocalDate ANCHOR_DATE = LocalDate.of(2020, 1, 6);
    private static UUID template;

    private RhythmSessions() { }

    /** One real session row, made through the services once per JVM; the rows below are clones of it. */
    static synchronized UUID template(DeckService decks, ItemService items, ExerciseService exercises, StudySessionService sessions, MediaCatalog media, JdbcClient jdbc) {
        if (template != null) return template;
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        StudyFixtures.Material material = fixtures.material();
        fixtures.publish(material, fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Вопрос?")), StudyFixtures.blocks(StudyFixtures.text("Ответ")), "ответ"), "Цель");
        template = fixtures.issueOne(material).session();
        return template;
    }

    /** A COMPLETE session of the account on {@code date}; returns its id. */
    static UUID complete(JdbcClient jdbc, UUID template, UUID account, String mode, LocalDate date) {
        return insert(jdbc, template, account, mode, date, "COMPLETE");
    }

    /** A session of the account in {@code status} (an ACTIVE one has no completion and never counts). */
    static UUID insert(JdbcClient jdbc, UUID template, UUID account, String mode, LocalDate date, String status) {
        UUID source = mode.equals("REPLAY") ? anchor(jdbc, template, account) : null;
        UUID id = UUID.randomUUID();
        List<String> columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                .query(String.class).list();
        String selected = columns.stream().map(column -> switch (column) {
            case "account_id" -> "CAST(:account AS uuid)";
            case "session_id" -> "CAST(:id AS uuid)";
            case "command_id" -> "gen_random_uuid()";
            case "mode" -> "CAST(:mode AS text)";
            case "practice_order" -> "CASE WHEN :mode = 'PRACTICE' THEN 'SEEDED' END";
            case "source_session_id" -> "CAST(:source AS uuid)";
            case "status" -> "CAST(:status AS text)";
            case "local_study_date" -> "CAST(:date AS date)";
            case "completed_at" -> "CASE WHEN :status = 'COMPLETE' THEN statement_timestamp() END";
            default -> "s." + column;
        }).collect(Collectors.joining(","));
        jdbc.sql("INSERT INTO app_learning.study_session(" + String.join(",", columns) + ") SELECT " + selected
                        + " FROM app_learning.study_session s WHERE s.session_id = :template")
                .param("account", account).param("id", id).param("mode", mode).param("source", source, java.sql.Types.OTHER).param("status", status).param("date", date)
                .param("template", template).update();
        return id;
    }

    /** The account's own COMPLETE SCHEDULED session of the ancient Monday (once per account), the source of its REPLAY rows. */
    private static UUID anchor(JdbcClient jdbc, UUID template, UUID account) {
        return jdbc.sql("SELECT session_id FROM app_learning.study_session WHERE account_id = :account AND local_study_date = :date AND mode = 'SCHEDULED'")
                .param("account", account).param("date", ANCHOR_DATE).query(UUID.class).optional().orElseGet(() -> complete(jdbc, template, account, "SCHEDULED", ANCHOR_DATE));
    }
}
