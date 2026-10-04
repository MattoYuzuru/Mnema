package app.mnema.learning.generation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Admission atomicity around the note pin, ownership of the pinned notes and the V29 backfill. */
class GenerationNotesAdmissionIntegrationTest extends GenerationIntegrationTest {
    private int count(String table, UUID owner) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single();
    }

    @Test
    void aNoteThatMovesBetweenTheCheckAndTheSnapshotRefusesTheAdmissionAndLeavesNothingBehind() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID note = note(owner, deck, "заметка");
        // The pre-check passes; then a trigger moves the note when the admission transaction inserts its source row, which is
        // after the check and before the snapshot copy (a concurrent edit at exactly that moment).
        String name = "race_" + note.toString().replace("-", "");
        jdbc.sql("CREATE FUNCTION app_learning." + name + "() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.note_id = '" + note + "' THEN UPDATE app_learning.capture_note SET row_version = row_version + 1 "
                + "WHERE note_id = NEW.note_id; END IF; RETURN NEW; END; $$").update();
        jdbc.sql("CREATE TRIGGER " + name + " AFTER INSERT ON app_learning.generation_session_source FOR EACH ROW EXECUTE FUNCTION app_learning."
                + name + "()").update();
        try {
            MockHttpServletResponse response = create(owner, deck, spec(null, noteSource(note, 0)), UUID.randomUUID());
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(json(response).path("code").stringValue("")).isEqualTo("SOURCE_UNAVAILABLE");
        } finally {
            jdbc.sql("DROP TRIGGER " + name + " ON app_learning.generation_session_source").update();
            jdbc.sql("DROP FUNCTION app_learning." + name + "()").update();
        }
        assertThat(count("generation_session", owner)).isZero();
        assertThat(count("generation_session_source", owner)).isZero();
        assertThat(count("generation_note_snapshot", owner)).isZero();
        assertThat(count("generation_step", owner)).isZero();
        assertThat(count("generation_artifact", owner)).isZero();
        assertThat(count("usage_reservation", owner)).isZero();
        assertThat(calls(owner)).isEmpty();
    }

    @Test
    void aForeignOwnersNoteIsAnOpaque404AndWritesNoSnapshot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID foreign = note(stranger, deck(stranger), "чужая");
        MockHttpServletResponse response = create(owner, deck, spec(null, noteSource(foreign, 0)), UUID.randomUUID());
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_note_snapshot WHERE note_id=:id").param("id", foreign)
                .query(Integer.class).single()).isZero();
        // the copy itself is scoped to the session's owner and deck as well
        UUID session = start(owner, deck, spec("p"));
        UUID otherDeckNote = note(owner, deck(owner), "из другой колоды");
        assertThat(repository.snapshotNote(session, owner, foreign, 0)).isFalse();
        assertThat(repository.snapshotNote(session, owner, otherDeckNote, 0)).isFalse();
    }

    @org.springframework.beans.factory.annotation.Autowired private GenerationRepository repository;

    @Test
    void aRepeatedNoteOrMaterialPinIsInvalidWhateverTheOverrides() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID note = note(owner, deck, "заметка");
        assertThat(create(owner, deck, spec(null, noteSource(note, 0), noteSource(note, 0)), UUID.randomUUID()).getStatus()).isEqualTo(400);
        ObjectNode withOverride = noteSource(note, 0);
        withOverride.putObject("overrides").put("effort", "SHORT");
        assertThat(create(owner, deck, spec(null, withOverride, noteSource(note, 0)), UUID.randomUUID()).getStatus()).isEqualTo(400);
        var material = fixtures.addMaterial(owner, deck, "Материал", "ТЕЛО");
        ObjectNode item = JSON.createObjectNode().put("role", "SOURCE").put("type", "ITEM").put("memberKey", material.member().toString())
                .put("itemRevisionId", material.itemRevision().toString());
        assertThat(create(owner, deck, spec(null, item, item.deepCopy()), UUID.randomUUID()).getStatus()).isEqualTo(400);
        assertThat(count("generation_session", owner)).isZero();
    }

    @Test
    void theV29BackfillRestoresTheSnapshotOfASessionThatPredatesIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID note = note(owner, deck, "ТЕКСТ-ДО-МИГРАЦИИ");
        UUID moved = note(owner, deck, "ушла вперёд");
        UUID session = start(owner, deck, spec(null, noteSource(note, 0), noteSource(moved, 0)));
        awaitState(session, "REVIEW");
        jdbc.sql("DELETE FROM app_learning.generation_note_snapshot WHERE session_id=:id").param("id", session).update();
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1 WHERE note_id=:id").param("id", moved).update();

        String sql = Files.readString(Path.of("src/main/resources/db/learning/migration/V29__generation_note_snapshot.sql"),
                StandardCharsets.UTF_8);
        String backfill = sql.substring(sql.indexOf("INSERT INTO app_learning.generation_note_snapshot")).strip();
        jdbc.sql(backfill.endsWith(";") ? backfill.substring(0, backfill.length() - 1) : backfill).update();

        // the note still at its pin is restored; the one that moved has no text to restore (its pin reads as gone)
        assertThat(repository.pinnedNoteText(session, note, 0)).contains("ТЕКСТ-ДО-МИГРАЦИИ");
        assertThat(repository.pinnedNoteText(session, moved, 0)).isEmpty();
    }
}
