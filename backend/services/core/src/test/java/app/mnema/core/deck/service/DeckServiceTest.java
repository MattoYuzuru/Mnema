package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.DeckSizeDTO;
import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import app.mnema.core.media.client.MediaResolved;
import app.mnema.core.media.service.MediaResolveCache;
import app.mnema.core.security.ContentAdminAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Mock
    CardTemplateRepository cardTemplateRepository;

    @Mock
    MediaResolveCache mediaResolveCache;

    @Mock
    ContentAdminAccessService contentAdminAccessService;

    @InjectMocks
    DeckService deckService;

    @Test
    void getPublicDecksByPage_delegatesToRepositoryAndResolvesIconUrls() {
        UUID publicDeckId = UUID.randomUUID();
        UUID iconId = UUID.randomUUID();
        PublicDeckEntity entity = publicDeck(publicDeckId, 1, UUID.randomUUID(), iconId, 2, true, true);
        Page<PublicDeckEntity> repoPage = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);

        when(publicDeckRepository.findLatestPublicVisibleDecks(any(Pageable.class))).thenReturn(repoPage);
        when(mediaResolveCache.resolve(List.of(iconId))).thenReturn(Map.of(iconId, media(iconId, "https://cdn/icon.png")));

        Page<PublicDeckDTO> result = deckService.getPublicDecksByPage(1, 10);

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.deckId()).isEqualTo(publicDeckId);
            assertThat(dto.iconUrl()).isEqualTo("https://cdn/icon.png");
        });
    }

    @Test
    void getPublicDeck_returnsSpecifiedVersionAndResolvesSingleIcon() {
        UUID deckId = UUID.randomUUID();
        UUID iconId = UUID.randomUUID();
        PublicDeckEntity entity = publicDeck(deckId, 4, UUID.randomUUID(), iconId, 2, true, true);

        when(publicDeckRepository.findByDeckIdAndVersion(deckId, 4)).thenReturn(Optional.of(entity));
        when(mediaResolveCache.resolve(List.of(iconId))).thenReturn(Map.of(iconId, media(iconId, "https://cdn/v4.png")));

        PublicDeckDTO result = deckService.getPublicDeck(deckId, 4);

        assertThat(result.version()).isEqualTo(4);
        assertThat(result.iconUrl()).isEqualTo("https://cdn/v4.png");
    }

    @Test
    void getUserDecksByPage_delegatesToRepositoryAndMaps() {
        UUID userId = UUID.randomUUID();
        UserDeckEntity entity = userDeck(UUID.randomUUID(), userId, UUID.randomUUID(), 1, 1, 1, false);
        Page<UserDeckEntity> repoPage = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);

        when(userDeckRepository.findByUserIdAndArchivedFalse(eq(userId), any(Pageable.class))).thenReturn(repoPage);

        Page<UserDeckDTO> result = deckService.getUserDecksByPage(userId, 1, 10);

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.userId()).isEqualTo(userId);
            assertThat(dto.displayName()).isEqualTo(entity.getDisplayName());
        });
    }

    @Test
    void getDeletedUserDecks_returnsArchivedDecks() {
        UUID userId = UUID.randomUUID();
        UserDeckEntity archived = userDeck(UUID.randomUUID(), userId, UUID.randomUUID(), 1, 1, 1, true);

        when(userDeckRepository.findByUserIdAndArchivedTrue(userId)).thenReturn(List.of(archived));

        assertThat(deckService.getDeletedUserDecks(userId)).singleElement().satisfies(dto -> {
            assertThat(dto.archived()).isTrue();
            assertThat(dto.userDeckId()).isEqualTo(archived.getUserDeckId());
        });
    }

    @Test
    void getUserDeckSize_checksOwnershipAndCountsCards() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, UUID.randomUUID(), 1, 1, 1, false);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository.countByUserDeckIdAndDeletedFalse(deckId)).thenReturn(12L);

        DeckSizeDTO result = deckService.getUserDeckSize(userId, deckId);

        assertThat(result.deckId()).isEqualTo(deckId);
        assertThat(result.cardsQty()).isEqualTo(12L);

        UUID foreignUser = UUID.randomUUID();
        assertThatThrownBy(() -> deckService.getUserDeckSize(foreignUser, deckId))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Access denied to deck " + deckId);
    }

    @Test
    void getUserDeckInternal_usesDeckOwnerForLookup() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, UUID.randomUUID(), 1, 1, 1, false);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));

        UserDeckDTO result = deckService.getUserDeckInternal(deckId);

        assertThat(result.userDeckId()).isEqualTo(deckId);
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    void getPublicDeckSize_usesLatestWhenVersionMissing() {
        UUID deckId = UUID.randomUUID();
        PublicDeckEntity latestDeck = publicDeck(deckId, 3, UUID.randomUUID(), null, 2, true, true);

        when(publicDeckRepository.findLatestByDeckId(deckId)).thenReturn(Optional.of(latestDeck));
        when(publicCardRepository.countByDeckIdAndDeckVersionAndActiveTrue(deckId, 3)).thenReturn(7L);

        DeckSizeDTO result = deckService.getPublicDeckSize(deckId, null);

        assertThat(result.deckId()).isEqualTo(deckId);
        assertThat(result.cardsQty()).isEqualTo(7L);
    }

    @Test
    void createNewDeck_usesResolvedLatestTemplateVersion() {
        UUID publicDeckId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();

        when(cardTemplateRepository.findById(templateId))
                .thenReturn(Optional.of(new CardTemplateEntity(
                        templateId,
                        userId,
                        "Template",
                        null,
                        true,
                        Instant.parse("2026-04-07T12:00:00Z"),
                        null,
                        null,
                        null,
                        null,
                        4
                )));
        when(publicDeckRepository.save(any(PublicDeckEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userDeckRepository.save(any(UserDeckEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserDeckDTO result = deckService.createNewDeck(userId, new PublicDeckDTO(
                null,
                null,
                null,
                "My deck",
                "Description",
                null,
                null,
                templateId,
                null,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                null,
                null,
                null,
                null
        ));

        assertThat(result.templateVersion()).isEqualTo(4);
        assertThat(result.subscribedTemplateVersion()).isEqualTo(4);
    }

    @Test
    void updateUserDeckMeta_retriesOnOptimisticLockAndUpdatesFields() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, UUID.randomUUID(), 1, 1, 1, false);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck), Optional.of(deck));
        when(userDeckRepository.save(any(UserDeckEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserDeckDTO result = deckService.updateUserDeckMeta(userId, deckId, new UserDeckDTO(
                deckId,
                userId,
                deck.getPublicDeckId(),
                1,
                1,
                1,
                1,
                false,
                "sm2",
                null,
                "Renamed",
                "Updated",
                deck.getCreatedAt(),
                null,
                true
        ));

        assertThat(result.displayName()).isEqualTo("Renamed");
        assertThat(result.displayDescription()).isEqualTo("Updated");
        assertThat(result.algorithmId()).isEqualTo("sm2");
        assertThat(result.archived()).isTrue();
        verify(userDeckRepository, times(2)).save(any(UserDeckEntity.class));
    }

    @Test
    void deleteUserDeck_marksDeckArchived() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, UUID.randomUUID(), 1, 1, 1, false);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        deckService.deleteUserDeck(userId, deckId);

        assertThat(deck.isArchived()).isTrue();
        verify(userDeckRepository).save(deck);
    }

    @Test
    void hardDeleteUserDeck_deletesPublicDeckWhenAuthorOwnsLastSubscription() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, publicDeckId, 1, 1, 1, true);
        PublicDeckEntity latestPublic = publicDeck(publicDeckId, 3, userId, null, 2, true, true);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestPublic));
        when(userDeckRepository.countByPublicDeckIdAndUserDeckIdNot(publicDeckId, deckId)).thenReturn(0L);

        deckService.hardDeleteUserDeck(userId, deckId);

        verify(userDeckRepository).delete(deck);
        verify(publicDeckRepository).deleteByDeckId(publicDeckId);
    }

    @Test
    void hardDeleteUserDeck_requiresArchivedState() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(deckId, userId, UUID.randomUUID(), 1, 1, 1, false);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));

        assertThatThrownBy(() -> deckService.hardDeleteUserDeck(userId, deckId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Deck must be archived before hard delete");
    }

    @Test
    void deletePublicDeck_requiresAuthorAndHidesDeck() {
        UUID userId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        PublicDeckEntity deck = publicDeck(publicDeckId, 2, userId, null, 1, true, true);

        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.save(any(PublicDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        deckService.deletePublicDeck(userId, publicDeckId);

        assertThat(deck.isListed()).isFalse();
        assertThat(deck.isPublicFlag()).isFalse();
        verify(publicDeckRepository).save(deck);
    }

    @Test
    void syncUserDeckToLatestVersion_updatesOnlyTemplateWhenAlreadyCurrent() {
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(userDeckId, userId, publicDeckId, 2, 2, 1, false);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, UUID.randomUUID(), null, 3, true, true);

        when(userDeckRepository.findById(userDeckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserDeckDTO result = deckService.syncUserDeckToLatestVersion(userId, userDeckId);

        assertThat(result.currentVersion()).isEqualTo(2);
        assertThat(result.templateVersion()).isEqualTo(3);
        verify(publicCardRepository, never()).findByDeckIdAndDeckVersion(any(), any());
    }

    @Test
    void syncUserDeckToLatestVersion_rewiresExistingCardsAndAddsMissingOnes() {
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID oldCardId = UUID.randomUUID();
        UUID rewiredCardId = UUID.randomUUID();
        UUID addedCardId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        UserDeckEntity deck = userDeck(userDeckId, userId, publicDeckId, 1, 1, 1, false);
        PublicDeckEntity latestDeck = publicDeck(publicDeckId, 2, UUID.randomUUID(), null, 2, true, true);
        PublicCardEntity latestRewired = publicCard(publicDeckId, 2, rewiredCardId, "a", new String[]{"public"}, true, 1, now);
        PublicCardEntity latestAdded = publicCard(publicDeckId, 2, addedCardId, "b", new String[]{"new"}, true, 2, now);
        PublicCardEntity inactive = publicCard(publicDeckId, 2, UUID.randomUUID(), "c", new String[]{"ignored"}, false, 3, now);
        PublicCardEntity oldPublic = publicCard(publicDeckId, 1, oldCardId, "a", new String[]{"public"}, true, 1, now.minusSeconds(60));

        UserCardEntity linked = new UserCardEntity(userId, userDeckId, oldCardId, false, false, null, null, null, now.minusSeconds(30), null);
        linked.setUserCardId(UUID.randomUUID());

        when(userDeckRepository.findById(userDeckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(latestDeck));
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 2)).thenReturn(List.of(latestRewired, latestAdded, inactive));
        when(userCardRepository.findByUserDeckId(userDeckId)).thenReturn(List.of(linked));
        when(publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(List.of(oldCardId))).thenReturn(List.of(oldPublic));
        when(userCardRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserDeckDTO result = deckService.syncUserDeckToLatestVersion(userId, userDeckId);

        assertThat(result.currentVersion()).isEqualTo(2);
        assertThat(result.templateVersion()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserCardEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userCardRepository, times(2)).saveAll(captor.capture());
        List<List<UserCardEntity>> invocations = captor.getAllValues();

        UserCardEntity rewired = invocations.get(0).getFirst();
        assertThat(rewired.getPublicCardId()).isEqualTo(rewiredCardId);
        assertThat(rewired.getTags()).containsExactly("public");

        UserCardEntity inserted = invocations.get(1).getFirst();
        assertThat(inserted.getPublicCardId()).isEqualTo(addedCardId);
        assertThat(inserted.isCustom()).isFalse();
    }

    @Test
    void syncUserDeckTemplate_updatesTemplateVersionFromCurrentPublicDeckVersion() {
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UserDeckEntity deck = userDeck(userDeckId, userId, publicDeckId, 3, 3, 1, false);
        PublicDeckEntity publicDeck = publicDeck(publicDeckId, 3, UUID.randomUUID(), null, 1, true, true);
        CardTemplateEntity template = new CardTemplateEntity(
                templateId,
                UUID.randomUUID(),
                "Template",
                null,
                true,
                Instant.parse("2026-04-07T12:00:00Z"),
                null,
                null,
                null,
                null,
                5
        );
        setTemplateId(publicDeck, templateId);

        when(userDeckRepository.findById(userDeckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.findByDeckIdAndVersion(publicDeckId, 3)).thenReturn(Optional.of(publicDeck));
        when(cardTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserDeckDTO result = deckService.syncUserDeckTemplate(userId, userDeckId);

        assertThat(result.templateVersion()).isEqualTo(5);
    }

    @Test
    void updatePublicDeckMeta_updatesFieldsAndValidatesTags() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        PublicDeckEntity deck = publicDeck(deckId, 2, userId, null, 1, true, true);

        when(publicDeckRepository.findLatestByDeckId(deckId)).thenReturn(Optional.of(deck));
        when(publicDeckRepository.save(any(PublicDeckEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PublicDeckDTO result = deckService.updatePublicDeckMeta(userId, deckId, null, new PublicDeckDTO(
                deckId,
                2,
                userId,
                "Updated",
                "Description",
                UUID.randomUUID(),
                null,
                deck.getTemplateId(),
                deck.getTemplateVersion(),
                false,
                false,
                LanguageTag.ru,
                new String[]{"one", "two"},
                deck.getCreatedAt(),
                null,
                null,
                null
        ));

        assertThat(result.name()).isEqualTo("Updated");
        assertThat(result.isPublic()).isFalse();
        assertThat(result.isListed()).isFalse();
        assertThat(result.language()).isEqualTo(LanguageTag.ru);

        assertThatThrownBy(() -> deckService.updatePublicDeckMeta(userId, deckId, null, new PublicDeckDTO(
                deckId,
                2,
                userId,
                "Updated",
                "Description",
                null,
                null,
                deck.getTemplateId(),
                deck.getTemplateVersion(),
                false,
                false,
                LanguageTag.ru,
                new String[]{"a", "b", "c", "d", "e", "f"},
                deck.getCreatedAt(),
                null,
                null,
                null
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void forkFromPublicDeck_createsSubscriptionAndCopiesOnlyActiveCards() {
        UUID userId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID activeCardId = UUID.randomUUID();

        when(userDeckRepository.findByUserIdAndPublicDeckId(userId, publicDeckId)).thenReturn(Optional.empty());

        PublicDeckEntity publicDeck = publicDeck(publicDeckId, 4, authorId, null, 3, true, true);
        when(publicDeckRepository.findLatestByDeckId(publicDeckId)).thenReturn(Optional.of(publicDeck));
        when(userDeckRepository.save(any(UserDeckEntity.class))).thenAnswer(invocation -> {
            UserDeckEntity saved = invocation.getArgument(0);
            saved.setUserDeckId(UUID.randomUUID());
            return saved;
        });
        when(publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, 4)).thenReturn(List.of(
                publicCard(publicDeckId, 4, activeCardId, "keep", null, true, 1, Instant.parse("2026-04-07T12:00:00Z")),
                publicCard(publicDeckId, 4, UUID.randomUUID(), "skip", null, false, 2, Instant.parse("2026-04-07T12:00:00Z"))
        ));
        when(userCardRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserDeckDTO result = deckService.forkFromPublicDeck(userId, publicDeckId);

        assertThat(result.publicDeckId()).isEqualTo(publicDeckId);
        assertThat(result.currentVersion()).isEqualTo(4);
        assertThat(result.templateVersion()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<app.mnema.core.deck.domain.entity.UserCardEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userCardRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(card -> assertThat(card.getPublicCardId()).isEqualTo(activeCardId));
    }

    private UserDeckEntity userDeck(UUID userDeckId,
                                    UUID userId,
                                    UUID publicDeckId,
                                    Integer subscribedVersion,
                                    Integer currentVersion,
                                    Integer templateVersion,
                                    boolean archived) {
        UserDeckEntity deck = new UserDeckEntity(
                userId,
                publicDeckId,
                subscribedVersion,
                currentVersion,
                templateVersion,
                templateVersion,
                true,
                SrAlgorithm.fsrs_v6.name(),
                null,
                "Deck " + userDeckId,
                "Description",
                Instant.parse("2026-04-07T12:00:00Z"),
                null,
                archived
        );
        deck.setUserDeckId(userDeckId);
        return deck;
    }

    private PublicDeckEntity publicDeck(UUID deckId,
                                        Integer version,
                                        UUID authorId,
                                        UUID iconMediaId,
                                        Integer templateVersion,
                                        boolean isPublic,
                                        boolean isListed) {
        return new PublicDeckEntity(
                deckId,
                version,
                authorId,
                "Deck " + deckId,
                "Description",
                iconMediaId,
                UUID.randomUUID(),
                templateVersion,
                isPublic,
                isListed,
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
                                        String checksum,
                                        String[] tags,
                                        boolean active,
                                        Integer orderIndex,
                                        Instant createdAt) {
        return new PublicCardEntity(
                deckId,
                deckVersion,
                null,
                cardId,
                null,
                orderIndex,
                tags,
                createdAt,
                null,
                active,
                checksum
        );
    }

    private MediaResolved media(UUID mediaId, String url) {
        return new MediaResolved(mediaId, "image", url, "image/png", 123L, null, 64, 64, Instant.now().plusSeconds(3600));
    }

    private void setTemplateId(PublicDeckEntity deck, UUID templateId) {
        try {
            var field = PublicDeckEntity.class.getDeclaredField("templateId");
            field.setAccessible(true);
            field.set(deck, templateId);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
