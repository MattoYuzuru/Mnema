package app.mnema.learning.promo;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared wiring of the promo tests: the real database and entitlement inbox, a movable clock and an Identity that answers from a map. */
@SpringBootTest(properties = {"learning.experiment-secret=test-experiment-secret"})
@Import(PromoTestConfiguration.class)
abstract class PromoIntegrationTest extends PostgresIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger NETWORKS = new AtomicInteger(1);

    @Autowired protected PromoService promo;
    @Autowired protected PromoAdminService admin;
    @Autowired protected PromoController controller;
    @Autowired protected PromoAdminController adminController;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected PromoTestConfiguration.MutableClock clock;
    @Autowired protected PromoTestConfiguration.StubStandings standings;
    @Autowired protected app.mnema.learning.usage.EntitlementSource entitlements;

    @BeforeEach
    void resetClock() {
        clock.set(PromoTestConfiguration.START);
        standings.unavailable = false;
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    protected UUID account(boolean verified, boolean admin) {
        UUID account = UUID.randomUUID();
        standings.put(account, verified, admin);
        return account;
    }

    protected static Jwt jwt(UUID account) {
        return Jwt.withTokenValue("test").header("alg", "RS256").subject(account.toString()).build();
    }

    /** A client of its own network: a distinct address hash per call, so the per-address limits of one test do not meet another's. */
    protected static PromoClient network() {
        byte[] hash = new byte[32];
        int number = NETWORKS.getAndIncrement();
        hash[0] = (byte) number;
        hash[1] = (byte) (number >> 8);
        hash[2] = (byte) (number >> 16);
        hash[31] = 1;
        return new PromoClient(hash);
    }

    protected MockMvc as(UUID account, Object... controllers) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(account)));
        return MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    protected static JsonNode body(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    protected PromoAdminService.Create tier(PromoType type, String plan, Integer days, Integer months, int max) {
        return new PromoAdminService.Create(type, plan, days, months, null, null, null, max, true, null, null);
    }

    /** Creates a code as an administrator and returns the plain text. */
    protected String code(PromoAdminService.Create command) {
        return admin.create(UUID.randomUUID(), command).path("code").stringValue(null);
    }

    protected Instant now() {
        return clock.now();
    }
}
