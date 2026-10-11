package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static app.mnema.learning.fork.ForkWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/6 part A (#445): the badge «Есть обновление» ({@code fork.updateAvailable}) and the auto-update setting ({@code PUT fork:auto-update}) through the real application. No
 * executor runs in this context (the default test role is {@code api}), so a copy is made ready by the test itself ({@code materialized_at}); the badge and the setting read no head.
 * The badge is computed at read from the stored base, the source's published revision and the PENDING decisions, and the read writes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"learning.community.forks.max-preparing=100", "learning.community.forks.max-per-window=1000"})
class ForkUpdateBadgeHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonNode CONTRACT = ForkWorlds.forksContract();

    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    @Autowired private app.mnema.learning.library.DeckModeration moderation;
    private ForkWorlds worlds;
    private final UUID owner = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void worlds() {
        worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    private UUID readyCopy(Source source) {
        UUID copy = worlds.forked(owner, source.code());
        worlds.markReady(copy);
        return copy;
    }

    private boolean badge(UUID copy) {
        JsonNode fork = worlds.forkOf(owner, copy);
        assertThat(fork.path("updateAvailable").isBoolean()).as(fork.toString()).isTrue();
        return fork.path("updateAvailable").booleanValue(false);
    }

    private HttpResponse<String> setting(UUID account, UUID deck, String body) {
        return worlds.send("PUT", "/decks/" + deck + "/fork:auto-update", account, body, null);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    private String rowMark(UUID copy) {
        return jdbc.sql("SELECT xmin::text FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(String.class).single();
    }

    // ---- the badge ----

    @Test
    void theBadgeIsTheBaseAgainstThePublishedRevisionOrAPendingDecisionOfAReadyAttachedCopy() {
        Source source = worlds.source("LINK", 2, 1);
        UUID copy = readyCopy(source);

        // base = P and nothing pending: no badge; the member set is that of the contract
        JsonNode fork = worlds.forkOf(owner, copy);
        assertThat(names(fork)).isEqualTo(names(CONTRACT.path("fork").path("ready")));
        assertThat(fork.path("updateAvailable").booleanValue(true)).isFalse();
        assertThat(fork.path("autoUpdate").booleanValue(true)).isFalse();

        // the author publishes again: base != P
        UUID published = worlds.republish(source, "LINK");
        assertThat(badge(copy)).isTrue();
        JsonNode listed = null;
        for (JsonNode row : json(worlds.get("/decks?limit=100", owner)).path("items")) {
            if (row.path("deckId").stringValue("").equals(copy.toString())) listed = row;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("fork").path("updateAvailable").booleanValue(false)).isTrue();

        // the apply moved the base to P and nothing was left for later (everything accepted or declined): no badge
        worlds.moveBase(copy, published);
        assertThat(badge(copy)).isFalse();

        // a unit left for later keeps the badge on although base = P; a decided unit does not
        worlds.keepMine(copy);
        assertThat(badge(copy)).isFalse();
        worlds.pend(copy);
        assertThat(badge(copy)).isTrue();
        assertThat(worlds.forkOf(owner, copy).path("sourceAvailable").booleanValue(false)).isTrue();
        jdbc.sql("DELETE FROM app_learning.fork_unit_decision WHERE deck_id = :d AND decision = 'PENDING'").param("d", copy).update();
        assertThat(badge(copy)).isFalse();

        // the author publishes once more: back on
        worlds.republish(source, "LINK");
        assertThat(badge(copy)).isTrue();
    }

    @Test
    void aDetachedCopyNeverShowsTheBadgeNorAPreparingOne() {
        Source source = worlds.source("LINK", 2, 1);
        UUID detached = readyCopy(source);
        UUID otherReader = UUID.randomUUID();
        UUID preparing = worlds.forked(otherReader, source.code());
        worlds.republish(source, "LINK");
        worlds.pend(detached);
        worlds.pend(preparing);
        assertThat(badge(detached)).isTrue();

        assertThat(worlds.send("POST", "/decks/" + detached + "/fork:detach", owner, null, null).statusCode()).isEqualTo(200);
        JsonNode fork = worlds.forkOf(owner, detached);
        assertThat(fork.path("detached").booleanValue(false)).isTrue();
        assertThat(fork.path("sourceAvailable").booleanValue(false)).isTrue();
        assertThat(fork.path("updateAvailable").booleanValue(true)).isFalse();
        assertThat(names(fork)).isEqualTo(names(CONTRACT.path("fork").path("detached")));
        JsonNode plan = json(worlds.get("/decks/" + detached + "/update-plan", owner));
        assertThat(plan.path("state").stringValue(null)).isEqualTo("DETACHED");
        assertThat(plan.path("units")).isEmpty();

        // a copy that is still being prepared has base != P and a pending row, and shows nothing
        JsonNode waiting = worlds.forkOf(otherReader, preparing);
        assertThat(waiting.path("preparing").booleanValue(false)).isTrue();
        assertThat(waiting.path("updateAvailable").booleanValue(true)).isFalse();
        assertThat(names(waiting)).isEqualTo(names(CONTRACT.path("fork").path("preparing")));
        worlds.markReady(preparing);
        assertThat(json(worlds.get("/decks/" + preparing, otherReader)).path("fork").path("updateAvailable").booleanValue(false)).isTrue();
    }

    @Test
    void aSourceThatCannotBeOpenedByTheStoredCodeShowsNoBadge() {
        // the code rotated away
        Source rotated = worlds.source("PUBLIC", 2, 0);
        UUID rotatedCopy = readyCopy(rotated);
        worlds.republish(rotated, "PUBLIC");
        assertThat(badge(rotatedCopy)).isTrue();
        worlds.level(rotated.author(), rotated.deck(), "LINK");
        JsonNode closed = worlds.forkOf(owner, rotatedCopy);
        assertThat(closed.path("sourceAvailable").booleanValue(true)).isFalse();
        assertThat(closed.path("updateAvailable").booleanValue(true)).isFalse();

        // made private
        Source hidden = worlds.source("LINK", 2, 0);
        UUID privateCopy = readyCopy(hidden);
        worlds.republish(hidden, "LINK");
        worlds.pend(privateCopy);
        assertThat(badge(privateCopy)).isTrue();
        worlds.level(hidden.author(), hidden.deck(), "PRIVATE");
        assertThat(worlds.forkOf(owner, privateCopy).path("updateAvailable").booleanValue(true)).isFalse();

        // hidden by a moderation decision
        Source moderated = worlds.source("LINK", 2, 0);
        UUID moderatedCopy = readyCopy(moderated);
        worlds.republish(moderated, "LINK");
        assertThat(badge(moderatedCopy)).isTrue();
        Boolean didHide = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> moderation.hide(moderated.deck(), java.time.Instant.now()));
        assertThat(didHide).isTrue();
        assertThat(worlds.forkOf(owner, moderatedCopy).path("sourceAvailable").booleanValue(true)).isFalse();
        assertThat(badge(moderatedCopy)).isFalse();
    }

    @Test
    void readingTheBadgeWritesNothing() {
        Source source = worlds.source("LINK", 2, 1);
        UUID copy = readyCopy(source);
        worlds.republish(source, "LINK");
        worlds.pend(copy);
        String mark = rowMark(copy);
        long decisions = jdbc.sql("SELECT count(*) FROM app_learning.fork_unit_decision WHERE deck_id = :d").param("d", copy).query(Long.class).single();
        long revisions = jdbc.sql("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id = :d").param("d", copy).query(Long.class).single();
        long jobs = jdbc.sql("SELECT count(*) FROM app_learning.learning_job").query(Long.class).single();
        for (int round = 0; round < 3; round++) {
            assertThat(badge(copy)).isTrue();
            assertThat(worlds.get("/decks?limit=100", owner).statusCode()).isEqualTo(200);
        }
        assertThat(rowMark(copy)).as("the copy's row was not updated").isEqualTo(mark);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.fork_unit_decision WHERE deck_id = :d").param("d", copy).query(Long.class).single()).isEqualTo(decisions);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id = :d").param("d", copy).query(Long.class).single()).isEqualTo(revisions);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job").query(Long.class).single()).isEqualTo(jobs);
    }

    // ---- the setting ----

    @Test
    void theOwnerStoresTheSettingIdempotentlyAndTheDeckJsonReportsItAsStored() {
        Source source = worlds.source("LINK", 2, 1);
        UUID copy = readyCopy(source);
        String mark = rowMark(copy);

        HttpResponse<String> on = setting(owner, copy, "{\"autoUpdate\":true}");
        assertThat(on.statusCode()).as(on.body()).isEqualTo(200);
        assertThat(on.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(json(on).toString()).isEqualTo(CONTRACT.path("autoUpdate").path("body").toString().replace(
                CONTRACT.path("autoUpdate").path("body").path("deckId").stringValue(null), copy.toString()));
        assertThat(names(json(on))).isEqualTo(names(CONTRACT.path("autoUpdate").path("body")));
        assertThat(worlds.forkOf(owner, copy).path("autoUpdate").booleanValue(false)).isTrue();
        assertThat(jdbc.sql("SELECT auto_update FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(Boolean.class).single()).isTrue();
        String changed = rowMark(copy);
        assertThat(changed).isNotEqualTo(mark);

        // the same value again: answered, nothing written
        assertThat(setting(owner, copy, "{\"autoUpdate\":true}").statusCode()).isEqualTo(200);
        assertThat(setting(owner, copy, "{ \"autoUpdate\" : true }").statusCode()).isEqualTo(200);
        assertThat(rowMark(copy)).as("an unchanged value is no write").isEqualTo(changed);

        HttpResponse<String> off = setting(owner, copy, "{\"autoUpdate\":false}");
        assertThat(json(off).path("autoUpdate").booleanValue(true)).isFalse();
        assertThat(worlds.forkOf(owner, copy).path("autoUpdate").booleanValue(true)).isFalse();
        // the setting does not touch anything else of the copy
        assertThat(jdbc.sql("SELECT detached_at IS NULL AND released_at IS NULL AND materialized_at IS NOT NULL FROM app_learning.deck_fork WHERE deck_id = :d")
                .param("d", copy).query(Boolean.class).single()).isTrue();
    }

    @Test
    void onlyTheOwnerOfALiveCopyMayStoreIt() {
        Source source = worlds.source("LINK", 2, 1);
        UUID copy = readyCopy(source);
        UUID plain = worlds.shared.deck(owner, "Обычная");
        problem(setting(UUID.randomUUID(), copy, "{\"autoUpdate\":true}"), 404, "RESOURCE_NOT_FOUND");
        problem(setting(source.author(), copy, "{\"autoUpdate\":true}"), 404, "RESOURCE_NOT_FOUND");
        problem(setting(owner, plain, "{\"autoUpdate\":true}"), 404, "RESOURCE_NOT_FOUND");
        problem(setting(source.author(), source.deck(), "{\"autoUpdate\":true}"), 404, "RESOURCE_NOT_FOUND");
        problem(setting(owner, UUID.randomUUID(), "{\"autoUpdate\":true}"), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT auto_update FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(Boolean.class).single()).isFalse();

        // a deleted copy is gone
        long version = Long.parseLong(json(worlds.get("/decks/" + copy, owner)).path("rowVersion").stringValue(null));
        assertThat(worlds.send("DELETE", "/decks/" + copy, owner, null, "\"" + version + "\"").statusCode()).isEqualTo(204);
        problem(setting(owner, copy, "{\"autoUpdate\":true}"), 404, "RESOURCE_NOT_FOUND");

        // learning.write is needed, a malformed id is 400
        UUID second = readyCopy(worlds.source("LINK", 1, 0));
        HttpResponse<String> readOnly = HttpIdentityFixture.send(port, "PUT", "/decks/" + second + "/fork:auto-update", HttpIdentityFixture.token(owner, "learning.read"),
                "{\"autoUpdate\":true}", Map.of());
        assertThat(readOnly.statusCode()).isEqualTo(403);
        assertThat(worlds.send("PUT", "/decks/not-an-id/fork:auto-update", owner, "{\"autoUpdate\":true}", null).statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT auto_update FROM app_learning.deck_fork WHERE deck_id = :d").param("d", second).query(Boolean.class).single()).isFalse();
    }

    @Test
    void thePreparingAndTheDetachedCopyRefuseTheSettingAndKeepTheirValue() {
        Source source = worlds.source("LINK", 2, 1);
        UUID preparing = worlds.forked(owner, source.code());
        problem(setting(owner, preparing, "{\"autoUpdate\":true}"), 409, "DECK_PREPARING");
        problem(setting(owner, preparing, "{}"), 409, "DECK_PREPARING");
        assertThat(jdbc.sql("SELECT auto_update FROM app_learning.deck_fork WHERE deck_id = :d").param("d", preparing).query(Boolean.class).single()).isFalse();

        worlds.markReady(preparing);
        assertThat(setting(owner, preparing, "{\"autoUpdate\":true}").statusCode()).isEqualTo(200);
        assertThat(worlds.send("POST", "/decks/" + preparing + "/fork:detach", owner, null, null).statusCode()).isEqualTo(200);
        for (String value : new String[] {"true", "false"}) {
            HttpResponse<String> refused = setting(owner, preparing, "{\"autoUpdate\":" + value + "}");
            problem(refused, 409, "CAPABILITY_UNAVAILABLE");
            assertThat(json(refused).path("capability").stringValue(null)).isEqualTo("forkAutoUpdate");
            assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("DETACHED");
        }
        // the stored value is as the owner left it, and the detached copy reports it with no badge
        JsonNode fork = worlds.forkOf(owner, preparing);
        assertThat(fork.path("autoUpdate").booleanValue(false)).isTrue();
        assertThat(fork.path("updateAvailable").booleanValue(true)).isFalse();
    }

    @Test
    void theBodyIsStrict() {
        Source source = worlds.source("LINK", 1, 0);
        UUID copy = readyCopy(source);
        for (String body : new String[] {"{}", "{\"autoUpdate\":\"true\"}", "{\"autoUpdate\":1}", "{\"autoUpdate\":null}", "{\"autoUpdate\":true,\"extra\":1}",
                "{\"autoUpdate\":true,\"autoUpdate\":false}", "[true]", "true", "{\"autoupdate\":true}", "{\"autoUpdate\":true"}) {
            assertThat(setting(owner, copy, body).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(jdbc.sql("SELECT auto_update FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(Boolean.class).single()).isFalse();
    }

    @Test
    void theMetadataAcknowledgementCarriesTheForkMemberOfTheNextReadAndANewDeckCarriesNull() {
        Source source = worlds.source("LINK", 2, 1);
        UUID copy = readyCopy(source);
        worlds.republish(source, "LINK");
        worlds.pend(copy);
        assertThat(setting(owner, copy, "{\"autoUpdate\":true}").statusCode()).isEqualTo(200);

        var body = ForkWorlds.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        body.putObject("metadata").put("title", "Моя копия").put("description", "правка");
        HttpResponse<String> saved = worlds.send("PATCH", "/decks/" + copy, owner, body.toString(), "\"0\"");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        JsonNode acknowledged = json(saved).path("deck").path("fork");
        assertThat(acknowledged.path("updateAvailable").booleanValue(false)).isTrue();
        assertThat(acknowledged.path("autoUpdate").booleanValue(false)).isTrue();
        assertThat(acknowledged).isEqualTo(worlds.forkOf(owner, copy));

        // the acknowledgement of a new ordinary deck has the member, null
        JsonNode created = decks.create(UUID.randomUUID(), new app.mnema.learning.catalog.deck.DeckCommand(UUID.randomUUID(), "Своя", "")).acknowledgement().path("deck");
        assertThat(created.has("fork")).isTrue();
        assertThat(created.path("fork").isNull()).isTrue();
    }
}
