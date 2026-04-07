package app.mnema.core.deck.adapter;

import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeckCardViewAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void getCardViewsReturnsEmptyForEmptyInput() {
        UserCardRepository userCardRepository = mock(UserCardRepository.class);
        PublicCardRepository publicCardRepository = mock(PublicCardRepository.class);
        DeckCardViewAdapter adapter = new DeckCardViewAdapter(userCardRepository, publicCardRepository);

        assertThat(adapter.getCardViews(UUID.randomUUID(), List.of())).isEmpty();
        verifyNoInteractions(userCardRepository, publicCardRepository);
    }

    @Test
    void getCardViewsReturnsCustomCardContent() {
        UUID userId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        UserCardEntity card = userCard(userId, userCardId, null, true, false, object("front", "Custom"));
        UserCardRepository userCardRepository = mock(UserCardRepository.class);
        PublicCardRepository publicCardRepository = mock(PublicCardRepository.class);
        when(userCardRepository.findAllById(List.of(userCardId))).thenReturn(List.of(card));

        DeckCardViewAdapter adapter = new DeckCardViewAdapter(userCardRepository, publicCardRepository);

        var result = adapter.getCardViews(userId, List.of(userCardId));

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.userCardId()).isEqualTo(userCardId);
            assertThat(view.isCustom()).isTrue();
            assertThat(view.effectiveContent().path("front").asText()).isEqualTo("Custom");
        });
    }

    @Test
    void getCardViewsMergesPublicContentAndKeepsLatestPublicVersion() {
        UUID userId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UserCardEntity card = userCard(userId, userCardId, publicCardId, false, false, object("back", "Override"));

        PublicCardEntity latest = publicCard(publicCardId, 2, object("front", "Base"));
        PublicCardEntity older = publicCard(publicCardId, 1, object("front", "Old"));
        UserCardRepository userCardRepository = mock(UserCardRepository.class);
        PublicCardRepository publicCardRepository = mock(PublicCardRepository.class);
        when(userCardRepository.findAllById(List.of(userCardId))).thenReturn(List.of(card));
        when(publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(java.util.Set.of(publicCardId)))
                .thenReturn(List.of(latest, older));

        DeckCardViewAdapter adapter = new DeckCardViewAdapter(userCardRepository, publicCardRepository);

        var result = adapter.getCardViews(userId, List.of(userCardId));

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.publicCardId()).isEqualTo(publicCardId);
            assertThat(view.effectiveContent().path("front").asText()).isEqualTo("Base");
            assertThat(view.effectiveContent().path("back").asText()).isEqualTo("Override");
        });
    }

    @Test
    void getCardViewsRejectsMissingForeignAndDeletedCards() {
        UUID userId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        UUID foreignCardId = UUID.randomUUID();
        UUID deletedCardId = UUID.randomUUID();
        UserCardRepository userCardRepository = mock(UserCardRepository.class);
        PublicCardRepository publicCardRepository = mock(PublicCardRepository.class);

        when(userCardRepository.findAllById(List.of(userCardId))).thenReturn(List.of());
        DeckCardViewAdapter adapter = new DeckCardViewAdapter(userCardRepository, publicCardRepository);
        assertThatThrownBy(() -> adapter.getCardViews(userId, List.of(userCardId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User card not found");

        UserCardEntity foreign = userCard(UUID.randomUUID(), foreignCardId, null, true, false, object("front", "x"));
        when(userCardRepository.findAllById(List.of(foreignCardId))).thenReturn(List.of(foreign));
        assertThatThrownBy(() -> adapter.getCardViews(userId, List.of(foreignCardId)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied");

        UserCardEntity deleted = userCard(userId, deletedCardId, null, true, true, object("front", "x"));
        when(userCardRepository.findAllById(List.of(deletedCardId))).thenReturn(List.of(deleted));
        assertThatThrownBy(() -> adapter.getCardViews(userId, List.of(deletedCardId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Card is deleted");
    }

    @Test
    void getCardViewsFailsWhenPublicCardCannotBeResolved() {
        UUID userId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UserCardEntity card = userCard(userId, userCardId, publicCardId, false, false, object("back", "Override"));
        UserCardRepository userCardRepository = mock(UserCardRepository.class);
        PublicCardRepository publicCardRepository = mock(PublicCardRepository.class);
        when(userCardRepository.findAllById(List.of(userCardId))).thenReturn(List.of(card));
        when(publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(java.util.Set.of(publicCardId))).thenReturn(List.of());

        DeckCardViewAdapter adapter = new DeckCardViewAdapter(userCardRepository, publicCardRepository);

        assertThatThrownBy(() -> adapter.getCardViews(userId, List.of(userCardId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Public card not found");
    }

    private static UserCardEntity userCard(UUID userId, UUID userCardId, UUID publicCardId, boolean custom, boolean deleted, ObjectNode contentOverride) {
        UserCardEntity card = new UserCardEntity(
                userId,
                UUID.randomUUID(),
                publicCardId,
                custom,
                deleted,
                null,
                null,
                contentOverride,
                Instant.now(),
                Instant.now()
        );
        card.setUserCardId(userCardId);
        return card;
    }

    private static PublicCardEntity publicCard(UUID publicCardId, int version, ObjectNode content) {
        return new PublicCardEntity(
                UUID.randomUUID(),
                version,
                null,
                publicCardId,
                content,
                1,
                null,
                Instant.now(),
                Instant.now(),
                true,
                "checksum"
        );
    }

    private static ObjectNode object(String key, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(key, value);
        return node;
    }
}
