package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardServiceTest {

    @Mock
    UserDeckRepository userDeckRepository;

    @Mock
    UserCardRepository userCardRepository;

    @Mock
    PublicCardRepository publicCardRepository;

    @Mock
    PublicDeckRepository publicDeckRepository;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    CardService cardService;

    @Test
    void getUserCardsByDeck_throwsSecurityExceptionWhenDeckNotOwnedByUser() {
        UUID currentUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        UserDeckEntity deck = new UserDeckEntity(
                otherUser,
                UUID.randomUUID(),
                1,
                1,
                true,
                SrAlgorithm.sm2.name(),
                null,
                "Deck",
                "Desc",
                Instant.now(),
                null,
                false
        );

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));

        assertThatThrownBy(() -> cardService.getUserCardsByDeck(currentUser, deckId, 1, 10))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void getUserCardsByDeck_returnsPageForOwner() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        UserDeckEntity deck = new UserDeckEntity(
                userId,
                UUID.randomUUID(),
                1,
                1,
                true,
                SrAlgorithm.sm2.name(),
                null,
                "Deck",
                "Desc",
                Instant.now(),
                null,
                false
        );

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        UserCardEntity cardEntity = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                false,
                "note",
                content,
                Instant.now(),
                null
        );

        Page<UserCardEntity> repoPage = new PageImpl<>(
                List.of(cardEntity),
                PageRequest.of(0, 50),
                1
        );

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository
                .findByUserDeckIdAndDeletedFalseOrderByCreatedAtAsc(eq(deckId), any(Pageable.class)))
                .thenReturn(repoPage);

        Page<UserCardDTO> result = cardService.getUserCardsByDeck(userId, deckId, 1, 50);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().personalNote()).isEqualTo("note");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userCardRepository).findByUserDeckIdAndDeletedFalseOrderByCreatedAtAsc(eq(deckId), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void getPublicCards_throwsIllegalArgumentExceptionOnInvalidPageOrLimit() {
        UUID deckId = UUID.randomUUID();

        assertThatThrownBy(() -> cardService.getPublicCards(deckId, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> cardService.getPublicCards(deckId, null, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getPublicCards_throwsSecurityExceptionWhenDeckIsNotPublic() {
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();

        PublicDeckEntity deck = new PublicDeckEntity(
                publicDeckId,
                1,
                UUID.randomUUID(),
                "Deck",
                "Desc",
                null,
                UUID.randomUUID(),
                false,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );

        when(publicDeckRepository.findLatestByDeckId(deckId))
                .thenReturn(Optional.of(deck));

        assertThatThrownBy(() -> cardService.getPublicCards(deckId, null, 1, 10))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Deck is not public");
    }

    @Test
    void getPublicCards_usesLatestVersionWhenVersionIsNull() {
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();

        PublicDeckEntity deck = new PublicDeckEntity(
                publicDeckId,
                3,
                UUID.randomUUID(),
                "Deck v3",
                "Desc",
                null,
                UUID.randomUUID(),
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );

        PublicCardEntity card = mock(PublicCardEntity.class);
        Page<PublicCardEntity> repoPage = new PageImpl<>(
                List.of(card),
                PageRequest.of(0, 50),
                1
        );

        when(publicDeckRepository.findLatestByDeckId(deckId))
                .thenReturn(Optional.of(deck));
        when(publicCardRepository.findByDeckIdAndDeckVersionOrderByOrderIndex(
                any(), anyInt(), any(Pageable.class)))
                .thenReturn(repoPage);

        Page<PublicCardDTO> result = cardService.getPublicCards(deckId, null, 1, 50);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(publicDeckRepository).findLatestByDeckId(deckId);
        verify(publicCardRepository).findByDeckIdAndDeckVersionOrderByOrderIndex(
                any(), eq(3), any(Pageable.class));
    }

    @Test
    void addNewCardToDeck_throwsSecurityExceptionWhenDeckNotOwnedByUser() {
        UUID currentUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        UserDeckEntity deck = new UserDeckEntity(
                otherUser,
                UUID.randomUUID(),
                1,
                1,
                true,
                SrAlgorithm.sm2.name(),
                null,
                "Deck",
                "Desc",
                Instant.now(),
                null,
                false
        );

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));

        CreateCardRequest request = new CreateCardRequest(
                null,
                1,
                new String[]{"tag"},
                "note",
                null,
                "checksum"
        );

        assertThatThrownBy(() -> cardService.addNewCardToDeck(currentUser, deckId, request))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void addNewCardToDeck_asAuthorCreatesPublicAndUserCard() {
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();

        UserDeckEntity userDeck = new UserDeckEntity(
                userId,
                publicDeckId,
                1,
                1,
                true,
                SrAlgorithm.sm2.name(),
                null,
                "Deck",
                "Desc",
                Instant.now(),
                null,
                false
        );

        PublicDeckEntity publicDeck = new PublicDeckEntity(
                publicDeckId,
                1,
                userId,
                "Deck",
                "Desc",
                null,
                templateId,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        CreateCardRequest request = new CreateCardRequest(
                content,
                1,
                new String[]{"tag"},
                "note",
                null,
                "checksum"
        );

        when(userDeckRepository.findById(userDeckId)).thenReturn(Optional.of(userDeck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId))
                .thenReturn(Optional.of(publicDeck));

        // сохранение новой версии public_decks должно вернуть не null
        when(publicDeckRepository.save(any(PublicDeckEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Старые карты клонируем из пустого списка
        when(publicCardRepository.findByDeckIdAndDeckVersion(any(UUID.class), anyInt()))
                .thenReturn(List.of());

        AtomicReference<PublicCardEntity> lastSavedPublicCardRef = new AtomicReference<>();

        when(publicCardRepository.saveAll(anyList()))
                .thenAnswer(invocation -> {
                    List<PublicCardEntity> entities = invocation.getArgument(0);
                    for (PublicCardEntity entity : entities) {
                        try {
                            var field = PublicCardEntity.class.getDeclaredField("cardId");
                            field.setAccessible(true);
                            field.set(entity, UUID.randomUUID());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        lastSavedPublicCardRef.set(entity);
                    }
                    return entities;
                });

        when(publicCardRepository.findByCardId(any()))
                .thenAnswer(invocation -> Optional.ofNullable(lastSavedPublicCardRef.get()));

        when(userCardRepository.save(any(UserCardEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserCardDTO result = cardService.addNewCardToDeck(userId, userDeckId, request);

        // для автора публичной колоды карта должна быть не кастомной
        assertThat(result.isCustom()).isFalse();
        assertThat(result.isDeleted()).isFalse();
        assertThat(result.personalNote()).isEqualTo("note");

        verify(publicCardRepository).saveAll(anyList());
        verify(userDeckRepository).save(any(UserDeckEntity.class));
        verify(userCardRepository).save(any(UserCardEntity.class));
    }
}
