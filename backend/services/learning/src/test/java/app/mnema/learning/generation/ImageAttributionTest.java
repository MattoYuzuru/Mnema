package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Candidate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageAttributionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID asset = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private static Candidate candidate(UUID asset, String source, String author, String license) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), "i1", asset, source, "id-1", "T", author, license, null, "https://example.org/p", false, 1, 1,
                "READY", Instant.now());
    }

    private static JsonNode document(String type, UUID asset, String caption) {
        ObjectNode attrs = JSON.createObjectNode().put("alt", "Лиса");
        if (asset != null) attrs.put("assetId", asset.toString());
        if (caption != null) attrs.put("caption", caption);
        ObjectNode block = JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("type", type);
        block.set("attrs", attrs);
        ObjectNode root = JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("type", "doc");
        ArrayNode content = root.putArray("content");
        content.add(block);
        ObjectNode document = JSON.createObjectNode();
        document.set("root", root);
        return document;
    }

    private static String caption(JsonNode document) {
        return document.path("root").path("content").get(0).path("attrs").path("caption").stringValue(null);
    }

    @Test
    void theAttributionIsAuthorSourceAndLicenseAndWithoutAnAuthorSourceAndLicense() {
        assertThat(ImageAttribution.text(candidate(asset, "WIKIMEDIA", "Jörg Hempel", "CC BY-SA 4.0"))).isEqualTo("Jörg Hempel · Wikimedia Commons · CC BY-SA 4.0");
        assertThat(ImageAttribution.text(candidate(asset, "PIXABAY", "", "Pixabay Content License"))).isEqualTo("Pixabay · Pixabay Content License");
        assertThat(ImageAttribution.text(candidate(asset, "OPENVERSE", "Ann", "CC0 1.0"))).isEqualTo("Ann · Openverse · CC0 1.0");
        assertThat(ImageAttribution.text(candidate(asset, "UNKNOWN", "A", "L"))).isEqualTo("A · UNKNOWN · L");
    }

    @Test
    void theCaptionOfTheCandidatesImageIsSetAppendedAfterAnExistingOneAndNothingElseChanges() {
        List<Candidate> candidates = List.of(candidate(asset, "PIXABAY", "Ann", "Pixabay Content License"));
        JsonNode plain = document("image", asset, null);
        JsonNode captioned = document("image", asset, "Лиса зимой");

        JsonNode applied = ImageAttribution.apply(plain, candidates);
        assertThat(caption(applied)).isEqualTo("Ann · Pixabay · Pixabay Content License");
        assertThat(plain.path("root").path("content").get(0).path("attrs").has("caption")).as("the original is untouched").isFalse();
        assertThat(caption(ImageAttribution.apply(captioned, candidates))).isEqualTo("Лиса зимой — Ann · Pixabay · Pixabay Content License");
        // an image that is none of the candidates, another kind of block and no candidates at all stay as they are
        assertThat(caption(ImageAttribution.apply(document("image", other, null), candidates))).isNull();
        assertThat(caption(ImageAttribution.apply(document("image", other, "своя"), candidates))).isEqualTo("своя");
        assertThat(caption(ImageAttribution.apply(document("audio", asset, null), candidates))).isNull();
        assertThat(ImageAttribution.apply(plain, List.of())).isSameAs(plain);
    }

    @Test
    void theCaptionNeverExceedsTheNodeSchemaBound() {
        List<Candidate> candidates = List.of(candidate(asset, "PIXABAY", "Ann", "Pixabay Content License"));
        String long1024 = "я".repeat(1_024);

        String applied = caption(ImageAttribution.apply(document("image", asset, long1024), candidates));

        assertThat(applied.codePointCount(0, applied.length())).isEqualTo(1_024);
        assertThat(applied).endsWith(" — Ann · Pixabay · Pixabay Content License");
        List<Candidate> huge = List.of(candidate(asset, "PIXABAY", "A".repeat(1_100), "L"));
        String hugeCaption = caption(ImageAttribution.apply(document("image", asset, "x"), huge));
        assertThat(hugeCaption.codePointCount(0, hugeCaption.length())).isLessThanOrEqualTo(1_024);
    }

    @Test
    void theProvenanceNamesEachStockImageTheDocumentUses() {
        Candidate used = candidate(asset, "WIKIMEDIA", "Ann", "CC BY 4.0");
        ArrayNode media = ImageAttribution.media(document("image", asset, null), List.of(used, candidate(other, "PIXABAY", "B", "L")));

        assertThat(media).hasSize(1);
        assertThat(media.get(0).path("assetId").stringValue(null)).isEqualTo(asset.toString());
        assertThat(media.get(0).path("source").stringValue(null)).isEqualTo("WIKIMEDIA");
        assertThat(media.get(0).path("sourceId").stringValue(null)).isEqualTo("id-1");
        assertThat(media.get(0).path("license").stringValue(null)).isEqualTo("CC BY 4.0");
        assertThat(media.get(0).path("sourcePageUrl").stringValue(null)).isEqualTo("https://example.org/p");
        assertThat(ImageAttribution.media(document("image", other, null), List.of(used))).isEmpty();
    }

    @Test
    void theMediaNodeOperationsReadAndReplaceTheAssetOfOneNode() {
        JsonNode document = document("image", asset, null);
        UUID node = UUID.fromString(document.path("root").path("content").get(0).path("id").stringValue(null));

        assertThat(MediaNodes.assetOf(document, node)).isEqualTo(asset);
        assertThat(MediaNodes.has(document, node)).isTrue();
        assertThat(MediaNodes.has(document, UUID.randomUUID())).isFalse();
        assertThat(MediaNodes.assetOf(document, UUID.randomUUID())).isNull();
        JsonNode replaced = MediaNodes.withAsset(document, node, other);
        assertThat(MediaNodes.assetOf(replaced, node)).isEqualTo(other);
        assertThat(MediaNodes.assetOf(document, node)).as("the original").isEqualTo(asset);
        assertThatThrownBy(() -> MediaNodes.withAsset(document, UUID.randomUUID(), other)).isInstanceOf(IllegalArgumentException.class);
        assertThat(MediaNodes.assetOf(document("paragraph", null, null), UUID.randomUUID())).isNull();
    }

    @Test
    void theLanguageOfAQueryIsTheLanguageOfItsScript() {
        assertThat(ImageSearchExecutor.language("лиса зимой")).isEqualTo("ru");
        assertThat(ImageSearchExecutor.language("red fox")).isEqualTo("en");
        assertThat(ImageSearchExecutor.language("きつね 雪")).isEqualTo("ja");
        assertThat(ImageSearchExecutor.language("狐狸 雪")).isEqualTo("zh");
        assertThat(ImageSearchExecutor.language("여우")).isEqualTo("ko");
        assertThat(ImageSearchExecutor.language("fox лиса")).isEqualTo("ru");
        assertThat(ImageSearchExecutor.language("")).isEqualTo("en");
        assertThat(ImageSearchExecutor.language("🦊 12")).isEqualTo("en");
    }
}
