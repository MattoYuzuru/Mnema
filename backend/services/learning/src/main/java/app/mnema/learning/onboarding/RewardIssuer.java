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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Issues the path reward of an account (OB-402, ADR-ONB-005): its personal code of the enabled {@code PATH_REWARD} campaign, once, and the only writer of
 * {@code onboarding_reward}. {@link PathReward} decides when to ask.
 *
 * <p><b>One transaction of its own, that never fails the caller.</b> {@link #issue} runs in a new transaction (the read that asks may already hold one) and catches whatever
 * fails in it: the failure is logged without the account and counted ({@code failed}), and the view is answered without a reward; the next read asks again. Under the account's
 * advisory lock (concurrent reads of one account serialize here, the second finds the reward the first committed) it returns an existing reward unchanged, then, in this order:
 * a usable promo secret, the enabled campaign, a recount of the account's visible milestones in this very transaction (every one done: the same {@link PathState} rule as the view,
 * and at least one), the campaign's daily limit (counted under the campaign's lock, so it is exact), and then it writes the personal code, the reward row and, as the last write
 * ({@link NotificationPublisher}'s rule, it holds the owner's cursor row until the commit), {@code PATH_REWARD_READY}. The reward and the code expire together, {@value #VALID_DAYS}
 * days after the database's clock read at the issuance. Nothing is issued by a rolled-back transaction, so the code, the row and the notification exist together or not at all.
 *
 * <p>{@code mnema_path_reward_total{outcome}}: {@code issued} (after the commit), {@code no_campaign}, {@code not_complete}, {@code limited}, {@code unavailable} (production without a
 * usable promo secret; logged once at WARN) and {@code failed}; no label is an id. An existing reward counts nothing.
 */
@Service
class RewardIssuer {
    private static final Logger log = LoggerFactory.getLogger(RewardIssuer.class);
    /** The offer's length (the contract's {@code constants.reward.validDays}). */
    static final int VALID_DAYS = 7;

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
        this.transaction.setTimeout(10);
    }

    /** Issues the reward of {@code account} if it qualifies and has none; never throws. */
    void issue(UUID account) {
        Outcome outcome;
        try {
            outcome = transaction.execute(status -> issueLocked(account));
        } catch (RuntimeException failure) {
            // The class only: the message of a database error can carry the key it names (an account id).
            log.warn("path reward issuance failed cause={}", failure.getClass().getName());
            count("failed");
            return;
        }
        if (outcome != null && outcome != Outcome.EXISTING) count(outcome.name().toLowerCase(Locale.ROOT));
    }

    private Outcome issueLocked(UUID account) {
        repository.lockAccount(account);
        if (repository.exists(account)) return Outcome.EXISTING;
        if (!codes.available()) {
            if (unavailableLogged.compareAndSet(false, true)) log.warn("path reward is not issued: promo codes are unavailable (MNEMA_PROMO_HASH_SECRET)");
            return Outcome.UNAVAILABLE;
        }
        PersonalPromoCodes.Campaign campaign = codes.enabledCampaign(PromoCampaignType.PATH_REWARD).orElse(null);
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
