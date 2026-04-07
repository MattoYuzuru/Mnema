package app.mnema.core.deck.domain.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardTemplateVersionEntityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void constructorAndMutatorsExposeVersionMetadata() {
        UUID templateId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        CardTemplateVersionEntity entity = new CardTemplateVersionEntity(
                templateId,
                3,
                MAPPER.createObjectNode().put("front", "Q"),
                MAPPER.createObjectNode().put("profile", "A1"),
                "https://img.example/icon.png",
                now,
                createdBy
        );

        entity.setLayout(MAPPER.createObjectNode().put("front", "Updated"));
        entity.setAiProfile(MAPPER.createObjectNode().put("profile", "B1"));
        entity.setIconUrl("https://img.example/next.png");

        assertThat(entity.getTemplateId()).isEqualTo(templateId);
        assertThat(entity.getVersion()).isEqualTo(3);
        assertThat(entity.getLayout().path("front").asText()).isEqualTo("Updated");
        assertThat(entity.getAiProfile().path("profile").asText()).isEqualTo("B1");
        assertThat(entity.getIconUrl()).isEqualTo("https://img.example/next.png");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getCreatedBy()).isEqualTo(createdBy);
    }
}
