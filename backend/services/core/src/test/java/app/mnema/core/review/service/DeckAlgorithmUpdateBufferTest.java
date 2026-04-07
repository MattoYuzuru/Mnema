package app.mnema.core.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeckAlgorithmUpdateBufferTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void applyPendingReturnsDeckConfigWhenNoEntryOrExpiredOrAlgorithmChanged() {
        DeckAlgorithmUpdateBuffer buffer = new DeckAlgorithmUpdateBuffer();
        UUID deckId = UUID.randomUUID();
        ObjectNode config = json("x", 1);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");

        assertThat(buffer.applyPending(deckId, "fsrs_v6", config, now)).isEqualTo(config);
        buffer.recordUpdate(deckId, "fsrs_v6", json("x", 2), now);

        assertThat(buffer.applyPending(deckId, "sm2", config, now.plusSeconds(1))).isEqualTo(config);
        assertThat(buffer.applyPending(deckId, "fsrs_v6", config, now.plusSeconds(60 * 31L))).isEqualTo(config);
    }

    @Test
    void recordUpdateReturnsPendingValueOnlyWhenFlushThresholdReached() {
        DeckAlgorithmUpdateBuffer buffer = new DeckAlgorithmUpdateBuffer();
        UUID deckId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T10:15:30Z");

        assertThat(buffer.recordUpdate(deckId, "fsrs_v6", json("step", 1), now)).isEmpty();
        assertThat(buffer.recordUpdate(deckId, "fsrs_v6", json("step", 1), now.plusSeconds(1))).isEmpty();

        Optional<com.fasterxml.jackson.databind.JsonNode> flushed = Optional.empty();
        for (int i = 2; i <= 6; i++) {
            flushed = buffer.recordUpdate(deckId, "fsrs_v6", json("step", i), now.plusSeconds(i));
        }

        assertThat(flushed).isPresent();
        assertThat(flushed.orElseThrow().path("step").asInt()).isEqualTo(6);
    }

    @Test
    void flushIfPendingRespectsPendingStateAndClearRemovesEntry() {
        DeckAlgorithmUpdateBuffer buffer = new DeckAlgorithmUpdateBuffer();
        UUID deckId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        ObjectNode updated = json("retention", 90);

        buffer.recordUpdate(deckId, "fsrs_v6", updated, now);

        assertThat(buffer.flushIfPending(deckId, "fsrs_v6", now.plusSeconds(1))).contains(updated);
        assertThat(buffer.flushIfPending(deckId, "fsrs_v6", now.plusSeconds(2))).isEmpty();

        buffer.clear(deckId);
        assertThat(buffer.applyPending(deckId, "fsrs_v6", json("retention", 80), now.plusSeconds(3)).path("retention").asInt()).isEqualTo(80);
    }

    private static ObjectNode json(String key, int value) {
        return MAPPER.createObjectNode().put(key, value);
    }
}
