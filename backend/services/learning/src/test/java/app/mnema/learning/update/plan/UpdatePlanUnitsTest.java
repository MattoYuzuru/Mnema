package app.mnema.learning.update.plan;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pure parts of the update plan: the vocabulary, the pins and the cursor, the page size, the node highlight and the one-line titles. */
class UpdatePlanUnitsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID DECK = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void theKindsAreStrictAndTheUnitIdIsTheKindAndTheLowerCaseKey() {
        assertThat(UnitKind.fromWire("MATERIAL")).contains(UnitKind.MATERIAL);
        for (String bad : new String[] {"material", "Material", " MATERIAL", "MATERIAL ", "", "ITEM", "NEW"}) assertThat(UnitKind.fromWire(bad)).as(bad).isEmpty();
        assertThat(UnitKind.fromWire(null)).isEmpty();
        assertThat(UnitKind.EXERCISE.unitId(UUID.fromString("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))).isEqualTo("EXERCISE:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(UnitKind.values()).containsExactly(UnitKind.METADATA, UnitKind.MATERIAL, UnitKind.EXERCISE, UnitKind.OBJECTIVE, UnitKind.ORDER);
        assertThat(UnitKind.METADATA.deckWide()).isTrue();
        assertThat(UnitKind.ORDER.deckWide()).isTrue();
        assertThat(UnitKind.MATERIAL.deckWide()).isFalse();
        assertThat(UnitClass.values()).containsExactly(UnitClass.NEW, UnitClass.SAFE, UnitClass.CONFLICT, UnitClass.DELETED, UnitClass.ASK_AGAIN);
    }

    @Test
    void theDefaultChoiceFollowsTheClass() {
        assertThat(PlanUnit.defaultFor(UnitClass.NEW)).isEqualTo(Choice.ACCEPT);
        assertThat(PlanUnit.defaultFor(UnitClass.SAFE)).isEqualTo(Choice.ACCEPT);
        assertThat(PlanUnit.defaultFor(UnitClass.CONFLICT)).isEqualTo(Choice.KEEP_MINE);
        assertThat(PlanUnit.defaultFor(UnitClass.ASK_AGAIN)).isEqualTo(Choice.KEEP_MINE);
        assertThat(PlanUnit.defaultFor(UnitClass.DELETED)).isEqualTo(Choice.KEEP);
    }

    private static PlanUnit unit(UnitKind kind, UnitClass unitClass) {
        return new PlanUnit(kind, UUID.randomUUID(), unitClass, null, null, null, null, List.of(), PlanUnit.defaultFor(unitClass));
    }

    @Test
    void theSummaryCountsTheWholePlanByClassAndOnlyMaterialsAndExercisesAreNew() {
        List<PlanUnit> units = List.of(unit(UnitKind.MATERIAL, UnitClass.NEW), unit(UnitKind.MATERIAL, UnitClass.NEW), unit(UnitKind.EXERCISE, UnitClass.NEW),
                unit(UnitKind.MATERIAL, UnitClass.SAFE), unit(UnitKind.OBJECTIVE, UnitClass.SAFE), unit(UnitKind.METADATA, UnitClass.SAFE), unit(UnitKind.ORDER, UnitClass.CONFLICT),
                unit(UnitKind.EXERCISE, UnitClass.DELETED), unit(UnitKind.MATERIAL, UnitClass.ASK_AGAIN), unit(UnitKind.MATERIAL, UnitClass.ASK_AGAIN));
        assertThat(UpdatePlan.Summary.of(units)).isEqualTo(new UpdatePlan.Summary(2, 1, 3, 1, 1, 2));
        assertThat(UpdatePlan.Summary.of(List.of())).isEqualTo(new UpdatePlan.Summary(0, 0, 0, 0, 0, 0));
        UpdatePlan closed = UpdatePlan.closed(UpdatePlan.State.DETACHED, DECK);
        assertThat(closed.hasPins()).isFalse();
        assertThat(closed.units()).isEmpty();
        assertThat(closed.planVersion()).isNull();
    }

    @Test
    void theDependenciesOfAUnitAreAnImmutableCopy() {
        List<String> mutable = new java.util.ArrayList<>(List.of("MATERIAL:x"));
        PlanUnit unit = new PlanUnit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.NEW, null, null, null, null, mutable, Choice.ACCEPT);
        mutable.add("OBJECTIVE:y");
        assertThat(unit.dependsOn()).containsExactly("MATERIAL:x");
        assertThatThrownBy(() -> unit.dependsOn().add("z")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(unit.with(UnitClass.CONFLICT, Reason.MATERIAL_DELETED, List.of("a")).defaultChoice()).isEqualTo(Choice.KEEP_MINE);
    }

    private static Decision pending(UUID key, UUID base, boolean baseAbsent, UUID offered) {
        return new Decision(UnitKind.MATERIAL, key, Decision.Verdict.PENDING, offered, false, base, baseAbsent);
    }

    @Test
    void thePlanVersionIsADigestOfTheThreeHeadsAndTheDecisions() {
        UUID head = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String version = PlanVersion.of(head, published, base, List.of());
        assertThat(version).matches("sha256:[0-9a-f]{64}");
        assertThat(PlanVersion.valid(version)).isTrue();
        assertThat(PlanVersion.of(head, published, base, List.of())).isEqualTo(version);
        assertThat(PlanVersion.of(UUID.randomUUID(), published, base, List.of())).isNotEqualTo(version);
        assertThat(PlanVersion.of(head, UUID.randomUUID(), base, List.of())).isNotEqualTo(version);
        assertThat(PlanVersion.of(head, published, UUID.randomUUID(), List.of())).isNotEqualTo(version);
        // the head, the published revision and the base are not interchangeable
        assertThat(PlanVersion.of(published, head, base, List.of())).isNotEqualTo(version);
        String one = PlanVersion.of(head, published, base, List.of(pending(key, base, false, published)));
        assertThat(one).isNotEqualTo(version);
        assertThat(PlanVersion.of(head, published, base, List.of(pending(key, base, true, published)))).isNotEqualTo(one);
        assertThat(PlanVersion.of(head, published, base, List.of(pending(key, UUID.randomUUID(), false, published)))).isNotEqualTo(one);
        assertThat(PlanVersion.of(head, published, base, List.of(pending(key, base, false, UUID.randomUUID())))).isNotEqualTo(one);
        assertThat(PlanVersion.of(head, published, base, List.of(new Decision(UnitKind.MATERIAL, key, Decision.Verdict.KEEP_MINE, published, false, null, false)))).isNotEqualTo(one);
        assertThat(PlanVersion.of(head, published, base, List.of(new Decision(UnitKind.EXERCISE, key, Decision.Verdict.PENDING, published, false, base, false)))).isNotEqualTo(one);
        for (String bad : new String[] {null, "", "sha256:", "sha256:" + "0".repeat(63), "sha256:" + "0".repeat(65), "sha256:" + "A".repeat(64), "sha1:" + "0".repeat(64), "0".repeat(71)}) {
            assertThat(PlanVersion.valid(bad)).as(String.valueOf(bad)).isFalse();
        }
    }

    @Test
    void aCursorRoundTripsAndEveryOtherShapeIsRefused() {
        String version = PlanVersion.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of());
        PlanVersion.Cursor cursor = new PlanVersion.Cursor(DECK, version, 150);
        String encoded = cursor.encode();
        assertThat(encoded).matches("[A-Za-z0-9_-]+").hasSizeLessThanOrEqualTo(200);
        assertThat(PlanVersion.Cursor.decode(encoded, DECK)).isEqualTo(cursor);
        assertThat(PlanVersion.Cursor.decode(null, DECK)).isNull();
        // another copy, an empty or oversized one, text that is not base64url, a shape that is not ours, a leading zero and a non-canonical spelling
        assertThatThrownBy(() -> PlanVersion.Cursor.decode(encoded, UUID.randomUUID())).isInstanceOf(InvalidRequestException.class);
        for (String bad : new String[] {"", "A".repeat(201), "%%", "Zm9v", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((DECK + "/" + "0".repeat(64) + "/01").getBytes()),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((DECK + "/" + "0".repeat(64) + "/-1").getBytes()),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((DECK + "/" + "0".repeat(64)).getBytes()),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("not-a-uuid/" + "0".repeat(64) + "/1").getBytes()),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((DECK + "/" + "G".repeat(64) + "/1").getBytes()),
                java.util.Base64.getEncoder().encodeToString((DECK + "/" + "0".repeat(64) + "/1").getBytes())}) {
            assertThatThrownBy(() -> PlanVersion.Cursor.decode(bad, DECK)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void thePageSizeIsOneToOneHundredAndStrict() {
        assertThat(UpdatePlanService.pageSize(null)).isEqualTo(50);
        assertThat(UpdatePlanService.pageSize("1")).isEqualTo(1);
        assertThat(UpdatePlanService.pageSize("100")).isEqualTo(100);
        for (String bad : new String[] {"0", "101", "-1", "01", "1.5", "abc", "", " 1", "1 ", "1000", "٣"}) {
            assertThatThrownBy(() -> UpdatePlanService.pageSize(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void theSettingsAreValidatedAtStartup() {
        UpdatePlanSettings defaults = new UpdatePlanSettings(java.time.Duration.ofSeconds(3), 200, 2);
        assertThat(defaults.maxFingerprints).isEqualTo(200);
        assertThat(new UpdatePlanSettings(java.time.Duration.ZERO, 0, 1).maxFingerprints).isZero();
        assertThat(new UpdatePlanSettings(java.time.Duration.ofSeconds(8), 100_000, 64).maxConcurrent).isEqualTo(64);
        for (Object[] bad : new Object[][] {{java.time.Duration.ofSeconds(3), -1, 2}, {java.time.Duration.ofSeconds(3), 100_001, 2}, {java.time.Duration.ofSeconds(3), 200, 0},
                {java.time.Duration.ofSeconds(3), 200, 65}, {java.time.Duration.ofSeconds(9), 200, 2}, {java.time.Duration.ofMillis(-1), 200, 2}, {null, 200, 2}}) {
            assertThatThrownBy(() -> new UpdatePlanSettings((java.time.Duration) bad[0], (Integer) bad[1], (Integer) bad[2])).as(java.util.Arrays.toString(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theBudgetStopsAtItsDeadlineAndAtItsPairsAndNeverBeforeTheFirstPair() {
        long[] now = {1_000};
        PlanBudget budget = new PlanBudget(java.time.Duration.ofNanos(500), 2, () -> now[0]);
        assertThat(budget.spent()).isFalse();
        assertThat(budget.takePair()).isTrue();
        now[0] += 499;
        assertThat(budget.spent()).isFalse();
        assertThat(budget.takePair()).isTrue();
        assertThat(budget.takePair()).as("the pairs of the plan are used").isFalse();
        PlanBudget timed = new PlanBudget(java.time.Duration.ofNanos(500), 10, () -> now[0]);
        now[0] += 500;
        assertThat(timed.spent()).isTrue();
        assertThat(timed.takePair()).as("the time is up").isFalse();
        assertThat(new PlanBudget(java.time.Duration.ZERO, 10, () -> now[0]).spent()).isTrue();
        // a clock that wraps around is still compared as a difference
        long[] wrap = {Long.MAX_VALUE - 10};
        PlanBudget wrapped = new PlanBudget(java.time.Duration.ofNanos(100), 1, () -> wrap[0]);
        wrap[0] += 50;
        assertThat(wrapped.spent()).isFalse();
        wrap[0] += 60;
        assertThat(wrapped.spent()).isTrue();
    }

    @Test
    void thePlansMustLeaveAConnectionFreeNextToThePublicReadsAndTheJobs() {
        UpdatePlanConnectionBudget.check(10, 2, 3, 4);
        UpdatePlanConnectionBudget.check(10, 2, 0, 4);
        UpdatePlanConnectionBudget.check(4, 2, 0, 1);
        assertThatThrownBy(() -> UpdatePlanConnectionBudget.check(10, 3, 3, 4)).isInstanceOf(IllegalStateException.class).hasMessageContaining("maximum-pool-size");
        assertThatThrownBy(() -> UpdatePlanConnectionBudget.check(4, 2, 0, 2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UpdatePlanConnectionBudget.check(10, 4, 3, 4)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theBulkheadNeverWaitsAndGivesItsPlacesBack() {
        UpdatePlanBulkhead bulkhead = new UpdatePlanBulkhead(new UpdatePlanSettings(java.time.Duration.ofSeconds(3), 200, 2));
        assertThat(bulkhead.tryAcquire()).isTrue();
        assertThat(bulkhead.tryAcquire()).isTrue();
        assertThat(bulkhead.tryAcquire()).isFalse();
        bulkhead.release();
        assertThat(bulkhead.tryAcquire()).isTrue();
    }

    @Test
    void aDecisionKnowsTheAbsentStatesApartFromTheRevisionIds() {
        UUID revision = UUID.randomUUID();
        Decision removed = new Decision(UnitKind.MATERIAL, UUID.randomUUID(), Decision.Verdict.KEEP_MINE, null, true, null, false);
        assertThat(removed.resolved()).isNull();
        assertThat(removed.pending()).isFalse();
        Decision offered = new Decision(UnitKind.MATERIAL, UUID.randomUUID(), Decision.Verdict.PENDING, revision, false, null, true);
        assertThat(offered.resolved()).isEqualTo(revision);
        assertThat(offered.pendingBase()).isNull();
        assertThat(offered.pending()).isTrue();
        assertThat(new Decision(UnitKind.MATERIAL, UUID.randomUUID(), Decision.Verdict.PENDING, revision, false, revision, false).pendingBase()).isEqualTo(revision);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void theHighlightNamesTheNodesWhoseTextAttributesTypeOrChildrenDifferAndTheOnesOnlyOneSideHas() {
        String a = "11111111-1111-4111-8111-111111111111";
        String b = "22222222-2222-4222-8222-222222222222";
        String c = "33333333-3333-4333-8333-333333333333";
        String d = "44444444-4444-4444-8444-444444444444";
        String root = "55555555-5555-4555-8555-555555555555";
        String before = "{\"formatVersion\":1,\"root\":{\"id\":\"" + root + "\",\"type\":\"doc\",\"version\":1,\"attrs\":{},\"content\":["
                + "{\"id\":\"" + a + "\",\"type\":\"paragraph\",\"version\":1,\"attrs\":{},\"content\":[{\"id\":\"" + b + "\",\"type\":\"text\",\"version\":1,\"attrs\":{\"text\":\"one\"},\"content\":[]}]}]}}";
        // identical: nothing
        assertThat(PlanSides.changedNodes(json(before), json(before))).isEmpty();
        // the text of a leaf changed: only the leaf
        String textual = before.replace("\"one\"", "\"two\"");
        assertThat(PlanSides.changedNodes(json(before), json(textual))).containsExactly(b);
        // a node was added under the paragraph: the new node and the parent whose children changed
        String added = before.replace("\"content\":[]}]}]}}", "\"content\":[]},{\"id\":\"" + c + "\",\"type\":\"text\",\"version\":1,\"attrs\":{\"text\":\"x\"},\"content\":[]}]}]}}");
        assertThat(PlanSides.changedNodes(json(before), json(added))).containsExactlyInAnyOrder(a, c);
        assertThat(PlanSides.changedNodes(json(added), json(before))).containsExactlyInAnyOrder(a, c);
        // a node's type changed
        String retyped = before.replace("\"type\":\"paragraph\"", "\"type\":\"heading\"");
        assertThat(PlanSides.changedNodes(json(before), json(retyped))).containsExactly(a);
        // a whole other document: both sides' nodes
        String other = before.replace(a, d);
        assertThat(PlanSides.changedNodes(json(before), json(other))).containsExactlyInAnyOrder(a, d, root);
    }

    @Test
    void aPromptIsThePlainQuestionAndNeverAnythingThatIsAnswerOrReference() {
        assertThat(PlanSides.prompt(json("{\"prompt\":[{\"kind\":\"TEXT\",\"text\":\"Что   значит\\nhablar?\"},{\"kind\":\"MATERIAL\",\"nodeId\":\"x\"},{\"kind\":\"TEXT\",\"text\":\"Ответьте.\"}],"
                + "\"reference\":[{\"kind\":\"TEXT\",\"text\":\"говорить\"}]}"))).isEqualTo("Что значит hablar? Ответьте.");
        assertThat(PlanSides.prompt(json("{\"prompt\":[],\"passage\":[{\"kind\":\"TEXT\",\"text\":\"Yo\"},{\"kind\":\"BLANK\",\"id\":\"b\"},{\"kind\":\"TEXT\",\"text\":\"español\"}]}"))).isEqualTo("Yo … español");
        assertThat(PlanSides.prompt(json("{\"prompt\":[{\"kind\":\"IMAGE\"}]}"))).isEmpty();
        assertThat(PlanSides.prompt(json("{}"))).isEmpty();
    }

    @Test
    void aTitleIsClippedToOneHundredSixtyCodePointsNotUtf16Units() {
        assertThat(PlanSides.clip(null)).isEmpty();
        assertThat(PlanSides.clip("короткий")).isEqualTo("короткий");
        String exact = "я".repeat(160);
        assertThat(PlanSides.clip(exact)).isEqualTo(exact);
        String clipped = PlanSides.clip("я".repeat(161));
        assertThat(clipped.codePointCount(0, clipped.length())).isEqualTo(160);
        assertThat(clipped).endsWith("…");
        // supplementary characters count once and are never cut in the middle
        String emoji = "😀".repeat(200);
        String clippedEmoji = PlanSides.clip(emoji);
        assertThat(clippedEmoji.codePointCount(0, clippedEmoji.length())).isEqualTo(160);
        assertThat(clippedEmoji.codePoints().limit(159).allMatch(point -> point == 0x1F600)).isTrue();
    }
}
