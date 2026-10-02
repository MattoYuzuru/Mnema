package app.mnema.identityaccount.mail;

import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The outbound mail API payload is an external wire contract; its exact JSON must not drift. */
class PostboxMailPayloadTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void signedRequestCarriesTheExactJsonDocument() throws Exception {
        var body = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/email/outbound-emails", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var mail = new PostboxMail("access", "secret",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/email/outbound-emails"),
                    true, json);

            assertThat(mail.reset("user@example.test", "https://mnema.app/reset?token=abc")).isTrue();

            assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 ");
            assertThat(contentType.get()).startsWith("application/json");
            assertThat(json.readTree(body.get())).isEqualTo(json.readTree("""
                    {"FromEmailAddress":"noreply@mnema.app",
                     "Destination":{"ToAddresses":["user@example.test"]},
                     "Content":{"Simple":{
                       "Subject":{"Data":"Reset your Mnema password","Charset":"UTF-8"},
                       "Body":{"Text":{"Data":"Reset your password within 10 minutes: https://mnema.app/reset?token=abc",
                                       "Charset":"UTF-8"}}}}}"""));
        } finally {
            server.stop(0);
        }
    }
}
