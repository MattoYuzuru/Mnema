package app.mnema.learning.experiment;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The experiment endpoint and the paywall's {@code experiments} member, on the real configuration (the default experiment is enabled). */
@SpringBootTest(properties = "learning.experiment-secret=http-test-secret")
class ExperimentHttpTest extends PostgresIntegrationTest {
    @Autowired private ExperimentController controller;
    @Autowired private ExperimentAssignments assignments;
    @Autowired private MeterRegistry meters;
    @Autowired private app.mnema.learning.usage.PlansController plans;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc as(UUID account, Object... controllers) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(account.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @Test
    void thePaywallCarriesTheServerAssignedVariantOfEveryEnabledExperiment() throws Exception {
        UUID account = UUID.randomUUID();

        var response = as(account, plans).perform(get("/plans")).andReturn().getResponse();

        var body = new tools.jackson.databind.json.JsonMapper().readTree(response.getContentAsString());
        assertThat(body.path("experiments").path("plans_year_first").stringValue(null)).isEqualTo(assignments.variant(account, "plans_year_first"));
        assertThat(body.path("pendingDiscount").isNull()).isTrue();
    }

    @Test
    void anEventIsCountedAgainstTheAssignedVariantAndAnUnknownExperimentIsIgnored() throws Exception {
        UUID account = UUID.randomUUID();
        String variant = assignments.variant(account, "plans_year_first");
        double before = counter(variant);

        var counted = as(account, controller).perform(post("/experiment-events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"plans_year_first\",\"event\":\"EXPOSURE\"}")).andReturn().getResponse();
        var ignored = as(account, controller).perform(post("/experiment-events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"nothing\",\"event\":\"EXPOSURE\"}")).andReturn().getResponse();

        assertThat(counted.getStatus()).isEqualTo(204);
        assertThat(ignored.getStatus()).isEqualTo(204);
        assertThat(counter(variant) - before).isEqualTo(1.0);
    }

    @Test
    void malformedEventsAreInvalidAndAFloodIsRateLimited() throws Exception {
        UUID account = UUID.randomUUID();
        for (String json : new String[] {"{}", "{\"key\":\"plans_year_first\"}", "{\"key\":\"plans_year_first\",\"event\":\"CLICK\"}",
                "{\"key\":1,\"event\":\"EXPOSURE\"}", "{\"key\":\"" + "k".repeat(41) + "\",\"event\":\"EXPOSURE\"}",
                "{\"key\":\"plans_year_first\",\"event\":\"EXPOSURE\",\"variant\":\"control\"}"}) {
            assertThat(as(account, controller).perform(post("/experiment-events").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andReturn().getResponse().getStatus()).as(json).isEqualTo(400);
        }
        int limited = 0;
        for (int index = 0; index < 40; index++) {
            if (as(account, controller).perform(post("/experiment-events").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"key\":\"plans_year_first\",\"event\":\"EXPOSURE\"}")).andReturn().getResponse().getStatus() == 429) limited++;
        }
        assertThat(limited).isPositive();
    }

    @Test
    void aFullTrackingTableRefusesANewKnownAccountWithBoundedRetryAndStillIgnoresUnknownKeys() throws Exception {
        var limited = new ExperimentEvents(assignments, new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC), 1);
        var boundedController = new ExperimentController(limited);
        String exposure = "{\"key\":\"plans_year_first\",\"event\":\"EXPOSURE\"}";
        assertThat(as(UUID.randomUUID(), boundedController).perform(post("/experiment-events")
                .contentType(MediaType.APPLICATION_JSON).content(exposure)).andReturn().getResponse().getStatus()).isEqualTo(204);
        var refused = as(UUID.randomUUID(), boundedController).perform(post("/experiment-events")
                .contentType(MediaType.APPLICATION_JSON).content(exposure)).andReturn().getResponse();
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(new tools.jackson.databind.json.JsonMapper().readTree(refused.getContentAsString()).path("code").stringValue(null))
                .isEqualTo("RATE_LIMITED");
        assertThat(as(UUID.randomUUID(), boundedController).perform(post("/experiment-events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"unknown\",\"event\":\"EXPOSURE\"}")).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(limited.trackedAccounts()).isOne();
    }

    private double counter(String variant) {
        var found = meters.find("mnema_experiment_events_total").tag("key", "plans_year_first").tag("variant", variant)
                .tag("event", "EXPOSURE").counter();
        return found == null ? 0.0 : found.count();
    }
}
