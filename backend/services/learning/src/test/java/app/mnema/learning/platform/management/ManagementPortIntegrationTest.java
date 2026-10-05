package app.mnema.learning.platform.management;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operations endpoints on their own port: with {@code management.server.port} set and metrics exposed, {@code /actuator/metrics} answers on the
 * management port (loopback), carries the AI series the runbook lists, and is not served by the public API port at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"management.endpoints.web.exposure.include=health,info,metrics",
        "learning.ai.provider=stub", "spring.datasource.hikari.maximum-pool-size=2"})
class ManagementPortIntegrationTest extends PostgresIntegrationTest {
    private static final int MANAGEMENT_PORT = freePort();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void management(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> MANAGEMENT_PORT);
        registry.add("management.server.address", () -> "127.0.0.1");
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void metricsAreServedOnTheManagementPortAndNeverOnThePublicOne() throws Exception {
        assertThat(port).isNotEqualTo(MANAGEMENT_PORT);
        HttpResponse<String> names = get("http://127.0.0.1:" + MANAGEMENT_PORT + "/actuator/metrics");
        assertThat(names.statusCode()).isEqualTo(200);
        JsonNode available = JSON.readTree(names.body()).path("names");
        assertThat(available).extracting(JsonNode::stringValue).contains("mnema_usage_reserved_credits");
        HttpResponse<String> reserved = get("http://127.0.0.1:" + MANAGEMENT_PORT + "/actuator/metrics/mnema_usage_reserved_credits");
        assertThat(reserved.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(reserved.body()).path("measurements").get(0).path("value").doubleValue(-1)).isGreaterThanOrEqualTo(0);
        assertThat(get("http://127.0.0.1:" + MANAGEMENT_PORT + "/actuator/health").statusCode()).isEqualTo(200);

        for (String path : new String[] {"/api/actuator/metrics", "/actuator/metrics", "/api/actuator/metrics/jvm.memory.used"}) {
            int status = get("http://127.0.0.1:" + port + path).statusCode();
            assertThat(status).as("the public port must not serve " + path).isNotEqualTo(200).isIn(401, 403, 404);
        }
    }
}
