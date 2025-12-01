package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PublicDeckRepositoryDataJpaTest {

    @Autowired
    private PublicDeckRepository publicDeckRepository;

    @Test
    void findByAuthorId_returnsOnlyDecksForAuthor() {
        UUID author1 = UUID.randomUUID();
        UUID author2 = UUID.randomUUID();

        PublicDeckEntity deck1 = new PublicDeckEntity(
                1,
                author1,
                "Deck 1",
                "Desc 1",
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

        PublicDeckEntity deck2 = new PublicDeckEntity(
                1,
                author2,
                "Deck 2",
                "Desc 2",
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

        publicDeckRepository.saveAll(List.of(deck1, deck2));

        List<PublicDeckEntity> result = publicDeckRepository.findByAuthorId(author1);

        assertThat(result)
                .hasSize(1)
                .allMatch(d -> d.getAuthorId().equals(author1));
    }

    @Test
    void findByDeckIdAndVersion_returnsExactVersion() {
        UUID author = UUID.randomUUID();

        PublicDeckEntity v1 = new PublicDeckEntity(
                1,
                author,
                "Deck v1",
                "Desc v1",
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
        PublicDeckEntity savedV1 = publicDeckRepository.save(v1);

        PublicDeckEntity v2 = new PublicDeckEntity(
                2,
                author,
                "Deck v2",
                "Desc v2",
                savedV1.getTemplateId(),
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );
        // сохраняем вторую версию с тем же deck_id
        try {
            var deckIdField = PublicDeckEntity.class.getDeclaredField("deckId");
            deckIdField.setAccessible(true);
            deckIdField.set(v2, savedV1.getDeckId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        publicDeckRepository.save(v2);

        var found = publicDeckRepository
                .findByDeckIdAndVersion(savedV1.getDeckId(), 2);

        assertThat(found).isPresent();
        assertThat(found.get().getVersion()).isEqualTo(2);
        assertThat(found.get().getName()).isEqualTo("Deck v2");
    }

    @Test
    void findTopByDeckIdOrderByVersionDesc_returnsLatestVersion() {
        UUID author = UUID.randomUUID();

        PublicDeckEntity v1 = new PublicDeckEntity(
                1,
                author,
                "Deck v1",
                "Desc v1",
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
        PublicDeckEntity savedV1 = publicDeckRepository.save(v1);

        PublicDeckEntity v2 = new PublicDeckEntity(
                2,
                author,
                "Deck v2",
                "Desc v2",
                savedV1.getTemplateId(),
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );
        try {
            var deckIdField = PublicDeckEntity.class.getDeclaredField("deckId");
            deckIdField.setAccessible(true);
            deckIdField.set(v2, savedV1.getDeckId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        publicDeckRepository.save(v2);

        var latest = publicDeckRepository
                .findTopByDeckIdOrderByVersionDesc(savedV1.getDeckId());

        assertThat(latest).isPresent();
        assertThat(latest.get().getVersion()).isEqualTo(2);
        assertThat(latest.get().getName()).isEqualTo("Deck v2");
    }

    @Test
    void findByPublicFlagTrueAndListedTrue_returnsOnlyPublicAndListedDecks() {
        UUID author = UUID.randomUUID();

        PublicDeckEntity publicAndListed = new PublicDeckEntity(
                1,
                author,
                "Public listed",
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

        PublicDeckEntity notPublic = new PublicDeckEntity(
                1,
                author,
                "Not public",
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

        PublicDeckEntity notListed = new PublicDeckEntity(
                1,
                author,
                "Not listed",
                "Desc",
                UUID.randomUUID(),
                true,
                false,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );

        publicDeckRepository.saveAll(List.of(publicAndListed, notPublic, notListed));

        Page<PublicDeckEntity> page = publicDeckRepository
                .findByPublicFlagTrueAndListedTrue(PageRequest.of(0, 10));

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(d -> d.isPublicFlag() && d.isListed());
    }
}
