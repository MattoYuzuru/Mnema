package app.mnema.core.deck.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeckUpdateSessionEntityTest {

    @Test
    void gettersAndMutatorsExposeSessionFields() {
        UUID deckId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T10:15:30Z");

        DeckUpdateSessionEntity entity = new DeckUpdateSessionEntity(deckId, operationId, authorId, 3, now, now);
        entity.setTargetVersion(4);
        entity.setUpdatedAt(now.plusSeconds(60));

        assertThat(entity.getDeckId()).isEqualTo(deckId);
        assertThat(entity.getOperationId()).isEqualTo(operationId);
        assertThat(entity.getAuthorId()).isEqualTo(authorId);
        assertThat(entity.getTargetVersion()).isEqualTo(4);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now.plusSeconds(60));
    }
}
