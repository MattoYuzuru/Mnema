package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.CardTemplateVersionEntity;
import app.mnema.core.deck.domain.entity.FieldTemplateEntity;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.type.CardFieldType;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.CardTemplateVersionRepository;
import app.mnema.core.deck.repository.FieldTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    UserDeckRepository userDeckRepository;

    @Mock
    UserCardRepository userCardRepository;

    @Mock
    PublicCardRepository publicCardRepository;

    @Mock
    CardTemplateRepository cardTemplateRepository;

    @Mock
    FieldTemplateRepository fieldTemplateRepository;

    @Mock
    CardTemplateVersionRepository cardTemplateVersionRepository;

    @InjectMocks
    SearchService searchService;

    @Test
    void searchUserDecks_throwsOnInvalidPageable() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> searchService.searchUserDecks(userId, "query", null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page and limit must be >= 1");
    }

    @Test
    void searchUserDecks_returnsEmptyWhenQueryAndTagsBlank() {
        UUID userId = UUID.randomUUID();

        Page<UserDeckDTO> result = searchService.searchUserDecks(userId, "  ", List.of(" "), 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(userDeckRepository);
    }

    @Test
    void searchUserDecks_usesTagsSearchWhenQueryMissing() {
        UUID userId = UUID.randomUUID();
        Page<UserDeckEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

        when(userDeckRepository.searchUserDecksByTags(eq(userId), anyString(), any(Pageable.class)))
                .thenReturn(page);

        searchService.searchUserDecks(userId, null, List.of(" verbs ", "verbs", "latin"), 1, 10);

        ArgumentCaptor<String> tagsCaptor = ArgumentCaptor.forClass(String.class);
        verify(userDeckRepository).searchUserDecksByTags(eq(userId), tagsCaptor.capture(), any(Pageable.class));
        assertThat(tagsCaptor.getValue()).isEqualTo("verbs,latin");
    }

    @Test
    void searchUserDecks_mapsRepositoryResults() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");
        JsonNode algorithmParams = json("{\"target\":0.85}");
        UserDeckEntity deck = new UserDeckEntity(
                userId,
                publicDeckId,
                3,
                4,
                7,
                6,
                true,
                "fsrs",
                algorithmParams,
                "Spanish",
                "Verbs",
                now,
                now.plusSeconds(60),
                false
        );
        deck.setUserDeckId(deckId);

        when(userDeckRepository.searchUserDecks(eq(userId), eq("spanish"), eq("verbs,latin"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(deck), PageRequest.of(0, 10), 1));

        Page<UserDeckDTO> result = searchService.searchUserDecks(
                userId,
                " spanish ",
                List.of("verbs", " latin ", "verbs"),
                1,
                10
        );

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.userDeckId()).isEqualTo(deckId);
            assertThat(dto.publicDeckId()).isEqualTo(publicDeckId);
            assertThat(dto.algorithmId()).isEqualTo("fsrs");
            assertThat(dto.algorithmParams()).isEqualTo(algorithmParams);
            assertThat(dto.displayName()).isEqualTo("Spanish");
            assertThat(dto.displayDescription()).isEqualTo("Verbs");
        });
    }

    @Test
    void searchUserCards_throwsWhenDeckNotFound() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> searchService.searchUserCards(userId, deckId, "query", null, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User deck not found: " + deckId);
    }

    @Test
    void searchUserCards_throwsWhenDeckNotOwned() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(UUID.randomUUID());
        deck.setUserDeckId(deckId);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));

        assertThatThrownBy(() -> searchService.searchUserCards(userId, deckId, "query", null, 1, 10))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Access denied to deck " + deckId);
    }

    @Test
    void searchUserCards_returnsEmptyWhenQueryAndTagsBlank() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId)));

        Page<UserCardDTO> result = searchService.searchUserCards(userId, deckId, " ", List.of(" "), 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(userCardRepository, publicCardRepository);
    }

    @Test
    void searchUserCards_usesTagsSearchAndBuildsEffectiveContentAndTags() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UUID linkedUserCardId = UUID.randomUUID();
        UUID customCardId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId)));

        UserCardEntity linkedCard = new UserCardEntity(
                userId,
                deckId,
                publicCardId,
                false,
                false,
                "linked note",
                null,
                json("{\"back\":\"override\",\"extra\":\"note\"}"),
                now,
                null
        );
        linkedCard.setUserCardId(linkedUserCardId);

        UserCardEntity customCard = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                false,
                "custom note",
                new String[]{"custom"},
                json("{\"front\":\"custom\"}"),
                now,
                null
        );
        customCard.setUserCardId(customCardId);

        when(userCardRepository.searchUserCardsByTags(eq(userId), eq(deckId), eq("verbs,shared"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(linkedCard, customCard), PageRequest.of(0, 10), 2));

        PublicCardEntity latestPublicCard = new PublicCardEntity(
                UUID.randomUUID(),
                5,
                null,
                publicCardId,
                json("{\"front\":\"base\",\"back\":\"original\"}"),
                1,
                new String[]{"public", "shared"},
                now,
                null,
                true,
                "checksum-new"
        );
        PublicCardEntity olderPublicCard = new PublicCardEntity(
                UUID.randomUUID(),
                4,
                null,
                publicCardId,
                json("{\"front\":\"old\",\"back\":\"stale\"}"),
                1,
                new String[]{"old"},
                now.minusSeconds(60),
                null,
                true,
                "checksum-old"
        );

        when(publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(anyCollection()))
                .thenReturn(List.of(latestPublicCard, olderPublicCard));

        Page<UserCardDTO> result = searchService.searchUserCards(
                userId,
                deckId,
                null,
                List.of(" verbs ", "", "shared", "verbs"),
                1,
                10
        );

        verify(userCardRepository).searchUserCardsByTags(eq(userId), eq(deckId), eq("verbs,shared"), any(Pageable.class));
        verify(publicCardRepository).findAllByCardIdInOrderByDeckVersionDesc(List.of(publicCardId));

        assertThat(result.getContent()).hasSize(2);

        UserCardDTO linkedResult = result.getContent().get(0);
        assertThat(linkedResult.userCardId()).isEqualTo(linkedUserCardId);
        assertThat(linkedResult.tags()).containsExactly("public", "shared");
        assertThat(linkedResult.effectiveContent()).isEqualTo(json("{\"front\":\"base\",\"back\":\"override\",\"extra\":\"note\"}"));

        UserCardDTO customResult = result.getContent().get(1);
        assertThat(customResult.userCardId()).isEqualTo(customCardId);
        assertThat(customResult.tags()).containsExactly("custom");
        assertThat(customResult.effectiveContent()).isEqualTo(json("{\"front\":\"custom\"}"));
    }

    @Test
    void searchUserCards_usesQuerySearchWhenPresentAndKeepsUnknownPublicCardFallbacks() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId)));

        UserCardEntity card = new UserCardEntity(
                userId,
                deckId,
                publicCardId,
                false,
                false,
                "note",
                null,
                json("\"override\""),
                now,
                null
        );
        card.setUserCardId(userCardId);

        when(userCardRepository.searchUserCards(eq(userId), eq(deckId), eq("query"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card), PageRequest.of(0, 10), 1));
        when(publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(List.of(publicCardId)))
                .thenReturn(List.of());

        Page<UserCardDTO> result = searchService.searchUserCards(userId, deckId, "query", null, 1, 10);

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.userCardId()).isEqualTo(userCardId);
            assertThat(dto.tags()).isNull();
            assertThat(dto.effectiveContent()).isEqualTo(json("\"override\""));
        });
    }

    @Test
    void searchUserCards_returnsEmptyPageWithoutResolvingPublicCardsWhenRepositoryPageEmpty() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(userDeck(deckId, userId)));
        when(userCardRepository.searchUserCards(eq(userId), eq(deckId), eq("query"), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        Page<UserCardDTO> result = searchService.searchUserCards(userId, deckId, "query", null, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(publicCardRepository);
    }

    @Test
    void searchTemplates_throwsOnUnknownScope() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> searchService.searchTemplates(userId, "query", "weird", 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown template scope: weird");
    }

    @Test
    void searchTemplates_returnsEmptyWhenQueryBlank() {
        UUID userId = UUID.randomUUID();

        Page<CardTemplateDTO> result = searchService.searchTemplates(userId, "  ", "public", 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(cardTemplateRepository, cardTemplateVersionRepository, fieldTemplateRepository);
    }

    @Test
    void searchTemplates_mineAndAllScopesDelegateToDedicatedRepositories() {
        UUID userId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);

        when(cardTemplateRepository.searchUserTemplates(eq(userId), eq("mine"), eq(pageable)))
                .thenReturn(Page.empty(pageable));
        when(cardTemplateRepository.searchUserAndPublicTemplates(eq(userId), eq("all"), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        Page<CardTemplateDTO> mine = searchService.searchTemplates(userId, "mine", "  mine ", 1, 10);
        Page<CardTemplateDTO> all = searchService.searchTemplates(userId, "all", "ALL", 1, 10);

        assertThat(mine.getContent()).isEmpty();
        assertThat(all.getContent()).isEmpty();
        verify(cardTemplateRepository).searchUserTemplates(eq(userId), eq("mine"), eq(pageable));
        verify(cardTemplateRepository).searchUserAndPublicTemplates(eq(userId), eq("all"), eq(pageable));
    }

    @Test
    void searchTemplates_publicScopeMapsLatestVersionFieldsAndEntityFallback() {
        UUID userId = UUID.randomUUID();
        UUID firstTemplateId = UUID.randomUUID();
        UUID secondTemplateId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        CardTemplateEntity firstTemplate = new CardTemplateEntity(
                firstTemplateId,
                userId,
                "Basic",
                "Public template",
                true,
                now,
                now.plusSeconds(10),
                json("{\"layout\":\"entity\"}"),
                json("{\"profile\":\"entity\"}"),
                "entity.png",
                1
        );
        CardTemplateEntity secondTemplate = new CardTemplateEntity(
                secondTemplateId,
                userId,
                "Fallback",
                "No version snapshot",
                false,
                now.minusSeconds(60),
                null,
                json("{\"layout\":\"fallback\"}"),
                json("{\"profile\":\"fallback\"}"),
                "fallback.png",
                3
        );

        PageRequest pageable = PageRequest.of(0, 10);
        when(cardTemplateRepository.searchPublicTemplates(eq("basic"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(firstTemplate, secondTemplate), pageable, 2));

        CardTemplateVersionEntity v1 = version(firstTemplateId, 1, json("{\"layout\":\"v1\"}"), json("{\"profile\":\"v1\"}"), "v1.png");
        CardTemplateVersionEntity v2 = version(firstTemplateId, 2, json("{\"layout\":\"v2\"}"), json("{\"profile\":\"v2\"}"), "v2.png");
        when(cardTemplateVersionRepository.findByTemplateIdIn(List.of(firstTemplateId, secondTemplateId)))
                .thenReturn(List.of(v1, v2));

        FieldTemplateEntity oldField = fieldEntity(UUID.randomUUID(), firstTemplateId, 1, "old", "Old", true, true, 0);
        FieldTemplateEntity latestField = fieldEntity(UUID.randomUUID(), firstTemplateId, 2, "front", "Front", true, true, 1);
        FieldTemplateEntity fallbackField = fieldEntity(UUID.randomUUID(), secondTemplateId, 3, "ignored", "Ignored", true, true, 0);
        when(fieldTemplateRepository.findByTemplateIdIn(List.of(firstTemplateId, secondTemplateId)))
                .thenReturn(List.of(oldField, latestField, fallbackField));

        Page<CardTemplateDTO> result = searchService.searchTemplates(userId, "basic", "public", 1, 10);

        assertThat(result.getContent()).hasSize(2);

        CardTemplateDTO mappedFirst = result.getContent().get(0);
        assertThat(mappedFirst.templateId()).isEqualTo(firstTemplateId);
        assertThat(mappedFirst.version()).isEqualTo(2);
        assertThat(mappedFirst.layout()).isEqualTo(json("{\"layout\":\"v2\"}"));
        assertThat(mappedFirst.aiProfile()).isEqualTo(json("{\"profile\":\"v2\"}"));
        assertThat(mappedFirst.iconUrl()).isEqualTo("v2.png");
        assertThat(mappedFirst.fields()).singleElement().satisfies(field -> {
            assertThat(field.name()).isEqualTo("front");
            assertThat(field.label()).isEqualTo("Front");
        });

        CardTemplateDTO mappedSecond = result.getContent().get(1);
        assertThat(mappedSecond.templateId()).isEqualTo(secondTemplateId);
        assertThat(mappedSecond.version()).isEqualTo(3);
        assertThat(mappedSecond.layout()).isEqualTo(json("{\"layout\":\"fallback\"}"));
        assertThat(mappedSecond.aiProfile()).isEqualTo(json("{\"profile\":\"fallback\"}"));
        assertThat(mappedSecond.iconUrl()).isEqualTo("fallback.png");
        assertThat(mappedSecond.fields()).isEmpty();
    }

    private UserDeckEntity userDeck(UUID deckId, UUID userId) {
        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserDeckId(deckId);
        deck.setUserId(userId);
        return deck;
    }

    private CardTemplateVersionEntity version(UUID templateId,
                                              int version,
                                              JsonNode layout,
                                              JsonNode aiProfile,
                                              String iconUrl) {
        return new CardTemplateVersionEntity(
                templateId,
                version,
                layout,
                aiProfile,
                iconUrl,
                Instant.parse("2026-04-07T12:00:00Z"),
                UUID.randomUUID()
        );
    }

    private FieldTemplateEntity fieldEntity(UUID fieldId,
                                            UUID templateId,
                                            int templateVersion,
                                            String name,
                                            String label,
                                            boolean required,
                                            boolean onFront,
                                            int orderIndex) {
        return new FieldTemplateEntity(
                fieldId,
                templateId,
                templateVersion,
                name,
                label,
                CardFieldType.text,
                required,
                onFront,
                orderIndex,
                null,
                null
        );
    }

    private static JsonNode json(String raw) {
        try {
            return OBJECT_MAPPER.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
