package app.mnema.learning.billing;

import app.mnema.learning.usage.CheckoutAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Who may check out: the mode, the tester list and the credentials, as the contract answers them and as the paywall learns them. */
class BillingAvailabilityTest extends BillingIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    private BillingController controllerFor(BillingSettings settings) {
        return new BillingController(new BillingService(repository, settings, bank, applier, receipts, entitlements, prices, discounts, clock, transactions));
    }

    private static BillingSettings settings(String mode, String testers, String publicUrl, String key) {
        return new BillingSettings(mode, testers, publicUrl, BANK.baseUrl(), key, BillingFixtures.PASSWORD_BASE64, Duration.ofHours(1), Duration.ofSeconds(10),
                10, Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private JsonNode refused(BillingController under, UUID owner) throws Exception {
        MockHttpServletResponse response = as(owner, under).perform(post("/billing/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"PLUS\",\"period\":\"MONTH\"}").header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(409);
        JsonNode problem = body(response);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("CAPABILITY_UNAVAILABLE");
        assertThat(problem.path("capability").stringValue(null)).isEqualTo("billing");
        assertThat(orders(owner)).isZero();
        return problem;
    }

    @Test
    void checkoutOffAnswersDisabledForEverybodyAndNothingIsCreated() throws Exception {
        UUID owner = UUID.randomUUID();

        assertThat(refused(controllerFor(settings("OFF", owner.toString(), "https://mnema.app", BillingFixtures.TERMINAL)), owner).path("reason").stringValue(null))
                .isEqualTo("DISABLED");
        assertThat(BANK.inits).isEmpty();
    }

    @Test
    void testersModeLetsOnlyTheListedAccountsThrough() throws Exception {
        UUID tester = UUID.randomUUID();
        BillingController testersOnly = controllerFor(settings("TESTERS", tester + "," + UUID.randomUUID(), "https://mnema.app", BillingFixtures.TERMINAL));

        assertThat(refused(testersOnly, UUID.randomUUID()).path("reason").stringValue(null)).isEqualTo("DISABLED");

        MockHttpServletResponse allowed = as(tester, testersOnly).perform(post("/billing/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"PLUS\",\"period\":\"MONTH\"}").header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().getResponse();
        assertThat(allowed.getStatus()).isEqualTo(201);
    }

    @Test
    void anOnModeWithoutCredentialsIsNotConfigured() throws Exception {
        assertThat(refused(controllerFor(settings("ON", "", "", BillingFixtures.TERMINAL)), UUID.randomUUID()).path("reason").stringValue(null))
                .isEqualTo("NOT_CONFIGURED");
        assertThat(refused(controllerFor(settings("ON", "", "https://mnema.app", "")), UUID.randomUUID()).path("reason").stringValue(null))
                .isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void thePaywallLearnsAvailabilityFromTheBillingBean() {
        CheckoutAvailability availability = context.getBean(CheckoutAvailability.class);

        assertThat(availability).isSameAs(settings);
        assertThat(availability.available(UUID.randomUUID())).isTrue();
        assertThat(settings("TESTERS", "", "https://mnema.app", BillingFixtures.TERMINAL).available(UUID.randomUUID())).isFalse();
        assertThat(settings("ON", "", "", "").available(UUID.randomUUID())).isFalse();
    }

    @Test
    void theReconcilerIsAWorkerRoleBeanOnly() {
        // The test configuration runs the api role: no process of it polls the bank on a timer.
        assertThat(context.getBeanProvider(PaymentReconciler.class).getIfAvailable()).isNull();
        assertThat(context.getBeanProvider(PaymentReconciliation.class).getIfAvailable()).isNotNull();
    }
}
