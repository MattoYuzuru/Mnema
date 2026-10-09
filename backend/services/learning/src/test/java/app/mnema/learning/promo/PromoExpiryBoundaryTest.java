package app.mnema.learning.promo;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** A code that expires while its row lock is acquired must be checked at the locked redemption instant. */
class PromoExpiryBoundaryTest extends PromoIntegrationTest {
    @MockitoSpyBean private PromoRepository repository;

    @Test
    void expiryIsCheckedAfterTheContendedLocksWithoutAnyWallClockSleep() {
        UUID learner = account(true, false);
        UUID command = UUID.randomUUID();
        String plain = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 15, null, null, null,
                now().plusSeconds(1), 1, true, null, null));
        doAnswer(invocation -> {
            Object locked = invocation.callRealMethod();
            clock.set("2026-10-02T09:00:44Z");
            return locked;
        }).when(repository).lockByHash(any(byte[].class));

        assertThatThrownBy(() -> promo.redeem(learner, jwt(learner), command, plain, network()))
                .isInstanceOfSatisfying(PromoRejectedException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(PromoRejectedException.Reason.INVALID));

        for (String table : new String[] {"promo_redemption", "entitlement_inbox"}) {
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE owner_id=:owner")
                    .param("owner", learner).query(Long.class).single()).as(table).isZero();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.command_receipt WHERE command_id=:command")
                .param("command", command).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_attempt WHERE owner_id=:owner")
                .param("owner", learner).query(Long.class).single()).isOne();
    }
}
