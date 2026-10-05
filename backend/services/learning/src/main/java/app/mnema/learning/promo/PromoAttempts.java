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
 * using. The rows live in the database because every instance is stateless; the counters of one account (and of one address) are taken under an
 * advisory lock so two attempts at once cannot both take the last place. The attempt that is over a limit answers {@code 429 RATE_LIMITED} with the
 * seconds until the oldest attempt of the window leaves it.
 */
@Component
class PromoAttempts {
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration RETENTION = Duration.ofDays(1);

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

    /**
     * Takes one place of the account's hour (and of the address's hour) in its own transaction, so a refused redemption still counts.
     *
     * @throws RateLimitedException a window is full
     */
    void take(UUID owner, PromoClient client) {
        Instant now = clock.now();
        Instant since = now.minus(WINDOW);
        Long wait = transaction.execute(status -> {
            // One fixed order (account, then address) so two attempts that share both cannot deadlock.
            repository.lockKey("promo.attempt:owner:" + owner);
            if (client.ipHash() != null) repository.lockKey("promo.attempt:ip:" + HexFormat.of().formatHex(client.ipHash()));
            repository.purgeAttempts(owner, now.minus(RETENTION));
            if (client.ipHash() != null) repository.purgeAttemptsOfIp(client.ipHash(), now.minus(RETENTION));
            if (repository.attemptsOfOwner(owner, since) >= settings.attemptsPerHour) {
                return retryAfter(repository.oldestAttemptOfOwner(owner, since), now);
            }
            if (client.ipHash() != null && repository.attemptsOfIp(client.ipHash(), since) >= settings.ipAttemptsPerHour) {
                return retryAfter(repository.oldestAttemptOfIp(client.ipHash(), since), now);
            }
            repository.insertAttempt(owner, client.ipHash(), now);
            return null;
        });
        if (wait != null) throw new RateLimitedException(wait);
    }

    private static long retryAfter(java.util.Optional<Instant> oldest, Instant now) {
        long seconds = oldest.map(first -> Duration.between(now, first.plus(WINDOW)).toSeconds() + 1).orElse(WINDOW.toSeconds());
        return Math.max(1, seconds);
    }
}
