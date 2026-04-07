package app.mnema.core.deck.domain.composite;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeckUpdateSessionIdTest {

    @Test
    void equalsHashCodeAndSettersUseCompositeKeyFields() {
        UUID deckId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        DeckUpdateSessionId id = new DeckUpdateSessionId();
        id.setDeckId(deckId);
        id.setOperationId(operationId);

        DeckUpdateSessionId same = new DeckUpdateSessionId(deckId, operationId);

        assertThat(id.getDeckId()).isEqualTo(deckId);
        assertThat(id.getOperationId()).isEqualTo(operationId);
        assertThat(id).isEqualTo(same);
        assertThat(id.hashCode()).isEqualTo(same.hashCode());
    }
}
