package app.mnema.ai.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.lang.reflect.Method;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiClientTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    @Test
    void detectsLocalGatewayBaseUrls() {
        assertThat(OpenAiClient.isLocalGatewayBaseUrl("http://local-ai-gateway:8089")).isTrue();
        assertThat(OpenAiClient.isLocalGatewayBaseUrl("http://localhost:8090")).isTrue();
        assertThat(OpenAiClient.isLocalGatewayBaseUrl("https://api.openai.com/v1")).isFalse();
    }

    @Test
    void localGatewayResponsesDoNotUseImmediateTransportRetries() {
        assertThat(OpenAiClient.resolveResponsesRetryCount(true)).isZero();
        assertThat(OpenAiClient.resolveResponsesRetryCount(false)).isEqualTo(4);
    }

    @Test
    void imageResponseFormatIsOmittedForGptImageModelsOnOpenAiApi() throws Exception {
        OpenAiClient client = new OpenAiClient(RestClient.builder(), openAiProps("https://api.openai.com"), OBJECT_MAPPER);
        Method method = OpenAiClient.class.getDeclaredMethod("shouldSendImageResponseFormat", String.class);
        method.setAccessible(true);

        assertThat((boolean) method.invoke(client, "gpt-image-1-mini")).isFalse();
        assertThat((boolean) method.invoke(client, "dall-e-3")).isTrue();
    }

    @Test
    void imageResponseFormatIsKeptForLocalGatewayCompatibility() throws Exception {
        OpenAiClient client = new OpenAiClient(RestClient.builder(), openAiProps("http://localhost:8089"), OBJECT_MAPPER);
        Method method = OpenAiClient.class.getDeclaredMethod("shouldSendImageResponseFormat", String.class);
        method.setAccessible(true);

        assertThat((boolean) method.invoke(client, "gpt-image-1-mini")).isTrue();
    }

    @Test
    void summarizeResponseIncludesStatusModelAndOutputTypes() {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("status", "completed");
        response.put("model", "qwen3:4b");
        response.putArray("output")
                .addObject().put("type", "reasoning");

        assertThat(OpenAiClient.summarizeResponse(response))
                .contains("status=completed")
                .contains("model=qwen3:4b")
                .contains("outputTypes=reasoning");
    }

    private static OpenAiProps openAiProps(String baseUrl) {
        return new OpenAiProps(
                baseUrl,
                "",
                "gpt-4.1-mini",
                "gpt-4o-mini-tts",
                "alloy",
                "mp3",
                "gpt-4o-mini-transcribe",
                "gpt-image-1-mini",
                "1024x1024",
                "low",
                "natural",
                "png",
                "sora-2",
                5,
                "720p",
                60,
                12,
                5,
                2_000L,
                30_000L,
                10_000L,
                600_000L
        );
    }
}
