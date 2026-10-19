package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static app.mnema.learning.onboarding.OnboardingFixture.JSON;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The weekly rhythm (OB-401, ADR-ONB-004) over the real application, servlet stack and PostgreSQL, with {@code learning.onboarding.rhythm.enabled} on. Every date is either one the test
 * writes into {@code study_session.local_study_date} or one the database clock gives; nothing reads the clock of the JVM, because a test that does fails on the night it runs past
 * midnight. The sessions of the counting tests are rows cloned with the dates the test wants ({@link RhythmSessions}) and completed together with the call of {@link WeeklyRhythm} in one
 * transaction, as the two completing writers do; the real writers ({@code AttemptRepository.completeSessionIfTerminal}, {@code StudySessionRepository.complete}) have their own tests below.
 *
 * <p>Statements the rhythm adds to the transaction that completes a session (counted at the JDBC connection, {@link #theCompletionCostsTheStatementsTheRhythmDocuments}): 0 for a REPLAY,
 * 1 (a primary-key read of the journey row) once the week is met, 4 (read, advisory lock, read, days) while the week is not met, 6 (those four, the guarded upsert of the journey row and
 * the insert of the milestone) for the completion that meets it. Rolled back; the notification is published at the commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "learning.onboarding.rhythm.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=16"})
@Import(StatementCounting.class)
class WeeklyRhythmIntegrationTest extends PostgresIntegrationTest {
    /** Mondays and the days around them; 2026-10-12 is a Monday. */
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final LocalDate NEXT_MON = MON.plusDays(7);
    private static final Map<String, String> NONE = Map.of();

