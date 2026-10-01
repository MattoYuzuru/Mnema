package app.mnema.learning.support;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.attempt.AttemptCommand;
import app.mnema.learning.study.session.StudySessionCommand;
import app.mnema.learning.study.session.StudySessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;

/**
 * Builds real decks, materials, exercises and Study sessions through the production services so
 * integration tests exercise the canonical write and read paths end to end.
 */
public final class StudyFixtures {
    public static final JsonMapper JSON = JsonMapper.builder().build();
    private final DeckService decks;
    private final ItemService items;
    private final ExerciseService exercises;
    private final StudySessionService sessions;
    private final MediaCatalog media;
    private final JdbcClient jdbc;

    public StudyFixtures(DeckService decks, ItemService items, ExerciseService exercises,
                         StudySessionService sessions, MediaCatalog media, JdbcClient jdbc) {
        this.decks = decks;
        this.items = items;
        this.exercises = exercises;
        this.sessions = sessions;
        this.media = media;
        this.jdbc = jdbc;
    }

    /**
     * One owner, one deck and one material with two paragraphs ("memory" and "forgetting") and a divider,
     * which is a valid native node without any text projection.
     */
    public record Material(UUID actor, UUID deck, UUID member, UUID itemRevision, UUID node, UUID distractor,
                           UUID divider, UUID root) { }

    /** An issued presentation as a learner reads it. */
    public record Issued(UUID session, JsonNode json) {
        public UUID id() { return UUID.fromString(json.path("presentationId").textValue()); }
        public String nonce() { return json.path("nonce").textValue(); }
        public JsonNode content() { return json.path("content"); }
    }

    public Material material() { return material(UUID.randomUUID()); }

    public Material material(UUID actor) {
        UUID deck = UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").textValue());
        return addMaterial(actor, deck, "memory", "forgetting");
    }

    /** Adds one more material to an existing deck. */
    public Material addMaterial(UUID actor, UUID deck, String first, String second) {
        JsonNode head = decks.read(actor, deck);
        UUID answerNode = UUID.randomUUID();
        UUID distractorNode = UUID.randomUUID();
        UUID dividerNode = UUID.randomUUID();
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        UUID rootNode = UUID.randomUUID();
        ObjectNode root = document.putObject("root").put("id", rootNode.toString())
                .put("type", "doc").put("version", 1);
        root.putObject("attrs");
        paragraph(root.putArray("content"), answerNode, first);
        paragraph(root.withArray("content"), distractorNode, second);
        ObjectNode divider = root.withArray("content").addObject().put("id", dividerNode.toString())
                .put("type", "divider").put("version", 1);
        divider.putObject("attrs");
        divider.putArray("content");
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").textValue());
        body.set("document", document);
        JsonNode item = items.publish(actor, deck, Long.parseLong(head.path("rowVersion").textValue()),
                ItemPublicationCommand.readCreate(bytes(body))).acknowledgement().path("changes").get(0);
        return new Material(actor, deck, UUID.fromString(item.path("memberKey").textValue()),
                UUID.fromString(item.path("itemRevisionId").textValue()), answerNode, distractorNode, dividerNode, rootNode);
    }

    private static void paragraph(ArrayNode parent, UUID id, String value) {
        ObjectNode paragraph = parent.addObject().put("id", id.toString()).put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode text = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        text.putObject("attrs").put("text", value);
        text.putArray("content");
    }

    // ---- blocks ----

    public static ObjectNode text(String value) { return JSON.createObjectNode().put("kind", "TEXT").put("text", value); }

    public static ObjectNode quote(Material material, UUID node) {
        return JSON.createObjectNode().put("kind", "MATERIAL").put("memberKey", material.member().toString())
                .put("itemRevisionId", material.itemRevision().toString()).put("nodeId", node.toString());
    }

    public static ObjectNode image(UUID asset, String alt) {
        return JSON.createObjectNode().put("kind", "IMAGE").put("assetId", asset.toString()).put("alt", alt);
    }

    public static ObjectNode audio(UUID asset, String title, String transcript) {
        return timed("AUDIO", asset, title, transcript);
    }

    public static ObjectNode video(UUID asset, String title, String transcript) {
        return timed("VIDEO", asset, title, transcript);
    }

    private static ObjectNode timed(String kind, UUID asset, String title, String transcript) {
        ObjectNode block = JSON.createObjectNode().put("kind", kind).put("assetId", asset.toString())
                .put("title", title);
        if (transcript != null) block.put("transcript", transcript);
        return block;
    }

    public static ObjectNode youtube(String videoId, String title) {
        return JSON.createObjectNode().put("kind", "YOUTUBE").put("videoId", videoId).put("title", title);
    }

