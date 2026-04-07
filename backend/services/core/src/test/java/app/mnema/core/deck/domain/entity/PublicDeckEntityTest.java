package app.mnema.core.deck.domain.entity;

import app.mnema.core.deck.domain.type.LanguageTag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDeckEntityTest {

    @Test
    void constructorsAndMutatorsExposeDeckMetadata() {
        UUID deckId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T10:15:30Z");

        PublicDeckEntity entity = new PublicDeckEntity(
                deckId,
                2,
                authorId,
                "Deck",
                "Description",
                UUID.randomUUID(),
                templateId,
                3,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                now,
                now,
                now,
                UUID.randomUUID()
        );

        entity.setName("Updated");
        entity.setDescription("Updated description");
        entity.setPublicFlag(false);
        entity.setListed(false);
        entity.setLanguageCode(LanguageTag.ru);
        entity.setTags(new String[]{"tag1", "tag2"});
        entity.setTemplateVersion(4);
        entity.setUpdatedAt(now.plusSeconds(60));
        entity.setPublishedAt(now.plusSeconds(120));

        assertThat(entity.getDeckId()).isEqualTo(deckId);
        assertThat(entity.getVersion()).isEqualTo(2);
        assertThat(entity.getAuthorId()).isEqualTo(authorId);
        assertThat(entity.getTemplateId()).isEqualTo(templateId);
        assertThat(entity.getName()).isEqualTo("Updated");
        assertThat(entity.getDescription()).isEqualTo("Updated description");
        assertThat(entity.isPublicFlag()).isFalse();
        assertThat(entity.isListed()).isFalse();
        assertThat(entity.getLanguageCode()).isEqualTo(LanguageTag.ru);
        assertThat(entity.getTags()).containsExactly("tag1", "tag2");
        assertThat(entity.getTemplateVersion()).isEqualTo(4);
        assertThat(entity.getUpdatedAt()).isEqualTo(now.plusSeconds(60));
        assertThat(entity.getPublishedAt()).isEqualTo(now.plusSeconds(120));
        assertThat(entity.getCards()).isEqualTo((List<PublicCardEntity>) null);
    }
}
