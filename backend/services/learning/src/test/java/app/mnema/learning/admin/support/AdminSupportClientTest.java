package app.mnema.learning.admin.support;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminSupportClientTest {
    private static final String SECRET = "support-machine-credential-32-characters";
    private HttpServer server;
    private AdminSupportClient client;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile String response = "{\"entries\":[],\"nextCursor\":null}";
    private volatile String contentType = "application/json";
    private volatile int status = 200;

    @BeforeEach void fixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/support", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Idempotency-Replayed", "true");
            if (status == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        client = new AdminSupportClient(new AdminSupportSettings("http://127.0.0.1:"+server.getAddress().getPort()+"/internal/support",
                SECRET, true, Duration.ofSeconds(1), 1));
    }

    @AfterEach void close() { client.close(); server.stop(0); }

    @Test void forwardsMachineCredentialAndPreservesReceiptAcknowledgement() {
        var list = client.get("/tickets?status=open");
        assertThat(list.status()).isEqualTo(200);
        assertThat(authorization.get()).isEqualTo("Bearer "+SECRET);
        var command = JsonNodeFactory.instance.objectNode().put("commandId", "018f0060-86dc-7e16-9429-7157420da650")
                .put("expectedVersion", 1).put("actorAccountId", "018f0060-86dc-7e16-9429-7157420da651")
                .put("type", "reply").put("text", "Answer");
        response = "{\"commandId\":\"018f0060-86dc-7e16-9429-7157420da650\",\"ticketId\":\"1\",\"version\":2,\"messageId\":\"2\",\"outboxId\":\"3\",\"delivery\":\"queued\"}";
        status = 202;
        var result = client.command("/tickets/1/commands", command);
        assertThat(result.status()).isEqualTo(202);
        assertThat(result.replayed()).isTrue();
        assertThat(result.body().path("delivery").stringValue()).isEqualTo("queued");
        assertThat(requestBody.get()).contains("Answer").contains(command.path("actorAccountId").stringValue());
    }

    @Test void conversationWithUnknownAttachmentSizeCrossesTheProxyWithoutInventingBytes() throws IOException {
        var conversation = (ObjectNode) SupportResponsesTest.fixture().path("conversation").deepCopy();
        var attachment = (ObjectNode) conversation.path("messages").get(0).path("attachment");
        attachment.putNull("size").put("name", "").put("mimeType", "");
        response = conversation.toString();
        var result = client.get("/tickets/" + conversation.path("ticket").path("id").stringValue());
        assertThat(result.status()).isEqualTo(200);
        var metadata = result.body().path("messages").get(0).path("attachment");
        assertThat(metadata.path("size").isNull()).isTrue();
        assertThat(metadata.path("name").stringValue()).isEmpty();
        assertThat(metadata.path("mimeType").stringValue()).isEmpty();
        assertThat(metadata.size()).isEqualTo(4);
        assertThat(result.body().path("messages").get(1).path("text").stringValue()).isEqualTo("Private fixture note");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void rejectsRedirectsAuthFailuresMalformedAndOversizedResponsesWithoutRetry() {
        for (int rejection : new int[]{302, 401, 403, 429, 500}) {
            status = rejection;
            assertThatThrownBy(() -> client.get("/tickets")).isInstanceOf(SupportUnavailableException.class);
        }
        assertThat(calls.get()).isEqualTo(5);
        status = 200;
        for (String malformed : new String[]{"not json", "{\"entries\":[],\"nextCursor\":null,\"token\":\"secret\"}", "a".repeat(1_048_577)}) {
            response = malformed;
            assertThatThrownBy(() -> client.get("/tickets")).isInstanceOf(SupportUnavailableException.class);
        }
        response = "{\"entries\":[],\"nextCursor\":null}";
        contentType = "text/html";
        assertThatThrownBy(() -> client.get("/tickets")).isInstanceOf(SupportUnavailableException.class);
    }

    @Test void mapsKnownBoundaryFailuresAndDisabledConfigurationMakesNoRequest() {
        status = 400;
        assertThatThrownBy(() -> client.get("/tickets")).isInstanceOf(InvalidRequestException.class);
        status = 404;
        assertThatThrownBy(() -> client.get("/tickets/1")).isInstanceOf(ResourceNotFoundException.class);
        status = 409;
        assertThatThrownBy(() -> client.get("/tickets/1")).isInstanceOf(SupportConflictException.class);
        int before = calls.get();
        var disabled = new AdminSupportClient(new AdminSupportSettings("", "", false, Duration.ofSeconds(1), 1));
        try { assertThatThrownBy(() -> disabled.get("/tickets")).isInstanceOf(SupportUnavailableException.class); }
        finally { disabled.close(); }
        assertThat(calls.get()).isEqualTo(before);
    }
}
