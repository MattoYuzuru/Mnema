package app.mnema.learning.generation.mbm;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The executable contract: every fixture of {@code contracts/generation/mbm-v1} through the real compiler. */
class MbmCompilerFixtureTest {

    private static final MbmCompiler COMPILER = new MbmCompiler();
    private static final NativeDocumentReader READER = new NativeDocumentReader();

    @Test
    void everyValidFixtureCompilesToItsGoldenDocumentAndPassesTheNativeReader() throws IOException {
        List<String> cases = MbmFixtures.cases("valid");
        assertThat(cases).hasSizeGreaterThanOrEqualTo(25);
        var softly = new SoftAssertions();
        for (String name : cases) {
            JsonNode meta = MbmFixtures.json("valid", name, ".meta.json");
            MbmResult result = COMPILER.compile(MbmFixtures.source("valid", name), MbmFixtures.options(meta),
                    new MbmFixtures.SequentialIds());

            softly.assertThat(result).as(name).isInstanceOf(MbmResult.Success.class);
            if (!(result instanceof MbmResult.Success success)) {
                softly.assertThat(result).as(name + " failure").isNull();
                continue;
            }
            JsonNode golden = MbmFixtures.json("valid", name, ".native.json");
            softly.assertThat(new String(MbmFixtures.canonical(success.document()), StandardCharsets.UTF_8))
                    .as(name + " document").isEqualTo(new String(MbmFixtures.canonical(golden), StandardCharsets.UTF_8));
            softly.assertThat(READER.read(MbmFixtures.JSON.writeValueAsBytes(success.document())).hasUnsupportedContent())
                    .as(name + " reader").isFalse();
            softly.assertThat(success.nodeCount()).as(name + " node count").isPositive();
            softly.assertThat(describe(success.warnings())).as(name + " warnings")
                    .isEqualTo(expectedFindings(meta.path("expected").path("warnings")));
            softly.assertThat(describeSlots(success.slots())).as(name + " slots")
                    .isEqualTo(describeExpectedSlots(meta.path("expected").path("slots")));
        }
        softly.assertAll();
    }

    @Test
    void everyInvalidFixtureFailsWithExactlyTheExpectedFindingsAndNoDocument() throws IOException {
        List<String> cases = MbmFixtures.cases("invalid");
        assertThat(cases).hasSizeGreaterThanOrEqualTo(39);
        var softly = new SoftAssertions();
        for (String name : cases) {
            JsonNode meta = MbmFixtures.json("invalid", name, ".meta.json");
            MbmResult result = COMPILER.compile(MbmFixtures.source("invalid", name), MbmFixtures.options(meta),
                    new MbmFixtures.SequentialIds());

            softly.assertThat(result).as(name).isInstanceOf(MbmResult.Failure.class);
            if (result instanceof MbmResult.Failure failure) {
                softly.assertThat(describe(failure.errors())).as(name)
                        .isEqualTo(expectedFindings(MbmFixtures.json("invalid", name, ".errors.json")));
            }
        }
        softly.assertAll();
    }

    private static List<String> describe(List<MbmFinding> findings) {
        var out = new ArrayList<String>();
        for (MbmFinding finding : findings) {
            out.add(finding.line() + ":" + finding.column() + ":" + finding.code() + ":" + finding.attribute());
        }
        return out;
    }

    private static List<String> expectedFindings(JsonNode findings) {
        var out = new ArrayList<String>();
        for (JsonNode finding : findings) {
            out.add(finding.path("line").intValue() + ":"
                    + (finding.has("column") ? finding.path("column").intValue() : null) + ":"
                    + finding.path("code").stringValue() + ":" + finding.path("attribute").stringValue(null));
        }
        return out;
    }

    private static List<Map<String, Object>> describeSlots(List<MbmSlot> slots) {
        var out = new ArrayList<Map<String, Object>>();
        for (MbmSlot slot : slots) {
            out.add(Map.of("slotKey", slot.slotKey(), "kind", slot.kind().name(), "nodeId", slot.nodeId().toString(),
                    "assetId", slot.assetId().toString(), "spec", slot.spec()));
        }
        return out;
    }

    private static List<Map<String, Object>> describeExpectedSlots(JsonNode slots) {
        var out = new ArrayList<Map<String, Object>>();
        for (JsonNode slot : slots) {
            var spec = new java.util.LinkedHashMap<String, String>();
            slot.path("spec").properties().forEach(entry -> spec.put(entry.getKey(), entry.getValue().stringValue()));
            out.add(Map.of("slotKey", slot.path("slotKey").stringValue(), "kind", slot.path("kind").stringValue(),
                    "nodeId", UUID.fromString(slot.path("nodeId").stringValue()).toString(),
                    "assetId", UUID.fromString(slot.path("assetId").stringValue()).toString(), "spec", spec));
        }
        return out;
    }
}
