package app.mnema.learning.promo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * The abuse limits of redemption ({@code learning.promo.*}) and the secret that keys the address and device hashes. Without
 * {@code MNEMA_PROMO_HASH_SECRET} (also the key of the stored code hashes) a random secret is drawn per process outside production: hashes are then
 * comparable inside one instance only and no stored code survives a restart, which is fine for local work and logged as a WARN. In production
 * ({@code APP_ENV=prod}) a missing secret, or one shorter than {@value #MIN_PRODUCTION_SECRET} characters, switches promo codes off (fail closed:
 * {@link #requireAvailable} answers 409 CAPABILITY_UNAVAILABLE for redemption and code creation, logged as an ERROR at startup) instead of failing
 * the whole service: a silent random secret would orphan every issued code at the next deploy, and a missing optional secret must not take the
 * running product down. Invalid limits fail at startup.
 */
@Component
final class PromoSettings {
    private static final Logger log = LoggerFactory.getLogger(PromoSettings.class);
    static final int MIN_PRODUCTION_SECRET = 32;

    final int attemptsPerHour;
    final int ipAttemptsPerHour;
    final int velocityAccounts;
    final Duration velocityWindow;
    final byte[] hashSecret;
    /** False only in production without a usable secret: promo codes are off there. */
    final boolean available;

    PromoSettings(@Value("${learning.promo.attempts-per-hour:5}") int attemptsPerHour,
                  @Value("${learning.promo.ip-attempts-per-hour:20}") int ipAttemptsPerHour,
                  @Value("${learning.promo.velocity.accounts:3}") int velocityAccounts,
                  @Value("${learning.promo.velocity.window:PT24H}") Duration velocityWindow,
                  @Value("${learning.promo.hash-secret:}") String hashSecret,
                  @Value("${APP_ENV:dev}") String environment) {
        if (attemptsPerHour < 1 || attemptsPerHour > 1_000 || ipAttemptsPerHour < 1 || ipAttemptsPerHour > 10_000
                || velocityAccounts < 1 || velocityAccounts > 1_000
                || velocityWindow.compareTo(Duration.ofHours(1)) < 0 || velocityWindow.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Invalid promo settings");
        }
        this.attemptsPerHour = attemptsPerHour;
        this.ipAttemptsPerHour = ipAttemptsPerHour;
        this.velocityAccounts = velocityAccounts;
        this.velocityWindow = velocityWindow;
        boolean production = "prod".equalsIgnoreCase(environment.strip());
        this.available = !production || (!hashSecret.isBlank() && hashSecret.length() >= MIN_PRODUCTION_SECRET);
        if (!available) {
            log.error("promo codes are off: MNEMA_PROMO_HASH_SECRET must be set to at least {} characters when APP_ENV=prod", MIN_PRODUCTION_SECRET);
        }
        if (hashSecret.isBlank()) {
            if (!production) log.warn("promo hash secret is not configured: a random per-process secret is used, issued codes do not survive a restart");
            this.hashSecret = new byte[32];
            new SecureRandom().nextBytes(this.hashSecret);
        } else {
            this.hashSecret = hashSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Throws 409 CAPABILITY_UNAVAILABLE ({@code capability: promoCodes}) when promo codes are off. */
    void requireAvailable() {
        if (!available) {
            throw new app.mnema.learning.platform.api.CapabilityUnavailableException(app.mnema.learning.platform.api.ProblemExtension.builder()
                    .put("capability", "promoCodes").put("reason", "PROVIDER_NOT_CONFIGURED").build());
        }
    }
}
