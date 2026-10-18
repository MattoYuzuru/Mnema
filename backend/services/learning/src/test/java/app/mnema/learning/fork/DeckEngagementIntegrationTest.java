package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.DeckEngagement;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.security.IdentityClaims.AccountSignals;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static app.mnema.learning.fork.ForkWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community/3 (#450): {@code deck_engagement} is written exactly once per (source deck, account, kind), synchronously in the transactions of the copy command and of the completion
 * of a scheduled study session, through the real application (the real executor materializes the copies, the real attempt service completes the sessions). The weight and the "new
 * account" flag of a copy come from the Identity claims of the request; the source's owner is never counted; a copy of a copy counts for its direct source only; study days are
 * distinct local dates; a retry changes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class DeckEngagementIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    @Autowired private AttemptService attempts;
    @Autowired private DeckEngagement engagement;
    private ForkWorlds worlds;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void worlds() {
        worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private record Row(String kind, float trust, boolean newAccount, int studyDays, LocalDate lastStudyDate, java.time.Instant firstAt, java.time.Instant lastAt) { }

    private List<Row> rows(UUID source, UUID account, String kind) {
        return jdbc.sql("SELECT kind, COALESCE(trust, 0) AS trust, COALESCE(new_account, FALSE) AS fresh, study_days, last_study_date, first_at, last_at "
                        + "FROM app_learning.deck_engagement WHERE deck_id = :d AND account_id = :a AND kind = :k")
                .param("d", source).param("a", account).param("k", kind)
                .query((row, ignored) -> new Row(row.getString("kind"), row.getFloat("trust"), row.getBoolean("fresh"), row.getInt("study_days"),
                        row.getObject("last_study_date", LocalDate.class), row.getTimestamp("first_at").toInstant(), row.getTimestamp("last_at").toInstant())).list();
    }

    private long all(UUID source) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck_engagement WHERE deck_id = :d").param("d", source).query(Long.class).single();
    }

    private void transact(Runnable body) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> body.run());
    }

    // ---- FORK ----

    @Test
    void theFirstCopyOfAnAccountIsCountedOnceWithTheWeightAndTheNewFlagOfItsClaims() {
        Source source = worlds.source("PUBLIC", 12, 2);
        UUID trusted = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID silent = UUID.randomUUID();
        HttpIdentityFixture.signals(trusted, true, false);
        HttpIdentityFixture.signals(fresh, false, true);
        // `silent`: Identity says nothing special (both claims false): the same as absent claims, which the filter test proves

        UUID command = UUID.randomUUID();
        HttpResponse<String> first = worlds.fork(trusted, command, source.code());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        UUID copy = UUID.fromString(json(first).path("deckId").stringValue(null));
        worlds.forked(fresh, source.code());
        worlds.forked(silent, source.code());

        Row row = rows(source.deck(), trusted, "FORK").getFirst();
        assertThat(rows(source.deck(), trusted, "FORK")).hasSize(1);
        assertThat(row.trust()).isEqualTo(1.0f);
        assertThat(row.newAccount()).isFalse();
        assertThat(row.studyDays()).isZero();
        assertThat(row.firstAt()).isEqualTo(row.lastAt());
        java.time.Instant forkedAt = jdbc.sql("SELECT forked_at FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(java.sql.Timestamp.class).single().toInstant();
        assertThat(row.firstAt()).isBetween(forkedAt.minusSeconds(5), forkedAt.plusSeconds(5));
        assertThat(rows(source.deck(), fresh, "FORK").getFirst()).extracting(Row::trust, Row::newAccount).containsExactly(0.25f, true);
        assertThat(rows(source.deck(), silent, "FORK").getFirst()).extracting(Row::trust, Row::newAccount).containsExactly(0.25f, false);
        assertThat(all(source.deck())).isEqualTo(3);

        // an exact retry replays the receipt, and the existing copy answers 200: neither writes
        assertThat(worlds.fork(trusted, command, source.code()).headers().firstValue("idempotency-replayed")).contains("true");
        worlds.awaitReady(trusted, copy);
        assertThat(worlds.fork(trusted, source.code()).statusCode()).isEqualTo(200);
        assertThat(rows(source.deck(), trusted, "FORK")).containsExactly(row);

        // a deleted and re-made copy is the same person: the first copy counts, whatever Identity says now
        long version = Long.parseLong(decks.read(trusted, copy).path("rowVersion").stringValue(null));
        decks.delete(trusted, copy, version);
        HttpIdentityFixture.signals(trusted, false, true);
        UUID again = worlds.forked(trusted, source.code());
        assertThat(again).isNotEqualTo(copy);
        assertThat(rows(source.deck(), trusted, "FORK")).containsExactly(row);
        assertThat(all(source.deck())).isEqualTo(3);
    }

    @Test
    void theOwnerOfTheSourceIsNeverCounted() {
        Source source = worlds.source("PUBLIC", 12, 1);
        HttpResponse<String> own = worlds.fork(source.author(), source.code());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(409);
        assertThat(json(own).path("code").stringValue(null)).isEqualTo("FORK_OWN_DECK");
        assertThat(all(source.deck())).isZero();
        // the net under the command: the writer itself refuses the owner
        transact(() -> assertThat(engagement.forked(source.deck(), source.author(), new AccountSignals(true, false))).isFalse());
        assertThat(all(source.deck())).isZero();
        transact(() -> assertThat(engagement.forked(source.deck(), UUID.randomUUID(), new AccountSignals(true, false))).isTrue());
        assertThat(all(source.deck())).isEqualTo(1);
        // the writers join the caller's transaction and refuse to run alone
        assertThatThrownBy(() -> engagement.forked(source.deck(), UUID.randomUUID(), AccountSignals.UNKNOWN)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void aCopyOfACopyCountsForItsDirectSourceOnly() {
        Source source = worlds.source("PUBLIC", 12, 3);
        UUID middle = UUID.randomUUID();
        UUID copy = worlds.forked(middle, source.code());
        worlds.awaitReady(middle, copy);
        HttpIdentityFixture.publicProfile(middle, true);
        String copiesCode = worlds.publish(middle, copy, "PUBLIC");
        UUID third = UUID.randomUUID();
        worlds.awaitReady(third, worlds.forked(third, copiesCode));

        assertThat(rows(source.deck(), middle, "FORK")).hasSize(1);
        assertThat(rows(copy, third, "FORK")).hasSize(1);
        assertThat(rows(source.deck(), third, "FORK")).isEmpty();
        // the middle account does not count for its own copy either
        assertThat(all(copy)).isEqualTo(1);
        assertThat(all(source.deck())).isEqualTo(1);
    }

    // ---- STUDY ----

    /** The learner studies the copy's first scheduled session to its end through the real attempt service; returns the session. */
    private UUID complete(UUID learner, UUID copy, Source source) {
        StudyFixtures.Material inherited = source.materials().getFirst();
        StudyFixtures.Material asCopy = new StudyFixtures.Material(learner, copy, inherited.member(), inherited.itemRevision(), inherited.node(), inherited.distractor(),
                inherited.divider(), inherited.root());
        List<Issued> issued = worlds.study.issue(asCopy, "SCHEDULED", null);
        assertThat(issued).isNotEmpty();
        for (Issued presentation : issued) attempts.submit(learner, copy, presentation.session(), StudyFixtures.attempt(presentation, StudyFixtures.textResponse("перевод")));
        UUID session = issued.getFirst().session();
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(String.class).single()).isEqualTo("COMPLETE");
        return session;
    }

    /** A COMPLETE copy of a session row with another id, local date and mode, as the next days' sessions would be. */
    private UUID cloneSession(UUID session, LocalDate date, String mode) {
        UUID id = UUID.randomUUID();
        String columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                .query(String.class).list().stream().collect(Collectors.joining(","));
        String selected = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                .query(String.class).list().stream().map(column -> switch (column) {
                    case "session_id" -> ":id";
                    case "command_id" -> ":command";
                    case "local_study_date" -> ":date";
                    case "mode" -> ":mode";
                    case "practice_order" -> "CASE WHEN :mode = 'PRACTICE' THEN 'SEEDED' END";
                    case "completed_at" -> "statement_timestamp()";
                    default -> column;
                }).collect(Collectors.joining(","));
        jdbc.sql("INSERT INTO app_learning.study_session(" + columns + ") SELECT " + selected + " FROM app_learning.study_session WHERE session_id = :source")
                .param("id", id).param("command", UUID.randomUUID()).param("date", date).param("mode", mode).param("source", session).update();
        return id;
    }

    private void completed(UUID learner, UUID session) {
        transact(() -> engagement.studied(learner, session));
    }

    @Test
    void aCompletedScheduledSessionOfACopyIsStudyOfItsSourceCountedPerDistinctLocalDate() {
        Source source = worlds.source("PUBLIC", 12, 4);
        UUID learner = UUID.randomUUID();
        UUID copy = worlds.forked(learner, source.code());
        worlds.awaitReady(learner, copy);

        // the real completion (the attempt service ends the session) writes STUDY in its own transaction
        UUID session = complete(learner, copy, source);
        LocalDate day = jdbc.sql("SELECT local_study_date FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(LocalDate.class).single();
        java.time.Instant completedAt = jdbc.sql("SELECT completed_at FROM app_learning.study_session WHERE session_id = :s").param("s", session)
                .query(java.sql.Timestamp.class).single().toInstant();
        Row study = rows(source.deck(), learner, "STUDY").getFirst();
        assertThat(rows(source.deck(), learner, "STUDY")).hasSize(1);
        assertThat(study.studyDays()).isEqualTo(1);
        assertThat(study.lastStudyDate()).isEqualTo(day);
        assertThat(study.firstAt()).isEqualTo(completedAt);
        assertThat(study.lastAt()).isEqualTo(completedAt);
        assertThat(rows(source.deck(), learner, "FORK")).hasSize(1);

        // a retry (the same session again) and another session of the same day add no study day, but the activity moves on
        completed(learner, session);
        UUID sameDay = cloneSession(session, day, "SCHEDULED");
        completed(learner, sameDay);
        Row same = rows(source.deck(), learner, "STUDY").getFirst();
        assertThat(same.studyDays()).isEqualTo(1);
        assertThat(same.lastAt()).isAfterOrEqualTo(completedAt);
        assertThat(same.firstAt()).isEqualTo(completedAt);

        // later days count once each, however often they are reported
        UUID next = cloneSession(session, day.plusDays(1), "SCHEDULED");
        completed(learner, next);
        completed(learner, next);
        assertThat(rows(source.deck(), learner, "STUDY").getFirst()).extracting(Row::studyDays, Row::lastStudyDate).containsExactly(2, day.plusDays(1));
        // a session of an earlier date (a changed time zone) cannot be told from a retry and is not counted
        completed(learner, cloneSession(session, day.minusDays(3), "SCHEDULED"));
        assertThat(rows(source.deck(), learner, "STUDY").getFirst()).extracting(Row::studyDays, Row::lastStudyDate).containsExactly(2, day.plusDays(1));

        // replay and practice sessions are feedback only: they are no engagement
        completed(learner, cloneSession(session, day.plusDays(5), "PRACTICE"));
        assertThat(rows(source.deck(), learner, "STUDY").getFirst().studyDays()).isEqualTo(2);

        // a detached copy is still a direct copy and keeps counting
        assertThat(worlds.send("POST", "/decks/" + copy + "/fork:detach", learner, null, null).statusCode()).isEqualTo(200);
        completed(learner, cloneSession(session, day.plusDays(2), "SCHEDULED"));
        assertThat(rows(source.deck(), learner, "STUDY").getFirst()).extracting(Row::studyDays, Row::lastStudyDate).containsExactly(3, day.plusDays(2));
        assertThat(all(source.deck())).isEqualTo(2);

        // a deleted copy no longer is a live copy: its sessions write nothing
        long version = Long.parseLong(decks.read(learner, copy).path("rowVersion").stringValue(null));
        decks.delete(learner, copy, version);
        completed(learner, cloneSession(session, day.plusDays(9), "SCHEDULED"));
        assertThat(rows(source.deck(), learner, "STUDY").getFirst().studyDays()).isEqualTo(3);
    }

    /** The two BACKFILL statements of V68, exactly as the migration runs them. */
    private static List<String> backfill() {
        try (var stream = DeckEngagementIntegrationTest.class.getResourceAsStream("/db/learning/migration/V68__deck_engagement_and_rank.sql")) {
            String migration = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            List<String> statements = new java.util.ArrayList<>();
            for (String kind : List.of("FORK", "STUDY")) {
                String block = migration.substring(migration.indexOf("-- BACKFILL BEGIN (" + kind + ")"), migration.indexOf("-- BACKFILL END (" + kind + ")"));
                statements.add(java.util.Arrays.stream(block.split("\n")).filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n")).replace(";", ""));
            }
            return statements;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Boolean[] unknownOrKnown(UUID source, UUID account) {
        return jdbc.sql("SELECT trust, new_account FROM app_learning.deck_engagement WHERE deck_id = :d AND account_id = :a AND kind = 'FORK'").param("d", source).param("a", account)
                .query((row, ignored) -> new Boolean[] {row.getObject("trust") == null ? null : row.getFloat("trust") == 1.0f, (Boolean) row.getObject("new_account")}).single();
    }

    @Test
    void everyPathThatCompletesASessionRunsInATransactionAndTheSessionIssuerCountsToo() throws Exception {
        // the two writers of status COMPLETE are StudySessionRepository.complete (the issuer, when the budget is spent or nothing is left) and AttemptRepository.completeSessionIfTerminal
        // (the last answer); DeckEngagement.studied is MANDATORY, so a caller outside a transaction would fail loudly. The answers are covered by the other tests; here the issuer path
        Source source = worlds.source("PUBLIC", 12, 4);
        UUID learner = UUID.randomUUID();
        UUID copy = worlds.forked(learner, source.code());
        worlds.awaitReady(learner, copy);
        StudyFixtures.Material inherited = source.materials().getFirst();
        StudyFixtures.Material asCopy = new StudyFixtures.Material(learner, copy, inherited.member(), inherited.itemRevision(), inherited.node(), inherited.distractor(),
                inherited.divider(), inherited.root());
        List<Issued> issued = worlds.study.issue(asCopy, "SCHEDULED", null);
        UUID session = issued.getFirst().session();
        assertThat(rows(source.deck(), learner, "STUDY")).isEmpty();
        // nothing is pending and the budget is spent: the next read of the presentations completes the session in the issuer
        jdbc.sql("UPDATE app_learning.study_session SET budget = issued_count, max_new_objectives = issued_new_objectives, batch_start = issued_count, batch_size = 0 WHERE session_id = :s").param("s", session).update();
        sessions.presentations(learner, copy, session);
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(String.class).single()).isEqualTo("COMPLETE");
        assertThat(rows(source.deck(), learner, "STUDY")).hasSize(1);
        assertThat(rows(source.deck(), learner, "STUDY").getFirst().studyDays()).isEqualTo(1);
        // every public entry point that can end a session is a transaction of its own (the asynchronous grade resolves in a TransactionTemplate)
        for (var method : List.of(AttemptService.class.getMethod("submit", UUID.class, UUID.class, UUID.class, app.mnema.learning.study.attempt.AttemptCommand.class),
                StudySessionService.class.getMethod("presentations", UUID.class, UUID.class, UUID.class), StudySessionService.class.getMethod("read", UUID.class, UUID.class, UUID.class))) {
            assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).as(method.getName()).isTrue();
        }
        assertThat(app.mnema.learning.study.attempt.AssessmentService.class.getMethods()).filteredOn(m -> m.getName().equals("selfRate"))
                .allSatisfy(m -> assertThat(m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue());
    }

    @Test
    void aClaimThatIdentityDidNotSendIsStoredAsUnknownAndCompletedByALaterCopyOfTheSameAccountOnly() {
        Source source = worlds.source("PUBLIC", 12, 2);
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.withoutSignals(account);
        UUID copy = worlds.forked(account, source.code());
        worlds.awaitReady(account, copy);
        assertThat(unknownOrKnown(source.deck(), account)).as("absent claims are NULL, not false").containsExactly(null, null);
        Row unknown = rows(source.deck(), account, "FORK").getFirst();
        // a second copy that again has no claims changes nothing
        decks.delete(account, copy, Long.parseLong(decks.read(account, copy).path("rowVersion").stringValue(null)));
        UUID second = worlds.forked(account, source.code());
        assertThat(unknownOrKnown(source.deck(), account)).containsExactly(null, null);
        // a later copy with claims fills BOTH NULLs, from that request, and leaves the date alone
        worlds.awaitReady(account, second);
        decks.delete(account, second, Long.parseLong(decks.read(account, second).path("rowVersion").stringValue(null)));
        HttpIdentityFixture.signals(account, true, true);
        UUID third = worlds.forked(account, source.code());
        assertThat(unknownOrKnown(source.deck(), account)).containsExactly(true, true);
        assertThat(rows(source.deck(), account, "FORK")).hasSize(1);
        assertThat(rows(source.deck(), account, "FORK").getFirst().firstAt()).isEqualTo(unknown.firstAt());
        // and once known it is never moved again: the first known weight is the person's
        worlds.awaitReady(account, third);
        decks.delete(account, third, Long.parseLong(decks.read(account, third).path("rowVersion").stringValue(null)));
        HttpIdentityFixture.signals(account, false, false);
        worlds.forked(account, source.code());
        assertThat(unknownOrKnown(source.deck(), account)).containsExactly(true, true);
        // an explicit false is known (0.25 and not new), unlike an absent claim
        UUID known = UUID.randomUUID();
        HttpIdentityFixture.signals(known, false, false);
        worlds.forked(known, source.code());
        assertThat(unknownOrKnown(source.deck(), known)).containsExactly(false, false);
    }

    @Test
    void theMigrationsBackfillBuildsFromTheExistingCopiesAndSessionsWhatTheIncrementalWritersBuild() {
        Source source = worlds.source("PUBLIC", 12, 4);
        UUID learner = UUID.randomUUID();
        HttpIdentityFixture.signals(learner, true, false);
        UUID copy = worlds.forked(learner, source.code());
        worlds.awaitReady(learner, copy);
        UUID session = complete(learner, copy, source);
        LocalDate day = jdbc.sql("SELECT local_study_date FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(LocalDate.class).single();
        completed(learner, cloneSession(session, day.plusDays(1), "SCHEDULED"));
        completed(learner, cloneSession(session, day.plusDays(1), "SCHEDULED"));
        completed(learner, cloneSession(session, day.plusDays(4), "SCHEDULED"));
        completed(learner, cloneSession(session, day.plusDays(6), "PRACTICE"));
        Row fork = rows(source.deck(), learner, "FORK").getFirst();
        Row study = rows(source.deck(), learner, "STUDY").getFirst();
        assertThat(study.studyDays()).isEqualTo(3);
        long version = Long.parseLong(decks.read(learner, copy).path("rowVersion").stringValue(null));

        jdbc.sql("DELETE FROM app_learning.deck_engagement WHERE deck_id = :d").param("d", source.deck()).update();
        for (int pass = 0; pass < 2; pass++) for (String statement : backfill()) jdbc.sql(statement).update();

        assertThat(all(source.deck())).as("one FORK and one STUDY row, however often the backfill runs").isEqualTo(2);
        Row rebuiltFork = rows(source.deck(), learner, "FORK").getFirst();
        assertThat(jdbc.sql("SELECT trust IS NULL AND new_account IS NULL FROM app_learning.deck_engagement WHERE deck_id = :d AND account_id = :a AND kind = 'FORK'")
                .param("d", source.deck()).param("a", learner).query(Boolean.class).single()).as("no claims are known for the past: NULL, which weighs 0.25 and is never new").isTrue();
        assertThat(rebuiltFork.firstAt()).isEqualTo(jdbc.sql("SELECT forked_at FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(java.sql.Timestamp.class).single().toInstant());
        assertThat(rebuiltFork.firstAt()).isBetween(fork.firstAt().minusSeconds(5), fork.firstAt().plusSeconds(5));
        Row rebuiltStudy = rows(source.deck(), learner, "STUDY").getFirst();
        assertThat(rebuiltStudy.studyDays()).isEqualTo(study.studyDays());
        assertThat(rebuiltStudy.lastStudyDate()).isEqualTo(study.lastStudyDate());
        assertThat(rebuiltStudy.firstAt()).isEqualTo(study.firstAt());
        assertThat(rebuiltStudy.lastAt()).isEqualTo(study.lastAt());
        // a deleted copy no longer counts as studied by the backfill (its FORK stays: the person copied it)
        decks.delete(learner, copy, version);
        jdbc.sql("DELETE FROM app_learning.deck_engagement WHERE deck_id = :d").param("d", source.deck()).update();
        for (String statement : backfill()) jdbc.sql(statement).update();
        assertThat(rows(source.deck(), learner, "FORK")).hasSize(1);
        assertThat(rows(source.deck(), learner, "STUDY")).isEmpty();
    }

    @Test
    void theAuthorsOwnSessionsAndSessionsOfOrdinaryDecksAreNoEngagement() {
        Source source = worlds.source("PUBLIC", 12, 3);
        StudyFixtures.Material own = source.materials().getFirst();
        List<Issued> issued = worlds.study.issue(own, "SCHEDULED", null);
        assertThat(issued).isNotEmpty();
        for (Issued presentation : issued) attempts.submit(source.author(), source.deck(), presentation.session(), StudyFixtures.attempt(presentation, StudyFixtures.textResponse("x")));
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id = :s").param("s", issued.getFirst().session()).query(String.class).single())
                .isEqualTo("COMPLETE");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_engagement WHERE account_id = :a").param("a", source.author()).query(Long.class).single()).isZero();
        assertThat(all(source.deck())).isZero();
        // the writer on a session that is not complete, one that does not exist and one of a foreign account writes nothing either
        transact(() -> engagement.studied(source.author(), UUID.randomUUID()));
        transact(() -> engagement.studied(UUID.randomUUID(), issued.getFirst().session()));
        assertThat(all(source.deck())).isZero();
    }

    @Test
    void theHotPathProbesTwoPrimaryKeysAndNothingElse() throws Exception {
        Source source = worlds.source("PUBLIC", 12, 1);
        String sql = studiedSql();
        UUID session = UUID.randomUUID();
        String plan = new TransactionTemplate(transactions).execute(status -> {
            // A test database is too small for the planner to prefer an index by cost: ask for the plan it would take if the tables were large (no sequential or bitmap scan of a
            // whole table) and take away the other indexes of study_session that start with the account (study_session_resume, and the partial study_session_study_dates
            // of V74 for the completed scheduled and practice dates), which compete with the keys only while the statistics say that an account has a single session.
            // Rolled back.
            jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
            jdbc.sql("DROP INDEX app_learning.study_session_resume").update();
            jdbc.sql("DROP INDEX app_learning.study_session_study_dates").update();
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
            var lines = jdbc.sql("EXPLAIN " + sql).param("account", source.author()).param("session", session).query(String.class).list();
            status.setRollbackOnly();
            return String.join("\n", lines);
        });
        // the session by its key (the primary key or the unique key of its id, both one row) and the copy by its primary key
        assertThat(plan).as(plan).containsPattern("Index Scan using study_session_(pkey|session_id_key)").contains("Index Scan using deck_fork_pkey")
                .doesNotContain("Seq Scan").doesNotContain("Bitmap");
        // the lookup of the copy is parameterized by the session's deck, never a scan that starts from the copies
        assertThat(plan).containsPattern("deck_fork_pkey[^\\n]*\\n\\s+Index Cond: \\(deck_id = ");
    }

    /** The text of the statement of {@link DeckEngagement#studied}, read from the class so that the test explains the SQL that runs. */
    private static String studiedSql() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/app/mnema/learning/community/DeckEngagement.java"));
        int from = source.indexOf("INSERT INTO app_learning.deck_engagement AS e(");
        int to = source.indexOf("\"\"\")", from);
        return source.substring(from, to);
    }
}
