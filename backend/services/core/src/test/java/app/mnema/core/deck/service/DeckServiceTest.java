package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
class DeckServiceTest {

    @Mock
    UserDeckRepository userDeckRepository;

    @Mock
    UserCardRepository userCardRepository;

    @Mock
    PublicCardRepository publicCardRepository;

    @Mock
    PublicDeckRepository publicDeckRepository;

    @InjectMocks
    DeckService deckService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getPublicDecksByPage_delegatesToRepositoryAndMaps() {
        PublicDeckEntity entity = new PublicDeckEntity(
                1,
                UUID.randomUUID(),
                "Deck 1",
                "Desc",
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
        Page<PublicDeckEntity> repoPage = new PageImpl<>(
                List.of(entity),
                PageRequest.of(0, 10),
                1
        );

        when(publicDeckRepository.findByPublicFlagTrueAndListedTrue(any(Pageable.class)))
                .thenReturn(repoPage);

        Page<PublicDeckDTO> result = deckService.getPublicDecksByPage(1, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
        PublicDeckDTO dto = result.getContent().getFirst();
        assertThat(dto.name()).isEqualTo("Deck 1");
        assertThat(dto.language()).isEqualTo(LanguageTag.en);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(publicDeckRepository).findByPublicFlagTrueAndListedTrue(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

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

        assertThatThrownBy(() -> deckService.getUserCardsByDeck(currentUser, deckId, 1, 10))
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
                null,          // publicCardId == null, репозиторий publicCardRepository не трогаем
                true,
                false,
                "note",
                content,
                Instant.now(),
                null,
                null,
                null,
                0,
                false
        );

        Page<UserCardEntity> repoPage = new PageImpl<>(
                List.of(cardEntity),
                PageRequest.of(0, 50),
                1
        );

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository
                .findByUserDeckIdAndDeletedFalseAndSuspendedFalseOrderByCreatedAtAsc(eq(deckId), any(Pageable.class)))
                .thenReturn(repoPage);

        Page<UserCardDTO> result = deckService.getUserCardsByDeck(userId, deckId, 1, 50);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().personalNote()).isEqualTo("note");
    }

