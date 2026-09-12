package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.*;
import static app.mnema.learning.catalog.content.storage.NativeStorageFormat.*;
import static org.assertj.core.api.Assertions.*;

class NativeSnapshotCodecTest {
    private final NativeSnapshotCodec codec = new NativeSnapshotCodec();
    private final UUID scope = UUID.randomUUID();

    @Test
    void existingCorpusAndOpaqueOptionalFieldsRoundTripWithoutNormalization() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/content/native-v1"))) root = root.getParent();
        var original = new NativeDocumentReader().read(Files.readAllBytes(root.resolve("contracts/content/native-v1/valid/mixed.json")));
        assertThat(decode(codec.encode(scope, original)).document().toJson()).isEqualTo(original.toJson());
        ObjectNode future = node(2, "future", node(3, "text"));
        future.put("id", future.path("id").textValue().toUpperCase(Locale.ROOT)).put("extra", true);
        future.withObject("attrs").put("lang", "uninterpreted").putArray("marks").add("b").add("a");
        ((ObjectNode) future.path("content").get(0)).withObject("attrs").put("opaque", 0.1);
        var nativeValue = read(document(future));
        var result = decode(codec.encode(scope, nativeValue));
        assertThat(result.document().toJson()).isEqualTo(nativeValue.toJson());
        assertThat(result.document().hasUnsupportedContent()).isTrue();
    }

    @Test
    void scalarAndPropertyNameBoundaryAndJsonDepthRemainIndependentOfPhysicalDepth() {
        ObjectNode future = node(2, "future");
        future.withObject("attrs").put("я".repeat(16_384), "🌿".repeat(8192));
        JsonNode nested = JsonNodeFactory.instance.textNode("preserved");
        for (int i = 0; i < 123; i++) nested = JsonNodeFactory.instance.arrayNode().add(nested);
        future.withObject("attrs").set("deep", nested);
        assertRoundTripAndBounds(document(future));
        ObjectNode tree = node(32, "future");
        tree.withObject("attrs").put("large", "я".repeat(16_384));
        for (int i = 31; i >= 2; i--) tree = node(i, "future", tree);
        var plan = assertRoundTripAndBounds(document(tree));
        assertThat(plan.snapshot().document().nodeCount()).isEqualTo(32);
        assertThat(plan.snapshot().objects().values()).allMatch(value -> value.dagRank() <= 8);
    }

    @Test
    void exactNativeByteAndTenThousandNodeLimitsFitWithoutLowerCodecLimits() {
        ObjectNode future = node(2, "future");
        for (int i = 0; i < 31; i++) future.withObject("attrs").put("field" + i, "x".repeat(32_768));
        future.withObject("attrs").put("padding", "");
        ObjectNode document = document(future);
        int remaining = NativeDocumentReader.MAX_BYTES - JSON.canonicalBytes(document).length;
        assertThat(remaining).isBetween(1, 32_768);
        future.withObject("attrs").put("padding", "x".repeat(remaining));
        assertThat(JSON.canonicalBytes(document)).hasSize(NativeDocumentReader.MAX_BYTES);
        assertRoundTripAndBounds(document);
        ObjectNode many = document();
        for (int i = 2; i <= 10_000; i++) ((ObjectNode) many.path("root")).withArray("content").add(node(i, "x"));
        var plan = assertRoundTripAndBounds(many);
        assertThat(plan.snapshot().document().nodeCount()).isEqualTo(10_000);
        assertThat(plan.snapshot().objectCount()).isLessThan(11_000);
    }

    @Test
    void multibyteReplacementAndInsertionReuseFragmentsAndUntouchedManifestBranches() {
        ObjectNode doc = document();
        String large = IntStream.range(0, 1500).mapToObj(i -> i + " 日本語🌿;").collect(java.util.stream.Collectors.joining());
        for (int i = 0; i < 40; i++) ((ObjectNode) doc.path("root")).withArray("content")
                .add(node(2 + i * 2, "paragraph", text(3 + i * 2, i == 20 ? large : "short")));
        var original = codec.encode(scope, read(doc));
        ObjectNode text = (ObjectNode) doc.path("root").path("content").get(20).path("content").get(0);
        String changed = large.replace("701 日本語", "701 中国語");
        text.withObject("attrs").put("text", changed);
        var replacement = codec.replace(decode(original), read(doc));
        assertThat(decode(replacement).document().toJson()).isEqualTo(read(doc).toJson());
        assertThat(replacement.additions().stream().filter(value -> value.kind() == ObjectKind.FRAGMENT)).hasSize(1);
        assertThat(replacement.additions()).hasSizeLessThan(8);
        long originalPages = original.snapshot().objects().values().stream().filter(value -> value.payload().path("role").asText().equals("nodes")).count();
        long newPages = replacement.additions().stream().filter(value -> value.payload().path("role").asText().equals("nodes")).count();
        assertThat(newPages).isLessThan(originalPages);
        text.withObject("attrs").put("text", changed.replace("702 日本語", "702 Ω🌿 日本語"));
        var insertion = codec.replace(decode(replacement), read(doc));
        assertThat(insertion.additions().stream().filter(value -> value.kind() == ObjectKind.FRAGMENT)).hasSize(1);
        assertThat(decode(insertion).document().toJson()).isEqualTo(read(doc).toJson());
        assertThat(codec.replace(decode(insertion), read(doc)).additions()).isEmpty();
    }

    @Test
    void fixedTopologyRejectsInsertionReorderingAndParentageChangesExplicitly() {
        ObjectNode doc = document(node(2, "future", node(3, "future")), node(4, "future"));
        var before = codec.encode(scope, read(doc));
        List<ObjectNode> changed = List.of(document(node(2, "future")),
                document(node(4, "future"), node(2, "future", node(3, "future"))),
                document(node(2, "future"), node(3, "future", node(4, "future"))));
        for (ObjectNode value : changed) assertThatThrownBy(() -> codec.replace(before.snapshot(), read(value)))
                .isInstanceOfSatisfying(NativeStorageFailure.class, failure -> assertThat(failure.code()).isEqualTo(NativeStorageFailure.Code.STRUCTURE_CHANGED));
        ((ObjectNode) doc.path("root").path("content").get(0)).put("id", doc.path("root").path("content").get(0).path("id").asText().toUpperCase(Locale.ROOT));
        assertThat(decode(codec.replace(before.snapshot(), read(doc))).document().toJson()).isEqualTo(read(doc).toJson());
    }

    @Test
    void seededFragmentEditsRetainExactScalarsAndBoundFragmentOccupancy() {
        String value = "α日本🌿".repeat(1000);
        List<String> fragments = split(value);
        java.util.Random random = new java.util.Random(74);
        for (int i = 0; i < 200; i++) {
            int at = random.nextInt(value.length());
            if (Character.isLowSurrogate(value.charAt(at))) at--;
            int width = Character.charCount(value.codePointAt(at));
            String insert = i % 3 == 0 ? "" : i % 3 == 1 ? "Ж🌿" : "x".repeat(2300);
            value = value.substring(0, at) + insert + value.substring(at + width);
            fragments = NativeSnapshotCodec.replaceFragments(fragments, value);
            assertThat(String.join("", fragments)).isEqualTo(value);
            assertThat(fragments).allMatch(part -> bytes(part) >= MIN_FRAGMENT_BYTES && bytes(part) <= MAX_FRAGMENT_BYTES);
        }
        assertThat(NativeSnapshotCodec.replaceFragments(List.of("α🌿β"), "α🌴β")).containsExactly("α🌴β");
        assertThat(NativeSnapshotCodec.replaceFragments(List.of("x".repeat(1024), "y".repeat(1024)), "last")).containsExactly("last");
    }

    @Test
    void historyReadsDependOnlyOnSelectedRootAfterOneThousandReplacements() {
        ObjectNode doc = document(node(2, "paragraph", text(3, "revision 0")));
        var first = codec.encode(scope, read(doc));
        var current = first;
        for (int i = 1; i <= 1000; i++) {
            ((ObjectNode) doc.path("root").path("content").get(0).path("content").get(0)).withObject("attrs").put("text", "revision " + i);
            current = codec.replace(current.snapshot(), read(doc));
        }
        assertThat(decode(first).document().toJson().toString()).contains("revision 0");
        assertThat(decode(current).document().toJson().toString()).contains("revision 1000");
        assertThat(current.snapshot().objectCount()).isEqualTo(first.snapshot().objectCount());
    }

    private NativeEncodingPlan assertRoundTripAndBounds(ObjectNode json) {
        var nativeValue = read(json);
        var plan = codec.encode(scope, nativeValue);
        assertThat(decode(plan).document().toJson()).isEqualTo(nativeValue.toJson());
        assertThat(plan.snapshot().objectCount()).isLessThanOrEqualTo(MAX_OBJECTS);
        assertThat(plan.snapshot().objects().values()).allMatch(object -> object.edges().size() <= 32 && object.dagRank() <= 8
                && JSON.canonicalBytes(object.payload()).length + 256 <= 16_384);
        return plan;
    }
}
