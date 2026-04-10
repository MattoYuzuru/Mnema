package app.mnema.ai.provider.openai;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiClientTest {

    @Test
    void fallbackEnabledForMissingResponsesEndpoint() {
        assertThat(OpenAiClient.shouldFallbackToChatCompat(404, "404 page not found")).isTrue();
        assertThat(OpenAiClient.shouldFallbackToChatCompat(405, "method not allowed")).isTrue();
    }

    @Test
    void fallbackEnabledForUnsupportedResponsesStyleBadRequest() {
        String message = "400 Bad Request: unsupported endpoint /v1/responses";
        assertThat(OpenAiClient.shouldFallbackToChatCompat(400, message)).isTrue();
    }

    @Test
    void fallbackDisabledForGenericBadRequest() {
        String message = "400 Bad Request: Invalid HTTP request received.";
        assertThat(OpenAiClient.shouldFallbackToChatCompat(400, message)).isFalse();
    }

    @Test
    void transportRetryDetectionHandlesTimeouts() {
        ResourceAccessException timeout = new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));

        assertThat(OpenAiClient.isRetryableTransportFailure(timeout)).isTrue();
        assertThat(OpenAiClient.isRetryableTransportFailure(new IllegalArgumentException("bad request"))).isFalse();
    }
}
