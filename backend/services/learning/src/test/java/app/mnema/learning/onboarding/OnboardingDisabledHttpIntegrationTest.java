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

import static app.mnema.learning.onboarding.OnboardingFixture.CONTRACT;
import static app.mnema.learning.onboarding.OnboardingFixture.JSON;
import static org.assertj.core.api.Assertions.assertThat;

/** The default configuration (the flag is off): a disabled view, nothing created, every command 409 {@code ONBOARDING_UNAVAILABLE}, the owner's report still readable. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnboardingDisabledHttpIntegrationTest extends PostgresIntegrationTest {
    private static final UUID CONSOLE = UUID.randomUUID();
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.admin.owner-id", CONSOLE::toString);
    }

    @Test
    void withTheFlagOffTheViewIsDisabledAndNothingIsCreated() {
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, false, true);

        HttpResponse<String> response = HttpIdentityFixture.send(port, "GET", "/onboarding", HttpIdentityFixture.reader(account), null, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body())).isEqualTo(CONTRACT.path("views").path("disabled"));
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(response.headers().firstValue("ETag")).isPresent();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account).query(Long.class).single()).isZero();
    }

    @Test
    void withTheFlagOffEveryCommandIs409EvenWhenItsBodyIsNotValid() {
        UUID account = UUID.randomUUID();
        String valid = CONTRACT.path("commands").path("replay").toString();
        String preferences = CONTRACT.path("commands").path("preferences").toString();
        for (String[] request : new String[][] {{"/onboarding/journey", valid}, {"/onboarding/preferences", preferences},
                {"/onboarding/journey", "{}"}, {"/onboarding/preferences", "[]"}}) {
            HttpResponse<String> response = HttpIdentityFixture.send(port, "PUT", request[0], HttpIdentityFixture.reader(account), request[1], Map.of());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            JsonNode expected = CONTRACT.path("problems").path("unavailable");
            JsonNode actual = JSON.readTree(response.body());
            for (String member : new String[] {"type", "title", "detail", "code"}) assertThat(actual.path(member)).isEqualTo(expected.path(member));
            assertThat(actual.path("instance").stringValue()).isEqualTo(request[0].equals("/onboarding/journey") ? "/api/onboarding/journey" : "/api/onboarding/preferences");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.command_receipt WHERE actor_id=:a").param("a", account).query(Long.class).single()).isZero();
    }

    @Test
    void theOwnerCanStillReadTheReportAfterARollback() {
        HttpResponse<String> response = HttpIdentityFixture.send(port, "GET", "/admin/onboarding/experiment?from=2024-01-01&to=2024-01-08",
                HttpIdentityFixture.adminToken(CONSOLE), null, Map.of());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("groups")).hasSize(2);
    }
}
