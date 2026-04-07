package app.mnema.core.deck.domain.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserCardEntityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void gettersAndSettersExposeMutableFields() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID publicCardId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        UserCardEntity entity = new UserCardEntity(
                userId,
                deckId,
                publicCardId,
                false,
                false,
                "note",
                new String[]{"tag"},
                MAPPER.createObjectNode().put("front", "Q"),
                now,
                now
        );

        UUID userCardId = UUID.randomUUID();
        entity.setUserCardId(userCardId);
        entity.setCustom(true);
        entity.setDeleted(true);
        entity.setPersonalNote("updated");
        entity.setTags(new String[]{"tag1", "tag2"});
        entity.setContentOverride(MAPPER.createObjectNode().put("back", "A"));

        assertThat(entity.getUserCardId()).isEqualTo(userCardId);
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getUserDeckId()).isEqualTo(deckId);
        assertThat(entity.getPublicCardId()).isEqualTo(publicCardId);
        assertThat(entity.isCustom()).isTrue();
        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getPersonalNote()).isEqualTo("updated");
        assertThat(entity.getTags()).containsExactly("tag1", "tag2");
        assertThat(entity.getContentOverride().path("back").asText()).isEqualTo("A");
    }
}
