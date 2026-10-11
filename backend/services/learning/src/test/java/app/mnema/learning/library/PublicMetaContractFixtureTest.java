package app.mnema.learning.library;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code contracts/decks/public-meta.json} is executable: the examples are exactly what the production code produces for them. */
class PublicMetaContractFixtureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGIN = "https://mnema.app";

    private static JsonNode fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/decks/public-meta.json"))) root = root.getParent();
        return JSON.readTree(Files.readString(root.resolve("contracts/decks/public-meta.json")));
    }

    @Test
    void thePublicDeckExampleIsWhatTheCodeProduces() throws Exception {
        JsonNode example = fixture().path("publicDeck");
        JsonNode json = example.path("json");
        Preview preview = new Preview(Preview.Kind.DECK, json.path("title").stringValue(null), json.path("description").stringValue(null),
                json.path("canonical").stringValue(null), json.path("robots").stringValue(null), json.path("image").stringValue(null),
                new Preview.Author(json.path("author").path("username").stringValue(null), json.path("author").path("displayName").stringValue(null)));
        assertThat(preview.toJson()).isEqualTo(json);
        assertThat(List.copyOf(preview.toJson().propertyNames())).containsExactly("kind", "title", "description", "canonical", "robots", "image", "author");
        assertThat(PreviewHead.render(preview)).isEqualTo(example.path("head").stringValue(null));
        assertThat(preview.canonical()).isEqualTo(ORIGIN + "/d/" + example.path("code").stringValue(null) + "/" + DeckSlug.of(preview.title()));
    }

    @Test
    void theGenericExampleIsWhatTheCodeProduces() throws Exception {
        JsonNode example = fixture().path("generic");
        Preview generic = Preview.generic(ORIGIN);
        assertThat(generic.toJson()).isEqualTo(example.path("json"));
        assertThat(PreviewHead.render(generic)).isEqualTo(example.path("head").stringValue(null));
        assertThat(generic.toJson().path("canonical").isNull()).isTrue();
    }

    @Test
    void theControllerConstantsMatchTheContract() throws Exception {
        JsonNode constants = fixture().path("constants");
        assertThat(constants.path("cacheControl").stringValue(null)).isEqualTo(PublicMetaController.CACHE);
        assertThat(constants.path("canonicalHeader").stringValue(null)).startsWith(PublicMetaController.CANONICAL_HEADER + ":");
        assertThat(constants.path("previewHeader").stringValue(null)).startsWith(PublicMetaController.PREVIEW_HEADER + ":");
        assertThat(constants.path("robotsHeader").stringValue(null)).startsWith(PublicMetaController.ROBOTS_HEADER + ":");
        assertThat(constants.path("fragmentContentSecurityPolicy").stringValue(null)).isEqualTo(PublicMetaController.FRAGMENT_POLICY);
        assertThat(constants.path("imagePath").stringValue(null)).isEqualTo(Preview.IMAGE_PATH);
    }
}
