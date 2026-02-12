package app.mnema.ai.provider.gemini;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiJobProcessorTest {

    @Test
    void parseRetryAfterMessageReadsSeconds() {
        String message = "Please retry in 37.588078075s.";

        Long parsed = GeminiJobProcessor.parseRetryAfterMessage(message);

        assertThat(parsed).isEqualTo(37588L);
    }

    @Test
    void parseRetryAfterMessageReturnsNullWhenMissing() {
        assertThat(GeminiJobProcessor.parseRetryAfterMessage("No retry hint")).isNull();
        assertThat(GeminiJobProcessor.parseRetryAfterMessage("")).isNull();
        assertThat(GeminiJobProcessor.parseRetryAfterMessage(null)).isNull();
    }
}
