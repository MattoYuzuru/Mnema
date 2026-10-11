package app.mnema.learning.update;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.update.CombinedUnits.ExerciseOp;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.CombinedUnits.Metadata;
import app.mnema.learning.update.CombinedUnits.ObjectiveOp;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shape of a command: what it accepts, what it refuses before any database is asked, and that its stored form and its identity round-trip. */
class CombinedUnitsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID deck = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID head = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID revision = UUID.randomUUID();

    private CombinedUnits items(ItemOp... ops) {
        return new CombinedUnits(UUID.randomUUID(), deck, actor, head, null, List.of(ops), List.of(), List.of());
    }

    @Test
    void anAdoptionAndALaterMoveOfTheSameMemberAreOneShapeButAMoveBeforeItOrAfterARemovalIsNot() {
        assertThat(items(new ItemOp.AdoptReplace(a, 1, revision), new ItemOp.Move(a, 1, 0)).items()).hasSize(2);
        assertThat(items(new ItemOp.AdoptInsert(a, 1, revision), new ItemOp.Move(a, 1, 0), new ItemOp.Remove(b, 0)).unitCount()).isEqualTo(3);
        assertThat(items(new ItemOp.Move(a, 1, 0), new ItemOp.Remove(b, 0)).items()).hasSize(2);
        assertInvalid(() -> items(new ItemOp.Move(a, 1, 0), new ItemOp.AdoptReplace(a, 0, revision)));
        assertInvalid(() -> items(new ItemOp.Remove(a, 1), new ItemOp.Move(a, 0, 2)));
        assertInvalid(() -> items(new ItemOp.Move(a, 1, 0), new ItemOp.Move(a, 0, 2)));
        assertInvalid(() -> items(new ItemOp.AdoptReplace(a, 1, revision), new ItemOp.Remove(a, 1)));
        assertInvalid(() -> items(new ItemOp.AdoptReplace(a, 1, revision), new ItemOp.AdoptInsert(a, 2, revision)));
    }

    @Test
    void identifiersOrdinalsAndMetadataAreChecked() {
        assertInvalid(() -> items(new ItemOp.Remove(a, -1)));
        assertInvalid(() -> items(new ItemOp.Remove(a, CombinedUnits.MAX_ORDINAL + 1)));
        assertInvalid(() -> items(new ItemOp.AdoptReplace(new UUID(0, 0), 0, revision)));
        assertInvalid(() -> items(new ItemOp.AdoptReplace(a, 0, null)));
        assertInvalid(() -> new CombinedUnits(UUID.randomUUID(), deck, actor, null, null, List.of(), List.of(), List.of()));
        assertInvalid(() -> new CombinedUnits(new UUID(0x0000000000001000L, 0x8000000000000000L), deck, actor, head, null, List.of(), List.of(), List.of()));
        assertInvalid(() -> new CombinedUnits(UUID.randomUUID(), deck, actor, head, null, null, List.of(), List.of()));
        assertInvalid(() -> new Metadata("", "d"));
        assertInvalid(() -> new Metadata("t", null));
        assertInvalid(() -> new Metadata("x".repeat(201), "d"));
        assertInvalid(() -> new CombinedUnits(UUID.randomUUID(), deck, actor, head, null, List.of(), List.of(new ExerciseOp.Remove(a, 0), new ExerciseOp.AdoptReplace(a, 0, revision)), List.of()));
        assertInvalid(() -> new CombinedUnits(UUID.randomUUID(), deck, actor, head, null, List.of(), List.of(),
                List.of(new ObjectiveOp.Adopt(a, revision), new ObjectiveOp.Adopt(a, UUID.randomUUID()))));
    }

    @Test
    void theStoredRowsRestoreTheSameCommandAndItsIdentityIsStable() {
        ObjectNode body = JSON.createObjectNode().put("base", "x").put("n", 1);
        CombinedUnits units = new CombinedUnits(UUID.randomUUID(), deck, actor, head, new Metadata("Название", "Описание"),
                List.of(new ItemOp.AdoptReplace(a, 3, revision), new ItemOp.Move(a, 3, 0), new ItemOp.Remove(b, 1), new ItemOp.AdoptInsert(UUID.randomUUID(), 2, UUID.randomUUID())),
                List.of(new ExerciseOp.AdoptInsert(UUID.randomUUID(), 0, UUID.randomUUID()), new ExerciseOp.Remove(b, 4), new ExerciseOp.AdoptReplace(a, 1, revision)),
                List.of(new ObjectiveOp.Adopt(a, revision)), new FinalWrites("fork.apply", body));
        assertThat(units.unitCount()).isEqualTo(4 + 3 + 1 + 1);
        List<CombinedUnits.Row> rows = units.rows();
        assertThat(rows).hasSize(8);
        assertThat(rows.stream().map(CombinedUnits.Row::unit).toList()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(rows.stream().map(CombinedUnits.Row::kind).toList()).containsExactly("ITEM_REPLACE", "ITEM_MOVE", "ITEM_REMOVE", "ITEM_INSERT",
                "EXERCISE_INSERT", "EXERCISE_REMOVE", "EXERCISE_REPLACE", "OBJECTIVE_ADOPT");
        CombinedUnits restored = CombinedUnits.restore(units.commandId(), deck, actor, head, units.metadataJson(), units.finalWrites().toJson(), rows);
        assertThat(restored).isEqualTo(units);
        CanonicalJsonHasher hasher = new CanonicalJsonHasher();
        assertThat(hasher.hash(restored.envelope()).sha256()).isEqualTo(hasher.hash(units.envelope()).sha256());

        // any difference is another identity: an ordinal, a revision, the order of operations, the final writes
        CombinedUnits shifted = new CombinedUnits(units.commandId(), deck, actor, head, units.metadata(), List.of(new ItemOp.AdoptReplace(a, 4, revision)), List.of(), List.of());
        CombinedUnits other = new CombinedUnits(units.commandId(), deck, actor, head, units.metadata(), List.of(new ItemOp.AdoptReplace(a, 3, revision)), List.of(), List.of());
        assertThat(hasher.hash(shifted.envelope()).sha256()).isNotEqualTo(hasher.hash(other.envelope()).sha256());
        assertThat(hasher.hash(other.envelope()).sha256()).isNotEqualTo(hasher.hash(units.envelope()).sha256());
    }

    @Test
    void finalWritesAreATaggedObjectOfBoundedSize() {
        assertThat(new FinalWrites("fork.apply", JSON.createObjectNode()).type()).isEqualTo("fork.apply");
        assertInvalid(() -> new FinalWrites("Fork", JSON.createObjectNode()));
        assertInvalid(() -> new FinalWrites("fork.apply", null));
        assertInvalid(() -> new FinalWrites(null, JSON.createObjectNode()));
        ObjectNode big = JSON.createObjectNode().put("blob", "x".repeat(FinalWrites.MAX_BYTES));
        assertInvalid(() -> new FinalWrites("fork.apply", big));
        ObjectNode fits = JSON.createObjectNode().put("blob", "x".repeat(FinalWrites.MAX_BYTES - 20));
        assertThat(new FinalWrites("fork.apply", fits).body().path("blob").stringValue(null)).hasSize(FinalWrites.MAX_BYTES - 20);
        FinalWrites writes = new FinalWrites("fork.apply", JSON.createObjectNode().put("k", "v"));
        assertThat(FinalWrites.fromJson(writes.toJson())).isEqualTo(writes);
        assertThat(FinalWrites.fromJson(null)).isNull();
    }

    @Test
    void theSettingsAreBoundedAndConsistent() {
        UpdateSettings defaults = new UpdateSettings(100, 10_000, 200, Duration.ofMinutes(30));
        assertThat(defaults.syncMaxUnits()).isEqualTo(100);
        assertThat(defaults.maxUnits()).isEqualTo(10_000);
        assertInvalidSettings(0, 10_000, 200, Duration.ofMinutes(30));
        assertInvalidSettings(1_001, 10_000, 200, Duration.ofMinutes(30));
        assertInvalidSettings(100, 99, 200, Duration.ofMinutes(30));
        assertInvalidSettings(100, 32_768, 200, Duration.ofMinutes(30));
        assertInvalidSettings(100, 10_000, 9, Duration.ofMinutes(30));
        assertInvalidSettings(100, 10_000, 1_001, Duration.ofMinutes(30));
        assertInvalidSettings(100, 10_000, 200, Duration.ofSeconds(59));
        assertInvalidSettings(100, 10_000, 200, Duration.ofHours(2));
        assertThat(new UpdateSettings(1, 32_767, 10, Duration.ofHours(1)).maxUnits()).isEqualTo(32_767);
    }

    @Test
    void aBigCommandIsAcceptedByTheShapeRules() {
        List<ItemOp> many = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) many.add(new ItemOp.AdoptReplace(UUID.randomUUID(), index, UUID.randomUUID()));
        assertThat(items(many.toArray(ItemOp[]::new)).unitCount()).isEqualTo(10_000);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(InvalidRequestException.class);
    }

    private static void assertInvalidSettings(int sync, int max, int slice, Duration lease) {
        assertThatThrownBy(() -> new UpdateSettings(sync, max, slice, lease)).isInstanceOf(IllegalArgumentException.class);
    }
}
