package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.CatalogWorlds.World;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.DeckVisibility;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/7 (#454), the owner's editorial shelf «Выбор Мнемы»: {@code GET} and {@code PUT /api/admin/community/editorial} with the example of {@code contracts/community/catalog.json}
 * executed, the access matrix of the owner console, {@code If-Match}, idempotency by command id, the audit rows, and what the public shelf and the sitemap do when a pick stops being listed.
 */
class CatalogEditorialHttpIntegrationTest extends CatalogHttpTest {
    private static final String PATH = "/admin/community/editorial";
    private static final List<World> SHARED = new ArrayList<>();

    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private PublicationService publication;
    @Autowired private DeckModeration moderation;
    @Autowired private CatalogProjector projector;
    private CatalogWorlds worlds;
    private List<World> shared;

    @BeforeEach
    void worlds() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        jdbc.sql("UPDATE app_learning.learning_job SET state = 'CANCELLED', finished_at = clock_timestamp() WHERE queue LIKE 'catalog.%' AND state = 'QUEUED'").update();
        synchronized (SHARED) {
            while (SHARED.size() < 5) SHARED.add(publicDeck("Выбор " + SHARED.size()));
            shared = List.copyOf(SHARED);
        }
        // the base class emptied the projection: the shared decks come back through the projector, as they would through the job
        shared.forEach(world -> worlds.project(projector, world.deck()));
    }

    private World publicDeck(String title) {
        World world = worlds.deck(title, "Описание " + title, 10);
        worlds.exercise(world.materials().getFirst(), ExerciseType.CHOICE);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        return world;
    }

    private String code(World world) {
        return jdbc.sql("SELECT public_code FROM app_learning.deck_publication WHERE deck_id = :d").param("d", world.deck()).query(String.class).single();
    }

    private String body(UUID command, String... codes) {
        return "{\"commandId\":\"" + command + "\",\"codes\":[" + String.join(",", java.util.Arrays.stream(codes).map(code -> "\"" + code + "\"").toList()) + "]}";
    }

    private HttpResponse<String> put(String version, UUID command, String... codes) {
        return admin("PUT", PATH, body(command, codes), version == null ? Map.of() : Map.of("If-Match", "\"" + version + "\""));
    }

    private long audits() {
        return jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE action = 'COMMUNITY_EDITORIAL_UPDATE'").query(Long.class).single();
    }

    @Test
    void theStateStartsEmptyAndTheExampleOfTheContractIsExecuted() {
        long before = audits();
        HttpResponse<String> empty = admin("GET", PATH, null, Map.of());
        assertThat(empty.statusCode()).as(empty.body()).isEqualTo(200);
        assertThat(empty.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(json(empty)).isEqualTo(JSON.readTree("{\"rowVersion\":\"0\",\"entries\":[]}"));
        JsonNode example = CONTRACT.path("editorial").path("put");
        assertThat(example.path("request").stringValue(null)).isEqualTo("PUT /api/admin/community/editorial (If-Match: \"3\")");
        // the same shape as the contract's state, entry by entry
        UUID first = UUID.randomUUID();
        HttpResponse<String> response = put("0", first, code(shared.get(0)), code(shared.get(1)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode state = json(response);
        JsonNode contractState = CONTRACT.path("editorial").path("state").path("response");
        assertThat(state.propertyNames()).containsExactlyElementsOf(contractState.propertyNames());
        assertThat(state.path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(state.path("entries")).hasSize(2);
        assertThat(state.path("entries").get(0).propertyNames()).containsExactlyElementsOf(contractState.path("entries").get(0).propertyNames());
        assertThat(state.path("entries").get(0).path("code").stringValue(null)).isEqualTo(code(shared.get(0)));
        assertThat(state.path("entries").get(0).path("title").stringValue(null)).isEqualTo("Выбор 0");
        assertThat(state.path("entries").get(0).path("listed").booleanValue()).isTrue();
        assertThat(state.path("entries").get(0).path("addedAt").stringValue(null)).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(response.headers().firstValue("etag")).contains("\"1\"");
        assertThat(json(admin("GET", PATH, null, Map.of()))).isEqualTo(state);
        assertThat(audits()).isEqualTo(before + 1);
    }

    @Test
    void aCommandIsIdempotentByItsIdAndAnUnchangedListChangesNothing() {
        UUID command = UUID.randomUUID();
        String a = code(shared.get(0));
        String b = code(shared.get(1));
        long before = audits();
        HttpResponse<String> first = put("0", command, a, b);
        HttpResponse<String> again = put("0", command, a, b);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(first.body());
        // a retry that read a newer version still replays the stored answer
        assertThat(put("1", command, a, b).body()).isEqualTo(first.body());
        assertThat(audits()).as("one row for one effective change").isEqualTo(before + 1);
        assertThat(json(admin("GET", PATH, null, Map.of())).path("rowVersion").stringValue(null)).isEqualTo("1");
        // the same command for another list or by another body is a conflict
        problem(put("1", command, b, a), 409, "IDEMPOTENCY_CONFLICT");
        // another command that leaves the list as it is: no new version, no audit row
        HttpResponse<String> same = put("1", UUID.randomUUID(), a, b);
        assertThat(same.statusCode()).isEqualTo(200);
        assertThat(json(same).path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(audits()).isEqualTo(before + 1);
        // a reorder is an effective change and keeps the dates of the decks that stay
        String addedAt = json(first).path("entries").get(0).path("addedAt").stringValue(null);
        JsonNode reordered = json(put("1", UUID.randomUUID(), b, a));
        assertThat(reordered.path("rowVersion").stringValue(null)).isEqualTo("2");
        assertThat(reordered.path("entries").get(1).path("code").stringValue(null)).isEqualTo(a);
        assertThat(reordered.path("entries").get(1).path("addedAt").stringValue(null)).isEqualTo(addedAt);
        assertThat(audits()).isEqualTo(before + 2);
        // the audit row names the actor, the shelf and the command
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE action = 'COMMUNITY_EDITORIAL_UPDATE' AND actor_account_id = :a AND resource_id = '00000000-0000-7000-8000-00000000e001' AND command_id = :c")
                .param("a", CONSOLE).param("c", command).query(Long.class).single()).isEqualTo(1);
        // emptying the list is a change too
        assertThat(json(put("2", UUID.randomUUID())).path("entries")).isEmpty();
    }

    @Test
    void theVersionIsCheckedWithIfMatch() {
        String a = code(shared.get(0));
        problem(put(null, UUID.randomUUID(), a), 428, "PRECONDITION_REQUIRED");
        problem(put("5", UUID.randomUUID(), a), 412, "VERSION_CONFLICT");
        for (String malformed : List.of("W/\"0\"", "0", "\"01\"", "\"-1\"", "\"a\"", "\"\"")) {
            HttpResponse<String> response = admin("PUT", PATH, body(UUID.randomUUID(), a), Map.of("If-Match", malformed));
            problem(response, 400, "INVALID_REQUEST");
        }
        assertThat(put("0", UUID.randomUUID(), a).statusCode()).isEqualTo(200);
        problem(put("0", UUID.randomUUID(), a), 412, "VERSION_CONFLICT");
        assertThat(json(admin("GET", PATH, null, Map.of())).path("rowVersion").stringValue(null)).isEqualTo("1");
    }

    @Test
    void onlyAPublicPublishedDeckCanBePickedAndTheOffendingCodesAreNamed() {
        World link = worlds.deck("По ссылке", "Описание", 10);
        worlds.publish(link, DeckVisibility.LINK, CatalogWorlds.metadata("x"));
        World hiddenLater = worlds.deck("Приватная снова", "Описание", 10);
        worlds.publish(hiddenLater);
        worlds.setLevel(hiddenLater, DeckVisibility.PRIVATE);
        World gone = worlds.deck("Удалённая", "Описание", 10);
        worlds.publish(gone);
        String goneCode = code(gone);
        worlds.delete(gone);
        String unknown = CatalogSeed.code();
        String good = code(shared.get(0));
        HttpResponse<String> response = put("0", UUID.randomUUID(), good, code(link), unknown, goneCode);
        problem(response, 400, "INVALID_REQUEST");
        assertThat(json(response).path("codes")).extracting(JsonNode::stringValue).containsExactly(code(link), unknown, goneCode);
        assertThat(json(response).toString()).doesNotContain(good);
        // a code that is not a code, a repeated code and a list longer than twenty-four are plain 400s that echo nothing
        for (String bad : List.of(body(UUID.randomUUID(), "short"), body(UUID.randomUUID(), good, good), body(UUID.randomUUID(), "0OIl000000"))) {
            HttpResponse<String> plain = admin("PUT", PATH, bad, Map.of("If-Match", "\"0\""));
            problem(plain, 400, "INVALID_REQUEST");
            assertThat(json(plain).has("codes")).isFalse();
        }
        List<String> many = new ArrayList<>();
        for (int index = 0; index < 25; index++) many.add(CatalogSeed.code());
        problem(put("0", UUID.randomUUID(), many.toArray(String[]::new)), 400, "INVALID_REQUEST");
        assertThat(json(admin("GET", PATH, null, Map.of())).path("entries")).isEmpty();
        // twenty-four is the limit
        List<String> twentyFour = new ArrayList<>(many.subList(0, 24));
        HttpResponse<String> atLimit = put("0", UUID.randomUUID(), twentyFour.toArray(String[]::new));
        problem(atLimit, 400, "INVALID_REQUEST");
        assertThat(json(atLimit).path("codes")).hasSize(24);
    }

    @Test
    void theBodyIsStrict() {
        String a = code(shared.get(0));
        for (String bad : List.of("{}", "{\"codes\":[]}", "{\"commandId\":\"" + UUID.randomUUID() + "\"}", "{\"commandId\":\"x\",\"codes\":[]}", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"codes\":\"" + a + "\"}",
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"codes\":[1]}", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"codes\":[],\"extra\":1}", "[]", "not json",
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"commandId\":\"" + UUID.randomUUID() + "\",\"codes\":[]}")) {
            problem(admin("PUT", PATH, bad, Map.of("If-Match", "\"0\"")), 400, "INVALID_REQUEST");
        }
        problem(admin("PUT", PATH + "?x=1", body(UUID.randomUUID(), a), Map.of("If-Match", "\"0\"")), 400, "INVALID_REQUEST");
        problem(admin("GET", PATH + "?x=1", null, Map.of()), 400, "INVALID_REQUEST");
    }

    @Test
    void onlyTheConsoleOwnerOnTheAdminClientMayReadOrWrite() {
        String body = body(UUID.randomUUID(), code(shared.get(0)));
        UUID learner = UUID.randomUUID();
        assertThat(HttpIdentityFixture.send(port, "GET", PATH, null, null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(HttpIdentityFixture.send(port, "PUT", PATH, null, body, Map.of("If-Match", "\"0\"")).statusCode()).isEqualTo(401);
        for (String bearer : List.of(HttpIdentityFixture.reader(learner), HttpIdentityFixture.reader(CONSOLE), HttpIdentityFixture.token(learner, "learning.read learning.write", "mnema-admin-web"))) {
            problem(HttpIdentityFixture.send(port, "GET", PATH, bearer, null, Map.of()), 403, "ACCESS_DENIED");
            problem(HttpIdentityFixture.send(port, "PUT", PATH, bearer, body, Map.of("If-Match", "\"0\"")), 403, "ACCESS_DENIED");
        }
        // the owner on the admin client with a read-only token may read and may not write
        String readOnly = HttpIdentityFixture.token(CONSOLE, "learning.read", "mnema-admin-web");
        assertThat(HttpIdentityFixture.send(port, "GET", PATH, readOnly, null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(HttpIdentityFixture.send(port, "PUT", PATH, readOnly, body, Map.of("If-Match", "\"0\"")).statusCode()).isEqualTo(403);
        assertThat(json(admin("GET", PATH, null, Map.of())).path("entries")).isEmpty();
    }

    @Test
    void aPickThatStopsBeingListedStaysForTheOwnerAndFallsOutOfTheShelfAndTheSitemap() throws Exception {
        // this test moves its decks through hiding and deletion: it makes its own
        List<World> picked = new ArrayList<>();
        for (int index = 0; index < 4; index++) picked.add(publicDeck("Своя " + index));
        World fifth = shared.get(4);
        // the public page needs four listed decks for a shelf
        String[] codes = picked.stream().map(this::code).toArray(String[]::new);
        assertThat(put("0", UUID.randomUUID(), codes).statusCode()).isEqualTo(200);
        service.invalidate();
        JsonNode shelf = ok(get("/public/community/shelves")).path("shelves");
        assertThat(shelf).isNotEmpty();
        assertThat(shelf.get(0).path("kind").stringValue(null)).isEqualTo("editorial");
        assertThat(codes(shelf.get(0))).containsExactly(codes);
        // moderation hides the third: the row of the catalogue is gone at once, the owner still sees the pick with listed=false, the shelf has three decks and so is no shelf
        worlds.hide(picked.get(2));
        JsonNode state = json(admin("GET", PATH, null, Map.of()));
        assertThat(state.path("entries")).hasSize(4);
        assertThat(state.path("entries").get(2).path("listed").booleanValue()).isFalse();
        assertThat(state.path("entries").get(2).path("title").stringValue(null)).isEqualTo("Своя 2");
        service.invalidate();
        assertThat(ok(get("/public/community/shelves")).path("shelves").toString()).doesNotContain("\"kind\":\"editorial\"");
        assertThat(get("/public/sitemap.xml").body()).doesNotContain("/d/" + codes[2] + "/").contains("/d/" + codes[0] + "/");
        // the un-hidden deck comes back through the job and the pick resumes its place
        worlds.unhide(picked.get(2));
        worlds.project(projector, picked.get(2).deck());
        service.invalidate();
        assertThat(codes(ok(get("/public/community/shelves")).path("shelves").get(0))).containsExactly(codes);
        // a deleted deck stays in the list as unlisted; nothing blocks the deletion
        worlds.delete(picked.get(3));
        assertThat(json(admin("GET", PATH, null, Map.of())).path("entries").get(3).path("listed").booleanValue()).isFalse();
        // replacing the list with a deleted deck's code is refused, replacing it without that deck is fine
        problem(put("1", UUID.randomUUID(), codes), 400, "INVALID_REQUEST");
        assertThat(put("1", UUID.randomUUID(), codes[0], codes[1], code(fifth)).statusCode()).isEqualTo(200);
        assertThat(fifth).isNotNull();
    }
}
