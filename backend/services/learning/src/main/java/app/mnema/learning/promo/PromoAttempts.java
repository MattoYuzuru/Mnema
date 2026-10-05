package app.mnema.learning.promo;

import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.usage.UsageClock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The hourly limit of redemption attempts, per account and per address hash ({@code learning.promo.attempts-per-hour},
 * {@code ip-attempts-per-hour}). Every attempt counts, right or wrong, and counts before the code is looked at, so guessing is as expensive as
 * using. The account's place is taken first ({@link #takeAccount}); the address's place only after the account has passed the eligibility check
 * ({@link #takeAddress}), so accounts that are refused for an unverified email cannot burn the allowance a household or an office shares. The rows live in
 * the database because every instance is stateless; the counters of one account (and of one address) are taken under an advisory lock so two attempts at
 * once cannot both take the last place. The attempt that is over a limit answers {@code 429 RATE_LIMITED} with the seconds until the oldest attempt of
 * the window leaves it. Rows older than {@link #RETENTION} are deleted by {@link PromoAttemptSweep}.
 */
@Component
class PromoAttempts {
    private static final Duration WINDOW = Duration.ofHours(1);
    /** The window plus an hour of margin: nothing older than this can count for a limit. */
    static final Duration RETENTION = Duration.ofHours(2);

    private final PromoRepository repository;
    private final PromoSettings settings;
    private final UsageClock clock;
    private final TransactionTemplate transaction;

    PromoAttempts(PromoRepository repository, PromoSettings settings, UsageClock clock, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(5);
    }

    private record Outcome(long attemptId, long retryAfterSeconds) { }

    /**
     * Takes one place of the account's hour in its own transaction, so a refused redemption still counts.
     *
     * @return the id of the attempt, to pass to {@link #takeAddress}
     * @throws RateLimitedException the account's window is full
     */
    long takeAccount(UUID owner) {
        Instant now = clock.now();
        Instant since = now.minus(WINDOW);
        Outcome outcome = transaction.execute(status -> {
            repository.lockKey("promo.attempt:owner:" + owner);
            repository.purgeAttempts(owner, now.minus(RETENTION));
            if (repository.attemptsOfOwner(owner, since) >= settings.attemptsPerHour) {
                return new Outcome(0, retryAfter(repository.oldestAttemptOfOwner(owner, since), now));
            }
            return new Outcome(repository.insertAttempt(owner, now), 0);
        });
        if (outcome.retryAfterSeconds() > 0) throw new RateLimitedException(outcome.retryAfterSeconds());
        return outcome.attemptId();
    }

    /**
     * Takes one place of the address's hour for an attempt the account has already taken. Without an address there is nothing to limit.
     *
     * @throws RateLimitedException the address's window is full (the account's place stays taken)
     */
    void takeAddress(long attemptId, PromoClient client) {
        if (client.ipHash() == null) return;
        Instant now = clock.now();
        Instant since = now.minus(WINDOW);
        Long wait = transaction.execute(status -> {
            repository.lockKey("promo.attempt:ip:" + HexFormat.of().formatHex(client.ipHash()));
            if (repository.attemptsOfIp(client.ipHash(), since) >= settings.ipAttemptsPerHour) {
                return retryAfter(repository.oldestAttemptOfIp(client.ipHash(), since), now);
            }
            repository.assignAddress(attemptId, client.ipHash());
            return null;
        });
        if (wait != null) throw new RateLimitedException(wait);
    }

    private static long retryAfter(java.util.Optional<Instant> oldest, Instant now) {
        long seconds = oldest.map(first -> Duration.between(now, first.plus(WINDOW)).toSeconds() + 1).orElse(WINDOW.toSeconds());
        return Math.max(1, seconds);
    }
}
