package app.mnema.ai.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiJobProcessorTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void parseRetryAfterMessageReadsSeconds() {
        String message = "Please retry in 12.5s.";

        Long parsed = OpenAiJobProcessor.parseRetryAfterMessage(message);

        assertThat(parsed).isEqualTo(12500L);
    }

    @Test
    void parseRetryAfterMessageReturnsNullWhenMissing() {
        assertThat(OpenAiJobProcessor.parseRetryAfterMessage("No retry hint")).isNull();
        assertThat(OpenAiJobProcessor.parseRetryAfterMessage("")).isNull();
        assertThat(OpenAiJobProcessor.parseRetryAfterMessage(null)).isNull();
    }

    @Test
    void resolveEnhanceModeUsesMissingFieldsForVisualAndTextActions() {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.putArray("actions").add("image");

        String mode = OpenAiJobProcessor.resolveEnhanceMode(params);

        assertThat(mode).isEqualTo("missing_fields");
    }

    @Test
    void resolveEnhanceModeUsesMissingAudioForAudioActions() {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.putArray("actions").add("missing_audio");

        String mode = OpenAiJobProcessor.resolveEnhanceMode(params);

        assertThat(mode).isEqualTo("missing_audio");
    }

    @Test
    void resolveEnhanceModeFallsBackToMissingFieldsWhenActionsEmpty() {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();

        String mode = OpenAiJobProcessor.resolveEnhanceMode(params);

        assertThat(mode).isEqualTo("missing_fields");
    }
}
