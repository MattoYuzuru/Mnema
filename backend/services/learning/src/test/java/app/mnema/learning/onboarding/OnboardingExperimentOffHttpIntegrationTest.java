package app.mnema.learning.onboarding;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.onboarding.OnboardingFixture.JSON;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tour is on but the holdout experiment is off (the default): every new account gets the tour, none is held out, and none is counted as experiment data.
 * The accounts are stored {@code UNASSIGNED}, so a report over their offers shows two empty groups instead of a tour group nobody was randomized into.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z"})
class OnboardingExperimentOffHttpIntegrationTest extends PostgresIntegrationTest {
    private static final UUID CONSOLE = UUID.randomUUID();
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.admin.owner-id", CONSOLE::toString);
    }

    @Test
    void withTheExperimentOffEveryNewAccountGetsTheTourAndTheReportHasNoGroupMembers() {
        for (int index = 0; index < 5; index++) {
            UUID account = UUID.randomUUID();
            HttpIdentityFixture.signals(account, false, true);

            HttpResponse<String> response = HttpIdentityFixture.send(port, "GET", "/onboarding", HttpIdentityFixture.reader(account), null, Map.of());

            JsonNode journey = JSON.readTree(response.body()).path("journey");
            assertThat(journey.path("state").stringValue()).isEqualTo("OFFERED");
            assertThat(journey.path("eligible").booleanValue()).isTrue();
            assertThat(journey.path("autoOffer").booleanValue()).isTrue();
            assertThat(jdbc.sql("SELECT cohort FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account).query(String.class).single())
                    .isEqualTo("UNASSIGNED");
            // move the offer into a window no other test uses
            jdbc.sql("UPDATE app_learning.onboarding_journey SET offered_at = timestamptz '2024-09-05T10:00:00Z' WHERE account_id=:a").param("a", account).update();
        }

        HttpResponse<String> report = HttpIdentityFixture.send(port, "GET", "/admin/onboarding/experiment?from=2024-09-01&to=2024-09-10",
                HttpIdentityFixture.adminToken(CONSOLE), null, Map.of());

        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        JsonNode groups = JSON.readTree(report.body()).path("groups");
        assertThat(groups).hasSize(2);
        for (JsonNode group : groups) {
            assertThat(group.path("accounts").intValue()).isZero();
            assertThat(group.path("activated").intValue()).isZero();
            assertThat(group.path("returned").intValue()).isZero();
        }
        assertThat(JSON.readTree(report.body()).path("smallSample").booleanValue()).isTrue();
    }
}
