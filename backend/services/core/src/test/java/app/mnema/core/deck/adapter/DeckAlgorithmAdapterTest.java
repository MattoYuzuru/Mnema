package app.mnema.core.deck.adapter;

import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.repository.UserDeckRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeckAlgorithmAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void getDeckAlgorithmReturnsDefaultWhenDeckAlgorithmBlank() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = deck(userId, deckId, " ", null);
        UserDeckRepository repository = mock(UserDeckRepository.class);
        when(repository.findById(deckId)).thenReturn(Optional.of(deck));

        DeckAlgorithmAdapter adapter = new DeckAlgorithmAdapter(repository);

        var result = adapter.getDeckAlgorithm(userId, deckId);

        assertThat(result.algorithmId()).isEqualTo("fsrs_v6");
        assertThat(result.algorithmParams()).isNull();
    }

    @Test
    void getDeckAlgorithmRejectsMissingOrForeignDeck() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckRepository repository = mock(UserDeckRepository.class);
        when(repository.findById(deckId)).thenReturn(Optional.empty());

        DeckAlgorithmAdapter adapter = new DeckAlgorithmAdapter(repository);
        assertThatThrownBy(() -> adapter.getDeckAlgorithm(userId, deckId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User deck not found");

        UserDeckEntity foreign = deck(UUID.randomUUID(), deckId, "fsrs_v6", null);
        when(repository.findById(deckId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> adapter.getDeckAlgorithm(userId, deckId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void updateDeckAlgorithmRetriesOnOptimisticLockingFailure() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = deck(userId, deckId, "sm2", null);
        ObjectNode config = MAPPER.createObjectNode().put("retention", 0.9);
        UserDeckRepository repository = mock(UserDeckRepository.class);
        when(repository.findById(deckId)).thenReturn(Optional.of(deck), Optional.of(deck));
        when(repository.save(deck))
                .thenThrow(new OptimisticLockingFailureException("retry"))
                .thenReturn(deck);

        DeckAlgorithmAdapter adapter = new DeckAlgorithmAdapter(repository);

        var result = adapter.updateDeckAlgorithm(userId, deckId, "fsrs_v6", config);

        assertThat(result.algorithmId()).isEqualTo("fsrs_v6");
        assertThat(deck.getAlgorithmId()).isEqualTo("fsrs_v6");
        assertThat(deck.getAlgorithmParams()).isEqualTo(config);
        verify(repository, times(2)).save(deck);
    }

    @Test
    void updateDeckAlgorithmPropagatesFinalOptimisticLockingFailure() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = deck(userId, deckId, "sm2", null);
        UserDeckRepository repository = mock(UserDeckRepository.class);
        when(repository.findById(deckId)).thenReturn(Optional.of(deck), Optional.of(deck), Optional.of(deck));
        when(repository.save(deck)).thenThrow(new OptimisticLockingFailureException("retry"));

        DeckAlgorithmAdapter adapter = new DeckAlgorithmAdapter(repository);

        assertThatThrownBy(() -> adapter.updateDeckAlgorithm(userId, deckId, "fsrs_v6", MAPPER.createObjectNode()))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private static UserDeckEntity deck(UUID userId, UUID deckId, String algorithmId, ObjectNode params) {
        UserDeckEntity entity = new UserDeckEntity(
                userId,
                UUID.randomUUID(),
                1,
                1,
                true,
                algorithmId,
                params,
                "Deck",
                "Description",
                Instant.now(),
                Instant.now(),
                false
        );
        entity.setUserDeckId(deckId);
        return entity;
    }
}
