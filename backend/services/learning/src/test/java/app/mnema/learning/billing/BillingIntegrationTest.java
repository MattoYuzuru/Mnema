package app.mnema.learning.billing;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.promo.PromoDiscounts;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.usage.EntitlementInbox;
import app.mnema.learning.usage.EntitlementSource;
import app.mnema.learning.usage.Plan;
import app.mnema.learning.usage.PlanPrices;
import app.mnema.learning.usage.UsageCalendar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The real application against PostgreSQL and a loopback bank ({@link FakeBank}): checkout is ON for everybody, the terminal is the fixture terminal of
 * {@code contracts/billing/tbank}, time is a movable clock. The controllers are driven through standalone MockMvc with a JWT subject; the HTTP security
 * chain has its own test ({@link BillingSecurityHttpTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BillingTestConfiguration.class)
abstract class BillingIntegrationTest extends PostgresIntegrationTest {
    static final FakeBank BANK = new FakeBank();

    @DynamicPropertySource
    static void billing(DynamicPropertyRegistry registry) {
        registry.add("learning.billing.checkout", () -> "ON");
        registry.add("learning.billing.public-base-url", () -> "https://mnema.test");
        registry.add("learning.billing.tbank.base-url", BANK::baseUrl);
        registry.add("learning.billing.tbank.terminal-key", () -> BillingFixtures.TERMINAL);
        registry.add("learning.billing.tbank.password-base64", () -> BillingFixtures.PASSWORD_BASE64);
        registry.add("learning.billing.request-timeout", () -> "PT5S");
    }

    @LocalServerPort protected int port;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected BillingController controller;
    @Autowired protected TBankNotificationController notificationController;
    @Autowired protected TBankNotifications notifications;
    @Autowired protected PaymentStateApplier applier;
    @Autowired protected PaymentReconciliation reconciliation;
    @Autowired protected BillingRepository repository;
    @Autowired protected BillingSettings settings;
    @Autowired protected TBankClient bank;
    @Autowired protected CommandReceiptService receipts;
    @Autowired protected PlanPrices prices;
    @Autowired protected PromoDiscounts discounts;
    @Autowired protected EntitlementSource entitlements;
    @Autowired protected EntitlementInbox inbox;
    @Autowired protected UsageCalendar calendar;
    @Autowired protected BillingTestConfiguration.MutableClock clock;

    @BeforeEach
    void resetBankAndClock() {
        clock.set(BillingTestConfiguration.START);
        BANK.reset();
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    protected static Jwt jwt(UUID account) {
        return Jwt.withTokenValue("test").header("alg", "RS256").subject(account.toString()).build();
    }

    protected MockMvc as(UUID account, Object... controllers) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(account)));
        return MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    protected static JsonNode body(MockHttpServletResponse response) throws Exception {
        return BillingFixtures.JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    protected MockHttpServletResponse checkout(UUID account, String json, String key) throws Exception {
        var request = post("/billing/checkout").contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) request.header("Idempotency-Key", key);
        return as(account, controller).perform(request).andReturn().getResponse();
    }

    protected MockHttpServletResponse checkout(UUID account, String plan) throws Exception {
        return checkout(account, "{\"plan\":\"" + plan + "\",\"period\":\"MONTH\"}", UUID.randomUUID().toString());
    }

    /** A new open order of {@code plan}: the 201 body. */
    protected JsonNode open(UUID account, String plan) throws Exception {
        MockHttpServletResponse response = checkout(account, plan);
        org.assertj.core.api.Assertions.assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return body(response);
    }

    protected MockHttpServletResponse read(UUID account, String orderId) throws Exception {
        return as(account, controller).perform(get("/billing/orders/" + orderId)).andReturn().getResponse();
    }

    /** What the bank POSTs to the notification endpoint, answered by the controller. */
    protected MockHttpServletResponse notify(JsonNode notification) throws Exception {
        return as(UUID.randomUUID(), notificationController).perform(post("/billing/tbank/notifications").contentType(MediaType.APPLICATION_JSON)
                .content(notification.toString())).andReturn().getResponse();
    }

    /** A signed fixture notification for the payment the bank opened for {@code orderId}. */
    protected ObjectNode notification(String fixture, String orderId, String status) {
        FakeBank.Payment payment = BANK.paymentOf(orderId);
        return BillingFixtures.notification(fixture, orderId, payment.paymentId, status, payment.amount);
    }

    protected String status(String orderId) {
        return jdbc.sql("SELECT status FROM app_learning.billing_order WHERE order_id=CAST(:id AS uuid)").param("id", orderId).query(String.class).single();
    }

    protected String column(String orderId, String column) {
        return jdbc.sql("SELECT " + column + " FROM app_learning.billing_order WHERE order_id=CAST(:id AS uuid)").param("id", orderId)
                .query(String.class).optional().orElse(null);
    }

    protected Instant instant(String orderId, String column) {
        return jdbc.sql("SELECT " + column + " FROM app_learning.billing_order WHERE order_id=CAST(:id AS uuid)").param("id", orderId)
                .query(Timestamp.class).single().toInstant();
    }

    protected long count(String sql, Object... params) {
        var statement = jdbc.sql(sql);
        for (int index = 0; index < params.length; index++) statement = statement.param(index + 1, params[index]);
        return statement.query(Long.class).single();
    }

    protected long orders(UUID owner) {
        return count("SELECT count(*) FROM app_learning.billing_order WHERE owner_id=?", owner);
    }

    protected long snapshots(UUID owner) {
        return count("SELECT count(*) FROM app_learning.entitlement_inbox WHERE owner_id=? AND source='BILLING'", owner);
    }

    protected List<String> events(String orderId) {
        return jdbc.sql("SELECT source || ':' || coalesce(bank_status,'-') || ':' || coalesce(outcome,'-') FROM app_learning.billing_event "
                + "WHERE order_id=CAST(:id AS uuid) ORDER BY event_id").param("id", orderId).query(String.class).list();
    }

    /** A pending promo discount of {@code percent} for {@code plan} (null: either), earned by a code of its own. */
    protected UUID discount(UUID owner, int percent, String plan) {
        UUID code = UUID.randomUUID();
        byte[] hash = new byte[32];
        new java.security.SecureRandom().nextBytes(hash);
        jdbc.sql("INSERT INTO app_learning.promo_code(code_id,code_hash,code_hint,type,plan,percent,valid_from,valid_until,max_redemptions,once_per_account,"
                        + "enabled,created_at,created_by) VALUES (?,?,'AB-CD','DISCOUNT_PERCENT',?,?,?,?,5,true,true,?,?)")
                .param(1, code).param(2, hash).param(3, plan, java.sql.Types.VARCHAR).param(4, percent)
                .param(5, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))).param(6, Timestamp.from(Instant.parse("2027-06-01T00:00:00Z")))
                .param(7, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))).param(8, UUID.randomUUID()).update();
        jdbc.sql("INSERT INTO app_learning.promo_discount(owner_id,percent,plan,valid_until,code_id,created_at) VALUES (?,?,?,?,?,?)")
                .param(1, owner).param(2, percent).param(3, plan, java.sql.Types.VARCHAR).param(4, Timestamp.from(Instant.parse("2027-06-01T00:00:00Z")))
                .param(5, code).param(6, Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"))).update();
        return code;
    }

    protected long discountRows(UUID owner) {
        return count("SELECT count(*) FROM app_learning.promo_discount WHERE owner_id=?", owner);
    }

    protected void accept(UUID owner, Plan plan, String id, String start, String end) {
        inbox.accept(new EntitlementInbox.Snapshot(id, owner, plan, "PROMO", Instant.parse(start), Instant.parse(end),
                BillingFixtures.JSON.createObjectNode().put("allowances", "catalog").put("plan", plan.name()), Instant.parse(end)));
    }
}
