package app.mnema.core.review.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonConfigMergerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mergeHandlesNullInputs() {
        JsonConfigMerger merger = new JsonConfigMerger();
        ObjectNode base = MAPPER.createObjectNode().put("front", "Base");

        assertThat(merger.merge(base, null)).isEqualTo(base);
        assertThat(merger.merge(null, null).isObject()).isTrue();
        assertThat(merger.merge(null, base)).isEqualTo(base);
    }

    @Test
    void mergeOverridesPrimitiveAndRecursivelyMergesObjects() {
        JsonConfigMerger merger = new JsonConfigMerger();
        ObjectNode base = MAPPER.createObjectNode();
        base.put("front", "Base");
        base.withObject("meta").put("lang", "en").put("level", 1);

        ObjectNode override = MAPPER.createObjectNode();
        override.put("front", "Override");
        override.withObject("meta").put("level", 2).put("topic", "verbs");

        var merged = merger.merge(base, override);

        assertThat(merged.path("front").asText()).isEqualTo("Override");
        assertThat(merged.path("meta").path("lang").asText()).isEqualTo("en");
        assertThat(merged.path("meta").path("level").asInt()).isEqualTo(2);
        assertThat(merged.path("meta").path("topic").asText()).isEqualTo("verbs");
    }
}
