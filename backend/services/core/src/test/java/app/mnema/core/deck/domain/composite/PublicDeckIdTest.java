package app.mnema.core.deck.domain.composite;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDeckIdTest {

    @Test
    void equalsHashCodeAndSettersUseCompositeKeyFields() {
        UUID deckId = UUID.randomUUID();
        PublicDeckId id = new PublicDeckId();
        id.setDeckId(deckId);
        id.setVersion(4);

        PublicDeckId same = new PublicDeckId(deckId, 4);

        assertThat(id.getDeckId()).isEqualTo(deckId);
        assertThat(id.getVersion()).isEqualTo(4);
        assertThat(id).isEqualTo(same);
        assertThat(id.hashCode()).isEqualTo(same.hashCode());
    }
}
