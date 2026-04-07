package app.mnema.core.deck.domain.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardTemplateEntityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void gettersSettersAndEqualityUseKeyFields() {
        UUID templateId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        CardTemplateEntity entity = new CardTemplateEntity(
                templateId,
                ownerId,
                "Template",
                "Description",
                true,
                now,
                now,
                MAPPER.createObjectNode().put("front", "Q"),
                MAPPER.createObjectNode().put("level", "A1"),
                "https://img.example/icon.png",
                3
        );

        entity.setDescription("Updated");
        entity.setPublic(false);
        entity.setLatestVersion(4);

        CardTemplateEntity same = new CardTemplateEntity(templateId, ownerId, "Template", null, false, now, now, null, null, null, 1);

        assertThat(entity.getTemplateId()).isEqualTo(templateId);
        assertThat(entity.getOwnerId()).isEqualTo(ownerId);
        assertThat(entity.getDescription()).isEqualTo("Updated");
        assertThat(entity.isPublic()).isFalse();
        assertThat(entity.getLatestVersion()).isEqualTo(4);
        assertThat(entity).isEqualTo(same);
        assertThat(entity.hashCode()).isEqualTo(same.hashCode());
    }
}
