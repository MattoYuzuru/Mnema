package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.StudyFixtures;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;

/** Builds Deck-hub test data through the production services: decks, bulk-created materials, exercises and study state. */
final class HubFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();
    final DeckService decks;
    final ItemService items;
    final JdbcClient jdbc;
    final StudyFixtures study;

    HubFixtures(DeckService decks, ItemService items, ExerciseService exercises, StudySessionService sessions,
                MediaCatalog media, JdbcClient jdbc) {
        this.decks = decks;
        this.items = items;
        this.jdbc = jdbc;
        this.study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
    }

    /** A published material: member key and the item revision it was created with. */
    record Created(UUID member, UUID revision) { }

    UUID deck(UUID actor) {
        return UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
    }

    JsonNode head(UUID actor, UUID deck) { return decks.read(actor, deck); }

    long version(UUID actor, UUID deck) { return Long.parseLong(head(actor, deck).path("rowVersion").stringValue(null)); }

    /** Creates {@code count} one-paragraph materials, 100 per publication (the publication limit), in authoring order. */
    List<Created> seed(UUID actor, UUID deck, int count) {
        List<Created> created = new ArrayList<>();
        for (int start = 0; start < count; start += 100) {
            JsonNode head = head(actor, deck);
            ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                    .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
            ArrayNode changes = body.putArray("changes");
            for (int index = start; index < Math.min(count, start + 100); index++) {
                changes.addObject().put("operation", "create").put("memberKey", UUID.randomUUID().toString())
                        .set("document", document("Material " + index));
            }
            JsonNode ack = items.publish(actor, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                    ItemPublicationCommand.readBulk(bytes(body))).acknowledgement();
            ack.path("changes").forEach(change -> created.add(new Created(
                    UUID.fromString(change.path("memberKey").stringValue(null)),
                    UUID.fromString(change.path("itemRevisionId").stringValue(null)))));
        }
        return created;
    }

    static ObjectNode document(String text) {
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        ObjectNode root = document.putObject("root").put("id", UUID.randomUUID().toString()).put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode paragraph = root.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode value = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        value.putObject("attrs").put("text", text);
        value.putArray("content");
        return document;
    }

    /** Publishes one exercise of the given type assessing {@code material}; returns its exercise id. */
    UUID exercise(UUID actor, UUID deck, Created material, String type, boolean enabled) {
        StudyFixtures.Material subject = new StudyFixtures.Material(actor, deck, material.member(), material.revision(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ObjectNode exercise = switch (type) {
            case "SELF_CHECK" -> study.selfCheck(subject, StudyFixtures.blocks(StudyFixtures.text("Recall it")),
                    StudyFixtures.blocks(StudyFixtures.text("Answer")));
            case "CHOICE" -> {
                UUID correct = UUID.randomUUID();
                yield study.choice(subject, false, StudyFixtures.blocks(StudyFixtures.text("Pick")),
                        StudyFixtures.blocks().add(StudyFixtures.option(correct, StudyFixtures.text("right")))
                                .add(StudyFixtures.option(UUID.randomUUID(), StudyFixtures.text("wrong"))), correct);
            }
            case "CLOZE" -> {
                UUID blank = UUID.randomUUID();
                yield study.cloze(subject, StudyFixtures.blocks(), StudyFixtures.blocks(StudyFixtures.text("Recall: "),
                        StudyFixtures.blank(blank, false, 0, true)), StudyFixtures.blankKey(blank, "memory"));
            }
            default -> study.freeResponse(subject, StudyFixtures.blocks(StudyFixtures.text("Recall it")),
                    StudyFixtures.blocks(), "memory");
        };
        exercise.put("enabled", enabled);
        JsonNode ack = study.publish(subject, exercise, "Objective " + UUID.randomUUID());
        return UUID.fromString(ack.path("exerciseId").stringValue(null));
    }

    /**
     * Gives the material's objectives a study state directly: assessed at {@code level}, due at {@code nextDue}
     * (or never assessed, only introduced, when {@code assessed} is false).
     */
    void state(UUID actor, UUID deck, UUID member, int level, boolean assessed, Instant nextDue) {
        for (UUID objective : jdbc.sql("SELECT DISTINCT objective_id FROM app_learning.exercise_content_binding "
                        + "WHERE deck_id=:deck AND member_key=:member AND role='ASSESSED'")
                .param("deck", deck).param("member", member).query(UUID.class).list()) {
            jdbc.sql("""
                    INSERT INTO app_learning.study_policy_assignment(account_id,deck_id,objective_id,reducer_config_id,assigned_at)
                    SELECT :actor,:deck,:objective,config_id,statement_timestamp() FROM app_learning.scheduler_config LIMIT 1
                    """).param("actor", actor).param("deck", deck).param("objective", objective).update();
            jdbc.sql("""
                    INSERT INTO app_learning.study_state(account_id,deck_id,objective_id,learning_epoch,level,correct_streak,
                        lapse_count,last_assessed_at,next_due,reducer_config_id,transition_sequence,row_version,introduced_at,
                        updated_at)
                    SELECT :actor,:deck,:objective,0,:level,0,0,
                           CASE WHEN :assessed THEN statement_timestamp() END,
                           CASE WHEN :assessed THEN CAST(:due AS timestamptz) END,
                           config_id,CASE WHEN :assessed THEN 1 ELSE 0 END,0,statement_timestamp(),statement_timestamp()
                      FROM app_learning.scheduler_config LIMIT 1
                    """).param("actor", actor).param("deck", deck).param("objective", objective).param("level", level)
                    .param("assessed", assessed).param("due", assessed ? nextDue.atOffset(java.time.ZoneOffset.UTC) : null,
                            java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
        }
    }
}
