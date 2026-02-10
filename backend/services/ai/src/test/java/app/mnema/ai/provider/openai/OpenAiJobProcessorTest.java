package app.mnema.ai.provider.openai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiJobProcessorTest {

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
}
