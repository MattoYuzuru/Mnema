package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.CardTemplateVersionRepository;
import app.mnema.core.deck.repository.FieldTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

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
    void searchUserDecks_usesQuerySearchWhenQueryPresent() {
        UUID userId = UUID.randomUUID();
        Page<UserDeckEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

        when(userDeckRepository.searchUserDecks(eq(userId), eq("spanish"), isNull(), any(Pageable.class)))
                .thenReturn(page);

        searchService.searchUserDecks(userId, " spanish ", null, 1, 10);

        verify(userDeckRepository).searchUserDecks(eq(userId), eq("spanish"), isNull(), any(Pageable.class));
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
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void searchUserCards_returnsEmptyWhenQueryAndTagsBlank() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setUserDeckId(deckId);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));

        Page<UserCardDTO> result = searchService.searchUserCards(userId, deckId, " ", List.of(" "), 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(userCardRepository);
    }

    @Test
    void searchUserCards_usesQuerySearchWhenPresent() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        Instant now = Instant.now();

        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setUserDeckId(deckId);

        UserCardEntity card = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                false,
                "note",
                null,
                null,
                now,
                null
        );

        Page<UserCardEntity> page = new PageImpl<>(List.of(card), PageRequest.of(0, 10), 1);

        when(userDeckRepository.findById(deckId)).thenReturn(Optional.of(deck));
        when(userCardRepository.searchUserCards(eq(userId), eq(deckId), eq("query"), isNull(), any(Pageable.class)))
                .thenReturn(page);

        Page<UserCardDTO> result = searchService.searchUserCards(userId, deckId, "query", null, 1, 10);

        assertThat(result.getContent()).hasSize(1);
        verify(userCardRepository).searchUserCards(eq(userId), eq(deckId), eq("query"), isNull(), any(Pageable.class));
    }

    @Test
    void searchTemplates_throwsOnUnknownScope() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> searchService.searchTemplates(userId, "query", "weird", 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchTemplates_returnsEmptyWhenQueryBlank() {
        UUID userId = UUID.randomUUID();

        var result = searchService.searchTemplates(userId, "  ", "public", 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(cardTemplateRepository);
    }

    @Test
    void searchTemplates_publicScopeDelegatesToRepository() {
        UUID userId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);

        when(cardTemplateRepository.searchPublicTemplates(eq("basic"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var result = searchService.searchTemplates(userId, "basic", "public", 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(cardTemplateRepository).searchPublicTemplates(eq("basic"), eq(pageable));
    }
}
