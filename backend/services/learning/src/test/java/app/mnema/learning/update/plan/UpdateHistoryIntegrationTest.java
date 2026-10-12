package app.mnema.learning.update.plan;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.update.apply.ApplyService;
import app.mnema.learning.update.plan.ApplyWorlds.Pick;
import app.mnema.learning.update.plan.PlanWorlds.Authored;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import app.mnema.learning.update.plan.PlanWorlds.Source;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static app.mnema.learning.update.plan.PlanWorlds.JSON;
import static app.mnema.learning.update.plan.PlanWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/6 part B (#445), «Что нового» and the return of one unit through the real application: the copy is updated by the background job and by the owner's own command, the
 * history is read over its route, and a unit is taken back over its route. The epic's acceptance criterion is the test: returning a unit restores the previous head, writes the
 * decision that keeps the next update from putting it back, and is refused once the owner has edited the unit since.
 */
class UpdateHistoryIntegrationTest extends AutoUpdateContext {
    private static final JsonNode CONTRACT = PlanWorlds.updatesContract();

    private static Set<String> names(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    private static String material(UUID member) { return UnitKind.MATERIAL.unitId(member); }

    private static String exercise(UUID id) { return UnitKind.EXERCISE.unitId(id); }

    private static JsonNode unit(JsonNode entry, String unitId) {
        for (JsonNode unit : entry.path("units")) if (unitId.equals(unit.path("unitId").stringValue(null))) return unit;
        throw new AssertionError("no unit " + unitId + " in " + entry);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).path("code").stringValue("")).isEqualTo(code);
    }

    private HttpResponse<String> revert(Copy copy, UUID revision, String unitId, UUID command, String... with) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString());
        if (with.length > 0) {
            ArrayNode list = body.putArray("with");
            for (String other : with) list.add(other);
        }
        int colon = unitId.indexOf(':');
        return worlds.send("POST", "/decks/" + copy.deck() + "/update-history/" + revision + "/units/" + unitId.substring(0, colon) + "/" + unitId.substring(colon + 1) + ":revert",
                copy.owner(), body.toString(), null);
    }

    /** The scene: a source of five materials with an exercise on each of the first two, the author's changes, the copy following him, the automatic update done. */
    private record Scene(Source source, Copy copy, UUID safe, UUID authorSafe, UUID removed, Material added, Authored created, Authored revised, Authored original, UUID published,
                         UUID revision) { }

    private Scene scene() {
        Source source = worlds.source("LINK", 5, 2);
        List<Material> m = source.materials();
        Copy copy = auto.autoCopy(source, hand);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID authorSafe = worlds.editText(author, deck, m.get(0).member(), "author zero");
        Authored revised = worlds.revise(author, deck, m.get(1), source.exercises().get(1), "Вопрос автора", "Цель автора");
        worlds.deleteMaterial(author, deck, m.get(4).member());
        Material added = worlds.study.addMaterial(author, deck, "новое слово", "перевод");
        Authored created = worlds.exercise(added, "Новый вопрос", null, "Новая цель");
        worlds.publish(author, deck, "LINK");
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(hand.runOne(AutoWorlds.AUTO)).isTrue();
        assertThat(auto.jobs(AutoWorlds.AUTO, copy.deck()).getFirst().state()).isEqualTo("SUCCEEDED");
        return new Scene(source, copy, m.get(0).member(), authorSafe, m.get(4).member(), added, created, revised, source.exercises().get(1), worlds.published(deck),
                worlds.headRevisionOf(copy.deck()));
    }

    // ---- the history ----

    @Test
    void theHistoryListsWhatEveryApplyChangedTheOwnersAndTheJobsNewestFirst() {
        Scene s = scene();
        Copy copy = s.copy();
        List<Material> m = s.source().materials();
        assertThat(ApplyWorlds.classes(apply.plan(copy))).isEqualTo(Map.of(material(s.removed()), "DELETED"));
        // the owner takes the removal himself: a second entry, a manual one
        assertThat(apply.apply(copy, false, Pick.material(s.removed(), "DELETE")).statusCode()).isEqualTo(200);
        String before = auto.full(copy.deck());

        HttpResponse<String> response = worlds.get("/decks/" + copy.deck() + "/update-history", copy.owner());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("private, no-store");
        JsonNode history = json(response);
        assertThat(names(history)).isEqualTo(names(CONTRACT.path("history").path("body")));
        assertThat(history.path("deckId").stringValue(null)).isEqualTo(copy.deck().toString());
        JsonNode entries = history.path("entries");
        assertThat(entries).hasSize(2);
        JsonNode manual = entries.get(0);
        JsonNode automatic = entries.get(1);
        assertThat(names(automatic)).isEqualTo(names(CONTRACT.path("history").path("body").path("entries").get(0)));
        assertThat(names(automatic.path("units").get(0))).isEqualTo(names(CONTRACT.path("history").path("body").path("entries").get(0).path("units").get(0)));
        assertThat(manual.path("mode").stringValue(null)).isEqualTo("MANUAL");
        assertThat(manual.path("deckRevisionId").stringValue(null)).isEqualTo(worlds.headRevisionOf(copy.deck()).toString());
        assertThat(manual.path("units")).hasSize(1);
        JsonNode removal = unit(manual, material(s.removed()));
        assertThat(removal.path("change").stringValue(null)).isEqualTo("REMOVED");
        assertThat(removal.path("revisionId").isNull()).isTrue();
        assertThat(removal.path("previousRevisionId").stringValue(null)).isEqualTo(m.get(4).itemRevision().toString());
        assertThat(removal.path("state").stringValue(null)).isEqualTo("CURRENT");
        assertThat(removal.path("revertible").booleanValue(false)).isTrue();
        assertThat(removal.path("title").stringValue("")).as("a removed unit is titled by what it was").isNotEmpty();

        assertThat(automatic.path("mode").stringValue(null)).isEqualTo("AUTO");
        assertThat(automatic.path("deckRevisionId").stringValue(null)).isEqualTo(s.revision().toString());
        assertThat(automatic.path("upstreamRevisionId").stringValue(null)).isEqualTo(s.published().toString());
        assertThat(automatic.path("seen").booleanValue(true)).isFalse();
        // the new exercise's objective is an implied part of it (Updates/4): adopted and listed below, but not counted as a unit
        assertThat(automatic.path("counts").path("applied").intValue()).isEqualTo(5);
        assertThat(automatic.path("counts").path("pending").intValue()).isEqualTo(1);
        assertThat(automatic.path("unitsTruncated").booleanValue(true)).isFalse();
        Set<String> listed = new TreeSet<>();
        automatic.path("units").forEach(unit -> listed.add(unit.path("unitId").stringValue(null)));
        assertThat(listed).containsExactlyInAnyOrder(material(s.safe()), material(s.added().member()), exercise(s.revised().exerciseId()), exercise(s.created().exerciseId()),
                UnitKind.OBJECTIVE.unitId(s.revised().objectiveId()), UnitKind.OBJECTIVE.unitId(s.created().objectiveId()));
        // the order is the order of kinds
        List<String> kinds = new java.util.ArrayList<>();
        automatic.path("units").forEach(unit -> kinds.add(unit.path("kind").stringValue(null)));
        assertThat(kinds.stream().map(UnitKind::valueOf).toList()).isSorted();
        assertThat(kinds).first().isEqualTo("MATERIAL");
        JsonNode changed = unit(automatic, material(s.safe()));
        assertThat(changed.path("change").stringValue(null)).isEqualTo("CHANGED");
        assertThat(changed.path("previousRevisionId").stringValue(null)).isEqualTo(m.get(0).itemRevision().toString());
        assertThat(changed.path("revisionId").stringValue(null)).isEqualTo(s.authorSafe().toString());
        assertThat(changed.path("state").stringValue(null)).isEqualTo("CURRENT");
        assertThat(changed.path("revertible").booleanValue(false)).isTrue();
        assertThat(changed.path("title").stringValue("")).contains("author zero");
        JsonNode created = unit(automatic, material(s.added().member()));
        assertThat(created.path("change").stringValue(null)).isEqualTo("ADDED");
        assertThat(created.path("previousRevisionId").isNull()).isTrue();
        JsonNode question = unit(automatic, exercise(s.revised().exerciseId()));
        assertThat(question.path("title").stringValue("")).contains("Вопрос автора");
        assertThat(question.path("previousRevisionId").stringValue(null)).isEqualTo(s.original().revisionId().toString());
        JsonNode objective = unit(automatic, UnitKind.OBJECTIVE.unitId(s.revised().objectiveId()));
        assertThat(objective.path("title").stringValue("")).isEqualTo("Цель автора");
        assertThat(objective.path("revertible").booleanValue(true)).as("an objective moves with its exercises").isFalse();
        assertThat(objective.path("previousRevisionId").stringValue(null)).isEqualTo(s.original().objectiveRevisionId().toString());

        // limit
        assertThat(json(worlds.get("/decks/" + copy.deck() + "/update-history?limit=1", copy.owner())).path("entries")).hasSize(1);
        for (String bad : new String[] {"0", "21", "-1", "abc", "1.5", ""}) {
            assertThat(worlds.get("/decks/" + copy.deck() + "/update-history?limit=" + bad, copy.owner()).statusCode()).as("limit=" + bad).isEqualTo(400);
        }
        assertThat(worlds.get("/decks/" + copy.deck() + "/update-history?limit=1&limit=2", copy.owner()).statusCode()).isEqualTo(400);
        assertThat(auto.full(copy.deck())).as("a read writes nothing").isEqualTo(before);
    }

    @Test
    void theHistoryOfACopyThatNobodyUpdatedIsEmptyAndNobodyElseCanReadAnyOfIt() {
        Source source = worlds.source("LINK", 2, 0);
        Copy copy = apply.copyByHand(source, hand);
        JsonNode empty = json(worlds.get("/decks/" + copy.deck() + "/update-history", copy.owner()));
        assertThat(empty.path("entries")).isEmpty();
        assertThat(names(empty)).isEqualTo(names(CONTRACT.path("history").path("body")));
        // a stranger, the author, an ordinary deck, an unknown deck and no token
        assertThat(worlds.get("/decks/" + copy.deck() + "/update-history", UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(worlds.get("/decks/" + copy.deck() + "/update-history", source.author()).statusCode()).isEqualTo(404);
        assertThat(worlds.get("/decks/" + source.deck() + "/update-history", source.author()).statusCode()).as("the author's deck is not a copy").isEqualTo(404);
        assertThat(worlds.get("/decks/" + UUID.randomUUID() + "/update-history", copy.owner()).statusCode()).isEqualTo(404);
        assertThat(worlds.get("/decks/not-a-uuid/update-history", copy.owner()).statusCode()).isEqualTo(400);
        assertThat(HttpIdentityFixture.send(port, "GET", "/decks/" + copy.deck() + "/update-history", null, null, java.util.Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void markingAnEntrySeenIsIdempotentOwnerOnlyAndRemovesTheNoticeOfTheDeck() {
        Scene s = scene();
        Copy copy = s.copy();
        String path = "/decks/" + copy.deck() + "/update-history/" + s.revision() + ":seen";
        assertThat(auto.deck(copy).path("fork").path("lastUpdate").path("deckRevisionId").stringValue(null)).isEqualTo(s.revision().toString());
        assertThat(worlds.send("POST", path, UUID.randomUUID(), null, null).statusCode()).as("a stranger").isEqualTo(404);
        assertThat(auto.deck(copy).path("fork").has("lastUpdate")).isTrue();

        HttpResponse<String> first = worlds.send("POST", path, copy.owner(), null, null);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(names(json(first))).isEqualTo(names(CONTRACT.path("seen").path("body")));
        assertThat(json(first).path("seen").booleanValue(false)).isTrue();
        String seenAt = jdbc.sql("SELECT seen_at::text FROM app_learning.fork_update_log WHERE deck_id=:d").param("d", copy.deck()).query(String.class).single();
        assertThat(worlds.send("POST", path, copy.owner(), null, null).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT seen_at::text FROM app_learning.fork_update_log WHERE deck_id=:d").param("d", copy.deck()).query(String.class).single()).as("the first time stays").isEqualTo(seenAt);
        assertThat(auto.deck(copy).path("fork").has("lastUpdate")).isFalse();
        assertThat(auto.history(copy).path("entries").get(0).path("seen").booleanValue(false)).isTrue();
        assertThat(worlds.send("POST", "/decks/" + copy.deck() + "/update-history/" + UUID.randomUUID() + ":seen", copy.owner(), null, null).statusCode()).isEqualTo(404);
        assertThat(worlds.send("POST", "/decks/" + copy.deck() + "/update-history/not-a-uuid:seen", copy.owner(), null, null).statusCode()).isEqualTo(400);
        assertThat(HttpIdentityFixture.send(port, "POST", path, HttpIdentityFixture.token(copy.owner(), "learning.read"), null, java.util.Map.of()).statusCode()).as("no write scope").isEqualTo(403);
    }

    // ---- returning a unit ----

    @Test
    void returningAChangedMaterialRestoresThePreviousHeadWritesTheDecisionAndTheNextUpdateDoesNotPutItBack() {
        Scene s = scene();
        Copy copy = s.copy();
        UUID mine = copy.deck();
        List<Material> m = s.source().materials();
        long revisions = apply.revisions(mine);
        UUID command = UUID.randomUUID();

        HttpResponse<String> response = revert(copy, s.revision(), material(s.safe()), command);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(response.headers().firstValue("idempotency-replayed")).isEmpty();
        JsonNode answer = json(response);
        assertThat(names(answer)).isEqualTo(names(CONTRACT.path("revert").path("reverted").path("body")));
        assertThat(answer.path("state").stringValue(null)).isEqualTo("REVERTED");
        assertThat(answer.path("commandId").stringValue(null)).isEqualTo(command.toString());
        assertThat(answer.path("units")).extracting(JsonNode::stringValue).containsExactly(material(s.safe()));
        assertThat(answer.path("deckRevisionId").stringValue(null)).isEqualTo(worlds.headRevisionOf(mine).toString());

        // the acceptance criterion: the previous head is the head again
        assertThat(worlds.itemHead(mine, s.safe())).isEqualTo(m.get(0).itemRevision()).isNotEqualTo(s.authorSafe());
        assertThat(apply.revisions(mine)).as("one revision").isEqualTo(revisions + 1);
        assertThat(apply.members(mine)).as("nothing moved").hasSize(5 + 1);
        // the other units of the update are untouched
        assertThat(apply.exerciseHead(mine, s.revised().exerciseId())).isEqualTo(s.revised().revisionId());
        assertThat(worlds.itemHead(mine, s.added().member())).isEqualTo(s.added().itemRevision());
        // the decision remembers the author's revision that was taken
        assertThat(apply.decisions(mine)).containsEntry("MATERIAL:" + s.safe(), "KEEP_MINE " + s.authorSafe() + " .");
        // the history says where the unit stands now
        JsonNode unit = unit(auto.history(copy).path("entries").get(0), material(s.safe()));
        assertThat(unit.path("state").stringValue(null)).isEqualTo("RETURNED");
        assertThat(unit.path("revertible").booleanValue(true)).isFalse();

        // the same command again: the stored answer, flagged, nothing more written; another body is a conflict
        String snapshot = auto.full(mine);
        HttpResponse<String> again = revert(copy, s.revision(), material(s.safe()), command);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(json(again)).isEqualTo(answer);
        assertThat(auto.full(mine)).isEqualTo(snapshot);
        problem(revert(copy, s.revision(), material(s.safe()), command, exercise(s.revised().exerciseId())), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(auto.full(mine)).isEqualTo(snapshot);
        // a new command for a unit that is back where it was is stale
        problem(revert(copy, s.revision(), material(s.safe()), UUID.randomUUID()), 409, "UPDATE_REVERT_STALE");
        assertThat(auto.full(mine)).isEqualTo(snapshot);

        // the next automatic run does not put it back: the plan hides it, and the author's next edit of the same unit is a question, never taken
        assertThat(ApplyWorlds.classes(apply.plan(copy))).doesNotContainKey(material(s.safe()));
        worlds.editText(s.source().author(), s.source().deck(), s.safe(), "author zero, again");
        worlds.publish(s.source().author(), s.source().deck(), "LINK");
        assertThat(ApplyWorlds.classes(apply.plan(copy))).containsEntry(material(s.safe()), "ASK_AGAIN");
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(hand.runOne(AutoWorlds.AUTO)).isTrue();
        assertThat(worlds.itemHead(mine, s.safe())).as("not re-applied").isEqualTo(m.get(0).itemRevision());
        assertThat(apply.revisions(mine)).isEqualTo(revisions + 1);
    }

    @Test
    void returningAnAddedMaterialRemovesItOnlyTogetherWithTheExerciseTakenWithItAndTheDecisionDeclinesBoth() {
        Scene s = scene();
        Copy copy = s.copy();
        UUID mine = copy.deck();
        String snapshot = auto.full(mine);

        // the material alone is refused, naming what must come with it
        HttpResponse<String> alone = revert(copy, s.revision(), material(s.added().member()), UUID.randomUUID());
        problem(alone, 400, "INVALID_REQUEST");
        JsonNode refusal = json(alone);
        assertThat(refusal.path("reason").stringValue(null)).isEqualTo("unit_needed");
        assertThat(refusal.path("unitId").stringValue(null)).isEqualTo(material(s.added().member()));
        assertThat(refusal.path("units")).extracting(JsonNode::stringValue).containsExactly(exercise(s.created().exerciseId()));
        assertThat(names(refusal)).isEqualTo(names(CONTRACT.path("problems").path("unitNeeded").path("body")));
        // the exercise alone is refused for the same reason, the other way round
        JsonNode other = json(revert(copy, s.revision(), exercise(s.created().exerciseId()), UUID.randomUUID()));
        assertThat(other.path("units")).extracting(JsonNode::stringValue).containsExactly(material(s.added().member()));
        assertThat(auto.full(mine)).as("a refusal writes nothing").isEqualTo(snapshot);

        long revisions = apply.revisions(mine);
        HttpResponse<String> both = revert(copy, s.revision(), material(s.added().member()), UUID.randomUUID(), exercise(s.created().exerciseId()));
        assertThat(both.statusCode()).as(both.body()).isEqualTo(200);
        assertThat(json(both).path("units")).extracting(JsonNode::stringValue).containsExactly(material(s.added().member()), exercise(s.created().exerciseId()));
        assertThat(apply.revisions(mine)).isEqualTo(revisions + 1);
        assertThat(worlds.itemHead(mine, s.added().member())).isNull();
        assertThat(apply.exerciseHead(mine, s.created().exerciseId())).isNull();
        assertThat(apply.members(mine)).doesNotContain(s.added().member());
        assertThat(apply.decisions(mine)).containsEntry("MATERIAL:" + s.added().member(), "DECLINE_NEW " + s.added().itemRevision() + " .")
                .containsEntry("EXERCISE:" + s.created().exerciseId(), "DECLINE_NEW " + s.created().revisionId() + " .");
        // the author has not changed them since: the plan does not offer them again, and an automatic run takes nothing
        assertThat(ApplyWorlds.classes(apply.plan(copy))).doesNotContainKeys(material(s.added().member()), exercise(s.created().exerciseId()));
        JsonNode unit = unit(auto.history(copy).path("entries").get(0), material(s.added().member()));
        assertThat(unit.path("state").stringValue(null)).isEqualTo("RETURNED");
    }

    @Test
    void anExerciseIsReturnedWithItsObjectiveAndAnAddedMaterialThatALearnersExerciseUsesStays() {
        Scene s = scene();
        Copy copy = s.copy();
        UUID mine = copy.deck();
        // a changed exercise comes back at its previous revision; its objective follows (the head the combined publication verifies is computed, never asked)
        HttpResponse<String> response = revert(copy, s.revision(), exercise(s.revised().exerciseId()), UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(apply.exerciseHead(mine, s.revised().exerciseId())).isEqualTo(s.original().revisionId());
        assertThat(apply.objectiveHead(mine, s.revised().objectiveId())).isEqualTo(s.original().objectiveRevisionId());
        assertThat(apply.decisions(mine)).containsEntry("EXERCISE:" + s.revised().exerciseId(), "KEEP_MINE " + s.revised().revisionId() + " .");

        // the owner builds an exercise of his own on the material the update added: returning the update's addition would leave it quoting nothing
        Material mineAdded = worlds.head(copy.owner(), mine, s.added());
        Authored own = worlds.exercise(mineAdded, "Мой вопрос", null, "Моя цель");
        String snapshot = auto.full(mine);
        HttpResponse<String> blocked = revert(copy, s.revision(), material(s.added().member()), UUID.randomUUID(), exercise(s.created().exerciseId()));
        problem(blocked, 400, "INVALID_REQUEST");
        assertThat(json(blocked).path("reason").stringValue(null)).isEqualTo("material_still_used");
        assertThat(json(blocked).path("dependency").stringValue(null)).isEqualTo(exercise(own.exerciseId()));
        assertThat(auto.full(mine)).isEqualTo(snapshot);
        assertThat(worlds.itemHead(mine, s.added().member())).isNotNull();
    }

    @Test
    void aRemovedMaterialComesBackAtItsPlaceAndAChangedMaterialWithItsExerciseComesBackAsOneGroup() {
        Source source = worlds.source("LINK", 5, 2);
        List<Material> m = source.materials();
        Copy copy = apply.copyByHand(source, hand);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID mine = copy.deck();
        // the author fixes a material and the exercise that quotes it, and removes another material
        UUID fixed = worlds.editText(author, deck, m.get(1).member(), "author one");
        Authored revised = worlds.revise(author, deck, m.get(1), source.exercises().get(1), "Вопрос автора", "Цель автора");
        worlds.deleteMaterial(author, deck, m.get(3).member());
        worlds.publish(author, deck, "LINK");
        assertThat(apply.apply(copy, true, Pick.material(m.get(3).member(), "DELETE")).statusCode()).isEqualTo(200);
        UUID revision = worlds.headRevisionOf(mine);
        assertThat(worlds.itemHead(mine, m.get(3).member())).isNull();
        assertThat(apply.members(mine)).doesNotContain(m.get(3).member());
        List<UUID> order = apply.members(mine);

        // the removed material is back at the place it had
        HttpResponse<String> back = revert(copy, revision, material(m.get(3).member()), UUID.randomUUID());
        assertThat(back.statusCode()).as(back.body()).isEqualTo(200);
        assertThat(worlds.itemHead(mine, m.get(3).member())).isEqualTo(m.get(3).itemRevision());
        List<UUID> restored = apply.members(mine);
        assertThat(restored).hasSize(order.size() + 1);
        assertThat(restored.indexOf(m.get(3).member())).as("where it was (the apply removed it from place 3)").isEqualTo(3);
        assertThat(apply.decisions(mine)).containsEntry("MATERIAL:" + m.get(3).member(), "KEEP_MINE - .");
        assertThat(ApplyWorlds.classes(apply.plan(copy))).as("the author's removal is remembered as answered").doesNotContainKey(material(m.get(3).member()));

        // the fixed material and its exercise were taken together: one cannot be returned without the other
        JsonNode needed = json(revert(copy, revision, material(m.get(1).member()), UUID.randomUUID()));
        assertThat(needed.path("reason").stringValue(null)).isEqualTo("unit_needed");
        assertThat(needed.path("units")).extracting(JsonNode::stringValue).containsExactly(exercise(revised.exerciseId()));
        HttpResponse<String> group = revert(copy, revision, material(m.get(1).member()), UUID.randomUUID(), exercise(revised.exerciseId()));
        assertThat(group.statusCode()).as(group.body()).isEqualTo(200);
        assertThat(worlds.itemHead(mine, m.get(1).member())).isEqualTo(m.get(1).itemRevision()).isNotEqualTo(fixed);
        assertThat(apply.exerciseHead(mine, revised.exerciseId())).isEqualTo(source.exercises().get(1).revisionId());
        assertThat(apply.objectiveHead(mine, revised.objectiveId())).isEqualTo(source.exercises().get(1).objectiveRevisionId());
    }

    @Test
    void aUnitTheOwnerEditedSinceIsRefusedAndNothingIsWritten() {
        Scene s = scene();
        Copy copy = s.copy();
        UUID mine = copy.deck();
        UUID edit = worlds.editText(copy.owner(), mine, s.safe(), "my own words");
        String snapshot = auto.full(mine);

        HttpResponse<String> refused = revert(copy, s.revision(), material(s.safe()), UUID.randomUUID());
        problem(refused, 409, "UPDATE_REVERT_STALE");
        JsonNode body = json(refused);
        assertThat(body.path("unitId").stringValue(null)).isEqualTo(material(s.safe()));
        assertThat(body.path("instance").stringValue(null)).endsWith(":revert");
        assertThat(names(body)).isEqualTo(names(CONTRACT.path("problems").path("revertStale").path("body")));
        assertThat(auto.full(mine)).isEqualTo(snapshot);
        assertThat(worlds.itemHead(mine, s.safe())).isEqualTo(edit);
        JsonNode unit = unit(auto.history(copy).path("entries").get(0), material(s.safe()));
        assertThat(unit.path("state").stringValue(null)).isEqualTo("CHANGED_SINCE");
        assertThat(unit.path("revertible").booleanValue(true)).isFalse();

        // another unit of the same apply is still returnable; the stale one inside a group refuses the whole command
        HttpResponse<String> mixed = revert(copy, s.revision(), exercise(s.revised().exerciseId()), UUID.randomUUID(), material(s.safe()));
        problem(mixed, 409, "UPDATE_REVERT_STALE");
        assertThat(json(mixed).path("unitId").stringValue(null)).isEqualTo(material(s.safe()));
        assertThat(auto.full(mine)).isEqualTo(snapshot);
        assertThat(revert(copy, s.revision(), exercise(s.revised().exerciseId()), UUID.randomUUID()).statusCode()).isEqualTo(200);
    }

    @Test
    void theOtherRefusalsOfTheReturnRoute() {
        Scene s = scene();
        Copy copy = s.copy();
        UUID mine = copy.deck();
        String snapshot = auto.full(mine);
        String unit = material(s.safe());
        String base = "/decks/" + mine + "/update-history/" + s.revision() + "/units/MATERIAL/" + s.safe() + ":revert";
        // not the owner's, not a copy, not an apply of this copy, not a unit of it
        problem(revert(new Copy(UUID.randomUUID(), mine), s.revision(), unit, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(revert(new Copy(s.source().author(), s.source().deck()), s.revision(), unit, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(revert(copy, UUID.randomUUID(), unit, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(revert(copy, s.revision(), material(UUID.randomUUID()), UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(revert(copy, s.revision(), material(s.source().materials().get(2).member()), UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        // the objective moves with its exercises, the title and the order are not units of the content
        for (String kind : new String[] {"OBJECTIVE", "METADATA", "ORDER", "TABLE", "material"}) {
            HttpResponse<String> response = worlds.send("POST", "/decks/" + mine + "/update-history/" + s.revision() + "/units/" + kind + "/" + s.safe() + ":revert", copy.owner(),
                    JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).toString(), null);
            problem(response, 400, "INVALID_REQUEST");
        }
        // the body
        for (String body : new String[] {"{}", "[]", "{\"commandId\":\"x\"}", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"extra\":1}",
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"with\":[\"MATERIAL:" + s.safe() + "\"]}", "not json"}) {
            HttpResponse<String> response = worlds.send("POST", base, copy.owner(), body, null);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
        }
        assertThat(HttpIdentityFixture.send(port, "POST", base, null, "{}", java.util.Map.of()).statusCode()).isEqualTo(401);
        assertThat(HttpIdentityFixture.send(port, "POST", base, HttpIdentityFixture.token(copy.owner(), "learning.read"),
                JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).toString(), java.util.Map.of()).statusCode()).isEqualTo(403);
        assertThat(auto.full(mine)).isEqualTo(snapshot);

        // detached: there is no source to follow, nothing to take back
        assertThat(worlds.send("POST", "/decks/" + mine + "/fork:detach", copy.owner(), null, null).statusCode()).isEqualTo(200);
        problem(revert(copy, s.revision(), unit, UUID.randomUUID()), 409, "FORK_DETACHED");
        JsonNode detached = unit(auto.history(copy).path("entries").get(0), unit);
        assertThat(detached.path("revertible").booleanValue(true)).as("a detached copy keeps its history but returns nothing").isFalse();
        assertThat(detached.path("state").stringValue(null)).isEqualTo("CURRENT");
        assertThat(auto.deck(copy).path("fork").has("lastUpdate")).as("no notice on a detached copy").isFalse();
        assertThat(worlds.itemHead(mine, s.safe())).isEqualTo(s.authorSafe());
    }

    @Test
    void thePlanAndTheHistoryNeverChangeWhatTheCopyHoldsOnTheirOwn() {
        Scene s = scene();
        Copy copy = s.copy();
        String snapshot = auto.full(copy.deck());
        // an automatic run for a revision the copy already stands on does nothing
        TransactionTemplate tx = new TransactionTemplate(transactions);
        ApplyService.AutoResult again = tx.execute(status -> applies.applyAuto(copy.owner(), copy.deck()));
        assertThat(again.outcome()).isEqualTo(ApplyService.AutoOutcome.NOTHING_SAFE);
        assertThat(again.unresolved()).isEqualTo(1);
        assertThat(auto.full(copy.deck())).isEqualTo(snapshot);
    }

    @Test
    void aRemovedMaterialComesBackBehindItsNeighbourEvenWhenTheSameApplyAddedAndRemovedMembersBeforeIt() {
        Source source = worlds.source("LINK", 5, 0);
        List<Material> m = source.materials();
        Copy copy = apply.copyByHand(source, hand);
        UUID author = source.author();
        UUID mine = copy.deck();
        // the author puts a new material FIRST and removes two in the middle: the places the journal keeps (3 and 2 in the old manifest) are not places in the new one
        Material first = worlds.study.addMaterial(author, source.deck(), "первое", "first");
        worlds.reorder(author, source.deck(), first.member(), 0);
        worlds.deleteMaterial(author, source.deck(), m.get(2).member());
        worlds.deleteMaterial(author, source.deck(), m.get(3).member());
        worlds.publish(author, source.deck(), "LINK");
        assertThat(apply.apply(copy, true, Pick.material(m.get(2).member(), "DELETE"), Pick.material(m.get(3).member(), "DELETE")).statusCode()).isEqualTo(200);
        UUID revision = worlds.headRevisionOf(mine);
        assertThat(apply.members(mine)).containsExactly(first.member(), m.get(0).member(), m.get(1).member(), m.get(4).member());

        // one of them back: right behind the member it followed (m1), not at the old place number
        HttpResponse<String> one = revert(copy, revision, material(m.get(3).member()), UUID.randomUUID());
        assertThat(one.statusCode()).as(one.body()).isEqualTo(200);
        assertThat(apply.members(mine)).containsExactly(first.member(), m.get(0).member(), m.get(1).member(), m.get(3).member(), m.get(4).member());
        // the other one: its neighbour in the old manifest (m1, the one before it, is there; m2 followed m1)
        HttpResponse<String> two = revert(copy, revision, material(m.get(2).member()), UUID.randomUUID());
        assertThat(two.statusCode()).as(two.body()).isEqualTo(200);
        assertThat(apply.members(mine)).as("m2 was before m3 in the old manifest, behind m1").containsExactly(first.member(), m.get(0).member(), m.get(1).member(), m.get(2).member(),
                m.get(3).member(), m.get(4).member());
    }

    @Test
    void bothRemovedMaterialsComeBackInOneCommandInTheOrderTheyHadAndAHistoryPageCostsAFixedNumberOfStatements() {
        Source source = worlds.source("LINK", 6, 0);
        List<Material> m = source.materials();
        Copy copy = apply.copyByHand(source, hand);
        UUID author = source.author();
        UUID mine = copy.deck();
        worlds.deleteMaterial(author, source.deck(), m.get(2).member());
        worlds.deleteMaterial(author, source.deck(), m.get(3).member());
        worlds.publish(author, source.deck(), "LINK");
        assertThat(apply.apply(copy, true, Pick.material(m.get(2).member(), "DELETE"), Pick.material(m.get(3).member(), "DELETE")).statusCode()).isEqualTo(200);
        UUID revision = worlds.headRevisionOf(mine);
        HttpResponse<String> both = revert(copy, revision, material(m.get(3).member()), UUID.randomUUID(), material(m.get(2).member()));
        assertThat(both.statusCode()).as(both.body()).isEqualTo(200);
        assertThat(apply.members(mine)).containsExactlyElementsOf(m.stream().map(Material::member).toList());

        // more applies, every one with units: the history of 2 entries and of 6 cost the same statements
        for (int index = 0; index < 5; index++) {
            worlds.editText(author, source.deck(), m.get(index % 2 == 0 ? 0 : 1).member(), "round " + index);
            worlds.editText(author, source.deck(), m.get(5).member(), "last " + index);
            worlds.publish(author, source.deck(), "LINK");
            assertThat(apply.apply(copy, true).statusCode()).isEqualTo(200);
        }
        assertThat(json(worlds.get("/decks/" + mine + "/update-history?limit=20", copy.owner())).path("entries")).hasSize(6);
        long[] two = statements(copy, 2);
        long[] six = statements(copy, 6);
        assertThat(json(worlds.get("/decks/" + mine + "/update-history?limit=6", copy.owner())).path("entries")).hasSize(6);
        assertThat(six[0]).as("6 entries cost the statements of 2: " + two[0] + " vs " + six[0]).isEqualTo(two[0]);
        assertThat(six[0]).isLessThanOrEqualTo(30);
        System.out.println("HISTORY_STATEMENTS entries=2 -> " + two[0] + ", entries=6 -> " + six[0]);
    }

    /** The statements of one call of the history route (warm: the second of two calls). */
    private long[] statements(Copy copy, int limit) {
        String path = "/decks/" + copy.deck() + "/update-history?limit=" + limit;
        worlds.get(path, copy.owner());
        long before = STATEMENTS.get();
        assertThat(worlds.get(path, copy.owner()).statusCode()).isEqualTo(200);
        return new long[] {STATEMENTS.get() - before};
    }
}
