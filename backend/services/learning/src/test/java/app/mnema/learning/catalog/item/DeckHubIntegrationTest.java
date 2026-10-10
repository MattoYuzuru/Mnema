package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.deck.DeckInsightsService;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.study.progress.StudyProgressService;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deck hub (#285) against PostgreSQL: insights equal the sums over materials on a 50-material deck, the
 * exerciseCount sort pages without duplicates or gaps, the exemplar flag and the bulk deletion.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeckHubIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private DeckInsightsService insights;
    @Autowired private StudyProgressService progress;
    @Autowired private ItemExemplarService exemplars;
    @Autowired private ItemBulkDeleteService deletions;

    private HubFixtures fixtures;
    private UUID actor;
    private UUID deck;
    private List<HubFixtures.Created> materials;
    private final Map<String, Integer> mechanicCounts = new LinkedHashMap<>();
    private final Map<UUID, Integer> expectedExerciseCount = new HashMap<>();

    @BeforeAll
    void fiftyMaterialDeck() {
        fixtures = new HubFixtures(decks, items, exercises, sessions, media, jdbc);
        actor = UUID.randomUUID();
        deck = fixtures.deck(actor);
        materials = fixtures.seed(actor, deck, 50);
        Instant now = Instant.now();
        for (int index = 0; index < materials.size(); index++) {
            HubFixtures.Created material = materials.get(index);
            List<String> types = switch (index % 5 == 0 ? 0 : index % 3 == 0 ? 3 : index % 3 == 1 ? 1 : 2) {
                case 0 -> List.of();
                case 1 -> List.of("SELF_CHECK");
                case 2 -> List.of("FREE_RESPONSE", "CHOICE");
                default -> List.of("SELF_CHECK", "CLOZE", "CHOICE");
            };
            for (String type : types) {
                fixtures.exercise(actor, deck, material, type, true);
                mechanicCounts.merge(type, 1, Integer::sum);
            }
            // A disabled exercise is authored but is neither coverage nor a counted mechanic.
            if (index % 7 == 1) fixtures.exercise(actor, deck, material, "FREE_RESPONSE", false);
            expectedExerciseCount.put(material.member(), types.size());
            if (types.isEmpty()) continue;
            switch (index % 4) {
                case 1 -> fixtures.state(actor, deck, material.member(), 0, false, null);
                case 2 -> fixtures.state(actor, deck, material.member(), 3, true, now.minus(2, ChronoUnit.HOURS));
                case 3 -> fixtures.state(actor, deck, material.member(), 3, true,
                        now.plus(new int[] {2, 3, 5, 9}[index % 4 == 3 ? (index / 4) % 4 : 0], ChronoUnit.DAYS));
                default -> { }
            }
        }
    }

    @Test
    void coverageStatesDueDaysAndMechanicsEqualTheSumOverMaterials() {
        JsonNode result = insights.read(actor, deck, "Europe/Moscow");
        Map<UUID, Integer> counts = listedCounts();

        int withExercises = (int) counts.values().stream().filter(count -> count > 0).count();
        assertThat(result.path("coverage").path("total").intValue()).isEqualTo(50);
        assertThat(result.path("coverage").path("withExercises").intValue()).isEqualTo(withExercises);
        assertThat(result.path("coverage").path("withoutExercises").intValue()).isEqualTo(50 - withExercises);
        assertThat(withExercises).isEqualTo(40);

        Map<String, Integer> states = new LinkedHashMap<>(Map.of("NOT_STARTED", 0, "LEARNING", 0, "DUE", 0, "ON_TRACK", 0));
        int[] due = new int[7];
        LocalDate today = LocalDate.parse(result.path("dueByDay").get(0).path("date").stringValue(null));
        String cursor = null;
        do {
            JsonNode page = progress.read(actor, deck, 100, cursor);
            for (JsonNode material : page.path("items")) {
                states.merge(material.path("state").stringValue(null), 1, Integer::sum);
                if (!material.path("nextDue").isNull()) {
                    long day = ChronoUnit.DAYS.between(today, Instant.parse(material.path("nextDue").stringValue(null))
                            .atZone(MOSCOW).toLocalDate());
                    if (day < 7) due[(int) Math.max(day, 0)]++;
                }
            }
            cursor = page.path("nextCursor").stringValue(null);
        } while (cursor != null);
        states.forEach((state, expected) -> assertThat(result.path("states").path(state).intValue()).as(state).isEqualTo(expected));
        assertThat(states.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(50);
        assertThat(states.get("DUE")).isPositive();
        assertThat(states.get("ON_TRACK")).isPositive();
        assertThat(states.get("LEARNING")).isPositive();
        for (int day = 0; day < 7; day++) {
            assertThat(result.path("dueByDay").get(day).path("materials").intValue()).as("day %d", day).isEqualTo(due[day]);
            assertThat(result.path("dueByDay").get(day).path("date").stringValue(null)).isEqualTo(today.plusDays(day).toString());
        }
        assertThat(result.path("dueByDay")).hasSize(7);
        assertThat(due[0]).isEqualTo(states.get("DUE"));

        int mechanicsTotal = 0;
        for (String mechanic : List.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE")) {
            int expected = mechanicCounts.getOrDefault(mechanic, 0);
            assertThat(result.path("exercisesByMechanic").path(mechanic).intValue()).as(mechanic).isEqualTo(expected);
            mechanicsTotal += expected;
        }
        assertThat(mechanicsTotal).isEqualTo(counts.values().stream().mapToInt(Integer::intValue).sum());
        assertThat(result.path("timezone").stringValue(null)).isEqualTo("Europe/Moscow");
        assertThat(result.path("deckRevisionId").stringValue(null))
                .isEqualTo(fixtures.head(actor, deck).path("revisionId").stringValue(null));
    }

    @Test
    void zoneComesFromTheAccountClaimElseTheProductCalendarZone() {
        assertThat(insights.read(actor, deck, "Asia/Tokyo").path("timezone").stringValue(null)).isEqualTo("Asia/Tokyo");
        assertThat(insights.read(actor, deck, null).path("timezone").stringValue(null)).isEqualTo("Europe/Moscow");
        assertThat(insights.read(actor, deck, "Not/AZone").path("timezone").stringValue(null)).isEqualTo("Europe/Moscow");
        assertThat(insights.read(actor, deck, " ").path("timezone").stringValue(null)).isEqualTo("Europe/Moscow");
    }

    @Test
    void emptyDeckAndForeignDeckAndOpenCaptures() {
        UUID owner = UUID.randomUUID();
        UUID empty = fixtures.deck(owner);
        JsonNode result = insights.read(owner, empty, "Europe/Moscow");
        assertThat(result.path("coverage").path("total").intValue()).isZero();
        assertThat(result.path("states").toString()).isEqualTo("{\"NOT_STARTED\":0,\"LEARNING\":0,\"DUE\":0,\"ON_TRACK\":0}");
        assertThat(result.path("exercisesByMechanic")).hasSize(7);
        assertThat(result.path("captures").path("open").intValue()).isZero();
        assertThat(result.path("captures").path("oldestOpenCreatedAt").isNull()).isTrue();
        assertThatThrownBy(() -> insights.read(UUID.randomUUID(), empty, null)).isInstanceOf(ResourceNotFoundException.class);

        capture(owner, empty, "2026-09-12T12:00:00.123456Z", false);
        capture(owner, empty, "2026-09-10T08:00:00Z", true);
        capture(owner, empty, "2026-09-15T08:00:00Z", false);
        JsonNode withNotes = insights.read(owner, empty, null);
        assertThat(withNotes.path("captures").path("open").intValue()).isEqualTo(2);
        assertThat(withNotes.path("captures").path("oldestOpenCreatedAt").stringValue(null))
                .isEqualTo("2026-09-12T12:00:00.123456Z");
    }

    @Test
    void exerciseCountSortPagesWithoutDuplicatesOrGapsAndKeepsAuthoringOrdinals() {
        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = items.list(actor, deck, "7", cursor, "exerciseCount", null);
            page.path("items").forEach(all::add);
            cursor = page.path("nextCursor").stringValue(null);
            pages++;
            assertThat(page.path("total").intValue()).isEqualTo(50);
            assertThat(page.path("items").size()).isLessThanOrEqualTo(7);
        } while (cursor != null);
        assertThat(pages).isEqualTo(8);
        assertThat(all).hasSize(50);
        assertThat(all.stream().map(item -> item.path("memberKey").stringValue(null)).distinct()).hasSize(50);
        int previousCount = -1;
        int previousOrdinal = -1;
        for (JsonNode item : all) {
            int count = item.path("exerciseCount").intValue();
            int ordinal = item.path("ordinal").intValue();
            assertThat(count).isGreaterThanOrEqualTo(previousCount);
            if (count == previousCount) assertThat(ordinal).isGreaterThan(previousOrdinal);
            assertThat(count).isEqualTo(expectedExerciseCount.get(UUID.fromString(item.path("memberKey").stringValue(null))));
            assertThat(materials.get(ordinal).member().toString()).isEqualTo(item.path("memberKey").stringValue(null));
            assertThat(item.path("title").stringValue(null)).isEqualTo("Material " + ordinal);
            assertThat(item.path("exemplar").booleanValue()).isFalse();
            previousCount = count;
            previousOrdinal = ordinal;
        }
        assertThat(all.subList(0, 10)).allSatisfy(item -> assertThat(item.path("exerciseCount").intValue()).isZero());
        assertThat(all.get(10).path("exerciseCount").intValue()).isPositive();
    }

    @Test
    void includeAddsCountsToTheOrderedListAndMalformedOptionsAreRejected() {
        JsonNode plain = items.list(actor, deck, "5", null, null, null);
        assertThat(plain.path("items").get(0).has("exerciseCount")).isFalse();
        assertThat(plain.path("items").get(0).has("exemplar")).isTrue();
        JsonNode counted = items.list(actor, deck, "5", null, "ordinal", "exerciseCount");
        for (JsonNode item : counted.path("items")) {
            assertThat(item.path("exerciseCount").intValue())
                    .isEqualTo(expectedExerciseCount.get(UUID.fromString(item.path("memberKey").stringValue(null))));
        }
        assertThat(counted.path("exemplars").toString()).isEqualTo("{\"count\":0,\"limit\":10}");

        for (String[] invalid : new String[][] {{"name", null}, {"", null}, {null, "exemplar"}, {null, ""},
                {null, "exerciseCount,exerciseCount"}, {null, "exerciseCount,exemplar"}}) {
            assertThatThrownBy(() -> items.list(actor, deck, "5", null, invalid[0], invalid[1]))
                    .isInstanceOf(InvalidRequestException.class);
        }
        String ordinalCursor = plain.path("nextCursor").stringValue(null);
        String sortedCursor = items.list(actor, deck, "5", null, "exerciseCount", null).path("nextCursor").stringValue(null);
        assertThatThrownBy(() -> items.list(actor, deck, "5", ordinalCursor, "exerciseCount", null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> items.list(actor, deck, "5", sortedCursor, null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> items.list(actor, deck, "5", "AAAA", "exerciseCount", null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void sortedCursorBindsTheDeckRevisionAndAnExerciseChangeInvalidatesIt() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        List<HubFixtures.Created> created = fixtures.seed(owner, own, 5);
        String cursor = items.list(owner, own, "2", null, "exerciseCount", null).path("nextCursor").stringValue(null);
        assertThat(cursor).isNotNull();
        assertThat(items.list(owner, own, "2", cursor, "exerciseCount", null).path("items")).hasSize(2);
        fixtures.exercise(owner, own, created.getFirst(), "SELF_CHECK", true);
        assertThatThrownBy(() -> items.list(owner, own, "2", cursor, "exerciseCount", null))
                .isInstanceOf(VersionConflictException.class);
        // The exercise moved the first material to the end of the sorted order.
        JsonNode fresh = items.list(owner, own, "5", null, "exerciseCount", null);
        assertThat(fresh.path("items").get(4).path("memberKey").stringValue(null)).isEqualTo(created.getFirst().member().toString());
        assertThat(fresh.path("items").get(4).path("ordinal").intValue()).isZero();
        assertThat(fresh.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void emptyAndForeignDecksListWithAndWithoutSort() {
        UUID owner = UUID.randomUUID();
        UUID empty = fixtures.deck(owner);
        assertThat(items.list(owner, empty, null, null, "exerciseCount", null).path("items")).isEmpty();
        assertThat(items.list(owner, empty, null, null, null, "exerciseCount").path("nextCursor").isNull()).isTrue();
        assertThatThrownBy(() -> items.list(UUID.randomUUID(), empty, null, null, "exerciseCount", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- exemplars ----

    @Test
    void exemplarSetClearReplayAndTheTenPerDeckLimit() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        List<HubFixtures.Created> created = fixtures.seed(owner, own, 12);
        long version = fixtures.version(owner, own);
        UUID command = UUID.randomUUID();
        var first = exemplars.set(owner, own, created.getFirst().member(), exemplar(command, created.getFirst(), true));
        assertThat(first.replayed()).isFalse();
        assertThat(first.acknowledgement().path("changed").booleanValue()).isTrue();
        assertThat(first.acknowledgement().path("exemplarCount").intValue()).isOne();
        assertThat(first.acknowledgement().path("itemRevisionId").stringValue(null))
                .isEqualTo(created.getFirst().revision().toString());
        // Not a revision: no new Deck version, no new item revision, item version and update time unchanged.
        assertThat(fixtures.version(owner, own)).isEqualTo(version);
        JsonNode read = items.read(owner, own, created.getFirst().member(), null);
        assertThat(read.path("exemplar").booleanValue()).isTrue();
        assertThat(read.path("itemVersion").stringValue(null)).isEqualTo("0");

        var replay = exemplars.set(owner, own, created.getFirst().member(), exemplar(command, created.getFirst(), true));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.acknowledgement()).isEqualTo(first.acknowledgement());
        assertThatThrownBy(() -> exemplars.set(owner, own, created.getFirst().member(),
                exemplar(command, created.getFirst(), false))).isInstanceOf(IdempotencyConflictException.class);
        var noop = exemplars.set(owner, own, created.getFirst().member(), exemplar(UUID.randomUUID(), created.getFirst(), true));
        assertThat(noop.acknowledgement().path("changed").booleanValue()).isFalse();
        assertThat(noop.acknowledgement().path("exemplarCount").intValue()).isOne();

        for (int index = 1; index < 10; index++) {
            exemplars.set(owner, own, created.get(index).member(), exemplar(UUID.randomUUID(), created.get(index), true));
        }
        JsonNode listed = items.list(owner, own, "20", null, null, null);
        assertThat(listed.path("exemplars").toString()).isEqualTo("{\"count\":10,\"limit\":10}");
        assertThat(listed.path("items").findValues("exemplar").stream().filter(JsonNode::booleanValue)).hasSize(10);
        assertThatThrownBy(() -> exemplars.set(owner, own, created.get(10).member(),
                exemplar(UUID.randomUUID(), created.get(10), true))).isInstanceOf(ExemplarLimitReachedException.class);
        assertThat(new ExemplarLimitReachedException().extension().members()).containsEntry("limit", 10L);
        // At the limit an already-marked item is a no-op and clearing frees a slot.
        assertThat(exemplars.set(owner, own, created.get(2).member(), exemplar(UUID.randomUUID(), created.get(2), true))
                .acknowledgement().path("changed").booleanValue()).isFalse();
        exemplars.set(owner, own, created.get(2).member(), exemplar(UUID.randomUUID(), created.get(2), false));
        assertThat(exemplars.set(owner, own, created.get(10).member(), exemplar(UUID.randomUUID(), created.get(10), true))
                .acknowledgement().path("exemplarCount").intValue()).isEqualTo(10);
        assertThat(count("deck_item_exemplar", own)).isEqualTo(10);
    }

    @Test
    void exemplarRequiresTheCurrentItemRevisionAndAnOwnedMember() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        HubFixtures.Created material = fixtures.seed(owner, own, 1).getFirst();
        JsonNode head = fixtures.head(owner, own);
        ObjectNode save = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", material.revision().toString()).put("expectedOrdinal", 0);
        ObjectNode edited = (ObjectNode) items.read(owner, own, material.member(), null).path("document").deepCopy();
        ((ObjectNode) edited.path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", "Edited");
        save.set("document", edited);
        items.publish(owner, own, fixtures.version(owner, own), ItemPublicationCommand.readSave(bytes(save), material.member()));

        assertThatThrownBy(() -> exemplars.set(owner, own, material.member(), exemplar(UUID.randomUUID(), material, true)))
                .isInstanceOf(VersionConflictException.class);
        assertThat(count("deck_item_exemplar", own)).isZero();
        assertThatThrownBy(() -> exemplars.set(UUID.randomUUID(), own, material.member(),
                exemplar(UUID.randomUUID(), material, true))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> exemplars.set(owner, own, UUID.randomUUID(), exemplar(UUID.randomUUID(), material, true)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ExemplarCommand.read(bytes(JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("exemplar", true)))).isInstanceOf(VersionPreconditionRequiredException.class);
        assertThatThrownBy(() -> ExemplarCommand.read(bytes(JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedItemRevisionId", material.revision().toString()).put("exemplar", "yes"))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ExemplarCommand.read(bytes(JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedItemRevisionId", material.revision().toString()).put("exemplar", true).put("extra", 1))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void deletingAMaterialDropsItsExemplarAndATombstonedDeckShowsNothing() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        List<HubFixtures.Created> created = fixtures.seed(owner, own, 3);
        exemplars.set(owner, own, created.get(1).member(), exemplar(UUID.randomUUID(), created.get(1), true));
        exemplars.set(owner, own, created.get(2).member(), exemplar(UUID.randomUUID(), created.get(2), true));
        assertThat(count("deck_item_exemplar", own)).isEqualTo(2);

        // The single-item delete is the same publication path as the bulk one.
        ObjectNode delete = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", fixtures.head(owner, own).path("revisionId").stringValue(null));
        delete.putArray("changes").addObject().put("operation", "delete").put("memberKey", created.get(1).member().toString())
                .put("expectedItemRevisionId", created.get(1).revision().toString()).put("expectedOrdinal", 1);
        items.publish(owner, own, fixtures.version(owner, own), ItemPublicationCommand.readBulk(bytes(delete)));
        assertThat(count("deck_item_exemplar", own)).isOne();
        assertThat(items.list(owner, own, null, null, null, null).path("exemplars").path("count").intValue()).isOne();

        // A stale row (a delete racing the insert) is invisible and uncounted.
        jdbc.sql("INSERT INTO app_learning.deck_item_exemplar(deck_id,reuse_scope_id,member_key,marked_at) "
                + "VALUES (:deck,(SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id=:deck),:member,now())")
                .param("deck", own).param("member", created.get(1).member()).update();
        JsonNode listed = items.list(owner, own, null, null, null, null);
        assertThat(listed.path("exemplars").path("count").intValue()).isOne();
        assertThat(listed.path("items").findValues("exemplar").stream().filter(JsonNode::booleanValue)).hasSize(1);

        decks.delete(owner, own, fixtures.version(owner, own));
        assertThatThrownBy(() -> items.list(owner, own, null, null, null, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> exemplars.set(owner, own, created.get(2).member(), exemplar(UUID.randomUUID(), created.get(2), false)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> insights.read(owner, own, null)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- bulk delete ----

    @Test
    void sevenMaterialsAreDeletedAtomicallyWithAnExactPreviewAndAReplayableResult() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        List<HubFixtures.Created> created = fixtures.seed(owner, own, 10);
        for (int index : new int[] {0, 1, 1, 2, 2, 2}) {
            fixtures.exercise(owner, own, created.get(index), index == 2 ? "CHOICE" : "SELF_CHECK", true);
        }
        fixtures.exercise(owner, own, created.get(1), "FREE_RESPONSE", false);   // disabled still counts as affected
        exemplars.set(owner, own, created.get(3).member(), exemplar(UUID.randomUUID(), created.get(3), true));
        List<HubFixtures.Created> selected = created.subList(0, 7);
        JsonNode head = fixtures.head(owner, own);
        String revision = head.path("revisionId").stringValue(null);

        JsonNode preview = deletions.preview(owner, own, selection(revision, selected));
        assertThat(preview.path("materialCount").intValue()).isEqualTo(7);
        assertThat(preview.path("affectedExerciseCount").intValue()).isEqualTo(7);
        assertThat(preview.path("deckRevisionId").stringValue(null)).isEqualTo(revision);
        assertThat(count("deck_item_change", own)).isEqualTo(10);  // preview wrote nothing

        UUID command = UUID.randomUUID();
        long version = fixtures.version(owner, own);
        var result = deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(deleteBody(command, revision, selected))));
        JsonNode body = result.acknowledgement();
        assertThat(result.replayed()).isFalse();
        assertThat(body.path("status").stringValue(null)).isEqualTo("COMPLETED");
        assertThat(body.path("requested").intValue()).isEqualTo(7);
        assertThat(body.path("deleted").intValue()).isEqualTo(7);
        assertThat(body.path("notDeleted")).isEmpty();
        assertThat(body.path("stopReason").isNull()).isTrue();
        assertThat(body.path("memberCount").intValue()).isEqualTo(3);
        assertThat(body.path("deckVersion").stringValue(null)).isEqualTo(Long.toString(version + 1));
        assertThat(count("deck_item_change", own)).isEqualTo(17);

        JsonNode remaining = items.list(owner, own, "20", null, null, null);
        assertThat(remaining.path("total").intValue()).isEqualTo(3);
        assertThat(remaining.path("items").findValues("memberKey").stream().map(JsonNode::stringValue))
                .containsExactlyElementsOf(created.subList(7, 10).stream().map(item -> item.member().toString()).toList());
        assertThat(remaining.path("exemplars").path("count").intValue()).isZero();  // the star went with its material
        assertThat(count("deck_item_exemplar", own)).isZero();
        // Study history, exercise definitions and immutable material revisions are retained.
        assertThat(count("item_revision", own)).isEqualTo(10);
        assertThat(count("exercise_definition", own)).isEqualTo(7);

        var replay = deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(deleteBody(command, revision, selected))));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.acknowledgement()).isEqualTo(body);
        // The order of ids does not change the command; a different selection under the same id does.
        List<HubFixtures.Created> reversed = new ArrayList<>(selected);
        java.util.Collections.reverse(reversed);
        assertThat(deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(deleteBody(command, revision, reversed))))
                .replayed()).isTrue();
        assertThatThrownBy(() -> deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(deleteBody(command, revision,
                selected.subList(0, 6)))))).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aStaleDeckDeletesNothingAndUnknownOrForeignMembersAreOpaque() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        List<HubFixtures.Created> created = fixtures.seed(owner, own, 4);
        String revision = fixtures.head(owner, own).path("revisionId").stringValue(null);
        long version = fixtures.version(owner, own);
        fixtures.seed(owner, own, 1);   // somebody else's publication advances the Deck

        assertThatThrownBy(() -> deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(deleteBody(
                UUID.randomUUID(), revision, created.subList(0, 2)))))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> deletions.preview(owner, own, selection(revision, created.subList(0, 2))))
                .isInstanceOf(VersionConflictException.class);
        assertThat(items.list(owner, own, null, null, null, null).path("total").intValue()).isEqualTo(5);

        String head = fixtures.head(owner, own).path("revisionId").stringValue(null);
        long current = fixtures.version(owner, own);
        HubFixtures.Created stranger = new HubFixtures.Created(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> deletions.delete(owner, own, current, BulkDeleteCommand.read(bytes(deleteBody(
                UUID.randomUUID(), head, List.of(created.getFirst(), stranger)))))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> deletions.delete(UUID.randomUUID(), own, current, BulkDeleteCommand.read(bytes(deleteBody(
                UUID.randomUUID(), head, List.of(created.getFirst())))))).isInstanceOf(ResourceNotFoundException.class);
        ObjectNode unknownRevision = deleteBody(UUID.randomUUID(), UUID.randomUUID().toString(), List.of(created.getFirst()));
        assertThatThrownBy(() -> deletions.delete(owner, own, current, BulkDeleteCommand.read(bytes(unknownRevision))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(items.list(owner, own, null, null, null, null).path("total").intValue()).isEqualTo(5);
    }

    @Test
    void selectionShapesAreStrict() {
        UUID id = UUID.randomUUID();
        String revision = UUID.randomUUID().toString();
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(JSON.createObjectNode().put("commandId", id.toString()).put("expectedDeckRevisionId", revision));
        ObjectNode both = JSON.createObjectNode().put("commandId", id.toString()).put("expectedDeckRevisionId", revision)
                .put("allInDeck", true);
        both.putArray("itemIds").add(UUID.randomUUID().toString());
        invalid.add(both);
        ObjectNode exceptWithoutAll = JSON.createObjectNode().put("commandId", id.toString()).put("expectedDeckRevisionId", revision);
        exceptWithoutAll.putArray("itemIds").add(UUID.randomUUID().toString());
        exceptWithoutAll.putArray("except");
        invalid.add(exceptWithoutAll);
        ObjectNode empty = JSON.createObjectNode().put("commandId", id.toString()).put("expectedDeckRevisionId", revision);
        empty.putArray("itemIds");
        invalid.add(empty);
        ObjectNode duplicate = JSON.createObjectNode().put("commandId", id.toString()).put("expectedDeckRevisionId", revision);
        String same = UUID.randomUUID().toString();
        duplicate.putArray("itemIds").add(same).add(same);
        invalid.add(duplicate);
        ObjectNode tooMany = JSON.createObjectNode().put("commandId", id.toString()).put("expectedDeckRevisionId", revision);
        ArrayNode many = tooMany.putArray("itemIds");
        for (int index = 0; index < 101; index++) many.add(UUID.randomUUID().toString());
        invalid.add(tooMany);
        invalid.add(JSON.createObjectNode().put("commandId", id.toString()).put("expectedDeckRevisionId", revision)
                .put("allInDeck", false));
        ObjectNode unknownField = JSON.createObjectNode().put("commandId", id.toString()).put("expectedDeckRevisionId", revision)
                .put("allInDeck", true).put("dryRun", true);
        invalid.add(unknownField);
        invalid.add(JSON.createObjectNode().put("commandId", "not-a-uuid").put("expectedDeckRevisionId", revision).put("allInDeck", true));
        for (ObjectNode body : invalid) {
            assertThatThrownBy(() -> BulkDeleteCommand.read(bytes(body))).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> BulkDeleteCommand.read(bytes(JSON.createObjectNode().put("commandId", id.toString())
                .put("allInDeck", true)))).isInstanceOf(VersionPreconditionRequiredException.class);
        assertThat(BulkDeleteCommand.read(bytes(JSON.createObjectNode().put("commandId", id.toString())
                .put("expectedDeckRevisionId", revision).put("allInDeck", true))).selection().except()).isEmpty();
    }

    @Test
    void allInDeckMinusExceptDeletesTheRestAndAnEmptyResolutionIsRejected() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        List<HubFixtures.Created> created = fixtures.seed(owner, own, 6);
        String revision = fixtures.head(owner, own).path("revisionId").stringValue(null);
        long version = fixtures.version(owner, own);

        ObjectNode everything = allBody(UUID.randomUUID(), revision, created);
        assertThatThrownBy(() -> deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(everything))))
                .isInstanceOf(InvalidRequestException.class);
        ObjectNode unknownExcept = allBody(UUID.randomUUID(), revision, List.of(new HubFixtures.Created(UUID.randomUUID(), UUID.randomUUID())));
        assertThatThrownBy(() -> deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(unknownExcept))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(deletions.preview(owner, own, allBody(null, revision, created.subList(0, 2))).path("materialCount").intValue())
                .isEqualTo(4);

        var result = deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(
                allBody(UUID.randomUUID(), revision, created.subList(0, 2)))));
        assertThat(result.acknowledgement().path("status").stringValue(null)).isEqualTo("COMPLETED");
        assertThat(result.acknowledgement().path("deleted").intValue()).isEqualTo(4);
        assertThat(items.list(owner, own, "10", null, null, null).path("items").findValues("memberKey").stream()
                .map(JsonNode::stringValue)).containsExactly(created.get(0).member().toString(), created.get(1).member().toString());
    }

    @Test
    void aSelectionAboveTheCapIsRejectedBeforeAnythingIsRead() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        fixtures.seed(owner, own, 3);
        String revision = fixtures.head(owner, own).path("revisionId").stringValue(null);
        long version = fixtures.version(owner, own);
        // Metadata only: a 600-member revision is rejected by the cap before the member root is read.
        new org.springframework.transaction.support.TransactionTemplate(transactionBean).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_revision SET member_count=600 WHERE deck_id=:deck AND revision_id=CAST(:revision AS uuid)")
                    .param("deck", own).param("revision", revision).update();
        });
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", revision).put("allInDeck", true);
        assertThatThrownBy(() -> deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(body))))
                .isInstanceOf(BulkSelectionTooLargeException.class);
        body.remove("commandId");
        assertThatThrownBy(() -> deletions.preview(owner, own, body)).isInstanceOf(BulkSelectionTooLargeException.class);
        assertThat(new BulkSelectionTooLargeException().extension().members()).containsEntry("limit", 500L);
        assertThat(count("deck_head_item", own)).isEqualTo(3);
    }

    @Test
    void aForeignPublicationBetweenChunksYieldsAPartialResultAndAnExactRetryReplaysIt() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        List<HubFixtures.Created> created = fixtures.seed(owner, own, 130);
        String revision = fixtures.head(owner, own).path("revisionId").stringValue(null);
        long version = fixtures.version(owner, own);
        int[] chunks = {0};
        ItemService interfering = interfering(() -> {
            if (chunks[0]++ == 0) fixtures.seed(owner, own, 1);   // a foreign publication lands after chunk 1
        });
        ItemBulkDeleteService service = new ItemBulkDeleteService(interfering, itemRepository(), receipts());
        UUID command = UUID.randomUUID();
        ObjectNode body = allBody(command, revision, created.subList(0, 5));   // 125 selected: chunks of 100 and 25

        var result = service.delete(owner, own, version, BulkDeleteCommand.read(bytes(body)));
        JsonNode partial = result.acknowledgement();
        assertThat(partial.path("status").stringValue(null)).isEqualTo("PARTIAL");
        assertThat(partial.path("requested").intValue()).isEqualTo(125);
        assertThat(partial.path("deleted").intValue()).isEqualTo(100);
        assertThat(partial.path("notDeleted")).hasSize(25);
        assertThat(partial.path("stopReason").stringValue(null)).isEqualTo("VERSION_CONFLICT");
        assertThat(partial.path("memberCount").intValue()).isEqualTo(30);   // as of the last applied chunk
        // Not-attempted members still exist untouched; exactly the first 100 selected are gone.
        Set<String> notDeleted = new HashSet<>();
        partial.path("notDeleted").forEach(key -> notDeleted.add(key.stringValue(null)));
        assertThat(notDeleted).doesNotContainAnyElementsOf(created.subList(0, 5).stream().map(m -> m.member().toString()).toList())
                .containsAll(created.subList(105, 130).stream().map(m -> m.member().toString()).toList());
        assertThat(count("deck_head_item", own)).isEqualTo(31);

        // The stored PARTIAL result is replayed verbatim; progress needs a new command and fresh preconditions.
        var replay = deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(body)));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.acknowledgement()).isEqualTo(partial);
    }

    @Test
    void aRetryAfterACrashBetweenChunksReplaysFinishedChunksAndNeverDeletesTwice() {
        UUID owner = UUID.randomUUID();
        UUID own = fixtures.deck(owner);
        List<HubFixtures.Created> created = fixtures.seed(owner, own, 125);
        String revision = fixtures.head(owner, own).path("revisionId").stringValue(null);
        long version = fixtures.version(owner, own);
        UUID command = UUID.randomUUID();
        ObjectNode body = allBody(command, revision, created.subList(0, 5));   // 120 selected: chunks of 100 and 20

        int[] chunks = {0};
        ItemService crashing = interfering(() -> {
            if (chunks[0]++ == 0) throw new IllegalStateException("simulated crash after chunk 1");
        });
        ItemBulkDeleteService crashed = new ItemBulkDeleteService(crashing, itemRepository(), receipts());
        assertThatThrownBy(() -> crashed.delete(owner, own, version, BulkDeleteCommand.read(bytes(body))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("deck_head_item", own)).isEqualTo(25);   // chunk 1 is durable, no outer receipt yet

        var retry = deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(body)));
        assertThat(retry.replayed()).isFalse();
        JsonNode result = retry.acknowledgement();
        assertThat(result.path("status").stringValue(null)).isEqualTo("COMPLETED");
        assertThat(result.path("deleted").intValue()).isEqualTo(120);
        assertThat(result.path("memberCount").intValue()).isEqualTo(5);
        assertThat(count("deck_head_item", own)).isEqualTo(5);
        // Two chunk publications in total (the replayed one did not publish again).
        assertThat(jdbc.sql("SELECT count(DISTINCT deck_revision_id) FROM app_learning.deck_item_change WHERE deck_id=:deck "
                + "AND change_kind='delete'").param("deck", own).query(Integer.class).single()).isEqualTo(2);
        assertThat(deletions.delete(owner, own, version, BulkDeleteCommand.read(bytes(body))).replayed()).isTrue();
    }

    @Test
    void chunkCommandIdsAreDeterministicValidCommandIdentifiers() {
        UUID command = UUID.randomUUID();
        UUID first = ItemBulkDeleteService.chunkCommandId(command, 0);
        assertThat(ItemBulkDeleteService.chunkCommandId(command, 0)).isEqualTo(first);
        assertThat(ItemBulkDeleteService.chunkCommandId(command, 1)).isNotEqualTo(first);
        assertThat(first.version()).isEqualTo(4);
        assertThat(first.variant()).isEqualTo(2);
    }

    // ---- helpers ----

    private Map<UUID, Integer> listedCounts() {
        Map<UUID, Integer> counts = new HashMap<>();
        String cursor = null;
        do {
            JsonNode page = items.list(actor, deck, "100", cursor, "ordinal", "exerciseCount");
            page.path("items").forEach(item -> counts.put(UUID.fromString(item.path("memberKey").stringValue(null)),
                    item.path("exerciseCount").intValue()));
            cursor = page.path("nextCursor").stringValue(null);
        } while (cursor != null);
        assertThat(counts).hasSize(50);
        return counts;
    }

    private ExemplarCommand exemplar(UUID command, HubFixtures.Created material, boolean value) {
        return ExemplarCommand.read(bytes(JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedItemRevisionId", material.revision().toString()).put("exemplar", value)));
    }

    private static ObjectNode selection(String revision, List<HubFixtures.Created> selected) {
        ObjectNode body = JSON.createObjectNode().put("expectedDeckRevisionId", revision);
        ArrayNode ids = body.putArray("itemIds");
        selected.forEach(item -> ids.add(item.member().toString()));
        return body;
    }

    private static ObjectNode deleteBody(UUID command, String revision, List<HubFixtures.Created> selected) {
        ObjectNode body = selection(revision, selected);
        body.put("commandId", command.toString());
        return body;
    }

    /** allInDeck except the given materials; a null command makes a preview selection. */
    private static ObjectNode allBody(UUID command, String revision, List<HubFixtures.Created> except) {
        ObjectNode body = JSON.createObjectNode().put("expectedDeckRevisionId", revision).put("allInDeck", true);
        ArrayNode ids = body.putArray("except");
        except.forEach(item -> ids.add(item.member().toString()));
        if (command != null) body.put("commandId", command.toString());
        return body;
    }

    private void capture(UUID owner, UUID target, String createdAt, boolean archived) {
        jdbc.sql("""
                INSERT INTO app_learning.capture_note(note_id,owner_id,deck_id,reuse_scope_id,row_version,source,note_text,archived,created_at,updated_at)
                VALUES (:id,:owner,:deck,(SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id=:deck),0,'src','text',:archived,CAST(:at AS timestamptz),CAST(:at AS timestamptz))
                """).param("id", UUID.randomUUID()).param("owner", owner).param("deck", target).param("archived", archived)
                .param("at", createdAt).update();
    }

    private int count(String table, UUID target) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE deck_id=:deck").param("deck", target)
                .query(Integer.class).single();
    }

    @Autowired private ItemRepository repositoryBean;
    @Autowired private app.mnema.learning.platform.idempotency.CommandReceiptService receiptBean;
    @Autowired private app.mnema.learning.platform.concurrency.CompareAndSetExecutor casBean;
    @Autowired private app.mnema.learning.storage.ImmutableStorage storageBean;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionBean;
    @Autowired private app.mnema.learning.catalog.content.ItemPreviews previewBean;

    private ItemRepository itemRepository() { return repositoryBean; }

    private app.mnema.learning.platform.idempotency.CommandReceiptService receipts() { return receiptBean; }

    /** A real ItemService whose {@code publish} runs {@code afterPublish} once each publication has committed. */
    private ItemService interfering(Runnable afterPublish) {
        return new ItemService(repositoryBean, receiptBean, casBean, storageBean, media, transactionBean, previewBean) {
            @Override
            public WriteResult publish(UUID owner, UUID deckId, long expected, ItemPublicationCommand command) {
                WriteResult result = super.publish(owner, deckId, expected, command);
                afterPublish.run();
                return result;
            }
        };
    }
}
