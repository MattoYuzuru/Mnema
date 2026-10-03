package app.mnema.learning.generation;

import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.usage.AdmissionPricing;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure parts of the generation module: block boundaries, checkpoints, cursors, settings and the spec reader. */
class GenerationUnitTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ------------------------------------------------------------ block boundary

    @Test
    void aBlockBoundaryIsTheOffsetAfterTheLastBlankLineOutsideAFence() {
        assertThat(DraftStreamer.lastBoundary("# A\n\nB")).isEqualTo(5);
        assertThat(DraftStreamer.lastBoundary("# A\nB\n")).isZero();
        assertThat(DraftStreamer.lastBoundary("")).isZero();
        assertThat(DraftStreamer.lastBoundary("\n\nтекст")).isZero();
        // a blank line inside a fenced block is not a boundary, the one after the closing fence is
        String fenced = "Intro\n\n```sql\nselect 1;\n\nselect 2;\n```\n\nafter";
        int boundary = DraftStreamer.lastBoundary(fenced);
        assertThat(fenced.substring(boundary)).isEqualTo("after");
        assertThat(DraftStreamer.lastBoundary("Intro\n\n```sql\nselect 1;\n\nselect 2;\n")).isEqualTo("Intro\n\n".length());
        // several blank lines are one boundary at the first of them
        assertThat(DraftStreamer.lastBoundary("A\n\n\n\nB")).isEqualTo(3);
        // a longer fence is closed only by a fence at least as long
        String longFence = "A\n\n````\ncode\n```\n\nstill code\n````\n\nB";
        assertThat(longFence.substring(DraftStreamer.lastBoundary(longFence))).isEqualTo("B");
    }

    // ----------------------------------------------------------------- streamer

    private record Sent(int generation, int start, ArrayNode blocks) { }

    private static DraftStreamer streamer(List<Sent> sent, boolean[] stop, Duration interval, int maxBytes, boolean accept) {
        return new DraftStreamer(MbmOptions.create(), (generation, start, blocks) -> {
            sent.add(new Sent(generation, start, blocks));
            return accept;
        }, () -> stop[0], interval, maxBytes, 1);
    }

    @Test
    void completeBlocksBecomeCheckpointsOnceAndTheNextBoundaryAppendsTheRest() {
        List<Sent> sent = new ArrayList<>();
        DraftStreamer streamer = streamer(sent, new boolean[1], Duration.ZERO, 28_672, true);
        streamer.onDelta("# Заго");
        streamer.onDelta("ловок\n\nПервый абзац.\n");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().start()).isZero();
        assertThat(sent.getFirst().blocks()).hasSize(1);
        assertThat(sent.getFirst().blocks().get(0).path("type").stringValue(null)).isEqualTo("heading");
        streamer.onDelta("\nВторой абзац.\n\n- пункт\n- пункт\n\nхвост");
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).start()).isEqualTo(1);
        assertThat(sent.get(1).blocks()).hasSize(3);
        assertThat(sent.get(1).generation()).isEqualTo(1);
        // nothing new, nothing sent
        streamer.onDelta("ещё\n");
        assertThat(sent).hasSize(2);
    }

    @Test
    void aRestartStartsANewGenerationFromBlockZero() {
        List<Sent> sent = new ArrayList<>();
        DraftStreamer streamer = streamer(sent, new boolean[1], Duration.ZERO, 28_672, true);
        streamer.onDelta("# A\n\nB\n\n");
        assertThat(streamer.generation()).isEqualTo(1);
        streamer.onRestart();
        assertThat(streamer.generation()).isEqualTo(2);
        streamer.onDelta("# C\n\nD\n\n");
        Sent last = sent.getLast();
        assertThat(last.generation()).isEqualTo(2);
        assertThat(last.start()).isZero();
        streamer.restart();
        assertThat(streamer.generation()).isEqualTo(3);
    }

    @Test
    void checkpointsAreThrottledAndAPrefixThatDoesNotCompileIsSkippedNotFatal() {
        List<Sent> sent = new ArrayList<>();
        DraftStreamer throttled = streamer(sent, new boolean[1], Duration.ofHours(1), 28_672, true);
        throttled.onDelta("# A\n\nB\n\n");
        throttled.onDelta("C\n\nD\n\n");
        assertThat(sent).hasSize(1);

        List<Sent> broken = new ArrayList<>();
        DraftStreamer tolerant = streamer(broken, new boolean[1], Duration.ZERO, 28_672, true);
        // an unknown directive makes the prefix fail: no checkpoint, no exception
        tolerant.onDelta("::unknown{x=\"1\"} текст\n\n");
        assertThat(broken).isEmpty();
        assertThat(tolerant.aborted()).isFalse();
    }

    @Test
    void aCheckpointThatCannotBeWrittenStopsThePreviewsAndNeverFailsTheDraft() {
        int[] calls = {0};
        DraftStreamer streamer = new DraftStreamer(MbmOptions.create(), (generation, start, blocks) -> {
            calls[0]++;
            throw new IllegalStateException("database trouble");
        }, () -> false, Duration.ZERO, 24_576, 1);
        streamer.onDelta("# A\n\nB\n\n");
        streamer.onDelta("C\n\nD\n\n");
        assertThat(calls[0]).isEqualTo(1);
        assertThat(streamer.aborted()).isFalse();
        // a restart makes the next draft preview again
        streamer.restart();
        streamer.onDelta("# A\n\nB\n\n");
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void aBlockTooLargeForAnEventIsNeverPreviewedAndTheSinkRefusingAbortsTheCall() {
        List<Sent> sent = new ArrayList<>();
        DraftStreamer small = streamer(sent, new boolean[1], Duration.ZERO, 1_024, true);
        small.onDelta("# A\n\n" + "слово ".repeat(500) + "\n\nB\n\n");
        // the heading fits, the huge paragraph does not: previewing stops for this draft
        assertThat(sent).hasSize(1);
        small.onDelta("C\n\nD\n\n");
        assertThat(sent).hasSize(1);

        DraftStreamer refused = streamer(new ArrayList<>(), new boolean[1], Duration.ZERO, 28_672, false);
        assertThatThrownBy(() -> refused.onDelta("# A\n\nB\n\n")).isInstanceOf(DraftStreamer.Aborted.class);
        assertThat(refused.aborted()).isTrue();

        boolean[] stop = {false};
        DraftStreamer cancelled = streamer(new ArrayList<>(), stop, Duration.ZERO, 28_672, true);
        cancelled.onDelta("# A");
        stop[0] = true;
        assertThatThrownBy(() -> cancelled.onDelta("\n\nB")).isInstanceOf(DraftStreamer.Aborted.class);
        assertThat(cancelled.aborted()).isTrue();
    }

    // ------------------------------------------------------------------ cursors

    @Test
    void aListCursorRoundTripsAndOnlyOurOwnEncodingIsAccepted() {
        ListCursor cursor = new ListCursor(Instant.parse("2026-10-02T09:00:42.123456Z"), UUID.randomUUID());
        assertThat(ListCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(ListCursor.decode(null)).isNull();
        for (String bad : List.of("", "!!", "a".repeat(200), "MC8w", "bm9wZQ", new ListCursor(Instant.EPOCH, UUID.randomUUID()).encode() + "A",
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("001/".concat(UUID.randomUUID().toString()).getBytes()),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("1/not-a-uuid".getBytes()))) {
            assertThatThrownBy(() -> ListCursor.decode(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }

    // ---------------------------------------------------------------- command inputs

    @Test
    void aDerivedCommandIdIsALegalCommandIdThatDependsOnItsParentAndItsNameOnly() {
        UUID parent = UUID.randomUUID();
        UUID derived = Commands.derive(parent, "artifact-1");
        assertThat(Commands.derive(parent, "artifact-1")).isEqualTo(derived);
        assertThat(Commands.derive(parent, "artifact-2")).isNotEqualTo(derived);
        assertThat(Commands.derive(UUID.randomUUID(), "artifact-1")).isNotEqualTo(derived);
        assertThat(derived).isNotEqualTo(parent);
        // it is a command id the catalog accepts: version 4, IETF variant
        assertThat(UuidPolicy.requireCommandId(derived)).isEqualTo(derived);
        assertThat(derived.version()).isEqualTo(4);
        assertThat(derived.variant()).isEqualTo(2);
    }

    @Test
    void anIfMatchIsExactlyOneQuotedCanonicalDecimalAndAnyOtherShapeIsRefused() {
        assertThat(Commands.ifMatch(List.of("\"0\""))).isZero();
        assertThat(Commands.ifMatch(List.of("\"12\""))).isEqualTo(12);
        assertThat(Commands.ifMatch(List.of("\"999999999999999999\""))).isEqualTo(999_999_999_999_999_999L);
        assertThatThrownBy(() -> Commands.ifMatch(List.of())).isInstanceOf(VersionPreconditionRequiredException.class);
        assertThatThrownBy(() -> Commands.ifMatch(null)).isInstanceOf(VersionPreconditionRequiredException.class);
        for (String malformed : List.of("12", "W/\"12\"", "*", "\"012\"", "\"-1\"", "\"\"", "\"1.5\"", "\"1000000000000000000\"", " \"1\"")) {
            assertThatThrownBy(() -> Commands.ifMatch(List.of(malformed))).as(malformed).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> Commands.ifMatch(List.of("\"1\"", "\"2\""))).isInstanceOf(InvalidRequestException.class);
        assertThat(Commands.raw(List.of())).isNull();
        assertThat(Commands.raw(List.of("\"3\""))).isEqualTo("\"3\"");
    }

    @Test
    void aBodyVersionIsADecimalStringAndNothingElse() throws Exception {
        assertThat(Commands.version(JSON.readTree("{\"v\":\"7\"}"), "v")).isEqualTo(7);
        for (String bad : List.of("{\"v\":7}", "{\"v\":\"07\"}", "{\"v\":\"-1\"}", "{\"v\":\"\"}", "{}", "{\"v\":null}")) {
            assertThatThrownBy(() -> Commands.version(JSON.readTree(bad), "v")).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }

    // ----------------------------------------------------------------- settings

    @Test
    void settingsRefuseValuesTheRuntimeCannotWorkWith() {
        GenerationSettings defaults = new GenerationSettings(Duration.ofDays(30), 3, java.math.BigDecimal.valueOf(85), 0.55,
                new GenerationSettings.Worker(Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ofSeconds(2), 4, Duration.ofMinutes(10)),
                new GenerationSettings.Step(3, Duration.ofSeconds(5), Duration.ofMinutes(2), Duration.ofMinutes(6), Duration.ofHours(1)),
                new GenerationSettings.Stream(Duration.ofMillis(750), 24_576),
                new GenerationSettings.Context(200, 40, 40, 2_500, 6_000, 12_000, 5_000),
                new GenerationSettings.Retention(Duration.ofMinutes(10), Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(1), 50));
        assertThat(defaults.maxActiveSessions()).isEqualTo(3);
        assertThatThrownBy(() -> new GenerationSettings(Duration.ZERO, 3, java.math.BigDecimal.ONE, 0.5, defaults.worker(), defaults.step(),
                defaults.stream(), defaults.context(), defaults.retention())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings(Duration.ofDays(1), 0, java.math.BigDecimal.ONE, 0.5, defaults.worker(), defaults.step(),
                defaults.stream(), defaults.context(), defaults.retention())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings(Duration.ofDays(1), 3, java.math.BigDecimal.ZERO, 0.5, defaults.worker(), defaults.step(),
                defaults.stream(), defaults.context(), defaults.retention())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings(Duration.ofDays(1), 3, java.math.BigDecimal.ONE, 1.5, defaults.worker(), defaults.step(),
                defaults.stream(), defaults.context(), defaults.retention())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Retention(Duration.ZERO, Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(1), 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Retention(Duration.ofMinutes(1), Duration.ofDays(1), Duration.ZERO, Duration.ofDays(1), 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Retention(Duration.ofMinutes(1), Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        // the heartbeat must be shorter than the lease it renews
        assertThatThrownBy(() -> new GenerationSettings.Worker(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(2), 4,
                Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Worker(Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ofSeconds(2), 0,
                Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Step(0, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMinutes(1), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Step(3, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMinutes(1), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Step(3, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofHours(2), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        // a step lifetime shorter than one run, or longer than a day, makes no sense
        assertThatThrownBy(() -> new GenerationSettings.Step(3, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMinutes(6), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Stream(Duration.ofMillis(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Context(0, 40, 40, 2_500, 6_000, 12_000, 5_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationSettings.Context(200, 40, 40, 2_500, 1_000, 12_000, 5_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeRolesAcceptApiWorkerAndAllAndRefuseAnythingElse() {
        assertThat(new RuntimeRoles("all").runsWorker()).isTrue();
        assertThat(new RuntimeRoles(" Worker ").runsWorker()).isTrue();
        assertThat(new RuntimeRoles("api").runsWorker()).isFalse();
        assertThat(new RuntimeRoles("API").value()).isEqualTo("api");
        for (String bad : new String[] {"", "none", "workers", null}) {
            assertThatThrownBy(() -> new RuntimeRoles(bad)).isInstanceOf(IllegalStateException.class);
        }
    }

    // ------------------------------------------------------------- spec reader

    @Test
    void theSpecReaderPicksTheSessionPartsOfAValidatedSpec() {
        JsonNode spec = JSON.readTree("""
                {"kind":"MATERIALS","outputLanguage":"ja","prompt":"тема","sources":[
                  {"role":"SOURCE","type":"NOTE","noteId":"20700000-0000-4000-8000-000000000001","noteRowVersion":"3"},
                  {"role":"SOURCE","type":"NOTE","noteId":"20700000-0000-4000-8000-000000000002","noteRowVersion":"0"},
                  {"role":"SOURCE","type":"ITEM","memberKey":"44444444-4444-4444-8444-444444444444","itemRevisionId":"55555555-5555-4555-8555-555555555555"},
                  {"role":"STYLE_EXAMPLE","type":"ITEM","memberKey":"44444444-4444-4444-8444-444444444441","itemRevisionId":"55555555-5555-4555-8555-555555555551"}],
                 "settings":{"media":{"audio":{"enabled":true,"lang":"ja","voice":"female"},"imageSearch":true},"similarToDeck":true,"factCheck":true}}
                """);
        MaterialsSpec parsed = MaterialsSpec.read(spec);
        assertThat(parsed.outputLanguage()).isEqualTo("ja");
        assertThat(parsed.notes()).hasSize(2);
        assertThat(parsed.notes().get(0).noteRowVersion()).isEqualTo(3L);
        assertThat(parsed.items()).hasSize(1);
        assertThat(parsed.styleExamples()).hasSize(1);
        assertThat(parsed.artifactCount()).isEqualTo(2);
        assertThat(parsed.maxMedia()).isEqualTo(2);
        assertThat(parsed.audio()).isTrue();
        assertThat(parsed.audioLanguage()).isEqualTo("ja");
        assertThat(parsed.similarToDeck()).isTrue();
        assertThat(parsed.factCheck()).isTrue();
        // no planner: AUTO is written and charged as MEDIUM
        assertThat(parsed.effort()).isEqualTo("AUTO");
        assertThat(parsed.workingEffort()).isEqualTo("MEDIUM");

        MaterialsSpec plain = MaterialsSpec.read(JSON.readTree("{\"kind\":\"MATERIALS\",\"prompt\":\"p\"}"));
        assertThat(plain.outputLanguage()).isEqualTo("ru");
        assertThat(plain.artifactCount()).isOne();
        assertThat(plain.maxMedia()).isZero();
        assertThat(plain.mergeNotes()).isFalse();
        MaterialsSpec merged = MaterialsSpec.read(JSON.readTree("""
                {"kind":"MATERIALS","settings":{"notesMode":"MERGE_INTO_ONE","effort":"DETAILED"},"sources":[
                  {"role":"SOURCE","type":"NOTE","noteId":"20700000-0000-4000-8000-000000000001","noteRowVersion":"3"},
                  {"role":"SOURCE","type":"NOTE","noteId":"20700000-0000-4000-8000-000000000002","noteRowVersion":"3"}]}
                """));
        assertThat(merged.artifactCount()).isOne();
        assertThat(merged.workingEffort()).isEqualTo("DETAILED");
        assertThat(AdmissionPricing.materialOperation("SHORT")).isEqualTo("MATERIAL_SHORT");
        assertThat(AdmissionPricing.materialOperation("MEDIUM")).isEqualTo("MATERIAL_MEDIUM");
        assertThat(AdmissionPricing.materialOperation("DETAILED")).isEqualTo("MATERIAL_DETAILED");
        assertThat(AdmissionPricing.materialOperation("AUTO")).isEqualTo("MATERIAL_MEDIUM");
    }
}
