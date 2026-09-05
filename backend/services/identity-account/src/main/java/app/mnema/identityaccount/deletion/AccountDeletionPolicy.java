package app.mnema.identityaccount.deletion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public final class AccountDeletionPolicy {
    private final boolean enabled;
    private final Duration recoveryPeriod;
    private final Duration recoverySessionPeriod;
    private final Duration leasePeriod;
    private final int batchSize;

    public AccountDeletionPolicy(@Value("${identity.deletion.enabled:false}") boolean enabled,
                                 @Value("${identity.deletion.recovery-period:}") String recoveryPeriod,
                                 @Value("${identity.deletion.recovery-session-period:PT5M}") Duration recoverySessionPeriod,
                                 @Value("${identity.deletion.lease-period:PT2M}") Duration leasePeriod,
                                 @Value("${identity.deletion.batch-size:10}") int batchSize) {
        Duration parsedRecovery;
        try {
            parsedRecovery = recoveryPeriod.isBlank() ? Duration.ZERO : Duration.parse(recoveryPeriod);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid account deletion policy", invalid);
        }
        if ((enabled && recoveryPeriod.isBlank()) || parsedRecovery.isNegative() ||
                recoverySessionPeriod.compareTo(Duration.ofSeconds(1)) < 0 ||
                leasePeriod.compareTo(Duration.ofSeconds(1)) < 0 || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Invalid account deletion policy");
        }
        this.enabled = enabled;
        this.recoveryPeriod = parsedRecovery;
        this.recoverySessionPeriod = recoverySessionPeriod;
        this.leasePeriod = leasePeriod;
        this.batchSize = batchSize;
    }

    public void requireEnabled() {
        if (!enabled) throw new app.mnema.identityaccount.contract.AccountFailure(503, "account_deletion_unavailable");
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration recoveryPeriod() {
        return recoveryPeriod;
    }

    public Duration recoverySessionPeriod() {
        return recoverySessionPeriod;
    }

    public Duration leasePeriod() {
        return leasePeriod;
    }

    public int batchSize() {
        return batchSize;
    }
}
