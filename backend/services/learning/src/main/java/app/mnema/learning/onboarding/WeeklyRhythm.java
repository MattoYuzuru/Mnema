package app.mnema.learning.onboarding;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The weekly rhythm (OB-401, ADR-ONB-004, {@code contracts/onboarding/README.md} «Weekly rhythm»): a week is met when the account has completed SCHEDULED or PRACTICE sessions on at
 * least {@code target} distinct local study dates of its ISO week (Monday first; REPLAY never counts), and every met week adds one to {@code weeks_met} of the account's journey row.
 * The days are never stored: they are read from {@code study_session} by the {@code local_study_date} Study wrote, so a change of time zone moves no session. Only while
 * {@code learning.onboarding.rhythm.enabled} is on ({@link RhythmSettings}); history is not replayed, because the target of a past week is not known.
 *
 * <p><b>Once per week, also under a race.</b> {@code last_met_week} (the Monday of the last counted week) is the idempotency key of the increment: the guarded upsert of
 * {@link RhythmRepository} adds a week only if the stored one is older, so a repeat, a late completion of an older week and a lost race change nothing. The days are counted under
 * an account-scoped transaction advisory lock, taken only while the week is not yet known to be met: of two completions that run together and meet the target together (one session
 * on each of two dates) the second waits for the first's commit and then counts both sessions, so the week is counted by exactly one of them, never by none. Once the week is met the
 * completion costs one primary-key read and takes no lock. The upsert never touches {@code row_version}, which is the client's compare-and-set for the tour commands.
 *
 * <p><b>Lock order.</b> The lock is taken before anything that publishes. The milestone recorder announces {@code RHYTHM_WEEK} in {@code beforeCommit}, where the notification
 * publisher takes the owner's cursor row until the commit and expects to be the last write of the transaction; every writer here takes the advisory lock first, then writes the
 * journey row, then (through the recorder) the milestone, and only the commit-time publication reaches the cursor. {@code PUT /preferences} takes the same lock before it updates the
 * journey row, so the two cannot wait for each other's row and lock in opposite orders.
 *
 * <p>Every write method is {@link Propagation#MANDATORY}: the week is counted in the transaction of the action that makes it true, and a rolled-back action counts nothing.
 */
@Service
public class WeeklyRhythm {
    /** The target of an account that has not chosen one (1-7 is the survey's range). */
    static final int DEFAULT_TARGET = 3;
    /** The modes whose completed sessions count: REPLAY never does. */
    static final Set<String> COUNTED_MODES = Set.of("SCHEDULED", "PRACTICE");

    private final RhythmRepository repository;
    private final RhythmSettings settings;
    private final MilestoneRecorder milestones;

    WeeklyRhythm(RhythmRepository repository, RhythmSettings settings, MilestoneRecorder milestones) {
        this.repository = repository;
        this.settings = settings;
        this.milestones = milestones;
    }

    boolean enabled() {
        return settings.enabled();
    }

    /**
     * A study session of the account has just completed (the writers call it right after {@link MilestoneRecorder#sessionCompleted}, in the branch of the row that changed to
     * COMPLETE). After the week is met this costs one primary-key read.
     *
     * @param mode the stored mode of the session; only SCHEDULED and PRACTICE count
     * @param studyDate the session's {@code local_study_date}: the week it belongs to
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sessionCompleted(UUID account, String mode, LocalDate studyDate) {
        if (!settings.enabled() || !COUNTED_MODES.contains(mode)) return;
        meetWeek(account, weekOf(studyDate));
    }

    /**
     * Serializes the rhythm writers of {@code account} until the end of the transaction. {@code PUT /preferences} calls it before it updates the journey row when it changes the
     * target, so that it and a completion take the lock before the row (see the lock order above). Does nothing while the rhythm is off.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID account) {
        if (settings.enabled()) repository.lock(account);
    }

    /**
     * The weekly target of the account has just changed: a lowered one may already be met by the days of the current week, which no completion would count until the next session.
     * The current week is the account's own (the zone is the token's {@code zoneinfo}, UTC when absent or invalid, as Study takes it).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void targetChanged(UUID account, String zoneinfo) {
        if (!settings.enabled()) return;
        meetWeek(account, weekOf(today(zoneinfo)));
    }

    private void meetWeek(UUID account, LocalDate week) {
        if (counted(repository.journey(account), week)) return;
        repository.lock(account);
        // Under the lock: the journey row as the last committer left it (a target changed or a week counted while this waited), then the days with that committer's sessions.
        Optional<RhythmRepository.Journey> journey = repository.journey(account);
        if (counted(journey, week)) return;
        int target = journey.map(RhythmRepository.Journey::weeklyTarget).orElse(DEFAULT_TARGET);
        if (repository.days(account, week).size() < target) return;
        if (repository.meet(account, week)) milestones.reach(account, MilestoneKey.RHYTHM_WEEK, 1);
    }

    private static boolean counted(Optional<RhythmRepository.Journey> journey, LocalDate week) {
        return journey.map(RhythmRepository.Journey::lastMetWeek).filter(last -> !last.isBefore(week)).isPresent();
    }

    /**
     * The rhythm of the account for the view: the week of its local today, its target, the days of the week so far and the weeks met; {@code null} while the rhythm is off. Three
     * reads: the database clock, the journey row by primary key and the days.
     *
     * @param zoneinfo the token's {@code zoneinfo} claim, or {@code null}
     */
    RhythmState state(UUID account, String zoneinfo) {
        if (!settings.enabled()) return null;
        LocalDate today = today(zoneinfo);
        LocalDate week = weekOf(today);
        Optional<RhythmRepository.Journey> journey = repository.journey(account);
        int target = journey.map(RhythmRepository.Journey::weeklyTarget).orElse(DEFAULT_TARGET);
        List<LocalDate> days = repository.days(account, week);
        return new RhythmState(week, today, target, days, journey.map(RhythmRepository.Journey::weeksMet).orElse(0));
    }

    /** The account's local date by the clock of the database (never the application's) in the account's zone. */
    private LocalDate today(String zoneinfo) {
        return repository.now().atZone(zone(zoneinfo)).toLocalDate();
    }

    /** The Monday of the ISO week of {@code date}. */
    static LocalDate weekOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Study's rule ({@code StudySessionService.timezone}): the claim when it is a zone, UTC when it is absent or invalid. */
    private static ZoneId zone(String zoneinfo) {
        try {
            return ZoneId.of(zoneinfo == null ? "UTC" : zoneinfo);
        } catch (DateTimeException failure) {
            return ZoneId.of("UTC");
        }
    }
}
