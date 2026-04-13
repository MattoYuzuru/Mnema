package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.DuplicateGroupDTO;
import app.mnema.core.deck.domain.dto.DuplicateResolveResultDTO;
import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.dto.MissingFieldSummaryDTO;
import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.entity.DeckUpdateSessionEntity;
import app.mnema.core.deck.domain.entity.FieldTemplateEntity;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.request.DuplicateResolveRequest;
import app.mnema.core.deck.domain.request.DuplicateSearchRequest;
import app.mnema.core.deck.domain.request.MissingFieldCardsRequest;
import app.mnema.core.deck.domain.request.MissingFieldSummaryRequest;
import app.mnema.core.deck.domain.type.CardFieldType;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.DeckUpdateSessionRepository;
import app.mnema.core.deck.repository.FieldTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import app.mnema.core.security.ContentAdminAccessService;
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

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Mock
    DeckUpdateSessionRepository deckUpdateSessionRepository;

    @Mock
    FieldTemplateRepository fieldTemplateRepository;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    ContentAdminAccessService contentAdminAccessService;

    @InjectMocks
    CardService cardService;

    @Test
    void getUserCardsByDeck_throwsSecurityExceptionWhenDeckNotOwnedByUser() {
        UUID currentUser = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, UUID.randomUUID(), null)));

        assertThatThrownBy(() -> cardService.getUserCardsByDeck(currentUser, deckId, 1, 10))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void getUserCardsByDeck_returnsPageForOwner() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserCardEntity cardEntity = userCard(userId, deckId, null, true, false, "note", null, textContent("front", "Q"));

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));
        when(userCardRepository.findByUserDeckIdAndDeletedFalseOrderByCreatedAtAscUserCardIdAsc(eq(deckId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cardEntity), PageRequest.of(0, 50), 1));

        Page<UserCardDTO> result = cardService.getUserCardsByDeck(userId, deckId, 1, 50);

        assertThat(result.getContent()).singleElement().satisfies(dto -> assertThat(dto.personalNote()).isEqualTo("note"));
    }

    @Test
    void getUserCardsByDeckInternal_usesDeckOwnerForLookup() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserCardEntity cardEntity = userCard(userId, deckId, null, true, false, "note", null, textContent("front", "Q"));

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));
        when(userCardRepository.findByUserDeckIdAndDeletedFalseOrderByCreatedAtAscUserCardIdAsc(eq(deckId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cardEntity), PageRequest.of(0, 25), 1));

        Page<UserCardDTO> result = cardService.getUserCardsByDeckInternal(deckId, 1, 25);

        assertThat(result.getContent()).singleElement().satisfies(dto -> assertThat(dto.personalNote()).isEqualTo("note"));
    }

    @Test
    void getUserCard_returnsEffectiveContentForOwner() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UserCardEntity card = userCard(userId, deckId, publicCardId, false, false, "note", null, textContent("back", "override"));
        PublicCardEntity publicCard = publicCard(UUID.randomUUID(), 2, publicCardId, textContent("front", "base"), new String[]{"public"}, true, "chk");

        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(publicCardId)).thenReturn(Optional.of(publicCard));

        UserCardDTO result = cardService.getUserCard(userId, deckId, card.getUserCardId());

        assertThat(result.tags()).containsExactly("public");
        assertThat(result.effectiveContent()).isEqualTo(json("{\"front\":\"base\",\"back\":\"override\"}"));
    }

    @Test
    void getUserCardInternal_usesCardOwnerForLookup() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UserCardEntity card = userCard(userId, deckId, publicCardId, false, false, "note", null, textContent("back", "override"));
        PublicCardEntity publicCard = publicCard(UUID.randomUUID(), 2, publicCardId, textContent("front", "base"), new String[]{"public"}, true, "chk");

        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(publicCardId)).thenReturn(Optional.of(publicCard));

        UserCardDTO result = cardService.getUserCardInternal(deckId, card.getUserCardId());

        assertThat(result.userCardId()).isEqualTo(card.getUserCardId());
        assertThat(result.effectiveContent()).isEqualTo(json("{\"front\":\"base\",\"back\":\"override\"}"));
    }

    @Test
    void getMissingFieldSummary_clampsSampleLimitAndKeepsRequestedOrder() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        UserCardEntity first = userCard(userId, deckId, null, true, false, "first", null, textContent("front", "A"));
        first.setUserCardId(firstId);
        UserCardEntity second = userCard(userId, deckId, null, true, false, "second", null, textContent("front", "B"));
        second.setUserCardId(secondId);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));
        when(userCardRepository.countMissingField(userId, deckId, "front")).thenReturn(2L);
        when(userCardRepository.countMissingField(userId, deckId, "audio")).thenReturn(0L);
        when(userCardRepository.findMissingFieldCardIds(userId, deckId, "front", 20)).thenReturn(List.of(secondId, firstId));
        when(userCardRepository.findMissingFieldCardIds(userId, deckId, "audio", 20)).thenReturn(List.of());
        when(userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(userId, deckId, List.of(secondId, firstId)))
                .thenReturn(List.of(first, second));

        MissingFieldSummaryDTO result = cardService.getMissingFieldSummary(
                userId,
                deckId,
                new MissingFieldSummaryRequest(List.of(" front ", "", "audio", "front"), 99)
        );

        assertThat(result.sampleLimit()).isEqualTo(20);
        assertThat(result.fields()).hasSize(2);
        assertThat(result.fields().get(0).field()).isEqualTo("front");
        assertThat(result.fields().get(0).missingCount()).isEqualTo(2L);
        assertThat(result.fields().get(0).sampleCards()).extracting(UserCardDTO::userCardId).containsExactly(secondId, firstId);
        assertThat(result.fields().get(1).field()).isEqualTo("audio");
        assertThat(result.fields().get(1).sampleCards()).isEmpty();
    }

    @Test
    void getMissingFieldCards_usesFieldLimitsAndDeduplicatesResults() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();

        UserCardEntity first = userCard(userId, deckId, null, true, false, "first", null, textContent("front", "A"));
        first.setUserCardId(firstId);
        UserCardEntity second = userCard(userId, deckId, null, true, false, "second", null, textContent("front", "B"));
        second.setUserCardId(secondId);
        UserCardEntity third = userCard(userId, deckId, null, true, false, "third", null, textContent("front", "C"));
        third.setUserCardId(thirdId);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));
        when(userCardRepository.findMissingFieldCardIds(userId, deckId, "front", 2)).thenReturn(List.of(firstId, secondId));
        when(userCardRepository.findMissingFieldCardIds(userId, deckId, "audio", 1)).thenReturn(List.of(secondId, thirdId));
        when(userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(userId, deckId, List.of(firstId, secondId, thirdId)))
                .thenReturn(List.of(third, first, second));

        List<UserCardDTO> result = cardService.getMissingFieldCards(
                userId,
                deckId,
                new MissingFieldCardsRequest(
                        null,
                        null,
                        List.of(
                                new MissingFieldCardsRequest.FieldLimit("front", 2),
                                new MissingFieldCardsRequest.FieldLimit("audio", 1)
                        )
                )
        );

        assertThat(result).extracting(UserCardDTO::userCardId).containsExactly(firstId, secondId, thirdId);
    }

    @Test
    void getMissingFieldCardsInternal_usesDeckOwnerForLookup() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();

        UserCardEntity first = userCard(userId, deckId, null, true, false, "first", null, textContent("front", "A"));
        first.setUserCardId(firstId);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));
        when(userCardRepository.findMissingFieldCardIds(userId, deckId, "front", 1)).thenReturn(List.of(firstId));
        when(userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(userId, deckId, List.of(firstId)))
                .thenReturn(List.of(first));

        List<UserCardDTO> result = cardService.getMissingFieldCardsInternal(
                deckId,
                new MissingFieldCardsRequest(List.of("front"), 1, null)
        );

        assertThat(result).singleElement().satisfies(dto -> assertThat(dto.userCardId()).isEqualTo(firstId));
    }

    @Test
    void getMissingFieldCards_rejectsBlankRequest() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));

        assertThatThrownBy(() -> cardService.getMissingFieldCards(userId, deckId, new MissingFieldCardsRequest(List.of(" "), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fields are required");
    }

    @Test
    void getDuplicateGroups_returnsExactGroupsByDefault() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID cardA = UUID.randomUUID();
        UUID cardB = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));
        when(userCardRepository.findDuplicateGroups(eq(userId), eq(deckId), any(String[].class), eq(10)))
                .thenReturn(List.of(duplicateGroupProjection(2, cardA, cardB)));

        UserCardEntity entityA = userCard(userId, deckId, null, true, false, null, null, textContent("front", "Hello"));
        entityA.setUserCardId(cardA);
        entityA.setCreatedAt(now.minusSeconds(5));
        UserCardEntity entityB = userCard(userId, deckId, null, true, false, null, null, textContent("front", "Hello!"));
        entityB.setUserCardId(cardB);
        entityB.setCreatedAt(now.minusSeconds(3));
        when(userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(userId, deckId, List.of(cardA, cardB)))
                .thenReturn(List.of(entityA, entityB));

        List<DuplicateGroupDTO> result = cardService.getDuplicateGroups(
                userId,
                deckId,
                new DuplicateSearchRequest(List.of("front"), 10, 5, null, null)
        );

        assertThat(result).singleElement().satisfies(group -> {
            assertThat(group.matchType()).isEqualTo("exact");
            assertThat(group.confidence()).isEqualTo(1.0d);
            assertThat(group.size()).isEqualTo(2);
        });
    }

    @Test
    void getDuplicateGroups_addsSemanticGroupsWhenEnabled() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");
        UUID cardA = UUID.randomUUID();
        UUID cardB = UUID.randomUUID();

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));
        when(userCardRepository.findDuplicateGroups(eq(userId), eq(deckId), any(String[].class), eq(10))).thenReturn(List.of());
        when(userCardRepository.findSemanticDuplicateCandidates(eq(userId), eq(deckId), any(String[].class), anyInt()))
                .thenReturn(List.of(
                        semanticProjection(cardA, now.minusSeconds(10), new String[]{"apple", "red fruit"}),
                        semanticProjection(cardB, now.minusSeconds(5), new String[]{"apple", "green fruit"})
                ));

        UserCardEntity entityA = userCard(userId, deckId, null, true, false, null, null, textContent("front", "apple"));
        entityA.setUserCardId(cardA);
        entityA.setCreatedAt(now.minusSeconds(10));
        UserCardEntity entityB = userCard(userId, deckId, null, true, false, null, null, textContent("front", "apple"));
        entityB.setUserCardId(cardB);
        entityB.setCreatedAt(now.minusSeconds(5));
        when(userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(eq(userId), eq(deckId), anyList()))
                .thenReturn(List.of(entityA, entityB));

        List<DuplicateGroupDTO> result = cardService.getDuplicateGroups(
                userId,
                deckId,
                new DuplicateSearchRequest(List.of("front", "back"), 10, 5, true, 0.92d)
        );

        assertThat(result).singleElement().satisfies(group -> {
            assertThat(group.matchType()).isEqualTo("semantic");
            assertThat(group.size()).isEqualTo(2);
            assertThat(group.confidence()).isGreaterThan(0.9d);
        });
    }

    @Test
    void resolveDuplicateGroups_marksSecondaryCardsDeletedLocally() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));
        when(userCardRepository.findDuplicateResolutionCandidates(eq(userId), eq(deckId), any(String[].class), any(String[].class)))
                .thenReturn(List.of(
                        duplicateResolutionProjection(keepId, null, 1),
                        duplicateResolutionProjection(deleteId, null, 2)
                ));
        when(userCardRepository.markDeletedByIds(eq(userId), eq(deckId), eq(List.of(deleteId)), any())).thenReturn(1);

        DuplicateResolveResultDTO result = cardService.resolveDuplicateGroups(
                userId,
                deckId,
                new DuplicateResolveRequest(List.of("front"), "local", null)
        );

        assertThat(result.groupsProcessed()).isEqualTo(1);
        assertThat(result.deletedCards()).isEqualTo(1);
        assertThat(result.keptCards()).isEqualTo(1);
        assertThat(result.globalApplied()).isFalse();
    }

    @Test
    void resolveDuplicateGroups_deactivatesPublicCardsGloballyInsideSession() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID keepCardId = UUID.randomUUID();
        UUID deleteCardId = UUID.randomUUID();
        UUID keepPublicCardId = UUID.randomUUID();
        UUID deletePublicCardId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        deck.setTemplateVersion(1);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, userId, templateId, 1, true);
        DeckUpdateSessionEntity session = new DeckUpdateSessionEntity(publicDeckId, operationId, userId, 2, now.minusSeconds(60), now.minusSeconds(30));
        FieldTemplateEntity scoreField = fieldTemplate(templateId, 1, "front", CardFieldType.text, true);
        UserCardEntity deleteCard = userCard(userId, deckId, deletePublicCardId, false, false, null, null, null);
        deleteCard.setUserCardId(deleteCardId);
        PublicCardEntity keepTarget = publicCard(publicDeckId, 2, keepPublicCardId, textContent("front", "keep"), new String[]{"keep"}, true, "keep");
        PublicCardEntity deleteTarget = publicCard(publicDeckId, 2, deletePublicCardId, textContent("front", "delete"), new String[]{"delete"}, true, "delete");

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 1)).thenReturn(List.of(scoreField));
        when(userCardRepository.findDuplicateResolutionCandidates(eq(userId), eq(deckId), any(String[].class), any(String[].class)))
                .thenReturn(List.of(
                        duplicateResolutionProjection(keepCardId, keepPublicCardId, 1),
                        duplicateResolutionProjection(deleteCardId, deletePublicCardId, 2)
                ));
        when(userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(userId, deckId, List.of(deleteCardId)))
                .thenReturn(List.of(deleteCard));
        when(deckUpdateSessionRepository.findByDeckIdAndOperationId(publicDeckId, operationId)).thenReturn(Optional.of(session));
        when(publicDeckRepository.findByDeckIdAndVersion(publicDeckId, 2)).thenReturn(Optional.of(latestDeck));
        when(deckUpdateSessionRepository.save(any(DeckUpdateSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 2)).thenReturn(List.of(keepTarget, deleteTarget));
        when(publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(any())).thenReturn(List.of());
        when(publicCardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userCardRepository.markDeletedByIds(eq(userId), eq(deckId), eq(List.of(deleteCardId)), any())).thenReturn(1);

        DuplicateResolveResultDTO result = cardService.resolveDuplicateGroups(
                userId,
                deckId,
                new DuplicateResolveRequest(List.of("front"), "global", operationId)
        );

        assertThat(result.deletedCards()).isEqualTo(1);
        assertThat(result.globalApplied()).isTrue();
        assertThat(deleteTarget.isActive()).isFalse();
        verify(publicCardRepository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    void getPublicCards_usesExplicitVersionAndRejectsPrivateDeck() {
        UUID deckId = UUID.randomUUID();
        PublicDeckEntity publicDeck = publicDeck(deckId, 3, UUID.randomUUID(), UUID.randomUUID(), 1, true);
        PublicCardEntity card = publicCard(deckId, 3, UUID.randomUUID(), textContent("front", "Q"), new String[]{"tag"}, true, "chk");

        when(publicDeckRepository.findByDeckIdAndVersion(deckId, 3)).thenReturn(Optional.of(publicDeck));
        when(publicCardRepository.findByDeckIdAndDeckVersionAndActiveTrueOrderByOrderIndex(eq(deckId), eq(3), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card), PageRequest.of(0, 10), 1));

        Page<PublicCardDTO> result = cardService.getPublicCards(deckId, 3, 1, 10);

        assertThat(result.getContent()).singleElement().satisfies(dto -> assertThat(dto.cardId()).isEqualTo(card.getCardId()));

        PublicDeckEntity privateDeck = publicDeck(deckId, 3, UUID.randomUUID(), UUID.randomUUID(), 1, false);
        when(publicDeckRepository.findByDeckIdAndVersion(deckId, 4)).thenReturn(Optional.of(privateDeck));

        assertThatThrownBy(() -> cardService.getPublicCards(deckId, 4, 1, 10))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Deck is not public");
    }

    @Test
    void getPublicCardById_throwsWhenDeckIsPrivate() {
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        PublicCardEntity card = publicCard(deckId, 2, cardId, textContent("front", "Q"), null, true, "chk");
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(cardId)).thenReturn(Optional.of(card));
        when(publicDeckRepository.findByDeckIdAndVersion(deckId, 2))
                .thenReturn(Optional.of(publicDeck(deckId, 2, UUID.randomUUID(), UUID.randomUUID(), 1, false)));

        assertThatThrownBy(() -> cardService.getPublicCardById(cardId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Deck is not public");
    }

    @Test
    void getFieldTemplatesForPublicDeck_returnsMappedFields() {
        UUID deckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        PublicDeckEntity publicDeck = publicDeck(deckId, 2, UUID.randomUUID(), templateId, 3, true);
        FieldTemplateEntity field = fieldTemplate(templateId, 3, "front", CardFieldType.markdown, true);

        when(publicDeckRepository.findLatestByDeckId(deckId)).thenReturn(Optional.of(publicDeck));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 3)).thenReturn(List.of(field));

        List<FieldTemplateDTO> result = cardService.getFieldTemplatesForPublicDeck(deckId, null);

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.name()).isEqualTo("front");
            assertThat(dto.fieldType()).isEqualTo(CardFieldType.markdown);
        });
    }

    @Test
    void addNewCardsToDeckBatch_forLocalDeckUsesContentOverrideAsEffectiveContent() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, null);
        ObjectNode override = textContent("front", "Override");

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<UserCardDTO> result = cardService.addNewCardsToDeckBatch(
                userId,
                deckId,
                List.of(new CreateCardRequest(null, 1, new String[]{"tag"}, "note", override, null)),
                null
        );

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.isCustom()).isTrue();
            assertThat(dto.publicCardId()).isNull();
            assertThat(dto.effectiveContent()).isEqualTo(override);
        });
    }

    @Test
    void addNewCardsToDeckBatch_forSubscriberCreatesCustomCardsOnly() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, UUID.randomUUID(), UUID.randomUUID(), 1, true);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<UserCardDTO> result = cardService.addNewCardsToDeckBatch(
                userId,
                deckId,
                List.of(new CreateCardRequest(textContent("front", "Q"), 1, new String[]{"tag"}, "note", null, null)),
                null
        );

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.isCustom()).isTrue();
            assertThat(dto.publicCardId()).isNull();
        });
    }

    @Test
    void addNewCardsToDeckBatch_forAuthorCreatesNewPublicDeckVersion() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 1, userId, templateId, 1, true);
        PublicCardEntity existingPublicCard = publicCard(publicDeckId, 1, UUID.randomUUID(), textContent("front", "Old"), new String[]{"old"}, true, null);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(publicDeckRepository.save(any(PublicDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 1)).thenReturn(List.of(existingPublicCard));
        when(publicCardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<UserCardDTO> result = cardService.addNewCardsToDeckBatch(
                userId,
                deckId,
                List.of(new CreateCardRequest(textContent("front", "New"), 2, new String[]{"new"}, "note", null, null)),
                null
        );

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.isCustom()).isFalse();
            assertThat(dto.publicCardId()).isNotNull();
        });
        assertThat(deck.getCurrentVersion()).isEqualTo(2);
        verify(publicCardRepository, times(2)).saveAll(anyList());
    }

    @Test
    void addNewCardsToDeckBatch_reusesExistingOperationSessionAndStartsOrderIndexFromZeroWhenDeckEmpty() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        deck.setCurrentVersion(3);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 3, userId, UUID.randomUUID(), 1, true);
        DeckUpdateSessionEntity session = new DeckUpdateSessionEntity(publicDeckId, operationId, userId, 3,
                Instant.parse("2026-04-07T11:59:00Z"), Instant.parse("2026-04-07T11:59:30Z"));

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(deckUpdateSessionRepository.findByDeckIdAndOperationId(publicDeckId, operationId)).thenReturn(Optional.of(session));
        when(publicDeckRepository.findByDeckIdAndVersion(publicDeckId, 3)).thenReturn(Optional.of(latestDeck));
        when(publicCardRepository.findMaxOrderIndex(publicDeckId, 3)).thenReturn(null);
        when(publicDeckRepository.save(any(PublicDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deckUpdateSessionRepository.save(any(DeckUpdateSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicCardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<UserCardDTO> result = cardService.addNewCardsToDeckBatch(
                userId,
                deckId,
                List.of(new CreateCardRequest(textContent("front", "First"), 99, new String[]{"tag"}, "note", null, null)),
                operationId
        );

        ArgumentCaptor<List<PublicCardEntity>> publicCardsCaptor = ArgumentCaptor.forClass(List.class);
        verify(publicCardRepository).saveAll(publicCardsCaptor.capture());
        assertThat(publicCardsCaptor.getValue()).singleElement().satisfies(card ->
                assertThat(card.getOrderIndex()).isEqualTo(1)
        );
        assertThat(result).singleElement().satisfies(dto -> assertThat(dto.publicCardId()).isNotNull());
    }

    @Test
    void addNewCardsToDeckBatch_rejectsTooManyTags() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));

        assertThatThrownBy(() -> cardService.addNewCardsToDeckBatch(
                userId,
                deckId,
                List.of(new CreateCardRequest(textContent("front", "Q"), 1, new String[]{"a", "b", "c", "d"}, null, null, null)),
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Too many tags");
    }

    @Test
    void addNewCardsToDeckBatch_rejectsTooLongTag() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId, null)));

        assertThatThrownBy(() -> cardService.addNewCardsToDeckBatch(
                userId,
                deckId,
                List.of(new CreateCardRequest(textContent("front", "Q"), 1, new String[]{"x".repeat(26)}, null, null, null)),
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tag is too long");
    }

    @Test
    void updateUserCard_localClearsTagOverrideWhenItMatchesPublicTags() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        UserCardEntity card = userCard(userId, deckId, publicCardId, false, false, "old", new String[]{"old"}, textContent("front", "old"));
        card.setUserCardId(userCardId);
        PublicCardEntity publicCard = publicCard(UUID.randomUUID(), 2, publicCardId, textContent("front", "base"), new String[]{"public"}, true, "chk");

        when(userCardRepository.findById(userCardId)).thenReturn(Optional.of(card));
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(publicCardId)).thenReturn(Optional.of(publicCard));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserCardDTO result = cardService.updateUserCard(
                userId,
                deckId,
                userCardId,
                new UserCardDTO(userCardId, publicCardId, false, true, "updated", new String[]{"public"}, textContent("back", "override")),
                false,
                null
        );

        assertThat(result.isDeleted()).isTrue();
        assertThat(result.tags()).containsExactly("public");
        assertThat(card.getTags()).isNull();
        assertThat(card.getContentOverride()).isEqualTo(textContent("back", "override"));
    }

    @Test
    void updateUserCard_globallyUpdatesTargetPublicCardInsideSession() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, userId, UUID.randomUUID(), 1, true);
        UserCardEntity card = userCard(userId, deckId, publicCardId, false, false, "old", new String[]{"old"}, textContent("front", "old"));
        DeckUpdateSessionEntity session = new DeckUpdateSessionEntity(publicDeckId, operationId, userId, 2, now.minusSeconds(60), now.minusSeconds(30));
        PublicCardEntity linkedPublicCard = publicCard(publicDeckId, 1, publicCardId, textContent("front", "old"), new String[]{"old"}, true, "checksum");
        PublicCardEntity targetCard = publicCard(publicDeckId, 2, publicCardId, textContent("front", "old"), new String[]{"old"}, true, "checksum");

        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(publicCardId)).thenReturn(Optional.of(linkedPublicCard), Optional.of(targetCard));
        when(deckUpdateSessionRepository.findByDeckIdAndOperationId(publicDeckId, operationId)).thenReturn(Optional.of(session));
        when(publicDeckRepository.findByDeckIdAndVersion(publicDeckId, 2)).thenReturn(Optional.of(latestDeck));
        when(publicDeckRepository.save(any(PublicDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deckUpdateSessionRepository.save(any(DeckUpdateSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 2)).thenReturn(List.of(targetCard));
        when(publicCardRepository.save(any(PublicCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserCardDTO result = cardService.updateUserCard(
                userId,
                deckId,
                card.getUserCardId(),
                new UserCardDTO(card.getUserCardId(), publicCardId, false, false, "updated", new String[]{"new"}, textContent("front", "new")),
                true,
                operationId
        );

        assertThat(result.personalNote()).isEqualTo("updated");
        assertThat(result.tags()).containsExactly("new");
        assertThat(card.getContentOverride()).isNull();
        assertThat(card.getTags()).isNull();
        assertThat(targetCard.getContent()).isEqualTo(textContent("front", "new"));
        assertThat(targetCard.getTags()).containsExactly("new");
    }

    @Test
    void updateUserCard_globallyFallsBackToChecksumMatchWhenCardIdChanges() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID stalePublicCardId = UUID.randomUUID();
        UUID latestPublicCardId = UUID.randomUUID();

        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, userId, UUID.randomUUID(), 1, true);
        UserCardEntity card = userCard(userId, deckId, stalePublicCardId, false, false, "old", new String[]{"old"}, textContent("front", "old"));
        PublicCardEntity linkedPublicCard = publicCard(publicDeckId, 1, stalePublicCardId, json("{\"back\":\"A\",\"front\":\"Q\"}"), new String[]{"old"}, true, "   ");
        PublicCardEntity latestSameChecksumCard = publicCard(publicDeckId, 2, latestPublicCardId, json("{\"front\":\"Q\",\"back\":\"A\"}"), new String[]{"old"}, true, null);

        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(stalePublicCardId))
                .thenReturn(Optional.of(linkedPublicCard));
        when(publicDeckRepository.save(any(PublicDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 2)).thenReturn(List.of(latestSameChecksumCard));
        when(publicCardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserCardDTO result = cardService.updateUserCard(
                userId,
                deckId,
                card.getUserCardId(),
                new UserCardDTO(card.getUserCardId(), stalePublicCardId, false, false, "updated", new String[]{"fresh"}, json("{\"front\":\"Updated\",\"back\":\"A\"}")),
                true,
                null
        );

        assertThat(result.personalNote()).isEqualTo("updated");
        assertThat(deck.getCurrentVersion()).isEqualTo(3);
        verify(publicCardRepository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    void updateUserCard_globallyRejectsNonUniqueChecksumMatch() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID stalePublicCardId = UUID.randomUUID();

        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, userId, UUID.randomUUID(), 1, true);
        UserCardEntity card = userCard(userId, deckId, stalePublicCardId, false, false, "old", null, textContent("front", "old"));
        PublicCardEntity linkedPublicCard = publicCard(publicDeckId, 1, stalePublicCardId, textContent("front", "same"), null, true, null);
        PublicCardEntity latestA = publicCard(publicDeckId, 2, UUID.randomUUID(), textContent("front", "same"), null, true, null);
        PublicCardEntity latestB = publicCard(publicDeckId, 2, UUID.randomUUID(), textContent("front", "same"), null, true, null);

        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(stalePublicCardId)).thenReturn(Optional.of(linkedPublicCard));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 2)).thenReturn(List.of(latestA, latestB));

        assertThatThrownBy(() -> cardService.updateUserCard(
                userId,
                deckId,
                card.getUserCardId(),
                new UserCardDTO(card.getUserCardId(), stalePublicCardId, false, false, "updated", null, textContent("front", "updated")),
                true,
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Public card checksum is not unique in latest version");
    }

    @Test
    void deleteUserCard_marksOnlyLocalCardDeletedWhenGlobalDeleteDisabled() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, null);
        UserCardEntity card = userCard(userId, deckId, null, true, false, null, null, textContent("front", "Q"));

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cardService.deleteUserCard(userId, deckId, card.getUserCardId());

        assertThat(card.isDeleted()).isTrue();
        verify(userCardRepository).save(card);
    }

    @Test
    void deleteUserCard_overloadWithBooleanFalseDeletesLocally() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, null);
        UserCardEntity card = userCard(userId, deckId, null, true, false, null, null, textContent("front", "Q"));

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cardService.deleteUserCard(userId, deckId, card.getUserCardId(), false);

        assertThat(card.isDeleted()).isTrue();
        verify(userCardRepository).save(card);
    }

    @Test
    void deleteUserCard_rejectsGlobalDeleteForCustomCard() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, UUID.randomUUID());
        UserCardEntity card = userCard(userId, deckId, null, true, false, null, null, textContent("front", "Q"));

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> cardService.deleteUserCard(userId, deckId, card.getUserCardId(), true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Custom card cannot be deleted globally");
    }

    @Test
    void deleteUserCard_globallyDeactivatesLinkedPublicCardInsideSession() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        UserCardEntity card = userCard(userId, deckId, publicCardId, false, false, null, null, null);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, userId, UUID.randomUUID(), 1, true);
        DeckUpdateSessionEntity session = new DeckUpdateSessionEntity(publicDeckId, operationId, userId, 2, now.minusSeconds(60), now.minusSeconds(30));
        PublicCardEntity targetCard = publicCard(publicDeckId, 2, publicCardId, textContent("front", "Q"), new String[]{"tag"}, true, "chk");

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(deckUpdateSessionRepository.findByDeckIdAndOperationId(publicDeckId, operationId)).thenReturn(Optional.of(session));
        when(publicDeckRepository.findByDeckIdAndVersion(publicDeckId, 2)).thenReturn(Optional.of(latestDeck));
        when(deckUpdateSessionRepository.save(any(DeckUpdateSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 2)).thenReturn(List.of(targetCard));
        when(publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(any())).thenReturn(List.of());
        when(publicCardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cardService.deleteUserCard(userId, deckId, card.getUserCardId(), true, operationId);

        assertThat(card.isDeleted()).isTrue();
        assertThat(targetCard.isActive()).isFalse();
        verify(publicCardRepository).saveAll(anyList());
        verify(userCardRepository).save(card);
    }

    @Test
    void deleteUserCard_globallyFallsBackToChecksumMatchWhenCardIdChanges() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID stalePublicCardId = UUID.randomUUID();

        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId);
        UserCardEntity card = userCard(userId, deckId, stalePublicCardId, false, false, null, null, null);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, userId, UUID.randomUUID(), 1, true);
        PublicCardEntity targetCard = publicCard(publicDeckId, 3, UUID.randomUUID(), json("{\"b\":\"A\",\"a\":\"Q\"}"), new String[]{"tag"}, true, null);
        PublicCardEntity linkedCard = publicCard(publicDeckId, 1, stalePublicCardId, json("{\"a\":\"Q\",\"b\":\"A\"}"), new String[]{"tag"}, true, " ");

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository.findById(card.getUserCardId())).thenReturn(Optional.of(card));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(publicDeckRepository.save(any(PublicDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 2)).thenReturn(List.of(targetCard));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 3)).thenReturn(List.of(targetCard));
        when(publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(any())).thenReturn(List.of(linkedCard));
        when(publicCardRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userCardRepository.save(any(UserCardEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cardService.deleteUserCard(userId, deckId, card.getUserCardId(), true, null);

        assertThat(card.isDeleted()).isTrue();
        assertThat(targetCard.isActive()).isFalse();
        verify(publicCardRepository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    void getPublicCardById_throwsWhenDeckVersionIsMissing() {
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        PublicCardEntity card = publicCard(deckId, 2, cardId, textContent("front", "Q"), null, true, "chk");
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(cardId)).thenReturn(Optional.of(card));
        when(publicDeckRepository.findByDeckIdAndVersion(deckId, 2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardService.getPublicCardById(cardId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Public deck not found for card");
    }

    @Test
    void getFieldTemplatesForPublicDeck_returnsEmptyWhenDeckHasNoTemplate() {
        UUID deckId = UUID.randomUUID();
        PublicDeckEntity publicDeck = publicDeck(deckId, 2, UUID.randomUUID(), null, null, true);
        when(publicDeckRepository.findLatestByDeckId(deckId)).thenReturn(Optional.of(publicDeck));

        List<FieldTemplateDTO> result = cardService.getFieldTemplatesForPublicDeck(deckId, null);

        assertThat(result).isEmpty();
        verifyNoInteractions(fieldTemplateRepository);
    }

    @Test
    void computeChecksum_isStableForDifferentJsonFieldOrder() throws Exception {
        Method computeChecksum = CardService.class.getDeclaredMethod("computeChecksum", com.fasterxml.jackson.databind.JsonNode.class);
        computeChecksum.setAccessible(true);

        String left = (String) computeChecksum.invoke(cardService, json("{\"front\":\"Q\",\"back\":{\"hint\":\"A\",\"extra\":1}}"));
        String right = (String) computeChecksum.invoke(cardService, json("{\"back\":{\"extra\":1,\"hint\":\"A\"},\"front\":\"Q\"}"));

        assertThat(left).isEqualTo(right);
    }

    private UserDeckEntity userDeck(UUID deckId, UUID userId, UUID publicDeckId) {
        UserDeckEntity deck = new UserDeckEntity(
                userId,
                publicDeckId,
                1,
                1,
                1,
                1,
                true,
                SrAlgorithm.fsrs_v6.name(),
                null,
                "Deck",
                "Desc",
                Instant.parse("2026-04-07T12:00:00Z"),
                null,
                false
        );
        deck.setUserDeckId(deckId);
        return deck;
    }

    private UserCardEntity userCard(UUID userId,
                                    UUID deckId,
                                    UUID publicCardId,
                                    boolean custom,
                                    boolean deleted,
                                    String note,
                                    String[] tags,
                                    com.fasterxml.jackson.databind.JsonNode contentOverride) {
        UserCardEntity card = new UserCardEntity(
                userId,
                deckId,
                publicCardId,
                custom,
                deleted,
                note,
                tags,
                contentOverride,
                Instant.parse("2026-04-07T12:00:00Z"),
                null
        );
        card.setUserCardId(UUID.randomUUID());
        return card;
    }

    private PublicDeckEntity publicDeck(UUID deckId,
                                        Integer version,
                                        UUID authorId,
                                        UUID templateId,
                                        Integer templateVersion,
                                        boolean isPublic) {
        return new PublicDeckEntity(
                deckId,
                version,
                authorId,
                "Deck",
                "Desc",
                null,
                templateId,
                templateVersion,
                isPublic,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.parse("2026-04-07T12:00:00Z"),
                null,
                null,
                null
        );
    }

    private PublicCardEntity publicCard(UUID deckId,
                                        Integer deckVersion,
                                        UUID cardId,
                                        com.fasterxml.jackson.databind.JsonNode content,
                                        String[] tags,
                                        boolean active,
                                        String checksum) {
        return new PublicCardEntity(
                deckId,
                deckVersion,
                null,
                cardId,
                content,
                1,
                tags,
                Instant.parse("2026-04-07T12:00:00Z"),
                null,
                active,
                checksum
        );
    }

    private FieldTemplateEntity fieldTemplate(UUID templateId,
                                              Integer templateVersion,
                                              String name,
                                              CardFieldType fieldType,
                                              boolean onFront) {
        return new FieldTemplateEntity(
                UUID.randomUUID(),
                templateId,
                templateVersion,
                name,
                Character.toUpperCase(name.charAt(0)) + name.substring(1),
                fieldType,
                true,
                onFront,
                onFront ? 0 : 1,
                null,
                null
        );
    }

    private UserCardRepository.DuplicateGroupProjection duplicateGroupProjection(int cnt, UUID... ids) {
        return new UserCardRepository.DuplicateGroupProjection() {
            @Override
            public UUID[] getCardIds() {
                return ids;
            }

            @Override
            public int getCnt() {
                return cnt;
            }
        };
    }

    private UserCardRepository.SemanticCandidateProjection semanticProjection(UUID cardId, Instant createdAt, String[] values) {
        return new UserCardRepository.SemanticCandidateProjection() {
            @Override
            public UUID getUserCardId() {
                return cardId;
            }

            @Override
            public Instant getCreatedAt() {
                return createdAt;
            }

            @Override
            public String[] getValues() {
                return values;
            }
        };
    }

    private UserCardRepository.DuplicateResolutionProjection duplicateResolutionProjection(UUID userCardId,
                                                                                           UUID publicCardId,
                                                                                           int rn) {
        return new UserCardRepository.DuplicateResolutionProjection() {
            @Override
            public UUID getUserCardId() {
                return userCardId;
            }

            @Override
            public UUID getPublicCardId() {
                return publicCardId;
            }

            @Override
            public int getRn() {
                return rn;
            }
        };
    }

    private ObjectNode textContent(String key, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) objectMapper.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
