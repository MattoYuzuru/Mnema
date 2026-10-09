package app.mnema.learning.billing;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The real HTTP security chain: the bank's notification path is open without a bearer, exactly one method of it, and nothing else of billing is. */
class BillingSecurityHttpTest extends BillingIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @AfterAll
    static void closeClient() {
        HTTP.close();
    }

    private HttpResponse<String> send(String method, String path, String accept, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) request.header("Content-Type", "application/json");
        if (accept != null) request.header("Accept", accept);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theNotificationPathNeedsNoBearerAndAnswersPlainText() throws Exception {
        HttpResponse<String> forged = send("POST", "/billing/tbank/notifications", null, BillingFixtures.tbank("notification-forged-token.json").toString());

        assertThat(forged.statusCode()).as("403 of the controller, not 401 of the bearer filter").isEqualTo(403);
        assertThat(forged.body()).isEqualTo("ERROR");
        assertThat(forged.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(forged.headers().firstValue("WWW-Authenticate")).isEmpty();

        // The bank sends whatever Accept it likes; the answer is not negotiated.
        HttpResponse<String> unknownOrder = send("POST", "/billing/tbank/notifications", "application/json",
                BillingFixtures.notification("notification-confirmed.json", UUID.randomUUID(), "7777777777", "CONFIRMED", 44_900).toString());
        assertThat(unknownOrder.statusCode()).isEqualTo(200);
        assertThat(unknownOrder.body()).isEqualTo("OK");
        assertThat(unknownOrder.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
    }

    @Test
    void aSignedConfirmationOverHttpPaysTheOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PRO").path("orderId").stringValue(null);
        BANK.move(orderId, "CONFIRMED");

        HttpResponse<String> response = send("POST", "/billing/tbank/notifications", "text/plain", notification("notification-confirmed.json", orderId, "CONFIRMED").toString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("OK");
        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void oversizedAndMalformedBodiesAreBadRequestsOverHttp() throws Exception {
        HttpResponse<String> large = send("POST", "/billing/tbank/notifications", null, "{\"Message\":\"" + "x".repeat(20_000) + "\"}");
        HttpResponse<String> garbage = send("POST", "/billing/tbank/notifications", null, "not json");

        assertThat(large.statusCode()).isEqualTo(400);
        assertThat(large.body()).isEqualTo("ERROR");
        assertThat(garbage.statusCode()).isEqualTo(400);
    }

    @Test
    void everyOtherMethodAndPathOfBillingStillNeedsAToken() throws Exception {
        for (String method : new String[] {"GET", "PUT", "DELETE", "PATCH"}) {
            HttpResponse<String> response = send(method, "/billing/tbank/notifications", null, method.equals("GET") || method.equals("DELETE") ? null : "{}");
            assertThat(response.statusCode()).as(method).isEqualTo(401);
            JsonNode problem = BillingFixtures.JSON.readTree(response.body());
            assertThat(problem.path("code").stringValue(null)).isEqualTo("AUTHENTICATION_REQUIRED");
        }
        assertThat(send("POST", "/billing/tbank/notifications/", null, "{}").statusCode()).as("a path of its own is not the open one").isEqualTo(401);
        HttpResponse<String> checkout = send("POST", "/billing/checkout", null, "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}");
        assertThat(checkout.statusCode()).isEqualTo(401);
        assertThat(BillingFixtures.JSON.readTree(checkout.body()).path("code").stringValue(null)).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(send("GET", "/billing/orders/" + UUID.randomUUID(), null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/billing/tbank/other", null, null).statusCode()).isEqualTo(401);
        assertThat(BANK.inits).isEmpty();
    }
}
