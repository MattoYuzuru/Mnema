package app.mnema.learning.onboarding;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import app.mnema.learning.promo.PersonalPromoCodes;
import app.mnema.learning.promo.PromoCampaignType;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Issues the path reward of an account (OB-402, ADR-ONB-005): its personal code of the enabled {@code PATH_REWARD} campaign, once, and the only writer of
 * {@code onboarding_reward}. {@link PathReward} decides when to ask, and asks outside any transaction.
 *
 * <p><b>Never nested in a read.</b> {@link #issueIfDue} and {@link #issue} refuse to run while the calling thread has a transaction ({@link IllegalStateException}, a programming
 * error): the issuing transaction takes a pooled connection of its own, so a caller that already held one would hold two for as long as the issuer waits for a lock, and a burst of
 * such reads could take every connection of the pool. The reads of the view therefore decide and issue first ({@link PathReward#current}) and open their own transaction afterwards.
 *
 * <p><b>The check before the transaction.</b> {@link #issueIfDue} is a lock-free check on autocommit statements, one connection at a time: every visible milestone of the account is done
 * (silent otherwise: most trusted accounts are on their way, and counting them per read would only be noise), the promo secret is usable, a campaign is enabled and it is below its daily
 * limit by an unlocked count. Only an account that passes it opens the issuing transaction, so an account that qualifies while there is no campaign, the secret is missing or the day's
 * limit is reached costs a few plain reads and never queues on a lock. The check is only a filter: the transaction repeats every decision under its locks.
 *
 * <p><b>One transaction of its own, that never fails the caller.</b> {@link #issue} runs in a new transaction and catches whatever fails in it: the failure is logged without the account
 * and counted ({@code failed}), and the view is answered without a reward; the next read asks again. The transaction is bounded ({@value #TIMEOUT_SECONDS} s, also the longest wait for a
 * lock), so a read is delayed by it at most that long. Under the account's advisory lock (concurrent reads of one account serialize here, the second finds the reward the first committed)
 * it returns an existing reward unchanged, then, in this order: a usable promo secret, the enabled campaign read again {@code FOR SHARE} (the owner's switch-off waits for this
 * transaction, or is waited for and seen, so a disabled campaign issues nothing), a recount of the account's visible milestones in this very transaction (every one done: the same
 * {@link PathState} rule as the view, and at least one), the campaign's daily limit (counted under the campaign's advisory lock, so it is exact), and then it writes the personal code, the
 * reward row and, as the last write ({@link NotificationPublisher}'s rule, it holds the owner's cursor row until the commit), {@code PATH_REWARD_READY}. The reward and the code expire
 * together, {@value #VALID_DAYS} days after the database's clock read at the issuance. Nothing is issued by a rolled-back transaction, so the code, the row and the notification exist
 * together or not at all.
 *
 * <p>{@code mnema_path_reward_total{outcome}}: {@code issued} (after the commit), {@code no_campaign}, {@code not_complete} (only a race: the check before the transaction passes
 * silently over an incomplete account), {@code limited}, {@code unavailable} (production without a usable promo secret; logged once at WARN) and {@code failed}; no label is an id. An
 * existing reward counts nothing.
 */
@Service
class RewardIssuer {
    private static final Logger log = LoggerFactory.getLogger(RewardIssuer.class);
    /** The offer's length (the contract's {@code constants.reward.validDays}). */
    static final int VALID_DAYS = 7;
    /** The issuing transaction's bound, which is also the longest a statement of it (a lock wait) can take. */
    static final int TIMEOUT_SECONDS = 5;

    private enum Outcome { ISSUED, EXISTING, NO_CAMPAIGN, NOT_COMPLETE, LIMITED, UNAVAILABLE }

    private final RewardRepository repository;
    private final RewardSettings settings;
    private final PersonalPromoCodes codes;
    private final MilestoneRepository milestones;
    private final PathVisibility visibility;
    private final NotificationPublisher publisher;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;
    private final AtomicBoolean unavailableLogged = new AtomicBoolean();

    RewardIssuer(RewardRepository repository, RewardSettings settings, PersonalPromoCodes codes, MilestoneRepository milestones, PathVisibility visibility,
                 NotificationPublisher publisher, MeterRegistry meters, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.settings = settings;
        this.codes = codes;
        this.milestones = milestones;
        this.visibility = visibility;
        this.publisher = publisher;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(TIMEOUT_SECONDS);
    }

    /**
     * Issues the reward of {@code account} when it looks due, for a caller that has seen that the account has none and that Identity calls it trusted; never throws.
     *
     * @return whether the account may have a reward now (this call issued it, or it turned out to have one): the caller reads it again; false means nothing was issued
     * @throws IllegalStateException the calling thread has a transaction (see the class comment)
     */
    boolean issueIfDue(UUID account) {
        requireNoTransaction();
        try {
            if (!due(account)) return false;
        } catch (RuntimeException failure) {
            failed(failure);
            return false;
        }
        return issue(account);
    }

    /** The lock-free filter: counts the refusals that are about the campaign or the platform, never the account's own progress. */
    private boolean due(UUID account) {
        PathState path = PathState.of(milestones.all(account), visibility.hidden());
        if (path.total() < 1 || path.done() != path.total()) return false;
        if (!codes.available()) {
            unavailable();
            return false;
        }
        PersonalPromoCodes.Campaign campaign = codes.enabledCampaign(PromoCampaignType.PATH_REWARD).orElse(null);
        if (campaign == null) {
            count("no_campaign");
            return false;
        }
        if (repository.issuedInLastDay(campaign.campaignId()) >= settings.dailyLimit()) {
            count("limited");
            return false;
        }
        return true;
    }

    /**
     * Issues the reward of {@code account} if it qualifies and has none, in a transaction of its own; never throws what the transaction throws.
     *
     * @return whether the account has a reward after this call (issued by it, or already there); false for a refusal and for a failure
     * @throws IllegalStateException the calling thread has a transaction (see the class comment)
     */
    boolean issue(UUID account) {
        requireNoTransaction();
        Outcome outcome;
        try {
            outcome = transaction.execute(status -> issueLocked(account));
        } catch (RuntimeException failure) {
            failed(failure);
            return false;
        }
        if (outcome != null && outcome != Outcome.EXISTING) count(outcome.name().toLowerCase(Locale.ROOT));
        return outcome == Outcome.ISSUED || outcome == Outcome.EXISTING;
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The path reward is issued outside any transaction: the issuing transaction takes a connection of its own");
        }
    }

    private void failed(RuntimeException failure) {
        // The class only: the message of a database error can carry the key it names (an account id).
        log.warn("path reward issuance failed cause={}", failure.getClass().getName());
        count("failed");
    }

    private void unavailable() {
        if (unavailableLogged.compareAndSet(false, true)) log.warn("path reward is not issued: promo codes are unavailable (MNEMA_PROMO_HASH_SECRET)");
        count("unavailable");
    }

    private Outcome issueLocked(UUID account) {
        repository.lockAccount(account);
        if (repository.exists(account)) return Outcome.EXISTING;
        if (!codes.available()) {
            if (unavailableLogged.compareAndSet(false, true)) log.warn("path reward is not issued: promo codes are unavailable (MNEMA_PROMO_HASH_SECRET)");
            return Outcome.UNAVAILABLE;
        }
        // FOR SHARE: the owner's switch-off of this campaign waits for this transaction (or was waited for just now), so a campaign disabled meanwhile issues nothing.
        PersonalPromoCodes.Campaign campaign = codes.lockEnabledCampaign(PromoCampaignType.PATH_REWARD).orElse(null);
        if (campaign == null) return Outcome.NO_CAMPAIGN;
        PathState path = PathState.of(milestones.all(account), visibility.hidden());
        if (path.total() < 1 || path.done() != path.total()) return Outcome.NOT_COMPLETE;
        repository.lockCampaign(campaign.campaignId());
        if (repository.issuedInLastDay(campaign.campaignId()) >= settings.dailyLimit()) return Outcome.LIMITED;
        Instant issuedAt = repository.now();
        Instant expiresAt = issuedAt.plus(VALID_DAYS, ChronoUnit.DAYS);
        UUID code = codes.issue(campaign, account, issuedAt, expiresAt);
        repository.insert(account, campaign.campaignId(), code, issuedAt, expiresAt);
        // The last write: the publisher holds the owner's cursor row until the commit.
        publisher.publish(account, NotificationKind.PATH_REWARD_READY, "path:reward", Map.of("expiresAt", expiresAt), NotificationRoute.PATH);
        return Outcome.ISSUED;
    }

    private void count(String outcome) {
        meters.counter("mnema_path_reward_total", "outcome", outcome).increment();
    }
}
