package app.mnema.learning.promo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A missing optional production secret keeps Learning running but cannot issue or redeem any promo code. */
@SpringBootTest(properties = {"APP_ENV=prod", "learning.promo.hash-secret="})
class PromoProductionUnavailableTest extends PromoIntegrationTest {
    @Test
    void productionStartsWithoutTheOptionalSecretAndRefusesPromoWritesBeforeTakingAnyAttempt() throws Exception {
        UUID administrator = account(true, true);
        UUID learner = account(true, false);
        var created = as(administrator, adminController).perform(post("/admin/promo-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":15,\"maxRedemptions\":1}"))
                .andReturn().getResponse();
        var redeemed = as(learner, controller).perform(post("/promo-codes/redemptions")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"code\":\"SPRING26\"}"))
                .andReturn().getResponse();

        for (var response : new org.springframework.mock.web.MockHttpServletResponse[] {created, redeemed}) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(body(response).path("code").stringValue()).isEqualTo("CAPABILITY_UNAVAILABLE");
            assertThat(body(response).path("capability").stringValue()).isEqualTo("promoCodes");
            assertThat(body(response).path("reason").stringValue()).isEqualTo("PROVIDER_NOT_CONFIGURED");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_code WHERE created_by=:owner")
                .param("owner", administrator).query(Long.class).single()).isZero();
        for (String table : new String[] {"promo_attempt", "promo_redemption", "entitlement_inbox"}) {
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE owner_id=:owner")
                    .param("owner", learner).query(Long.class).single()).as(table).isZero();
        }
    }
}