    @Test
    void getPublicCards_throwsIllegalArgumentExceptionOnInvalidPageOrLimit() {
        UUID deckId = UUID.randomUUID();

        assertThatThrownBy(() -> deckService.getPublicCards(deckId, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> deckService.getPublicCards(deckId, null, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getPublicCards_throwsSecurityExceptionWhenDeckIsNotPublic() {
        UUID deckId = UUID.randomUUID();

        PublicDeckEntity deck = new PublicDeckEntity(
                1,
                UUID.randomUUID(),
                "Deck",
                "Desc",
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

        when(publicDeckRepository.findTopByDeckIdOrderByVersionDesc(deckId))
                .thenReturn(Optional.of(deck));

        assertThatThrownBy(() -> deckService.getPublicCards(deckId, null, 1, 10))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Deck is not public");
    }

    @Test
    void getPublicCards_usesLatestVersionWhenVersionIsNull() {
        UUID deckId = UUID.randomUUID();

        PublicDeckEntity deck = new PublicDeckEntity(
                3,
                UUID.randomUUID(),
                "Deck v3",
                "Desc",
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

        when(publicDeckRepository.findTopByDeckIdOrderByVersionDesc(deckId))
                .thenReturn(Optional.of(deck));
        when(publicCardRepository.findByDeckIdAndDeckVersionOrderByOrderIndex(
                any(), anyInt(), any(Pageable.class)))
                .thenReturn(repoPage);

        Page<PublicCardDTO> result = deckService.getPublicCards(deckId, null, 1, 50);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(publicDeckRepository).findTopByDeckIdOrderByVersionDesc(deckId);
        verify(publicCardRepository).findByDeckIdAndDeckVersionOrderByOrderIndex(
                any(), eq(3), any(Pageable.class));
    }

    @Test
    void createNewDeck_createsPublicAndUserDeck() {
        UUID userId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();

        PublicDeckDTO requestDto = new PublicDeckDTO(
                null,
                null,
                null,
                "My deck",
                "Description",
                templateId,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                null,
                null,
                null,
                null
        );

        PublicDeckEntity savedPublicDeck = new PublicDeckEntity(
                1,
                userId,
                "My deck",
                "Description",
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

        when(publicDeckRepository.save(any(PublicDeckEntity.class)))
                .thenReturn(savedPublicDeck);

        UserDeckEntity savedUserDeck = new UserDeckEntity(
                userId,
                UUID.randomUUID(),
                1,
                1,
                true,
                SrAlgorithm.sm2.name(),
                null,
                "My deck",
                "Description",
                Instant.now(),
                null,
                false
        );

        when(userDeckRepository.save(any(UserDeckEntity.class)))
                .thenReturn(savedUserDeck);

        UserDeckDTO result = deckService.createNewDeck(userId, requestDto);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.displayName()).isEqualTo("My deck");
        assertThat(result.subscribedVersion()).isEqualTo(1);
        assertThat(result.currentVersion()).isEqualTo(1);
        assertThat(result.algorithmId()).isEqualTo(SrAlgorithm.sm2.name());
        assertThat(result.autoUpdate()).isTrue();
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

        assertThatThrownBy(() -> deckService.addNewCardToDeck(currentUser, deckId, request))
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
                1,
                userId,
                "Deck",
                "Desc",
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
        when(publicDeckRepository.findByDeckIdAndVersion(publicDeckId, 1))
                .thenReturn(Optional.of(publicDeck));

        AtomicReference<PublicCardEntity> savedPublicCardRef = new AtomicReference<>();

        when(publicCardRepository.save(any(PublicCardEntity.class)))
                .thenAnswer(invocation -> {
                    PublicCardEntity entity = invocation.getArgument(0);
                    try {
                        var field = PublicCardEntity.class.getDeclaredField("cardId");
                        field.setAccessible(true);
                        field.set(entity, UUID.randomUUID());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    savedPublicCardRef.set(entity);
                    return entity;
                });

        when(publicCardRepository.findByCardId(any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedPublicCardRef.get()));

        UserCardEntity savedUserCard = new UserCardEntity(
                userId,
                userDeckId,
                UUID.randomUUID(),
                true,
                false,
                "note",
                null,
                Instant.now(),
                null,
                null,
                null,
                0,
                false
        );
        when(userCardRepository.save(any(UserCardEntity.class)))
                .thenReturn(savedUserCard);

        UserCardDTO result = deckService.addNewCardToDeck(userId, userDeckId, request);

        assertThat(result.isCustom()).isTrue();
        assertThat(result.isDeleted()).isFalse();
        assertThat(result.isSuspended()).isFalse();
        assertThat(result.personalNote()).isEqualTo("note");

        verify(publicCardRepository).save(any(PublicCardEntity.class));
        verify(userCardRepository).save(any(UserCardEntity.class));
    }

    @Test
    void forkFromPublicDeck_returnsExistingSubscriptionIfPresent() {
        UUID userId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();

        UserDeckEntity existingDeck = new UserDeckEntity(
                userId,
                publicDeckId,
                1,
                1,
                true,
                SrAlgorithm.sm2.name(),
                null,
                "Existing deck",
                "Desc",
                Instant.now(),
                null,
                false
        );

        when(userDeckRepository.findByUserIdAndPublicDeckId(userId, publicDeckId))
                .thenReturn(Optional.of(existingDeck));

        UserDeckDTO result = deckService.forkFromPublicDeck(userId, publicDeckId);

        assertThat(result.displayName()).isEqualTo("Existing deck");
        verify(publicDeckRepository, never()).findTopByDeckIdOrderByVersionDesc(any());
    }

    @Test
    void forkFromPublicDeck_throwsWhenAuthorTriesToForkOwnDeck() {
        UUID authorId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();

        when(userDeckRepository.findByUserIdAndPublicDeckId(authorId, publicDeckId))
                .thenReturn(Optional.empty());

        PublicDeckEntity publicDeck = new PublicDeckEntity(
                1,
                authorId,
                "Author deck",
                "Desc",
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

        when(publicDeckRepository.findTopByDeckIdOrderByVersionDesc(publicDeckId))
                .thenReturn(Optional.of(publicDeck));

        assertThatThrownBy(() -> deckService.forkFromPublicDeck(authorId, publicDeckId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Author cannot fork own deck");
    }
}
