package app.mnema.core.review.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SrAlgorithmEntityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void gettersAndSettersExposeAlgorithmMetadata() {
        SrAlgorithmEntity entity = new SrAlgorithmEntity();
        Instant now = Instant.parse("2026-04-07T10:15:30Z");

        entity.setAlgorithmId("fsrs_v6");
        entity.setName("FSRS");
        entity.setDescription("Modern scheduler");
        entity.setVersion("6");
        entity.setConfigSchema(MAPPER.createObjectNode().put("type", "object"));
        entity.setDefaultConfig(MAPPER.createObjectNode().put("requestRetention", 0.9));
        entity.setCreatedAt(now);

        assertThat(entity.getAlgorithmId()).isEqualTo("fsrs_v6");
        assertThat(entity.getName()).isEqualTo("FSRS");
        assertThat(entity.getDescription()).isEqualTo("Modern scheduler");
        assertThat(entity.getVersion()).isEqualTo("6");
        assertThat(entity.getConfigSchema().path("type").asText()).isEqualTo("object");
        assertThat(entity.getDefaultConfig().path("requestRetention").asDouble()).isEqualTo(0.9);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
    }
}