    public static ArrayNode blocks(ObjectNode... values) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode value : values) array.add(value);
        return array;
    }

    public static ObjectNode blank(UUID id, boolean fixed, int length, boolean firstLetterHint) {
        ObjectNode blank = JSON.createObjectNode().put("kind", "BLANK").put("blankId", id.toString());
        ObjectNode size = blank.putObject("size").put("mode", fixed ? "FIXED" : "ANSWER_LENGTH");
        if (fixed) size.put("length", length);
        blank.put("firstLetterHint", firstLetterHint);
        return blank;
    }

    public static ObjectNode option(UUID id, ObjectNode... blocks) {
        return JSON.createObjectNode().put("optionId", id.toString()).set("blocks", blocks(blocks));
    }

    public static ObjectNode item(UUID id, ObjectNode... blocks) {
        return JSON.createObjectNode().put("itemId", id.toString()).set("blocks", blocks(blocks));
    }

    public static ObjectNode textKey(String... accepted) {
        ObjectNode key = JSON.createObjectNode().put("kind", "TEXT");
        accepted(key, accepted);
        return key;
    }

    public static ObjectNode blankKey(UUID blank, String... accepted) {
        ObjectNode key = JSON.createObjectNode().put("blankId", blank.toString());
        accepted(key, accepted);
        return key;
    }

    private static void accepted(ObjectNode key, String... accepted) {
        ArrayNode values = key.putArray("accepted");
        for (String value : accepted) values.add(value);
        key.putArray("normalization").add("UNICODE_NFC").add("TRIM").add("CASE_FOLD");
        key.put("matchingMode", "STRICT");
    }

    // ---- exercises: the {type, schemaVersion, enabled, subject, content, answerKey, evaluatorPolicy} object ----

    private ObjectNode exercise(Material material, String type, ObjectNode content, ObjectNode key, String evaluator) {
        ObjectNode exercise = JSON.createObjectNode().put("type", type).put("schemaVersion", 2).put("enabled", true);
        exercise.putObject("subject").put("memberKey", material.member().toString())
                .put("itemRevisionId", material.itemRevision().toString());
        exercise.set("content", content);
        exercise.set("answerKey", key);
        exercise.putObject("evaluatorPolicy").put("id", evaluator).put("version", "1");
        return exercise;
    }

    public ObjectNode selfCheck(Material material, ArrayNode prompt, ArrayNode reference) {
        ObjectNode content = JSON.createObjectNode();
        content.set("prompt", prompt);
        content.set("reference", reference);
        return exercise(material, "SELF_CHECK", content, JSON.createObjectNode().put("kind", "SELF_REPORT"), "self-check");
    }

    public ObjectNode freeResponse(Material material, ArrayNode prompt, ArrayNode reference, String... accepted) {
        ObjectNode content = JSON.createObjectNode();
        content.set("prompt", prompt);
        content.set("reference", reference);
        content.put("responseInput", "TEXT");
        return exercise(material, "FREE_RESPONSE", content, textKey(accepted), "deterministic-text");
    }

    public ObjectNode cloze(Material material, ArrayNode prompt, ArrayNode passage, ObjectNode... keys) {
        ObjectNode content = JSON.createObjectNode();
        content.set("prompt", prompt);
        content.set("passage", passage);
        ObjectNode key = JSON.createObjectNode().put("kind", "CLOZE");
        ArrayNode blanks = key.putArray("blanks");
        for (ObjectNode blank : keys) blanks.add(blank);
        return exercise(material, "CLOZE", content, key, "deterministic-cloze");
    }

    public ObjectNode choice(Material material, boolean multiple, ArrayNode prompt, ArrayNode options,
                             UUID... correct) {
        ObjectNode content = JSON.createObjectNode();
        content.set("prompt", prompt);
        content.put("selectionMode", multiple ? "MULTIPLE" : "SINGLE");
        content.set("options", options);
        ObjectNode key = JSON.createObjectNode().put("kind", "CHOICE");
        ArrayNode ids = key.putArray("correctOptionIds");
        for (UUID id : correct) ids.add(id.toString());
        return exercise(material, "CHOICE", content, key, "deterministic-choice");
    }

    public ObjectNode match(Material material, ArrayNode prompt, ArrayNode left, ArrayNode right, UUID[][] pairs) {
        ObjectNode content = JSON.createObjectNode();
        content.set("prompt", prompt);
        content.set("left", left);
        content.set("right", right);
        ObjectNode key = JSON.createObjectNode().put("kind", "MATCH");
        ArrayNode values = key.putArray("pairs");
        for (UUID[] pair : pairs) {
            values.addObject().put("leftId", pair[0].toString()).put("rightId", pair[1].toString());
        }
        return exercise(material, "MATCH", content, key, "deterministic-match");
    }

    // ---- publication ----

    /** A create command pinned to the deck's current head. */
    public ObjectNode createBody(Material material, ObjectNode exercise, String title) {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", decks.read(material.actor(), material.deck()).path("revisionId").textValue());
        body.putObject("objective").put("operation", "create").put("title", title);
        body.set("exercise", exercise);
        return body;
    }

    public long deckVersion(Material material) {
        return Long.parseLong(decks.read(material.actor(), material.deck()).path("rowVersion").textValue());
    }

    public JsonNode publish(Material material, ObjectNode exercise) {
        return publish(material, exercise, "Objective");
    }

    public JsonNode publish(Material material, ObjectNode exercise, String title) {
        ObjectNode body = createBody(material, exercise, title);
        return exercises.publish(material.actor(), material.deck(), null, deckVersion(material),
                ExerciseCommand.readCreate(bytes(body))).acknowledgement();
    }

    // ---- media ----

    public UUID pendingAsset(UUID owner) { return media.reserve(owner, UUID.randomUUID(), MediaCatalog.Origin.UPLOAD); }

    /** A READY asset whose verified source has the given MIME type. */
    public UUID readyAsset(UUID owner, String mime) {
        UUID asset = pendingAsset(owner);
        ready(asset, mime);
        return asset;
    }

    public void ready(UUID asset, String mime) {
        UUID blob = UUID.randomUUID();
        byte[] hash = new byte[32];
        java.nio.ByteBuffer.wrap(hash).putLong(blob.getMostSignificantBits()).putLong(blob.getLeastSignificantBits());
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) "
                + "VALUES (:blob,:hash,32,:mime,:key,CURRENT_TIMESTAMP)")
                .param("blob", blob).param("hash", hash).param("mime", mime).param("key", "k/" + blob).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state='PROCESSING',updated_at=CURRENT_TIMESTAMP "
                + "WHERE asset_id=:asset").param("asset", asset).update();
        if (!media.ready(asset, 0, blob)) throw new IllegalStateException("Asset could not become READY");
    }

    // ---- study ----

    public StudySessionCommand scheduled() {
        return start("SCHEDULED", null);
    }

    public StudySessionCommand start(String mode, UUID sourceSession) {
        ObjectNode request = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("mode", mode);
        if (mode.equals("PRACTICE")) request.put("includeNew", true).put("order", "SEEDED");
        if (mode.equals("REPLAY")) request.put("sourceSessionId", sourceSession.toString());
        ObjectNode budget = JSON.createObjectNode().put("maxPresentations", 20);
        if (mode.equals("SCHEDULED")) budget.put("maxNewObjectives", 20);
        request.set("budget", budget);
        return StudySessionCommand.read(bytes(request));
    }

    /** Starts a session and returns the (possibly just prepared) session document. */
    public JsonNode session(Material material, String mode, UUID sourceSession) {
        StudySessionService.StartResult started = sessions.start(material.actor(), material.deck(), "UTC",
                start(mode, sourceSession));
        UUID id = UUID.fromString(started.body().path("sessionId").textValue());
        return started.preparing() ? sessions.read(material.actor(), material.deck(), id) : started.body();
    }

    /** The presentations of a fresh scheduled session, in issue order. */
    public List<Issued> issue(Material material, String mode, UUID sourceSession) {
        JsonNode session = session(material, mode, sourceSession);
        UUID id = UUID.fromString(session.path("sessionId").textValue());
        List<Issued> result = new java.util.ArrayList<>();
        session.path("presentations").forEach(value -> result.add(new Issued(id, value)));
        return List.copyOf(result);
    }

    public Issued issueOne(Material material) { return issue(material, "SCHEDULED", null).getFirst(); }

    public static AttemptCommand attempt(Issued presentation, ObjectNode response) {
        return attempt(UUID.randomUUID(), presentation, response);
    }

    public static AttemptCommand attempt(UUID attemptId, Issued presentation, ObjectNode response) {
        ObjectNode body = JSON.createObjectNode().put("attemptId", attemptId.toString())
                .put("presentationId", presentation.id().toString()).put("nonce", presentation.nonce());
        body.set("response", response);
        body.putNull("confidence");
        body.put("durationMs", 1_000);
        return AttemptCommand.read(bytes(body));
    }

    public static ObjectNode textResponse(String value) {
        return JSON.createObjectNode().put("kind", "TEXT").put("text", value);
    }
}
