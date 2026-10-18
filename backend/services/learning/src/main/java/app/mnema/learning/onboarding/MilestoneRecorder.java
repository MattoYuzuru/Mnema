package app.mnema.learning.onboarding;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the facts of the «Путь Mnema» in the transaction of the domain action that makes them true (OB-301, ADR-ONB-002, {@code contracts/onboarding/milestones.json}). The domain
 * writers call it and know nothing else of the path. Every method is {@link Propagation#MANDATORY}: a milestone is never written outside the action's transaction, so a rolled-back
 * action leaves no milestone and no notification. Each call is ONE statement ({@link MilestoneRepository}) with no external call; an already achieved milestone costs that
 * statement and writes nothing: a milestone of target 1 is an {@code INSERT ... ON CONFLICT DO NOTHING} that takes no lock on the existing row, while a counter or a stage is an
 * upsert, which PostgreSQL runs with a lock on the conflicting row (so two transactions of one account that advance counters serialize on it, briefly).
 *
 * <p><b>Known benign races</b> (accepted, each self-heals or only blurs a number): two sessions of one account that complete concurrently on two different dates both read
 * {@code TWO_DAYS} at its old progress and the existence probe sees neither of them (their transactions are not yet committed), so each writes level 1 and the second date is
 * counted only by the next completion; two transactions that achieve different milestones of one account at the same moment each count the milestones committed when they publish,
 * so their notifications may carry the same {@code done}/{@code total}; the cursor row of the notification center serializes the publications themselves.
 *
 * <p><b>Notification.</b> When a call reaches the target of a milestone the recorder remembers it for the transaction and registers, once, a {@link TransactionSynchronization} that
 * publishes one {@code PATH_MILESTONE} per milestone reached ({@code path:<key>}, params {@code key}, {@code done}, {@code total}) in {@code beforeCommit}: the notification
 * publisher takes the owner's cursor-row lock until the commit, and its contract asks to be the last write of the transaction, which {@code beforeCommit} is for every caller whatever
 * order its writers run in. Only while {@code learning.onboarding.enabled} is on (the rows are written either way) and never for a milestone the configuration hides
 * ({@link PathVisibility}). A publisher failure fails the commit and with it the action. After the commit {@code mnema_path_milestone_total{key,source}} counts the milestones the
 * transaction reached; no log line carries an account or a parameter.
 */
@Service
public class MilestoneRecorder {
    private static final String LIVE = "LIVE";

    private final MilestoneRepository repository;
    private final OnboardingSettings settings;
    private final PathVisibility visibility;
    private final NotificationPublisher publisher;
    private final MeterRegistry meters;

    MilestoneRecorder(MilestoneRepository repository, OnboardingSettings settings, PathVisibility visibility, NotificationPublisher publisher, MeterRegistry meters) {
        this.repository = repository;
        this.settings = settings;
        this.visibility = visibility;
        this.publisher = publisher;
        this.meters = meters;
    }

    /**
     * Raises the progress of {@code key} to {@code level}, capped at its target; it never lowers it and never touches an achieved milestone. A milestone of target 1 is reached with
     * level 1; the stages of {@link MilestoneKey#CAPTURE_NOTE} are level 1 (saved) and 2 (sorted out); {@link MilestoneKey#TWO_DAYS} counts dates.
     *
     * @throws IllegalArgumentException a level below 1 (a programming error)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reach(UUID account, MilestoneKey key, int level) {
        requireLevel(level);
        if (repository.reach(account, key, level)) achieved(account, key);
    }

    /**
     * Like {@link #reach} for a writer that has only the id of a deck: nothing is recorded unless {@code deck} is a deck of {@code account} with purpose USER, which the same statement
     * checks (a primary-key probe), so the writer needs no extra query. Use {@link #reach} when the writer has already read the deck's purpose.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reachInUserDeck(UUID account, UUID deck, MilestoneKey key, int level) {
        requireLevel(level);
        if (repository.reachInUserDeck(account, deck, key, level)) achieved(account, key);
    }

    /** Adds one to the progress of a counting milestone ({@link MilestoneKey#OWN_EXERCISES}), capped at its target. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void increment(UUID account, MilestoneKey key) {
        if (repository.increment(account, key)) achieved(account, key);
    }

    /**
     * A study session of the account has just completed (the row that changed to COMPLETE; the writers call it in the branch of {@code DeckEngagement.studied}).
     * {@code FIRST_SESSION} and {@code TWO_DAYS} count SCHEDULED and PRACTICE sessions of any deck; REPLAY never counts. After both are achieved this costs one primary-key range read;
     * before, the second day is the existence of a completed session on another local study date.
     *
     * @param mode the stored mode of the session
     * @param studyDate the session's {@code local_study_date}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sessionCompleted(UUID account, String mode, LocalDate studyDate) {
        if (!"SCHEDULED".equals(mode) && !"PRACTICE".equals(mode)) return;
        MilestoneRepository.SessionProgress known = repository.sessionProgress(account);
        if (!done(known.firstSession()) && repository.reach(account, MilestoneKey.FIRST_SESSION, 1)) achieved(account, MilestoneKey.FIRST_SESSION);
        if (done(known.twoDays())) return;
        int before = known.twoDays() == null ? 0 : known.twoDays().progress();
        int level = repository.studiedOnAnotherDay(account, studyDate) ? 2 : 1;
        if (level > before && repository.reach(account, MilestoneKey.TWO_DAYS, level)) achieved(account, MilestoneKey.TWO_DAYS);
    }

    private static boolean done(MilestoneRepository.Row row) {
        return row != null && row.achievedAt() != null;
    }

    private static void requireLevel(int level) {
        if (level < 1) throw new IllegalArgumentException("A milestone level starts at 1");
    }

    /** Remembers that this transaction reached {@code key}; the first one registers the synchronization of the transaction. */
    private void achieved(UUID account, MilestoneKey key) {
        // The synchronizations belong to the transaction (a suspended outer one keeps its own), unlike a thread-bound resource, which a REQUIRES_NEW inside it would see.
        Announcement announcement = TransactionSynchronizationManager.getSynchronizations().stream().filter(Announcement.class::isInstance).map(Announcement.class::cast)
                .findFirst().orElse(null);
        if (announcement == null) {
            announcement = new Announcement();
            TransactionSynchronizationManager.registerSynchronization(announcement);
        }
        announcement.reached.add(new Reached(account, key));
    }

    private record Reached(UUID account, MilestoneKey key) { }

    /** The end of the transaction: the notifications in {@code beforeCommit}, the metric after the commit. One per transaction, holding what it reached. */
    private final class Announcement implements TransactionSynchronization {
        private final List<Reached> reached = new ArrayList<>();

        @Override
        public void beforeCommit(boolean readOnly) {
            if (!settings.enabled()) return;
            Set<MilestoneKey> hidden = visibility.hidden();
            int total = MilestoneKey.values().length - hidden.size();
            Map<UUID, Integer> done = new HashMap<>();
            for (Reached each : reached) {
                if (hidden.contains(each.key())) continue;
                int count = done.computeIfAbsent(each.account(), account -> doneCount(account, hidden));
                publisher.publish(each.account(), NotificationKind.PATH_MILESTONE, "path:" + each.key().name(),
                        Map.of("key", each.key().name(), "done", count, "total", total), NotificationRoute.PATH);
            }
        }

        @Override
        public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) return;
            for (Reached each : reached) meters.counter("mnema_path_milestone_total", "key", each.key().name(), "source", LIVE).increment();
        }
    }

    /** The visible milestones the account has achieved, this transaction's included (it is read after every write of the action). */
    private int doneCount(UUID account, Set<MilestoneKey> hidden) {
        int done = 0;
        for (Map.Entry<MilestoneKey, MilestoneRepository.Row> entry : repository.all(account).entrySet()) {
            if (entry.getValue().achievedAt() != null && !hidden.contains(entry.getKey())) done++;
        }
        return done;
    }
}