    @LocalServerPort private int port;
    @Autowired private WeeklyRhythm rhythm;
    @Autowired private OnboardingService onboarding;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private AttemptService attempts;
    @Autowired private MediaCatalog media;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
    }

    // ---- helpers ----

    private UUID template() {
        return RhythmSessions.template(decks, items, exercises, sessions, media, jdbc);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    /** An account Identity marks new (the tour is offered to it by its first read). */
    private static UUID newAccount() {
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, false, true);
        return account;
    }

    /** A session of the account completed on {@code date}, and the call of the rhythm that its completion makes, in one transaction. */
    private void complete(UUID account, String mode, LocalDate date) {
        UUID template = template();
        tx().executeWithoutResult(status -> {
            RhythmSessions.complete(jdbc, template, account, mode, date);
            rhythm.sessionCompleted(account, mode, date);
        });
    }

    /** A completed session that nothing counted (the rhythm is not called): the history before the rhythm looks at it. */
    private void stored(UUID account, String mode, LocalDate date) {
        RhythmSessions.complete(jdbc, template(), account, mode, date);
    }

    /** The row of a NOT_OFFERED account that chose a target: what {@code PUT /preferences} leaves, at version 1. */
    private void target(UUID account, int target) {
        jdbc.sql("INSERT INTO app_learning.onboarding_journey(account_id,tour_version,state,weekly_target,replay_count,weeks_met,row_version,updated_at) "
                + "VALUES (:a,1,'NOT_OFFERED',:target,0,0,1,statement_timestamp())").param("a", account).param("target", target).update();
    }

    private long rows(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account).query(Long.class).single();
    }

    private String column(UUID account, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account)
                .query((row, number) -> row.getString(1)).list().getFirst();
    }

    private boolean reached(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a AND milestone_key='RHYTHM_WEEK' AND achieved_at IS NOT NULL")
                .param("a", account).query(Long.class).single() == 1;
    }

    private long announcements(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id=:a AND dedupe_key='path:RHYTHM_WEEK'").param("a", account).query(Long.class).single();
    }

    private double reachedCount() {
        return meters.counter("mnema_path_milestone_total", "key", "RHYTHM_WEEK", "source", "LIVE").count();
    }

    /** The database's local date in {@code zone}: the «today» the server derives, never the JVM's. */
    private LocalDate today(String zone) {
        return jdbc.sql("SELECT (statement_timestamp() AT TIME ZONE :zone)::date").param("zone", zone).query(LocalDate.class).single();
    }

    private HttpResponse<String> get(UUID account, String zoneinfo) {
        return HttpIdentityFixture.send(port, "GET", "/onboarding", HttpIdentityFixture.reader(account, zoneinfo), null, NONE);
    }

    private HttpResponse<String> preferences(UUID account, String zoneinfo, ObjectNode body) {
        return HttpIdentityFixture.send(port, "PUT", "/onboarding/preferences", HttpIdentityFixture.reader(account, zoneinfo), body.toString(), NONE);
    }

    private HttpResponse<String> journey(UUID account, ObjectNode body) {
        return HttpIdentityFixture.send(port, "PUT", "/onboarding/journey", HttpIdentityFixture.reader(account), body.toString(), NONE);
    }

    private static ObjectNode preference(String rowVersion) {
        return JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("rowVersion", rowVersion);
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.stringValue()));
        return values;
    }

    // ---- what counts ----

    @Test
    void aPracticeOnANewDayCountsAndThirdDayMeetsTheDefaultTargetOfThreeCreatingTheNotOfferedRow() {
        UUID account = UUID.randomUUID();
        double before = reachedCount();

        complete(account, "PRACTICE", MON);
        complete(account, "SCHEDULED", MON.plusDays(1));
        assertThat(rows(account)).as("nothing is written before the week is met").isZero();
        assertThat(reached(account)).isFalse();

        complete(account, "PRACTICE", MON.plusDays(3));

        assertThat(rows(account)).isOne();
        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "last_met_week")).isEqualTo("2026-10-12");
        assertThat(column(account, "state")).isEqualTo("NOT_OFFERED");
        assertThat(column(account, "cohort")).isNull();
        assertThat(column(account, "offered_at")).isNull();
        assertThat(column(account, "weekly_target")).isNull();
        assertThat(column(account, "row_version")).as("the rhythm never makes the tour's compare-and-set stale").isEqualTo("0");
        assertThat(reached(account)).isTrue();
        assertThat(announcements(account)).isOne();
        assertThat(reachedCount() - before).isEqualTo(1.0);

        // more days of the met week change nothing
        complete(account, "PRACTICE", MON.plusDays(4));
        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(announcements(account)).isOne();
    }

    @Test
    void aSecondSessionTheSameDayAndAReplayChangeNothing() {
        UUID account = UUID.randomUUID();
        target(account, 2);

        complete(account, "PRACTICE", MON);
        complete(account, "SCHEDULED", MON);
        complete(account, "PRACTICE", MON);
        assertThat(column(account, "weeks_met")).as("one date, however many sessions").isEqualTo("0");

        // a replay is a completed session too, on a date of its own: neither the row nor the mode counts it
        complete(account, "REPLAY", MON.plusDays(1));
        assertThat(column(account, "weeks_met")).isEqualTo("0");
        assertThat(column(account, "last_met_week")).isNull();
        assertThat(reached(account)).isFalse();

        // an unfinished session of another date is not a day either
        UUID template = template();
        tx().executeWithoutResult(status -> {
            RhythmSessions.insert(jdbc, template, account, "PRACTICE", MON.plusDays(2), "ACTIVE");
            rhythm.sessionCompleted(account, "PRACTICE", MON.plusDays(2));
        });
        assertThat(column(account, "weeks_met")).isEqualTo("0");

        complete(account, "SCHEDULED", MON.plusDays(3));
        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "row_version")).isEqualTo("1");
    }

    @Test
    void theIsoWeekEndsOnSundayAndTheNextMondayStartsAnEmptyWeek() {
        UUID account = UUID.randomUUID();
        target(account, 2);

        complete(account, "PRACTICE", MON.plusDays(5));
        complete(account, "PRACTICE", MON.plusDays(6));
        assertThat(column(account, "weeks_met")).as("Saturday and Sunday are the last two days of the week of 12 October").isEqualTo("1");
        assertThat(column(account, "last_met_week")).isEqualTo("2026-10-12");

        complete(account, "PRACTICE", NEXT_MON);
        assertThat(column(account, "weeks_met")).as("Monday starts a new week; Sunday does not carry over").isEqualTo("1");
        assertThat(column(account, "last_met_week")).isEqualTo("2026-10-12");

        complete(account, "PRACTICE", NEXT_MON.plusDays(1));
        assertThat(column(account, "weeks_met")).isEqualTo("2");
        assertThat(column(account, "last_met_week")).isEqualTo("2026-10-19");
    }

    @Test
    void aLaterWeekCountsAgainAndTheMilestoneIsAnnouncedOnce() {
        UUID account = UUID.randomUUID();
        target(account, 1);

        complete(account, "PRACTICE", MON);
        complete(account, "PRACTICE", NEXT_MON);
        complete(account, "SCHEDULED", NEXT_MON.plusDays(7));

        assertThat(column(account, "weeks_met")).isEqualTo("3");
        assertThat(column(account, "last_met_week")).isEqualTo("2026-10-26");
        assertThat(reached(account)).isTrue();
        assertThat(announcements(account)).as("the first met week is the milestone, the next ones are not").isOne();
    }

    @Test
    void aLateCompletionOfAnOlderWeekAfterANewerOneWasCountedDoesNotCount() {
        UUID account = UUID.randomUUID();
        target(account, 2);
        complete(account, "PRACTICE", NEXT_MON);
        complete(account, "PRACTICE", NEXT_MON.plusDays(1));
        assertThat(column(account, "weeks_met")).isEqualTo("1");

        // the sessions of the week before arrive late (a client that was offline): the stored dates are older than last_met_week
        complete(account, "PRACTICE", MON);
        complete(account, "PRACTICE", MON.plusDays(1));

        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "last_met_week")).isEqualTo("2026-10-19");
        // and the same week is counted once, however it is completed again
        complete(account, "SCHEDULED", NEXT_MON.plusDays(2));
        assertThat(column(account, "weeks_met")).isEqualTo("1");
    }

    // ---- races ----

    /** Each date is completed by its own transaction; they all insert their session, wait for each other, and only then count, so none of them sees the others' rows. */
    private void concurrently(UUID account, String mode, LocalDate... dates) throws Exception {
        UUID template = template();
        CyclicBarrier barrier = new CyclicBarrier(dates.length);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (LocalDate date : dates) {
                done.add(pool.submit(() -> tx().executeWithoutResult(status -> {
                    RhythmSessions.complete(jdbc, template, account, mode, date);
                    try {
                        barrier.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException | BrokenBarrierException | TimeoutException failure) {
                        throw new IllegalStateException(failure);
                    }
                    rhythm.sessionCompleted(account, mode, date);
                })));
            }
            for (Future<?> each : done) each.get(60, TimeUnit.SECONDS);
        }
    }

    @Test
    void twoCompletionsThatBothMeetTheTargetCountTheWeekExactlyOnce() throws Exception {
        UUID account = UUID.randomUUID();
        stored(account, "PRACTICE", MON);
        stored(account, "PRACTICE", MON.plusDays(1));
        double before = reachedCount();

        concurrently(account, "PRACTICE", MON.plusDays(2), MON.plusDays(3));

        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "last_met_week")).isEqualTo("2026-10-12");
        assertThat(reachedCount() - before).as("one transaction reached the milestone").isEqualTo(1.0);
        assertThat(announcements(account)).isOne();
    }

    @Test
    void twoCompletionsThatTogetherMeetTheTargetCountItBecauseOfTheLock() throws Exception {
        UUID account = UUID.randomUUID();
        stored(account, "PRACTICE", MON);
        double before = reachedCount();

        // three days, default target 3: neither transaction sees the other's uncommitted row, so without the lock both would count two and the week would never be met
        concurrently(account, "SCHEDULED", MON.plusDays(1), MON.plusDays(2));

        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(reached(account)).isTrue();
        assertThat(reachedCount() - before).isEqualTo(1.0);
    }

    @Test
    void manyCompletionsOfOneAccountMeetingItsTargetTogetherCountOneWeek() throws Exception {
        UUID account = UUID.randomUUID();
        target(account, 4);

        concurrently(account, "PRACTICE", MON, MON.plusDays(1), MON.plusDays(2), MON.plusDays(3), MON.plusDays(4), MON.plusDays(5));

        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "row_version")).isEqualTo("1");
    }

    // ---- the row, the version, the flows ----

    @Test
    void aCompletionNeverChangesTheRowVersionSoTheNextTourCommandIsNotStale() {
        UUID account = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.onboarding_journey(account_id,tour_version,state,cohort,step_key,offered_at,weekly_target,replay_count,weeks_met,row_version,updated_at) "
                + "VALUES (:a,1,'IN_PROGRESS','TOUR','welcome',statement_timestamp(),2,0,0,3,statement_timestamp())").param("a", account).update();

        complete(account, "PRACTICE", MON);
        complete(account, "PRACTICE", MON.plusDays(1));

        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "row_version")).isEqualTo("3");
        assertThat(column(account, "state")).isEqualTo("IN_PROGRESS");
        HttpResponse<String> pause = journey(account, JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("rowVersion", "3").put("action", "PAUSE"));
        assertThat(pause.statusCode()).as(pause.body()).isEqualTo(200);
        assertThat(json(pause).path("journey").path("rowVersion").stringValue()).isEqualTo("4");
        assertThat(column(account, "weeks_met")).as("a tour command keeps the counters").isEqualTo("1");
    }

    @Test
    void aLoweredTargetMeetsTheCurrentWeekAndOnlyOnce() {
        UUID account = UUID.randomUUID();
        String zone = "Asia/Tokyo";
        LocalDate today = today(zone);
        stored(account, "PRACTICE", today);
        double before = reachedCount();

        // no row, version "0": the target creates the NOT_OFFERED row (version 1) and the week of «today» in Tokyo already has one day
        ObjectNode lower = preference("0").put("weeklyTarget", 1);
        HttpResponse<String> answer = preferences(account, zone, lower);

        JsonNode view = json(answer);
        assertThat(view.path("journey").path("state").stringValue()).isEqualTo("NOT_OFFERED");
        assertThat(view.path("journey").path("rowVersion").stringValue()).isEqualTo("1");
        assertThat(view.path("journey").path("weeklyTarget").intValue()).isOne();
        assertThat(view.path("rhythm").path("weeksMet").intValue()).as("the answer is read after the week was counted").isOne();
        assertThat(texts(view.path("rhythm").path("days"))).containsExactly(today.toString());
        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "last_met_week")).isEqualTo(WeeklyRhythm.weekOf(today).toString());
        assertThat(reached(account)).isTrue();
        assertThat(reachedCount() - before).isEqualTo(1.0);

        // raising it, lowering it again and clearing it count nothing more: the week is counted
        assertThat(preferences(account, zone, preference("1").put("weeklyTarget", 7)).statusCode()).isEqualTo(200);
        assertThat(preferences(account, zone, preference("2").put("weeklyTarget", 1)).statusCode()).isEqualTo(200);
        assertThat(preferences(account, zone, preference("3").putNull("weeklyTarget")).statusCode()).isEqualTo(200);
        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "row_version")).isEqualTo("4");
    }

    @Test
    void aTargetThatTheCurrentWeekDoesNotReachCountsNothingAndAnExperienceAnswerNeverChecksTheWeek() {
        UUID account = UUID.randomUUID();
        String zone = "Asia/Tokyo";
        stored(account, "PRACTICE", today(zone));

        assertThat(preferences(account, zone, preference("0").put("weeklyTarget", 2)).statusCode()).isEqualTo(200);
        assertThat(column(account, "weeks_met")).isEqualTo("0");
        assertThat(column(account, "last_met_week")).isNull();
        assertThat(reached(account)).isFalse();

        // an answer that does not touch the target never looks at the week, even one that is already met by the days
        UUID other = UUID.randomUUID();
        stored(other, "PRACTICE", today(zone));
        target(other, 1);
        assertThat(preferences(other, zone, preference("1").put("experience", "NEW")).statusCode()).isEqualTo(200);
        assertThat(column(other, "weeks_met")).isEqualTo("0");
    }

    @Test
    void aStoredNotOfferedRowOfANewAccountBecomesTheOfferedRowOnTheFirstReadAndKeepsItsRhythm() {
        UUID account = newAccount();
        target(account, 2);
        complete(account, "PRACTICE", MON);
        complete(account, "PRACTICE", MON.plusDays(1));
        assertThat(column(account, "state")).isEqualTo("NOT_OFFERED");

        JsonNode first = json(get(account, null));

        assertThat(first.path("journey").path("state").stringValue()).isEqualTo("OFFERED");
        assertThat(first.path("journey").path("eligible").booleanValue()).isTrue();
        assertThat(first.path("journey").path("autoOffer").booleanValue()).isTrue();
        assertThat(first.path("journey").path("offeredAt").isNull()).isFalse();
        assertThat(first.path("journey").path("weeklyTarget").intValue()).isEqualTo(2);
        assertThat(first.path("rhythm").path("weeksMet").intValue()).isOne();
        assertThat(column(account, "state")).isEqualTo("OFFERED");
        assertThat(column(account, "cohort")).isEqualTo("UNASSIGNED");
        assertThat(column(account, "offered_at")).isNotNull();
        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "row_version")).as("version 1 -> 2 by the upgrade").isEqualTo("2");

        JsonNode again = json(get(account, null));
        assertThat(again.path("journey")).isEqualTo(first.path("journey"));
        assertThat(column(account, "row_version")).isEqualTo("2");
    }

    @Test
    void aStoredNotOfferedRowOfAnAccountThatIsNotNewStaysAsItIsAndReadsLikeNoRow() {
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, true, false);
        target(account, 4);

        JsonNode view = json(get(account, null));

        assertThat(view.path("journey").path("state").stringValue()).isEqualTo("NOT_OFFERED");
        assertThat(view.path("journey").path("eligible").booleanValue()).isFalse();
        assertThat(view.path("journey").path("autoOffer").booleanValue()).isFalse();
        assertThat(view.path("journey").path("offeredAt").isNull()).isTrue();
        assertThat(view.path("journey").path("weeklyTarget").intValue()).isEqualTo(4);
        assertThat(view.path("journey").path("rowVersion").stringValue()).isEqualTo("1");
        assertThat(column(account, "state")).isEqualTo("NOT_OFFERED");
        assertThat(column(account, "row_version")).isEqualTo("1");
    }

    // ---- the view ----

    @Test
    void theViewCarriesTheWeekOfTheAccountsOwnToday() {
        UUID account = UUID.randomUUID();
        String zone = "Europe/Berlin";
        LocalDate today = today(zone);
        LocalDate week = WeeklyRhythm.weekOf(today);
        stored(account, "PRACTICE", week);
        stored(account, "SCHEDULED", today);
        stored(account, "SCHEDULED", week.minusDays(1));
        stored(account, "PRACTICE", week.plusDays(7));
        RhythmSessions.insert(jdbc, template(), account, "PRACTICE", today, "ACTIVE");
        stored(account, "REPLAY", today);

        JsonNode view = json(get(account, zone));

        JsonNode rhythm = view.path("rhythm");
        assertThat(rhythm.propertyNames()).containsExactlyInAnyOrder("weekStart", "today", "target", "days", "weeksMet");
        assertThat(rhythm.path("weekStart").stringValue()).isEqualTo(week.toString());
        assertThat(rhythm.path("today").stringValue()).isIn(today.toString(), today(zone).toString());
        assertThat(rhythm.path("target").intValue()).as("no row: the default").isEqualTo(3);
        assertThat(rhythm.path("weeksMet").intValue()).isZero();
        assertThat(texts(rhythm.path("days"))).as("ascending distinct dates of the week; the week before, the week after, the unfinished session and the replay are not in it")
                .containsExactlyElementsOf(new TreeSet<>(List.of(week.toString(), today.toString())));
        assertThat(rows(account)).as("a read writes nothing for an account that is not new").isZero();
        assertThat(view.path("path").path("hidden")).extracting(JsonNode::stringValue).doesNotContain("RHYTHM_WEEK");
        assertThat(view.path("milestones")).extracting(milestone -> milestone.path("key").stringValue()).contains("RHYTHM_WEEK");
    }

    @Test
    void aChangeOfTimeZoneMovesTodayAndTheWeekButNoSession() {
        UUID account = UUID.randomUUID();
        // the two ends of the world differ by 25 hours: their local dates are never the same
        String east = "Pacific/Kiritimati";
        String west = "Pacific/Pago_Pago";
        LocalDate eastToday = today(east);
        LocalDate westToday = today(west);
        stored(account, "PRACTICE", westToday);
        stored(account, "PRACTICE", eastToday);
        List<String> dates = jdbc.sql("SELECT local_study_date::text FROM app_learning.study_session WHERE account_id=:a ORDER BY local_study_date").param("a", account)
                .query(String.class).list();

        JsonNode inEast = json(get(account, east)).path("rhythm");
        JsonNode inWest = json(get(account, west)).path("rhythm");
        JsonNode inUtc = json(get(account, null)).path("rhythm");
        JsonNode inNowhere = json(get(account, "Not/AZone")).path("rhythm");

        assertThat(inEast.path("today").stringValue()).isNotEqualTo(inWest.path("today").stringValue());
        assertThat(LocalDate.parse(inEast.path("weekStart").stringValue())).isEqualTo(WeeklyRhythm.weekOf(LocalDate.parse(inEast.path("today").stringValue())));
        assertThat(LocalDate.parse(inWest.path("weekStart").stringValue())).isEqualTo(WeeklyRhythm.weekOf(LocalDate.parse(inWest.path("today").stringValue())));
        for (JsonNode week : new JsonNode[] {inEast, inWest, inUtc, inNowhere}) {
            LocalDate start = LocalDate.parse(week.path("weekStart").stringValue());
            assertThat(texts(week.path("days"))).as(week.toString()).containsExactlyElementsOf(dates.stream().filter(day -> {
                LocalDate date = LocalDate.parse(day);
                return !date.isBefore(start) && date.isBefore(start.plusDays(7));
            }).toList());
        }
        // an absent or invalid zone is UTC, as Study takes it
        assertThat(inNowhere.path("today")).isEqualTo(inUtc.path("today"));
        // the sessions are what they were
        assertThat(jdbc.sql("SELECT local_study_date::text FROM app_learning.study_session WHERE account_id=:a ORDER BY local_study_date").param("a", account)
                .query(String.class).list()).isEqualTo(dates);
    }

    @Test
    void theAnswersOfTheCommandsCarryTheWeekToo() {
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, false, false);
        stored(account, "PRACTICE", today("UTC"));

        JsonNode preferences = json(preferences(account, null, preference("0").put("experience", "NEW")));
        assertThat(preferences.path("rhythm").path("days")).hasSize(1);
        JsonNode replay = json(journey(account, JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("rowVersion", "1").put("action", "REPLAY")
                .put("stepKey", "choose")));
        assertThat(replay.path("journey").path("state").stringValue()).isEqualTo("IN_PROGRESS");
        assertThat(replay.path("rhythm").path("target").intValue()).isEqualTo(3);
        assertThat(replay.path("rhythm").path("days")).hasSize(1);
    }

    // ---- the real writers ----

    private Material materialWithExercise(UUID account) {
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        Material material = fixtures.material(account);
        fixtures.publish(material, fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Вопрос?")), StudyFixtures.blocks(StudyFixtures.text("Ответ")), "ответ"), "Цель");
        return material;
    }

    /** Issues a session of {@code mode} and answers every presentation; the last answer completes it (AttemptRepository.completeSessionIfTerminal). */
    private UUID study(Material material, String mode, UUID source) {
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        List<Issued> issued = fixtures.issue(material, mode, source);
        assertThat(issued).isNotEmpty();
        for (Issued presentation : issued) {
            attempts.submit(material.actor(), material.deck(), presentation.session(), StudyFixtures.attempt(presentation, StudyFixtures.textResponse("ответ")));
        }
        UUID session = issued.getFirst().session();
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id=:s").param("s", session).query(String.class).single()).isEqualTo("COMPLETE");
        return session;
    }

    private LocalDate studyDateOf(UUID session) {
        return jdbc.sql("SELECT local_study_date FROM app_learning.study_session WHERE session_id=:s").param("s", session).query(LocalDate.class).single();
    }

    @Test
    void theLastAnswerOfARealPracticeSessionMeetsTheWeekThroughTheAttemptWriter() {
        UUID account = UUID.randomUUID();
        Material material = materialWithExercise(account);
        target(account, 1);

        UUID session = study(material, "PRACTICE", null);

        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "last_met_week")).isEqualTo(WeeklyRhythm.weekOf(studyDateOf(session)).toString());
        assertThat(reached(account)).isTrue();
        assertThat(column(account, "row_version")).isEqualTo("1");
    }

    @Test
    void theIssuerThatEndsAnExhaustedSessionMeetsTheWeekToo() {
        UUID account = UUID.randomUUID();
        Material material = materialWithExercise(account);
        target(account, 1);
        UUID session = new StudyFixtures(decks, items, exercises, sessions, media, jdbc).issue(material, "SCHEDULED", null).getFirst().session();
        assertThat(column(account, "weeks_met")).isEqualTo("0");

        // nothing is pending and the budget is spent: the next read of the presentations completes the session in the issuer (StudySessionRepository.complete)
        jdbc.sql("UPDATE app_learning.study_session SET budget = issued_count, max_new_objectives = issued_new_objectives, batch_start = issued_count, batch_size = 0 WHERE session_id = :s")
                .param("s", session).update();
        sessions.presentations(account, material.deck(), session);

        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id=:s").param("s", session).query(String.class).single()).isEqualTo("COMPLETE");
        assertThat(column(account, "weeks_met")).isEqualTo("1");
        assertThat(column(account, "last_met_week")).isEqualTo(WeeklyRhythm.weekOf(studyDateOf(session)).toString());
    }

    @Test
    void aRealReplayOfAScheduledSessionIsNotADayOfTheRhythm() {
        UUID account = UUID.randomUUID();
        Material material = materialWithExercise(account);
        target(account, 2);
        UUID scheduled = study(material, "SCHEDULED", null);
        assertThat(column(account, "weeks_met")).as("one day of two").isEqualTo("0");

        UUID replay = study(material, "REPLAY", scheduled);

        assertThat(jdbc.sql("SELECT mode FROM app_learning.study_session WHERE session_id=:s").param("s", replay).query(String.class).single()).isEqualTo("REPLAY");
        assertThat(column(account, "weeks_met")).isEqualTo("0");
        assertThat(reached(account)).isFalse();
        // and the days read back are the one scheduled date
        assertThat(texts(json(get(account, null)).path("rhythm").path("days"))).containsExactly(studyDateOf(scheduled).toString());
    }

    // ---- statements ----

    @Test
    void theCompletionCostsTheStatementsTheRhythmDocuments() {
        UUID account = UUID.randomUUID();
        target(account, 2);
        stored(account, "PRACTICE", MON);
        stored(account, "PRACTICE", MON.plusDays(1));
        stored(account, "PRACTICE", NEXT_MON);

        // a replay is not looked at
        assertThat(counted(() -> rhythm.sessionCompleted(account, "REPLAY", MON))).as("a replay").isEmpty();
        // the week of NEXT_MON has one day of two: the journey read, the lock, the journey read under the lock, the days
        List<String> notMet = counted(() -> rhythm.sessionCompleted(account, "PRACTICE", NEXT_MON));
        assertThat(notMet).as("the week is not met: read, lock, read, days").hasSize(4);
        assertThat(notMet.get(0)).startsWith("SELECT").contains("onboarding_journey");
        assertThat(notMet.get(1)).contains("pg_advisory_xact_lock");
        assertThat(notMet.get(2)).startsWith("SELECT").contains("onboarding_journey");
        assertThat(notMet.get(3)).startsWith("SELECT DISTINCT local_study_date").contains("study_session");
        // the week of MON has two days: the completion that meets it also writes the counter and the milestone
        List<String> meeting = counted(() -> rhythm.sessionCompleted(account, "PRACTICE", MON));
        assertThat(meeting).as("the week is met: the four, the guarded upsert, the milestone").hasSize(6);
        assertThat(meeting.get(4)).contains("INSERT INTO app_learning.onboarding_journey").contains("DO UPDATE").doesNotContain("row_version =");
        assertThat(meeting.get(5)).contains("INSERT INTO app_learning.account_milestone");

        // once the week is met (committed), a completion of it costs one primary-key read
        tx().executeWithoutResult(status -> rhythm.sessionCompleted(account, "PRACTICE", MON));
        List<String> met = counted(() -> rhythm.sessionCompleted(account, "SCHEDULED", MON.plusDays(2)));
        assertThat(met).as("the week is met").hasSize(1);
        assertThat(met.getFirst()).startsWith("SELECT").contains("onboarding_journey").contains("account_id");
    }

    /** The statements of {@code work} inside a transaction that is rolled back (nothing of the account changes). */
    private List<String> counted(Runnable work) {
        AtomicReference<List<String>> log = new AtomicReference<>();
        tx().executeWithoutResult(status -> {
            StatementCounting.start();
            work.run();
            log.set(StatementCounting.stop());
            status.setRollbackOnly();
        });
        return log.get();
    }

    // ---- the plan ----

    @Test
    void theDaysOfAWeekAreReadFromThePartialIndexOfV74OnAPopulatedTable() {
        UUID template = template();
        UUID account = UUID.randomUUID();
        // 300 other accounts with 100 sessions each, and the account with 120 over a year, so that the planner sees a table worth an index
        List<String> columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                .query(String.class).list();
        String selected = String.join(",", columns.stream().map(column -> switch (column) {
            case "account_id" -> "CASE WHEN g <= 120 THEN CAST(:account AS uuid) ELSE md5('rhythm-plan-' || (g % 300))::uuid END";
            case "session_id", "command_id" -> "gen_random_uuid()";
            case "mode" -> "CASE WHEN g % 5 = 0 THEN 'PRACTICE' ELSE 'SCHEDULED' END";
            case "practice_order" -> "CASE WHEN g % 5 = 0 THEN 'SEEDED' END";
            case "status" -> "'COMPLETE'";
            case "local_study_date" -> "DATE '2026-01-01' + (g % 365)";
            case "completed_at" -> "statement_timestamp()";
            default -> "s." + column;
        }).toList());
        try {
            jdbc.sql("INSERT INTO app_learning.study_session(" + String.join(",", columns) + ") SELECT " + selected
                    + " FROM app_learning.study_session s CROSS JOIN generate_series(1, 30000) g WHERE s.session_id = :template")
                    .param("account", account).param("template", template).update();
            jdbc.sql("ANALYZE app_learning.study_session").update();

            String plan = jdbc.sql("EXPLAIN (FORMAT JSON) " + RhythmRepository.DAYS).param("account", account).param("from", MON).param("to", NEXT_MON)
                    .query(String.class).single();

            assertThat(plan).contains("\"Index Name\": \"study_session_study_dates\"").doesNotContain("Seq Scan");
            // the same statement answers what the rows say
            assertThat(jdbc.sql(RhythmRepository.DAYS).param("account", account).param("from", MON).param("to", NEXT_MON).query(LocalDate.class).list())
                    .isSortedAccordingTo(LocalDate::compareTo).doesNotHaveDuplicates().allSatisfy(day -> assertThat(day).isBetween(MON, NEXT_MON.minusDays(1)));
        } finally {
            jdbc.sql("DELETE FROM app_learning.study_session WHERE account_id = :account OR account_id IN (SELECT md5('rhythm-plan-' || g)::uuid FROM generate_series(0, 299) g)")
                    .param("account", account).update();
        }
    }
}
