package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.PublicDeckRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeckServiceTest {

    @Mock
    UserDeckRepository userDeckRepository;

    @Mock
    UserCardRepository userCardRepository;

    @Mock
    PublicDeckRepository publicDeckRepository;

    @InjectMocks
    DeckService deckService;

    @Test
    void getPublicDecksByPage_delegatesToRepositoryAndMaps() {
        UUID publicDeckId = UUID.randomUUID();
        PublicDeckEntity entity = new PublicDeckEntity(
                publicDeckId,
                1,
                UUID.randomUUID(),
                "Deck 1",
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
        Page<PublicDeckEntity> repoPage = new PageImpl<>(
                List.of(entity),
                PageRequest.of(0, 10),
                1
        );

        when(publicDeckRepository.findLatestPublicVisibleDecks(any(Pageable.class)))
                .thenReturn(repoPage);

        Page<PublicDeckDTO> result = deckService.getPublicDecksByPage(1, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
        PublicDeckDTO dto = result.getContent().getFirst();
        assertThat(dto.name()).isEqualTo("Deck 1");
        assertThat(dto.language()).isEqualTo(LanguageTag.en);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(publicDeckRepository).findLatestPublicVisibleDecks(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void getUserDecksByPage_delegatesToRepositoryAndMaps() {
        UUID userId = UUID.randomUUID();

        UserDeckEntity entity = new UserDeckEntity(
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

        Page<UserDeckEntity> repoPage = new PageImpl<>(
                List.of(entity),
                PageRequest.of(0, 10),
                1
        );

        when(userDeckRepository.findByUserIdAndArchivedFalse(eq(userId), any(Pageable.class)))
                .thenReturn(repoPage);

        Page<UserDeckDTO> result = deckService.getUserDecksByPage(userId, 1, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
        UserDeckDTO dto = result.getContent().getFirst();
        assertThat(dto.userId()).isEqualTo(userId);
        assertThat(dto.displayName()).isEqualTo("My deck");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userDeckRepository).findByUserIdAndArchivedFalse(eq(userId), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void createNewDeck_createsPublicAndUserDeck() {
        UUID publicDeckId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();

        PublicDeckDTO requestDto = new PublicDeckDTO(
                null,
                null,
                null,
                "My deck",
                "Description",
                null,
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
                publicDeckId,
                1,
                userId,
                "My deck",
                "Description",
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

        verify(publicDeckRepository).save(any(PublicDeckEntity.class));
        verify(userDeckRepository).save(any(UserDeckEntity.class));
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
        verify(publicDeckRepository, never()).findLatestByDeckId(any());
        verify(userCardRepository, never()).saveAll(any());
    }

    @Test
    void forkFromPublicDeck_throwsWhenAuthorTriesToForkOwnDeck() {
        UUID authorId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();

        when(userDeckRepository.findByUserIdAndPublicDeckId(authorId, publicDeckId))
                .thenReturn(Optional.empty());

        PublicDeckEntity publicDeck = new PublicDeckEntity(
                publicDeckId,
                1,
                authorId,
                "Author deck",
                "Desc",
                UUID.randomUUID(),
                null,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );

        when(publicDeckRepository.findLatestByDeckId(publicDeckId))
                .thenReturn(Optional.of(publicDeck));

        assertThatThrownBy(() -> deckService.forkFromPublicDeck(authorId, publicDeckId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Author cannot fork own deck");
    }
}
