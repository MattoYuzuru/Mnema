package app.mnema.learning.promo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * The abuse limits of redemption ({@code learning.promo.*}) and the secret that keys the address and device hashes. Without
 * {@code MNEMA_PROMO_HASH_SECRET} a random secret is drawn per process: hashes are then comparable inside one instance only, which weakens the
 * velocity rule across instances but never stores or exposes an address. Invalid limits fail at startup.
 */
@Component
final class PromoSettings {
    final int attemptsPerHour;
    final int ipAttemptsPerHour;
    final int velocityAccounts;
    final Duration velocityWindow;
    final byte[] hashSecret;

    PromoSettings(@Value("${learning.promo.attempts-per-hour:5}") int attemptsPerHour,
                  @Value("${learning.promo.ip-attempts-per-hour:5}") int ipAttemptsPerHour,
                  @Value("${learning.promo.velocity.accounts:3}") int velocityAccounts,
                  @Value("${learning.promo.velocity.window:PT24H}") Duration velocityWindow,
                  @Value("${learning.promo.hash-secret:}") String hashSecret) {
        if (attemptsPerHour < 1 || attemptsPerHour > 1_000 || ipAttemptsPerHour < 1 || ipAttemptsPerHour > 10_000
                || velocityAccounts < 1 || velocityAccounts > 1_000
                || velocityWindow.compareTo(Duration.ofHours(1)) < 0 || velocityWindow.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Invalid promo settings");
        }
        this.attemptsPerHour = attemptsPerHour;
        this.ipAttemptsPerHour = ipAttemptsPerHour;
        this.velocityAccounts = velocityAccounts;
        this.velocityWindow = velocityWindow;
        if (hashSecret.isBlank()) {
            this.hashSecret = new byte[32];
            new SecureRandom().nextBytes(this.hashSecret);
        } else {
            this.hashSecret = hashSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
