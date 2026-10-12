package app.mnema.learning.update.history;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.update.FinalWrites;
import app.mnema.learning.update.history.HistoryUnit.Change;
import app.mnema.learning.update.history.HistoryUnit.State;
import app.mnema.learning.update.plan.UnitKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Updates/6 part B (#445): the pure parts of «Что нового» and of returning a unit, without a database. */
class UpdateHistoryUnitsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID KEY = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID OLD = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID NEW = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID OTHER = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID COMMAND = UUID.fromString("cdcdcdcd-cdcd-4dcd-8dcd-cdcdcdcdcdcd");
    private static final UUID DECK = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REVISION = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static RevertCommand read(String body) {
        return RevertCommand.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aUnitIsAddedChangedOrRemovedAndStandsCurrentReturnedOrChangedSince() {
        // changed: previous OLD, made NEW
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, NEW).state()).isEqualTo(State.CURRENT);
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, OLD).state()).isEqualTo(State.RETURNED);
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, OTHER).state()).isEqualTo(State.CHANGED_SINCE);
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, null).state()).as("deleted since").isEqualTo(State.CHANGED_SINCE);
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, NEW).change()).isEqualTo(Change.CHANGED);
        // added: nothing before; returned means it is gone again
        assertThat(HistoryUnit.of(UnitKind.EXERCISE, KEY, null, NEW, NEW).change()).isEqualTo(Change.ADDED);
        assertThat(HistoryUnit.of(UnitKind.EXERCISE, KEY, null, NEW, NEW).state()).isEqualTo(State.CURRENT);
        assertThat(HistoryUnit.of(UnitKind.EXERCISE, KEY, null, NEW, null).state()).isEqualTo(State.RETURNED);
        assertThat(HistoryUnit.of(UnitKind.EXERCISE, KEY, null, NEW, OTHER).state()).isEqualTo(State.CHANGED_SINCE);
        // removed: nothing after; current means it is still gone
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, null, null).change()).isEqualTo(Change.REMOVED);
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, null, null).state()).isEqualTo(State.CURRENT);
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, null, OLD).state()).isEqualTo(State.RETURNED);
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, null, OTHER).state()).isEqualTo(State.CHANGED_SINCE);
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, NEW).unitId()).isEqualTo("MATERIAL:" + KEY);
    }

    @Test
    void onlyACurrentMaterialOrExerciseOfACopyThatIsStillAttachedCanBeReturned() {
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, NEW).revertible(false)).isTrue();
        assertThat(HistoryUnit.of(UnitKind.EXERCISE, KEY, OLD, NEW, NEW).revertible(false)).isTrue();
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, NEW).revertible(true)).as("detached").isFalse();
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, OTHER).revertible(false)).as("edited since").isFalse();
        assertThat(HistoryUnit.of(UnitKind.MATERIAL, KEY, OLD, NEW, OLD).revertible(false)).as("returned already").isFalse();
        assertThat(HistoryUnit.of(UnitKind.OBJECTIVE, KEY, OLD, NEW, NEW).revertible(false)).isFalse();
        assertThat(new HistoryUnit(UnitKind.METADATA, DECK, Change.CHANGED, null, null, null).revertible(false)).isFalse();
        assertThat(new HistoryUnit(UnitKind.ORDER, DECK, Change.REORDERED, null, null, null).revertible(false)).isFalse();
    }

    @Test
    void theReturnBodyIsStrictBoundedAndKeepsItsUnitsInTheOrderGiven() {
        RevertCommand plain = read("{\"commandId\":\"" + COMMAND + "\"}");
        assertThat(plain.commandId()).isEqualTo(COMMAND);
        assertThat(plain.with()).isEmpty();
        RevertCommand group = read("{\"commandId\":\"" + COMMAND + "\",\"with\":[\"EXERCISE:" + OLD + "\",\"MATERIAL:" + NEW + "\"]}");
        assertThat(group.with()).extracting(RevertCommand.Unit::unitId).containsExactly("EXERCISE:" + OLD, "MATERIAL:" + NEW);
        for (String bad : new String[] {
                "", "[]", "{}", "{\"with\":[]}", "{\"commandId\":1}", "{\"commandId\":\"not-a-uuid\"}",
                "{\"commandId\":\"" + COMMAND.toString().toUpperCase() + "\"}",
                "{\"commandId\":\"" + COMMAND + "\",\"extra\":1}",
                "{\"commandId\":\"" + COMMAND + "\",\"commandId\":\"" + COMMAND + "\"}",
                "{\"commandId\":\"" + COMMAND + "\",\"with\":\"MATERIAL:" + NEW + "\"}",
                "{\"commandId\":\"" + COMMAND + "\",\"with\":[1]}",
                "{\"commandId\":\"" + COMMAND + "\",\"with\":[\"MATERIAL\"]}",
                "{\"commandId\":\"" + COMMAND + "\",\"with\":[\"material:" + NEW + "\"]}",
                "{\"commandId\":\"" + COMMAND + "\",\"with\":[\"MATERIAL:" + KEY.toString().toUpperCase() + "\"]}",
                "{\"commandId\":\"" + COMMAND + "\",\"with\":[\"OBJECTIVE:" + NEW + "\"]}",
                "{\"commandId\":\"" + COMMAND + "\",\"with\":[\"ORDER:" + DECK + "\"]}",
                "{\"commandId\":\"" + COMMAND + "\",\"with\":[\"MATERIAL:" + NEW + "\",\"MATERIAL:" + NEW + "\"]}",
                "{\"commandId\":\"00000000-0000-0000-0000-000000000000\"}",
                "{\"commandId\":\"" + COMMAND + "\",\"pad\":\"" + "x".repeat(RevertCommand.MAX_REQUEST_BYTES) + "\"}"}) {
            assertThatThrownBy(() -> read(bad)).as(bad.length() > 120 ? "a body over the limit" : bad).isInstanceOf(InvalidRequestException.class);
        }
        StringBuilder many = new StringBuilder("{\"commandId\":\"" + COMMAND + "\",\"with\":[");
        for (int index = 0; index <= RevertCommand.MAX_WITH; index++) many.append(index == 0 ? "" : ",").append("\"MATERIAL:").append(UUID.nameUUIDFromBytes(new byte[] {(byte) index})).append('"');
        assertThatThrownBy(() -> read(many + "]}")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theReceiptEnvelopeDoesNotDependOnTheOrderOfTheOtherUnits() {
        RevertCommand.Unit primary = new RevertCommand.Unit(UnitKind.MATERIAL, KEY);
        RevertCommand one = new RevertCommand(COMMAND, List.of(new RevertCommand.Unit(UnitKind.EXERCISE, OLD), new RevertCommand.Unit(UnitKind.EXERCISE, NEW)));
        RevertCommand two = new RevertCommand(COMMAND, List.of(new RevertCommand.Unit(UnitKind.EXERCISE, NEW), new RevertCommand.Unit(UnitKind.EXERCISE, OLD)));
        assertThat(one.envelope(DECK, REVISION, primary)).isEqualTo(two.envelope(DECK, REVISION, primary));
        assertThat(one.envelope(DECK, REVISION, primary)).isNotEqualTo(new RevertCommand(COMMAND, List.of()).envelope(DECK, REVISION, primary));
        assertThat(one.envelope(DECK, REVISION, primary).path("unitId").stringValue(null)).isEqualTo("MATERIAL:" + KEY);
        assertThat(new RevertCommand(COMMAND, List.of()).envelope(DECK, OTHER, primary)).isNotEqualTo(new RevertCommand(COMMAND, List.of()).envelope(DECK, REVISION, primary));
    }

    @Test
    void theFinalWritesOfAReturnSurviveAStorageRoundTripAndTheReceiptIdIsAVersion4Derivation() {
        var request = JSON.createObjectNode().put("deckId", DECK.toString());
        RevertWrites writes = new RevertWrites(COMMAND, request, List.of("MATERIAL:" + KEY, "EXERCISE:" + OLD), List.of(
                new RevertWrites.Row(UnitKind.MATERIAL, KEY, "KEEP_MINE", NEW.toString()), new RevertWrites.Row(UnitKind.EXERCISE, OLD, "DECLINE_NEW", NEW.toString()),
                new RevertWrites.Row(UnitKind.MATERIAL, OTHER, "KEEP_MINE", "-")));
        FinalWrites stored = writes.toFinalWrites();
        assertThat(stored.type()).isEqualTo("update.revert");
        assertThat(RevertWrites.of(stored)).isEqualTo(writes);
        assertThatThrownBy(() -> RevertWrites.of(new FinalWrites("update.apply", JSON.createObjectNode()))).isInstanceOf(IllegalStateException.class);
        UUID receipt = RevertFinalWriter.derive(COMMAND);
        assertThat(receipt).isEqualTo(RevertFinalWriter.derive(COMMAND)).isNotEqualTo(COMMAND).isNotEqualTo(RevertFinalWriter.derive(UUID.randomUUID()));
        assertThat(receipt.version()).isEqualTo(4);
        assertThat(receipt.variant()).isEqualTo(2);
    }

    @Test
    void theHistoryLimitIsOneToTwentyAndNothingElse() {
        assertThat(UpdateHistoryService.limit(null)).isEqualTo(5);
        assertThat(UpdateHistoryService.limit("1")).isEqualTo(1);
        assertThat(UpdateHistoryService.limit("20")).isEqualTo(20);
        for (String bad : new String[] {"0", "21", "-1", "01", "1.5", "", " 5", "5 ", "abc", "100"}) {
            assertThatThrownBy(() -> UpdateHistoryService.limit(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }
}
