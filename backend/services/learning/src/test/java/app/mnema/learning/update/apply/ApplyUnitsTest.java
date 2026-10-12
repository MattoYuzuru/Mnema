package app.mnema.learning.update.apply;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.update.apply.ApplyRows.Op;
import app.mnema.learning.update.apply.ApplyRows.Row;
import app.mnema.learning.update.apply.ApplySelection.Fate;
import app.mnema.learning.update.plan.PlanUnit;
import app.mnema.learning.update.plan.Reason;
import app.mnema.learning.update.plan.UnitClass;
import app.mnema.learning.update.plan.UnitKind;
import app.mnema.learning.update.plan.UpdatePlan;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Updates/4 (#443), the pure parts of the apply command: the strict body, the choices per class, «accept all safe», dependencies, the decision rows and the derived receipt id. */
class ApplyUnitsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VERSION = "sha256:" + "0123456789abcdef".repeat(4);
    private static final UUID DECK = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static ApplyCommand read(String body) {
        return ApplyCommand.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String command(String extra) {
        return "{\"commandId\":\"" + UUID.randomUUID() + "\",\"planVersion\":\"" + VERSION + "\"" + extra + "}";
    }

    // ---- the contract ----

    private static JsonNode contract() {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("contracts/decks/updates.json"))) root = root.getParent();
        try {
            return JSON.readTree(java.nio.file.Files.readString(root.resolve("contracts/decks/updates.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void theContractNamesTheChoicesTheLimitsAndTheRequestTheCodeTakes() {
        JsonNode constants = contract().path("constants");
        assertThat(constants.path("applyChoices").valueStream().map(node -> node.stringValue(null)).toList())
                .containsExactlyElementsOf(java.util.Arrays.stream(ApplyChoice.values()).map(Enum::name).toList());
        assertThat(constants.path("applyMaxBytes").intValue()).isEqualTo(ApplyCommand.MAX_REQUEST_BYTES);
        for (UnitClass unitClass : UnitClass.values()) {
            List<String> allowed = java.util.Arrays.stream(ApplyChoice.values()).filter(choice -> ApplySelection.allowed(unitClass, choice)).map(Enum::name).toList();
            assertThat(constants.path("choicesPerClass").path(unitClass.name()).valueStream().map(node -> node.stringValue(null)).toList()).as(unitClass.name()).isEqualTo(allowed);
        }
        // the first choice of the contract's list takes the author's version for every class, and the default of the review screen is one of the two
        constants.path("choicesPerClass").properties().forEach(entry -> {
            assertThat(entry.getValue().get(0).stringValue(null)).isIn("ACCEPT", "DELETE");
            assertThat(entry.getValue().valueStream().map(node -> node.stringValue(null)).toList()).contains(constants.path("defaultChoices").path(entry.getKey()).stringValue(null));
        });
        assertThat(constants.path("applyStates").valueStream().map(node -> node.stringValue(null)).toList()).containsExactly("APPLIED", "APPLYING");
        assertThat(constants.path("states").valueStream().map(node -> node.stringValue(null)).toList()).doesNotContain("APPLYING");
        assertThat(constants.path("applyingState").stringValue(null)).isEqualTo("APPLYING");
        assertThat(constants.path("applyReasons").valueStream().map(node -> node.stringValue(null)).toList())
                .containsExactlyInAnyOrder("unknown_unit", "choice_not_allowed", "duplicate_unit", "exercise_needs_material", "dependency_declined", "material_still_used",
                        "publication_refused");
        // the example request is a request the code reads
        JsonNode example = contract().path("apply").path("request").path("body");
        ApplyCommand parsed = read(example.toString());
        assertThat(parsed.commandId().toString()).isEqualTo(example.path("commandId").stringValue(null));
        assertThat(parsed.acceptSafe()).isTrue();
        assertThat(parsed.choices()).hasSize(3);
        assertThat(parsed.envelope(DECK).path("choices")).hasSize(3);
    }

    // ---- the body ----

    @Test
    void theBodyIsStrictAndBounded() {
        UUID key = UUID.randomUUID();
        ApplyCommand parsed = read(command(",\"acceptSafe\":true,\"choices\":[{\"unitId\":\"MATERIAL:" + key + "\",\"choice\":\"ACCEPT\"},{\"unitId\":\"ORDER:" + DECK
                + "\",\"choice\":\"KEEP_MINE\"}]"));
        assertThat(parsed.acceptSafe()).isTrue();
        assertThat(parsed.choices()).extracting(ApplyCommand.Pick::unitId, pick -> pick.choice()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("MATERIAL:" + key, ApplyChoice.ACCEPT), org.assertj.core.groups.Tuple.tuple("ORDER:" + DECK, ApplyChoice.KEEP_MINE));
        ApplyCommand minimal = read(command(""));
        assertThat(minimal.acceptSafe()).isFalse();
        assertThat(minimal.choices()).isEmpty();

        String unit = "MATERIAL:" + key;
        List<String> broken = List.of(
                "{}", "[]", "{\"commandId\":\"" + UUID.randomUUID() + "\"}", command(",\"extra\":1"), command(",\"acceptSafe\":\"yes\""), command(",\"acceptSafe\":null"),
                command(",\"choices\":{}"), command(",\"choices\":[1]"), command(",\"choices\":[{\"unitId\":\"" + unit + "\"}]"),
                command(",\"choices\":[{\"unitId\":\"" + unit + "\",\"choice\":\"accept\"}]"), command(",\"choices\":[{\"unitId\":\"" + unit + "\",\"choice\":\"MAYBE\"}]"),
                command(",\"choices\":[{\"unitId\":\"" + unit + "\",\"choice\":\"ACCEPT\",\"revisionId\":\"" + key + "\"}]"),
                command(",\"choices\":[{\"unitId\":\"MATERIAL:" + key.toString().toUpperCase() + "\",\"choice\":\"ACCEPT\"}]"),
                command(",\"choices\":[{\"unitId\":\"material:" + key + "\",\"choice\":\"ACCEPT\"}]"), command(",\"choices\":[{\"unitId\":\"" + key + "\",\"choice\":\"ACCEPT\"}]"),
                command(",\"choices\":[{\"unitId\":\"MATERIAL:\",\"choice\":\"ACCEPT\"}]"), command(",\"choices\":[{\"unitId\":\"WEIRD:" + key + "\",\"choice\":\"ACCEPT\"}]"),
                // a unit named twice, even with the same choice
                command(",\"choices\":[{\"unitId\":\"" + unit + "\",\"choice\":\"ACCEPT\"},{\"unitId\":\"" + unit + "\",\"choice\":\"ACCEPT\"}]"),
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"commandId\":\"" + UUID.randomUUID() + "\",\"planVersion\":\"" + VERSION + "\"}",
                "{\"commandId\":\"not-a-uuid\",\"planVersion\":\"" + VERSION + "\"}", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"planVersion\":\"sha256:xyz\"}",
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"planVersion\":\"" + VERSION.toUpperCase() + "\"}",
                "{\"commandId\":\"" + new UUID(0, 0) + "\",\"planVersion\":\"" + VERSION + "\"}",
                // a command id must be UUIDv4 or v7
                "{\"commandId\":\"" + UUID.nameUUIDFromBytes(new byte[] {1}) + "\",\"planVersion\":\"" + VERSION + "\"}");
        for (String body : broken) {
            assertThatThrownBy(() -> read(body)).as(body).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(UuidPolicy.requireCommandId(UUID.randomUUID())).isNotNull();
        // above 64 KiB: refused before it is parsed
        String huge = command(",\"choices\":[" + ("{\"unitId\":\"ORDER:" + DECK + "\",\"choice\":\"ACCEPT\"},").repeat(1_000) + "{\"unitId\":\"ORDER:" + DECK + "\",\"choice\":\"ACCEPT\"}]");
        assertThat(huge.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(ApplyCommand.MAX_REQUEST_BYTES);
        assertThatThrownBy(() -> read(huge)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ApplyCommand.read(new ByteArrayInputStream(new byte[0]))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theEnvelopeDoesNotDependOnTheOrderTheChoicesWereListedIn() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        ApplyCommand one = new ApplyCommand(id, VERSION, true, List.of(new ApplyCommand.Pick(UnitKind.MATERIAL, a, ApplyChoice.ACCEPT), new ApplyCommand.Pick(UnitKind.EXERCISE, b, ApplyChoice.DECLINE)));
        ApplyCommand other = new ApplyCommand(id, VERSION, true, List.of(new ApplyCommand.Pick(UnitKind.EXERCISE, b, ApplyChoice.DECLINE), new ApplyCommand.Pick(UnitKind.MATERIAL, a, ApplyChoice.ACCEPT)));
        assertThat(one.envelope(DECK)).isEqualTo(other.envelope(DECK));
        ApplyCommand different = new ApplyCommand(id, VERSION, false, one.choices());
        assertThat(different.envelope(DECK)).isNotEqualTo(one.envelope(DECK));
        assertThat(one.envelope(UUID.randomUUID())).as("the copy is part of the command").isNotEqualTo(one.envelope(DECK));
    }

    // ---- the receipt of the answer ----

    @Test
    void theReceiptOfTheAnswerLivesUnderAnIdDerivedFromTheClientsIdThatIsAValidCommandId() {
        UUID client = UUID.randomUUID();
        UUID derived = ApplyReceipt.derive(client);
        assertThat(derived).isEqualTo(ApplyReceipt.derive(client)).isNotEqualTo(client).isNotEqualTo(ApplyReceipt.derive(UUID.randomUUID()));
        assertThat(derived.version()).isEqualTo(4);
        assertThat(derived.variant()).isEqualTo(2);
        assertThat(UuidPolicy.requireCommandId(derived)).isEqualTo(derived);
        assertThat(ApplyReceipt.identity(UUID.randomUUID(), client).commandId()).isEqualTo(derived);
    }

    // ---- the choices of the plan ----

    private static PlanUnit unit(UnitKind kind, UnitClass unitClass, UUID author, UUID mine, UUID base, String... dependsOn) {
        return unit(kind, UUID.randomUUID(), unitClass, null, author, mine, base, dependsOn);
    }

    private static PlanUnit unit(UnitKind kind, UUID key, UnitClass unitClass, Reason reason, UUID author, UUID mine, UUID base, String... dependsOn) {
        return new PlanUnit(kind, key, unitClass, reason, author, mine, base, List.of(dependsOn), ApplyOpsTest.defaultFor(unitClass));
    }

    private static UpdatePlan plan(PlanUnit... units) {
        return new UpdatePlan(UpdatePlan.State.READY, DECK, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), VERSION, null, new UpdatePlan.Summary(0, 0, 0, 0, 0, 0),
                List.of(units));
    }

    private static ApplyCommand.Pick pick(PlanUnit unit, ApplyChoice choice) {
        return new ApplyCommand.Pick(unit.kind(), unit.key(), choice);
    }

    private static ApplySelection select(UpdatePlan plan, boolean acceptSafe, ApplyCommand.Pick... picks) {
        return select(plan, Map.of(), acceptSafe, picks);
    }

    private static ApplySelection select(UpdatePlan plan, Map<UUID, Set<UUID>> bound, boolean acceptSafe, ApplyCommand.Pick... picks) {
        return ApplySelection.of(plan, new ApplyCommand(UUID.randomUUID(), VERSION, acceptSafe, List.of(picks)), bound);
    }

    private static Object reason(InvalidRequestException failure, String member) {
        return failure.extension().members().get(member);
    }

    @Test
    void everyClassTakesExactlyItsChoices() {
        assertThat(java.util.Arrays.stream(ApplyChoice.values()).filter(choice -> ApplySelection.allowed(UnitClass.NEW, choice))).containsExactly(ApplyChoice.ACCEPT, ApplyChoice.DECLINE);
        assertThat(java.util.Arrays.stream(ApplyChoice.values()).filter(choice -> ApplySelection.allowed(UnitClass.SAFE, choice))).containsExactly(ApplyChoice.ACCEPT, ApplyChoice.DECLINE);
        assertThat(java.util.Arrays.stream(ApplyChoice.values()).filter(choice -> ApplySelection.allowed(UnitClass.CONFLICT, choice))).containsExactly(ApplyChoice.ACCEPT, ApplyChoice.KEEP_MINE);
        assertThat(java.util.Arrays.stream(ApplyChoice.values()).filter(choice -> ApplySelection.allowed(UnitClass.ASK_AGAIN, choice))).containsExactly(ApplyChoice.ACCEPT, ApplyChoice.KEEP_MINE);
        assertThat(java.util.Arrays.stream(ApplyChoice.values()).filter(choice -> ApplySelection.allowed(UnitClass.DELETED, choice))).containsExactly(ApplyChoice.DELETE, ApplyChoice.KEEP);

        PlanUnit safe = unit(UnitKind.MATERIAL, UnitClass.SAFE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PlanUnit conflict = unit(UnitKind.MATERIAL, UnitClass.CONFLICT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PlanUnit deleted = unit(UnitKind.MATERIAL, UnitClass.DELETED, null, UUID.randomUUID(), UUID.randomUUID());
        UpdatePlan plan = plan(safe, conflict, deleted);
        for (ApplyCommand.Pick wrong : List.of(pick(safe, ApplyChoice.KEEP_MINE), pick(safe, ApplyChoice.DELETE), pick(safe, ApplyChoice.KEEP), pick(conflict, ApplyChoice.DECLINE),
                pick(conflict, ApplyChoice.DELETE), pick(deleted, ApplyChoice.ACCEPT), pick(deleted, ApplyChoice.KEEP_MINE), pick(deleted, ApplyChoice.DECLINE))) {
            assertThatThrownBy(() -> select(plan, false, wrong)).as(wrong.toString()).isInstanceOfSatisfying(InvalidRequestException.class, failure -> {
                assertThat(reason(failure, "reason")).isEqualTo("choice_not_allowed");
                assertThat(reason(failure, "unitId")).isEqualTo(wrong.unitId());
            });
        }
        assertThatThrownBy(() -> select(plan, false, new ApplyCommand.Pick(UnitKind.MATERIAL, UUID.randomUUID(), ApplyChoice.ACCEPT))).isInstanceOfSatisfying(InvalidRequestException.class,
                failure -> assertThat(reason(failure, "reason")).isEqualTo("unknown_unit"));
        // a unit of another kind with the same key is another unit
        assertThatThrownBy(() -> select(plan, false, new ApplyCommand.Pick(UnitKind.EXERCISE, safe.key(), ApplyChoice.ACCEPT))).isInstanceOf(InvalidRequestException.class);
        ApplySelection chosen = select(plan, false, pick(safe, ApplyChoice.DECLINE), pick(conflict, ApplyChoice.ACCEPT), pick(deleted, ApplyChoice.KEEP));
        assertThat(chosen.fate(safe)).isEqualTo(Fate.DECLINE);
        assertThat(chosen.fate(conflict)).isEqualTo(Fate.ACCEPT);
        assertThat(chosen.fate(deleted)).isEqualTo(Fate.DECLINE);
        assertThat(chosen.also()).isZero();
    }

    @Test
    void anExerciseThatNeedsAMaterialTheCopyDeletedCannotTakeTheAuthorsVersion() {
        PlanUnit exercise = unit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.CONFLICT, Reason.MATERIAL_DELETED, UUID.randomUUID(), null, null);
        UpdatePlan plan = plan(exercise);
        assertThatThrownBy(() -> select(plan, false, pick(exercise, ApplyChoice.ACCEPT))).isInstanceOfSatisfying(InvalidRequestException.class,
                failure -> assertThat(reason(failure, "reason")).isEqualTo("exercise_needs_material"));
        assertThat(select(plan, true).fate(exercise)).as("accept all safe never touches a conflict").isEqualTo(Fate.PENDING);
        assertThat(select(plan, false, pick(exercise, ApplyChoice.KEEP_MINE)).fate(exercise)).isEqualTo(Fate.DECLINE);
    }

    @Test
    void acceptAllSafeTakesNewAndSafeUnitsOnlyAndNeverWhatTheChoicesName() {
        PlanUnit added = unit(UnitKind.MATERIAL, UnitClass.NEW, UUID.randomUUID(), null, null);
        PlanUnit fix = unit(UnitKind.MATERIAL, UnitClass.SAFE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PlanUnit declined = unit(UnitKind.EXERCISE, UnitClass.SAFE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PlanUnit title = unit(UnitKind.METADATA, DECK, UnitClass.SAFE, null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PlanUnit conflict = unit(UnitKind.MATERIAL, UnitClass.CONFLICT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PlanUnit removal = unit(UnitKind.MATERIAL, UnitClass.DELETED, null, UUID.randomUUID(), UUID.randomUUID());
        PlanUnit again = unit(UnitKind.MATERIAL, UnitClass.ASK_AGAIN, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PlanUnit order = unit(UnitKind.ORDER, DECK, UnitClass.CONFLICT, Reason.BOTH_CHANGED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UpdatePlan plan = plan(added, fix, declined, title, conflict, removal, again, order);
        ApplySelection selection = select(plan, true, pick(declined, ApplyChoice.DECLINE));
        assertThat(selection.fate(added)).isEqualTo(Fate.ACCEPT);
        assertThat(selection.fate(fix)).isEqualTo(Fate.ACCEPT);
        assertThat(selection.fate(title)).isEqualTo(Fate.ACCEPT);
        assertThat(selection.fate(declined)).isEqualTo(Fate.DECLINE);
        for (PlanUnit left : List.of(conflict, removal, again, order)) assertThat(selection.fate(left)).as(left.unitId()).isEqualTo(Fate.PENDING);
        assertThat(selection.count(Fate.ACCEPT)).isEqualTo(3);
        assertThat(selection.count(Fate.DECLINE)).isEqualTo(1);
        assertThat(selection.count(Fate.PENDING)).isEqualTo(4);
        assertThat(selection.also()).isZero();
        // without the flag nothing is taken by itself
        assertThat(select(plan, false).count(Fate.ACCEPT)).isZero();
        assertThat(select(plan, false).count(Fate.PENDING)).isEqualTo(8);
        // and the flag does not override a named choice to take
        assertThat(select(plan, true, pick(conflict, ApplyChoice.ACCEPT), pick(removal, ApplyChoice.DELETE)).count(Fate.ACCEPT)).isEqualTo(6);
    }

    @Test
    void takingAUnitTakesItsDependenciesAndANamedRefusalOfOneIsRefused() {
        PlanUnit material = unit(UnitKind.MATERIAL, UnitClass.NEW, UUID.randomUUID(), null, null);
        PlanUnit objective = unit(UnitKind.OBJECTIVE, UnitClass.CONFLICT, UUID.randomUUID(), UUID.randomUUID(), null);
        PlanUnit exercise = unit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.CONFLICT, Reason.OBJECTIVE_RENAMED, UUID.randomUUID(), null, null, material.unitId(), objective.unitId());
        UpdatePlan plan = plan(material, objective, exercise);

        ApplySelection selection = select(plan, false, pick(exercise, ApplyChoice.ACCEPT));
        assertThat(selection.fate(exercise)).isEqualTo(Fate.ACCEPT);
        assertThat(selection.fate(material)).as("a dependency, even of another class").isEqualTo(Fate.ACCEPT);
        assertThat(selection.fate(objective)).isEqualTo(Fate.ACCEPT);
        assertThat(selection.also()).isEqualTo(2);
        // a dependency taken by name too is not "also"
        assertThat(select(plan, false, pick(exercise, ApplyChoice.ACCEPT), pick(material, ApplyChoice.ACCEPT)).also()).isEqualTo(1);

        assertThatThrownBy(() -> select(plan, false, pick(exercise, ApplyChoice.ACCEPT), pick(objective, ApplyChoice.KEEP_MINE))).isInstanceOfSatisfying(InvalidRequestException.class,
                failure -> {
                    assertThat(reason(failure, "reason")).isEqualTo("dependency_declined");
                    assertThat(reason(failure, "unitId")).isEqualTo(exercise.unitId());
                    assertThat(reason(failure, "dependency")).isEqualTo(objective.unitId());
                });
        assertThatThrownBy(() -> select(plan, true, pick(exercise, ApplyChoice.ACCEPT), pick(material, ApplyChoice.DECLINE))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aUnitTakenOnlyByAcceptAllSafeWaitsWhenItsDependencyWasDeclinedByName() {
        PlanUnit material = unit(UnitKind.MATERIAL, UnitClass.NEW, UUID.randomUUID(), null, null);
        PlanUnit exercise = unit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.NEW, null, UUID.randomUUID(), null, null, material.unitId());
        PlanUnit other = unit(UnitKind.MATERIAL, UnitClass.NEW, UUID.randomUUID(), null, null);
        UpdatePlan plan = plan(material, exercise, other);
        ApplySelection selection = select(plan, true, pick(material, ApplyChoice.DECLINE));
        assertThat(selection.fate(material)).isEqualTo(Fate.DECLINE);
        assertThat(selection.fate(exercise)).isEqualTo(Fate.PENDING);
        assertThat(selection.fate(other)).isEqualTo(Fate.ACCEPT);
        assertThat(selection.also()).isZero();
        // the same plan with the flag and no refusal takes all three, and nothing is "also"
        ApplySelection all = select(plan, true);
        assertThat(all.count(Fate.ACCEPT)).isEqualTo(3);
        assertThat(all.also()).isZero();
        // a dependency chain through a unit that waits is not taken either
        PlanUnit second = unit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.NEW, null, UUID.randomUUID(), null, null, material.unitId());
        assertThat(select(plan(material, exercise, second), true, pick(material, ApplyChoice.DECLINE)).count(Fate.PENDING)).isEqualTo(2);
    }

    @Test
    void anObjectiveMovesOnlyWithTheExercisesThatEvidenceItAndTheEnginesRuleNeverSurfacesAsABare400() {
        PlanUnit objective = unit(UnitKind.OBJECTIVE, UnitClass.SAFE, UUID.randomUUID(), UUID.randomUUID(), null);
        PlanUnit first = unit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.SAFE, null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), objective.unitId());
        PlanUnit second = unit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.SAFE, null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), objective.unitId());
        UpdatePlan plan = plan(objective, first, second);

        // «accept all safe» with one of the exercises declined: the objective waits, and so does the other exercise (it needs the objective)
        ApplySelection declined = select(plan, true, pick(first, ApplyChoice.DECLINE));
        assertThat(declined.fate(objective)).isEqualTo(Fate.PENDING);
        assertThat(declined.fate(first)).isEqualTo(Fate.DECLINE);
        assertThat(declined.fate(second)).isEqualTo(Fate.PENDING);
        // all of them: all three, and nothing is «also»
        ApplySelection all = select(plan, true);
        assertThat(all.count(Fate.ACCEPT)).isEqualTo(3);
        assertThat(all.also()).isZero();
        // an exercise taken by name brings the objective even though the other one was declined (the newest pinned revision is the author's either way)
        ApplySelection named = select(plan, true, pick(first, ApplyChoice.ACCEPT), pick(second, ApplyChoice.DECLINE));
        assertThat(named.fate(objective)).isEqualTo(Fate.ACCEPT);
        assertThat(named.fate(first)).isEqualTo(Fate.ACCEPT);
        assertThat(named.fate(second)).isEqualTo(Fate.DECLINE);

        // the objective taken by name brings every exercise that evidences it
        ApplySelection byName = select(plan, false, pick(objective, ApplyChoice.ACCEPT));
        assertThat(byName.count(Fate.ACCEPT)).isEqualTo(3);
        assertThat(byName.also()).isEqualTo(2);
        // ... unless one of them was declined by name: refused, naming both
        assertThatThrownBy(() -> select(plan, false, pick(objective, ApplyChoice.ACCEPT), pick(second, ApplyChoice.DECLINE))).isInstanceOfSatisfying(InvalidRequestException.class,
                failure -> {
                    assertThat(reason(failure, "reason")).isEqualTo("dependency_declined");
                    assertThat(reason(failure, "unitId")).isEqualTo(objective.unitId());
                    assertThat(reason(failure, "dependency")).isEqualTo(second.unitId());
                });
        // an exercise that cannot take the author's version (its material is gone) cannot come with the objective either
        PlanUnit stuck = unit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.CONFLICT, Reason.MATERIAL_DELETED, UUID.randomUUID(), null, null, objective.unitId());
        assertThatThrownBy(() -> select(plan(objective, first, stuck), false, pick(objective, ApplyChoice.ACCEPT))).isInstanceOfSatisfying(InvalidRequestException.class, failure -> {
            assertThat(reason(failure, "reason")).isEqualTo("exercise_needs_material");
            assertThat(reason(failure, "unitId")).isEqualTo(stuck.unitId());
        });
        // and «accept all safe» leaves an objective whose exercise is a conflict where it is
        PlanUnit conflict = unit(UnitKind.EXERCISE, UUID.randomUUID(), UnitClass.CONFLICT, Reason.MATERIAL_CHANGED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), objective.unitId());
        ApplySelection waiting = select(plan(objective, first, conflict), true);
        assertThat(waiting.fate(objective)).isEqualTo(Fate.PENDING);
        assertThat(waiting.fate(first)).isEqualTo(Fate.PENDING);
    }

    @Test
    void removingAMaterialTakesTheRemovalOfTheExercisesAboutItOrIsRefusedByName() {
        PlanUnit material = unit(UnitKind.MATERIAL, UnitClass.DELETED, null, UUID.randomUUID(), UUID.randomUUID());
        PlanUnit exercise = unit(UnitKind.EXERCISE, UnitClass.DELETED, null, UUID.randomUUID(), UUID.randomUUID());
        Map<UUID, Set<UUID>> bound = Map.of(material.key(), Set.of(exercise.key()));
        UpdatePlan plan = plan(material, exercise);

        ApplySelection pulled = select(plan, bound, false, pick(material, ApplyChoice.DELETE));
        assertThat(pulled.fate(exercise)).as("the author removed it too: it goes with the material").isEqualTo(Fate.ACCEPT);
        assertThat(pulled.also()).isEqualTo(1);
        assertThat(select(plan, bound, false, pick(material, ApplyChoice.DELETE), pick(exercise, ApplyChoice.DELETE)).also()).isZero();
        assertThatThrownBy(() -> select(plan, bound, false, pick(material, ApplyChoice.DELETE), pick(exercise, ApplyChoice.KEEP))).isInstanceOfSatisfying(InvalidRequestException.class,
                failure -> {
                    assertThat(reason(failure, "reason")).isEqualTo("material_still_used");
                    assertThat(reason(failure, "unitId")).isEqualTo(material.unitId());
                    assertThat(reason(failure, "dependency")).isEqualTo(exercise.unitId());
                });
        // the material kept: nothing to say
        assertThat(select(plan, bound, false, pick(material, ApplyChoice.KEEP)).count(Fate.ACCEPT)).isZero();
        // an exercise of the learner's own (not in the plan) keeps the material: refused by the material alone
        assertThatThrownBy(() -> select(plan(material), Map.of(material.key(), Set.of(UUID.randomUUID())), false, pick(material, ApplyChoice.DELETE)))
                .isInstanceOfSatisfying(InvalidRequestException.class, failure -> {
                    assertThat(reason(failure, "reason")).isEqualTo("material_still_used");
                    assertThat(reason(failure, "unitId")).isEqualTo(material.unitId());
                    assertThat(failure.extension().members()).doesNotContainKey("dependency");
                });
        // an exercise the author changed (not removed) is not a removal either
        PlanUnit changed = unit(UnitKind.EXERCISE, UnitClass.SAFE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> select(plan(material, changed), Map.of(material.key(), Set.of(changed.key())), false, pick(material, ApplyChoice.DELETE)))
                .isInstanceOfSatisfying(InvalidRequestException.class, failure -> assertThat(reason(failure, "dependency")).isEqualTo(changed.unitId()));
    }

    // ---- the decision rows ----

    private static Row only(ApplySelection selection, PlanUnit unit) {
        return ApplyRows.build(selection, Set.of()).stream().filter(row -> row.kind() == unit.kind() && row.key().equals(unit.key())).findFirst().orElse(null);
    }

    @Test
    void theRowsRememberTheAuthorsStateAtTheTimeOfTheAnswerAndKeepTheOwnBaseOfAPendingUnit() {
        UUID author = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        PlanUnit newMaterial = unit(UnitKind.MATERIAL, UnitClass.NEW, author, null, null);
        PlanUnit fix = unit(UnitKind.MATERIAL, UnitClass.SAFE, author, UUID.randomUUID(), base);
        PlanUnit conflict = unit(UnitKind.MATERIAL, UnitClass.CONFLICT, author, UUID.randomUUID(), base);
        PlanUnit learnerDeleted = unit(UnitKind.MATERIAL, UnitClass.CONFLICT, UUID.randomUUID(), null, base);
        PlanUnit removed = unit(UnitKind.EXERCISE, UnitClass.DELETED, null, UUID.randomUUID(), base);
        PlanUnit objective = unit(UnitKind.OBJECTIVE, UnitClass.SAFE, author, null, null);
        PlanUnit title = unit(UnitKind.METADATA, DECK, UnitClass.SAFE, null, author, base, UUID.randomUUID());
        PlanUnit pendingNew = unit(UnitKind.EXERCISE, UnitClass.NEW, author, null, null);
        PlanUnit pendingRemoved = unit(UnitKind.MATERIAL, UnitClass.DELETED, null, UUID.randomUUID(), base);
        PlanUnit taken = unit(UnitKind.MATERIAL, UnitClass.SAFE, author, UUID.randomUUID(), base);
        PlanUnit takenAskAgain = unit(UnitKind.MATERIAL, UnitClass.ASK_AGAIN, author, UUID.randomUUID(), base);
        UpdatePlan plan = plan(newMaterial, fix, conflict, learnerDeleted, removed, objective, title, pendingNew, pendingRemoved, taken, takenAskAgain);
        ApplySelection selection = select(plan, false, pick(newMaterial, ApplyChoice.DECLINE), pick(fix, ApplyChoice.DECLINE), pick(conflict, ApplyChoice.KEEP_MINE),
                pick(learnerDeleted, ApplyChoice.KEEP_MINE), pick(removed, ApplyChoice.KEEP), pick(objective, ApplyChoice.DECLINE), pick(title, ApplyChoice.DECLINE),
                pick(taken, ApplyChoice.ACCEPT), pick(takenAskAgain, ApplyChoice.ACCEPT));

        assertThat(only(selection, newMaterial)).isEqualTo(new Row(Op.DECLINE_NEW, UnitKind.MATERIAL, newMaterial.key(), author.toString(), ""));
        assertThat(only(selection, fix)).isEqualTo(new Row(Op.KEEP_MINE, UnitKind.MATERIAL, fix.key(), author.toString(), ""));
        assertThat(only(selection, conflict)).isEqualTo(new Row(Op.KEEP_MINE, UnitKind.MATERIAL, conflict.key(), author.toString(), ""));
        assertThat(only(selection, learnerDeleted)).as("the learner had deleted it and keeps it deleted").isEqualTo(
                new Row(Op.KEEP_DELETED, UnitKind.MATERIAL, learnerDeleted.key(), learnerDeleted.authorRevisionId().toString(), ""));
        assertThat(only(selection, removed)).as("the author removed it and the learner keeps it: KEEP_MINE with an absent author state")
                .isEqualTo(new Row(Op.KEEP_MINE, UnitKind.EXERCISE, removed.key(), "-", ""));
        assertThat(only(selection, objective)).isEqualTo(new Row(Op.KEEP_MINE, UnitKind.OBJECTIVE, objective.key(), author.toString(), ""));
        assertThat(only(selection, title)).isEqualTo(new Row(Op.KEEP_MINE, UnitKind.METADATA, DECK, author.toString(), ""));
        assertThat(only(selection, pendingNew)).as("pending: the author's state offered and the old base (absent)").isEqualTo(
                new Row(Op.PENDING, UnitKind.EXERCISE, pendingNew.key(), author.toString(), "-"));
        assertThat(only(selection, pendingRemoved)).isEqualTo(new Row(Op.PENDING, UnitKind.MATERIAL, pendingRemoved.key(), "-", base.toString()));
        assertThat(only(selection, taken)).as("a unit that was taken needs no row").isNull();
        // ... and loses the decision it had
        List<Row> rows = ApplyRows.build(selection, Set.of(takenAskAgain.unitId(), taken.unitId(), conflict.unitId()));
        assertThat(rows.stream().filter(row -> row.op() == Op.REMOVE)).extracting(Row::key).containsExactlyInAnyOrder(takenAskAgain.key(), taken.key());
        assertThat(rows).hasSize(11);
        // the rows survive the final writes
        ApplyWrites writes = new ApplyWrites(UUID.randomUUID(), VERSION, base, author, JSON.createObjectNode().put("deckId", DECK.toString()), 2, 7, 2, 0, rows, ApplyWrites.MANUAL, List.of());
        ApplyWrites again = ApplyWrites.of(writes.toFinalWrites());
        assertThat(again.rows()).isEqualTo(rows);
        assertThat(again.base()).isEqualTo(base);
        assertThat(again.published()).isEqualTo(author);
        assertThat(again.planVersion()).isEqualTo(VERSION);
        assertThat(again.declined()).isEqualTo(7);
        assertThat(again.request().path("deckId").stringValue(null)).isEqualTo(DECK.toString());
    }

    @Test
    void aCommandWithMoreRowsThanTheFinalWritesCanCarryIsALimitProblem() {
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index <= ApplyWrites.MAX_ROWS; index++) rows.add(new Row(Op.PENDING, UnitKind.MATERIAL, UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        ApplyWrites writes = new ApplyWrites(UUID.randomUUID(), VERSION, UUID.randomUUID(), UUID.randomUUID(), JSON.createObjectNode(), 0, 0, rows.size(), 0, rows, ApplyWrites.MANUAL, List.of());
        assertThatThrownBy(writes::toFinalWrites).isInstanceOf(app.mnema.learning.platform.api.ResourceLimitExceededException.class);
        // the biggest command that is allowed fits the final writes with room to spare
        ApplyWrites biggest = new ApplyWrites(UUID.randomUUID(), VERSION, UUID.randomUUID(), UUID.randomUUID(), JSON.createObjectNode(), 0, 0, ApplyWrites.MAX_ROWS, 0,
                rows.subList(0, ApplyWrites.MAX_ROWS), ApplyWrites.MANUAL, List.of());
        JsonNode body = biggest.toFinalWrites().body();
        assertThat(body.path("rows")).hasSize(ApplyWrites.MAX_ROWS);
        assertThat(body.toString().getBytes(StandardCharsets.UTF_8).length).isLessThan(app.mnema.learning.update.FinalWrites.MAX_BYTES);
        assertThat(ProblemExtension.limit(ApplyWrites.MAX_ROWS).members()).containsEntry("limit", (long) ApplyWrites.MAX_ROWS);
    }
}
