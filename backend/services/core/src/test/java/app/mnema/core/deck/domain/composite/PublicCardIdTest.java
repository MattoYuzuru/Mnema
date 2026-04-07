package app.mnema.core.deck.domain.composite;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicCardIdTest {

    @Test
    void equalsHashCodeAndSettersUseCompositeKeyFields() {
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        PublicCardId id = new PublicCardId();
        id.setDeckId(deckId);
        id.setDeckVersion(2);
        id.setCardId(cardId);

        PublicCardId same = new PublicCardId(deckId, 2, cardId);

        assertThat(id.getDeckId()).isEqualTo(deckId);
        assertThat(id.getDeckVersion()).isEqualTo(2);
        assertThat(id.getCardId()).isEqualTo(cardId);
        assertThat(id).isEqualTo(same);
        assertThat(id.hashCode()).isEqualTo(same.hashCode());
    }
}
