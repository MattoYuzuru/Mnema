package app.mnema.learning.platform.management;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    @Autowired @Qualifier("managementPort") private SecurityFilterChain security;

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

    @Test
    void csrfProtectionIsInstalledOnThePrivateManagementChain() {
        assertThat(security.getFilters()).filteredOn(CsrfFilter.class::isInstance).hasSize(1);
        assertThat(security.getFilters()).filteredOn(LogoutFilter.class::isInstance).isEmpty();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        request.setLocalPort(MANAGEMENT_PORT);
        assertThat(security.matches(request)).isTrue();
        request.setLocalPort(port);
        assertThat(security.matches(request)).isFalse();
    }

    @Test
    void safeManagementReadsRemainTokenlessAndCreateNoCookieOrSession() throws Exception {
        for (String method : new String[] {"GET", "HEAD"}) {
            for (String path : new String[] {"/actuator/metrics", "/actuator/health", "/actuator/health/readiness"}) {
                HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + MANAGEMENT_PORT + path))
                        .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(method + " " + path).isEqualTo(200);
                assertThat(response.headers().allValues("Set-Cookie")).as("safe reads remain stateless and defer token generation").isEmpty();
            }
        }
    }

    @Test
    void logoutHasNoSafeMethodPageRedirectOrCookieSideEffectOnTheManagementPort() throws Exception {
        for (String method : new String[] {"GET", "HEAD"}) {
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + MANAGEMENT_PORT + "/logout"))
                    .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(method + " /logout is not a management endpoint").isEqualTo(404);
            assertThat(response.headers().allValues("Location")).isEmpty();
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
    }

    @Test
    void unsafeMethodsStayForbiddenEvenWithAValidCookieCsrfTokenAndNeverCreateASession() throws Exception {
        CsrfFilter csrf = security.getFilters().stream().filter(CsrfFilter.class::isInstance).map(CsrfFilter.class::cast).findFirst().orElseThrow();
        MockHttpServletRequest seed = new MockHttpServletRequest("GET", "/actuator/metrics");
        MockHttpServletResponse seeded = new MockHttpServletResponse();
        AtomicReference<String> masked = new AtomicReference<>();
        // Materialize the real deferred token in a test-only terminal chain; the production metrics endpoint never does this.
        csrf.doFilter(seed, seeded, (request, response) -> masked.set(((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken()));
        var cookie = seeded.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(seed.getSession(false)).isNull();

        MockHttpServletRequest valid = new MockHttpServletRequest("POST", "/actuator/metrics");
        valid.setCookies(cookie);
        valid.addHeader("X-XSRF-TOKEN", masked.get());
        AtomicBoolean acceptedByCsrf = new AtomicBoolean();
        csrf.doFilter(valid, new MockHttpServletResponse(), (request, response) -> acceptedByCsrf.set(true));
        assertThat(acceptedByCsrf).as("this is a valid token, so authorization must still refuse the write").isTrue();
        assertThat(valid.getSession(false)).isNull();

        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            for (String path : new String[] {"/actuator/metrics", "/logout"}) {
                for (boolean withToken : new boolean[] {false, true}) {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + MANAGEMENT_PORT + path))
                            .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody());
                    if (withToken) request.header("Cookie", "XSRF-TOKEN=" + cookie.getValue()).header("X-XSRF-TOKEN", masked.get());
                    HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as(method + " " + path + " token=" + withToken).isEqualTo(403);
                    assertThat(response.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.contains("JSESSIONID"))).isTrue();
                }
            }
        }
    }
}
