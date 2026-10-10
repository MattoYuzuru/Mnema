package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import io.micrometer.core.instrument.MeterRegistry;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/7 on the real servlet stack: the optional-bearer chain, the resolution matrix over every public route, the published revision against the
 * owner's head, the shared lineage scope and the shapes of the answers. Limits are high here; the limiter and the kill switch have their own classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicDeckHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private DeckAccess access;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MeterRegistry meters;
    private LibraryFixtures fixtures;

    private final UUID owner = UUID.randomUUID();
    private final UUID grantee = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void fixtures() { fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications); }

    private HttpResponse<String> get(String path, UUID account) {
        return HttpIdentityFixture.send(port, "GET", path, account == null ? null : HttpIdentityFixture.reader(account), null, Map.of());
    }

    private static JsonNode json(HttpResponse<String> response) {
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    private enum Who { OWNER, GRANTEE, OTHER, GUEST }

    private UUID account(Who who) {
        return switch (who) { case OWNER -> owner; case GRANTEE -> grantee; case OTHER -> other; case GUEST -> null; };
    }

    private List<String> routes(String code, Deck deck) {
        return List.of("/public/decks/" + code, "/public/decks/" + code + "/items", "/public/decks/" + code + "/items/" + deck.material().member(),
                "/public/decks/" + code + "/exercises");
    }

    /** A 404 body with the request path removed, so unknown, private, deleted and rotated codes can be compared byte for byte. */
    private static String anonymised(HttpResponse<String> response) {
        ObjectNode body = (ObjectNode) json(response);
        body.remove("instance");
        return response.statusCode() + " " + response.headers().firstValue("content-type").orElse("") + " " + response.headers().firstValue("cache-control").orElse("")
                + " " + body;
    }

    @Test
    void everyRouteFollowsTheResolutionMatrixForEveryLevelStateAndViewer() {
        Deck link = fixtures.deck(owner, "Ссылка");
        Deck invite = fixtures.deck(owner, "Приглашение");
        Deck pub = fixtures.deck(owner, "Публичная колода");
        Deck privateAgain = fixtures.deck(owner, "Снова приватная");
        Deck noRevision = fixtures.deck(owner, "Без публикации");
        Deck deleted = fixtures.deck(owner, "Удалённая");
        Deck rotated = fixtures.deck(owner, "Старая ссылка");
        for (Deck deck : List.of(link, invite, pub, privateAgain, noRevision, deleted, rotated)) publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER);
        String linkCode = fixtures.publishAt(link, DeckVisibility.LINK);
        String inviteCode = fixtures.publishAt(invite, DeckVisibility.INVITE);
        String publicCode = fixtures.publishAt(pub, DeckVisibility.PUBLIC);
        fixtures.publishAt(privateAgain, DeckVisibility.PUBLIC);
        String privateCode = fixtures.level(privateAgain, DeckVisibility.PRIVATE);
        String noRevisionCode = fixtures.level(noRevision, DeckVisibility.PUBLIC);
        String deletedCode = fixtures.publishAt(deleted, DeckVisibility.PUBLIC);
        decks.delete(owner, deleted.id(), Long.parseLong(decks.read(owner, deleted.id()).path("rowVersion").stringValue(null)));
        String rotatedOld = fixtures.publishAt(rotated, DeckVisibility.PUBLIC);
        fixtures.level(rotated, DeckVisibility.PRIVATE);
        String unknown = PublicCodes.next(new java.security.SecureRandom());

        Set<String> hidden = new HashSet<>();
        for (Who who : Who.values()) {
            for (String path : routes(linkCode, link)) assertThat(get(path, account(who)).statusCode()).as(who + " " + path).isEqualTo(200);
            for (String path : routes(publicCode, pub)) assertThat(get(path, account(who)).statusCode()).as(who + " " + path).isEqualTo(200);
            for (String path : routes(inviteCode, invite)) {
                HttpResponse<String> response = get(path, account(who));
                if (who == Who.OWNER || who == Who.GRANTEE) {
                    assertThat(response.statusCode()).as(who + " " + path).isEqualTo(200);
                } else {
                    problem(response, 403, "DECK_INVITE_ONLY");
                    // the refusal tells nothing about the deck
                    assertThat(response.body()).doesNotContain("Приглашение", "description", "ownerId", owner.toString(), invite.id().toString(),
                            "memberCount");
                    assertThat(json(response).propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
                }
            }
            // the owner of a deck that is private again or never published still reads the published revision he has; the rest is the one 404
            for (Deck deck : List.of(privateAgain)) {
                String code = privateCode;
                for (String path : routes(code, deck)) {
                    HttpResponse<String> response = get(path, account(who));
                    if (who == Who.OWNER) assertThat(response.statusCode()).as(who + " " + path).isEqualTo(200);
                    else { problem(response, 404, "RESOURCE_NOT_FOUND"); hidden.add(anonymised(response).replace(code, "CODE")); }
                }
            }
            for (String path : routes(noRevisionCode, noRevision)) {
                HttpResponse<String> response = get(path, account(who));
                problem(response, 404, "RESOURCE_NOT_FOUND");
                hidden.add(anonymised(response).replace(noRevisionCode, "CODE"));
            }
            for (String code : List.of(deletedCode, rotatedOld, unknown, "short", "0000000000")) {
                for (String path : routes(code, link)) {
                    HttpResponse<String> response = get(path, account(who));
                    problem(response, 404, "RESOURCE_NOT_FOUND");
                    hidden.add(anonymised(response).replace(code, "CODE").replace(link.material().member().toString(), "MEMBER"));
                }
            }
        }
        // unknown, private, never published, deleted and rotated-away codes are indistinguishable (the member key in the path is the only difference)
        assertThat(hidden.stream().map(text -> text.replaceAll("\"detail\":\"[^\"]*\"", "")).collect(java.util.stream.Collectors.toSet())).hasSize(1);
    }

    @Test
    void theSummaryShowsOnlyThePublishedFieldsAndTheSlugOnlyForPublicDecks() {
        Deck pub = fixtures.deck(owner, "Испанский язык");
        Deck link = fixtures.deck(owner, "Испанский по ссылке");
        Deck invite = fixtures.deck(owner, "Испанский по приглашению");
        String publicCode = fixtures.publishAt(pub, DeckVisibility.PUBLIC);
        String linkCode = fixtures.publishAt(link, DeckVisibility.LINK);
        String inviteCode = fixtures.publishAt(invite, DeckVisibility.INVITE);
        publications.grant(owner, invite.id(), grantee, GrantRole.VIEWER);

        HttpResponse<String> response = get("/public/decks/" + publicCode, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(response.headers().allValues("vary")).anySatisfy(value -> assertThat(value).containsIgnoringCase("authorization"));
        JsonNode summary = json(response);
        assertThat(summary.propertyNames()).containsExactlyInAnyOrder("code", "visibility", "access", "title", "description", "memberCount",
                "exerciseCount", "publishedAt", "ownerId", "slug");
        assertThat(summary.path("code").stringValue(null)).isEqualTo(publicCode);
        assertThat(summary.path("visibility").stringValue(null)).isEqualTo("PUBLIC");
        assertThat(summary.path("access").stringValue(null)).isEqualTo("PUBLIC");
        assertThat(summary.path("title").stringValue(null)).isEqualTo("Испанский язык");
        assertThat(summary.path("description").stringValue(null)).isEqualTo("Описание Испанский язык");
        assertThat(summary.path("memberCount").intValue()).isEqualTo(1);
        assertThat(summary.path("exerciseCount").intValue()).isEqualTo(1);
        assertThat(summary.path("ownerId").stringValue(null)).isEqualTo(owner.toString());
        assertThat(summary.path("slug").stringValue(null)).isEqualTo("ispanskiy-yazyk");
        assertThat(java.time.Instant.parse(summary.path("publishedAt").stringValue(null))).isNotNull();
        assertThat(response.body()).doesNotContain(pub.id().toString());

        JsonNode linked = json(get("/public/decks/" + linkCode, other));
        assertThat(linked.propertyNames()).doesNotContain("slug");
        assertThat(linked.path("access").stringValue(null)).isEqualTo("LINK");
        assertThat(linked.path("visibility").stringValue(null)).isEqualTo("LINK");
        JsonNode invited = json(get("/public/decks/" + inviteCode, grantee));
        assertThat(invited.propertyNames()).doesNotContain("slug");
        assertThat(invited.path("access").stringValue(null)).isEqualTo("GRANTEE");
        JsonNode own = json(get("/public/decks/" + publicCode, owner));
        assertThat(own.path("access").stringValue(null)).isEqualTo("OWNER");
        assertThat(get("/public/decks/" + publicCode, null).body()).isEqualTo(response.body());
    }

    @Test
    void theItemsAndExercisesPagesCarryNoEditingMetadataAndNoAnswers() {
        Deck deck = fixtures.deck(owner, "Страницы");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);

        JsonNode items = json(get("/public/decks/" + code + "/items", null));
        assertThat(items.propertyNames()).containsExactlyInAnyOrder("code", "total", "items", "nextCursor");
        assertThat(items.path("total").intValue()).isEqualTo(1);
        assertThat(items.path("nextCursor").isNull()).isTrue();
        JsonNode entry = items.path("items").get(0);
        assertThat(entry.propertyNames()).containsExactlyInAnyOrder("memberKey", "itemRevisionId", "ordinal", "title");
        assertThat(entry.path("memberKey").stringValue(null)).isEqualTo(deck.material().member().toString());
        assertThat(entry.path("itemRevisionId").stringValue(null)).isEqualTo(deck.material().itemRevision().toString());
        assertThat(entry.path("ordinal").intValue()).isZero();
        assertThat(entry.path("title").stringValue(null)).isEqualTo("memory");

        HttpResponse<String> itemResponse = get("/public/decks/" + code + "/items/" + deck.material().member(), null);
        JsonNode item = json(itemResponse);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("code", "memberKey", "itemRevisionId", "ordinal", "formatVersion", "document");
        assertThat(item.path("itemRevisionId").stringValue(null)).isEqualTo(deck.material().itemRevision().toString());
        assertThat(item.path("document").toString()).contains("memory", "forgetting");
        assertThat(itemResponse.body()).doesNotContain(deck.id().toString(), "deckId", "deckRevisionId", "deckVersion", "draft", "journal");

        HttpResponse<String> exerciseResponse = get("/public/decks/" + code + "/exercises", null);
        JsonNode list = json(exerciseResponse);
        assertThat(list.propertyNames()).containsExactlyInAnyOrder("code", "total", "exercises", "nextCursor");
        JsonNode exercise = list.path("exercises").get(0);
        assertThat(exercise.propertyNames()).containsExactlyInAnyOrder("exerciseId", "exerciseRevisionId", "ordinal", "type", "enabled", "prompt");
        assertThat(exercise.path("exerciseId").stringValue(null)).isEqualTo(deck.exercise().toString());
        assertThat(exercise.path("type").stringValue(null)).isEqualTo("FREE_RESPONSE");
        assertThat(exercise.path("enabled").booleanValue()).isTrue();
        // the objective's own title is authoring data and is not part of the public page
        assertThat(exercise.path("prompt").stringValue(null)).isEqualTo("Что такое память?");
        assertThat(exerciseResponse.body()).doesNotContain(LibraryFixtures.SECRET_ANSWER, LibraryFixtures.SECRET_REFERENCE, LibraryFixtures.OBJECTIVE_TITLE, "objective", "answerKey", "accepted",
                "evaluator", "binding", "reference", "content", deck.id().toString());
    }

    @Test
    void everyMechanicShowsOnlyItsQuestion() {
        Deck deck = fixtures.deck(owner, "Механики");
        var study = fixtures.study();
        var material = deck.material();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        var blocks = app.mnema.learning.support.StudyFixtures.blocks(app.mnema.learning.support.StudyFixtures.text("Вопрос про выбор"));
        study.publish(material, study.choice(material, false, blocks, app.mnema.learning.support.StudyFixtures.blocks(
                app.mnema.learning.support.StudyFixtures.option(a, app.mnema.learning.support.StudyFixtures.text("SECRET-OPTION-A")),
                app.mnema.learning.support.StudyFixtures.option(b, app.mnema.learning.support.StudyFixtures.text("SECRET-OPTION-B"))), a), "Выбор");
        study.publish(material, study.cloze(material, app.mnema.learning.support.StudyFixtures.blocks(),
                app.mnema.learning.support.StudyFixtures.blocks(app.mnema.learning.support.StudyFixtures.text("Yo "),
                        app.mnema.learning.support.StudyFixtures.blank(c, true, 5, false), app.mnema.learning.support.StudyFixtures.text(" a casa")),
                app.mnema.learning.support.StudyFixtures.blankKey(c, "SECRET-BLANK")), "Пропуск");
        study.publish(material, study.selfCheck(material, app.mnema.learning.support.StudyFixtures.blocks(
                        app.mnema.learning.support.StudyFixtures.quote(material, material.node())),
                app.mnema.learning.support.StudyFixtures.blocks(app.mnema.learning.support.StudyFixtures.text("SECRET-SELF-REFERENCE"))), "Без текста");
        String code = fixtures.publishAt(deck, DeckVisibility.LINK);
        HttpResponse<String> response = get("/public/decks/" + code + "/exercises", null);
        JsonNode list = json(response);
        assertThat(list.path("total").intValue()).isEqualTo(4);
        List<String> types = new ArrayList<>();
        list.path("exercises").forEach(exercise -> types.add(exercise.path("type").stringValue(null)));
        assertThat(types).containsExactly("FREE_RESPONSE", "CHOICE", "CLOZE", "SELF_CHECK");
        assertThat(list.path("exercises").get(1).path("prompt").stringValue(null)).isEqualTo("Вопрос про выбор");
        assertThat(list.path("exercises").get(2).path("prompt").stringValue(null)).isEqualTo("Yo … a casa");
        assertThat(list.path("exercises").get(3).path("prompt").isNull()).isTrue();
        assertThat(response.body()).doesNotContain("SECRET");
    }

    @Test
    void nonOwnersReadThePublishedRevisionWhileTheOwnerKeepsTheHead() {
        Deck deck = fixtures.deck(owner, "Первая редакция");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        SharedScopeFixture editing = new SharedScopeFixture(decks, items, storage, jdbc, transactions);

        // after the publication the owner renames the deck, rewrites the material, adds one and publishes another exercise
        decks.save(owner, deck.id(), Long.parseLong(decks.read(owner, deck.id()).path("rowVersion").stringValue(null)),
                new DeckCommand(UUID.randomUUID(), "Вторая редакция", "Новое описание"));
        JsonNode rewritten = items.read(owner, deck.id(), deck.material().member(), null).path("document").deepCopy();
        ((ObjectNode) rewritten.path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", "rewritten");
        UUID edited = editing.save(owner, deck.id(), deck.material().member(), deck.material().itemRevision(), rewritten);
        var added = fixtures.study().addMaterial(owner, deck.id(), "added", "later");
        fixtures.study().publish(added, fixtures.study().selfCheck(added,
                app.mnema.learning.support.StudyFixtures.blocks(app.mnema.learning.support.StudyFixtures.text("Новый вопрос")),
                app.mnema.learning.support.StudyFixtures.blocks(app.mnema.learning.support.StudyFixtures.text("ответ"))), "Новая цель");

        // the owner's own routes see the head
        assertThat(decks.read(owner, deck.id()).path("metadata").path("title").stringValue(null)).isEqualTo("Вторая редакция");
        assertThat(items.list(owner, deck.id(), null, null).path("total").intValue()).isEqualTo(2);
        assertThat(items.read(owner, deck.id(), deck.material().member(), null).path("itemRevisionId").stringValue(null)).isEqualTo(edited.toString());
        assertThat(exercises.list(owner, deck.id(), null, null).path("total").intValue()).isEqualTo(2);

        // everybody else, the owner on the public route included, sees the published revision
        for (UUID viewer : new UUID[] {null, other, owner}) {
            JsonNode summary = json(get("/public/decks/" + code, viewer));
            assertThat(summary.path("title").stringValue(null)).as("viewer " + viewer).isEqualTo("Первая редакция");
            assertThat(summary.path("description").stringValue(null)).isEqualTo("Описание Первая редакция");
            assertThat(summary.path("memberCount").intValue()).isEqualTo(1);
            assertThat(summary.path("exerciseCount").intValue()).isEqualTo(1);
            JsonNode list = json(get("/public/decks/" + code + "/items", viewer));
            assertThat(list.path("total").intValue()).isEqualTo(1);
            assertThat(list.path("items").get(0).path("itemRevisionId").stringValue(null)).isEqualTo(deck.material().itemRevision().toString());
            JsonNode item = json(get("/public/decks/" + code + "/items/" + deck.material().member(), viewer));
            assertThat(item.path("document").toString()).contains("memory").doesNotContain("rewritten");
            assertThat(json(get("/public/decks/" + code + "/exercises", viewer)).path("total").intValue()).isEqualTo(1);
        }
    }

    @Test
    void aMaterialOutsideThePublishedManifestIsNotFoundEvenWhenTheDeckHasIt() {
        Deck deck = fixtures.deck(owner, "Манифест");
        String code = fixtures.publishAt(deck, DeckVisibility.LINK);
        var added = fixtures.study().addMaterial(owner, deck.id(), "later", "added");
        // in the head and the lineage, not in the published manifest
        assertThat(items.read(owner, deck.id(), added.member(), null).path("memberKey").stringValue(null)).isEqualTo(added.member().toString());
        problem(get("/public/decks/" + code + "/items/" + added.member(), null), 404, "RESOURCE_NOT_FOUND");
        problem(get("/public/decks/" + code + "/items/" + UUID.randomUUID(), null), 404, "RESOURCE_NOT_FOUND");
        assertThat(get("/public/decks/" + code + "/items/" + deck.material().member(), null).statusCode()).isEqualTo(200);
        // a member of the deck's lineage whose revision is no longer the published one reads the published revision only
        fixtures.publishHead(deck.id());
        assertThat(get("/public/decks/" + code + "/items/" + added.member(), null).statusCode()).isEqualTo(200);
        assertThat(json(get("/public/decks/" + code, null)).path("memberCount").intValue()).isEqualTo(2);
    }

    @Test
    void anEmptyPublishedDeckHasEmptyPagesAndNoMembers() {
        UUID id = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Пустая", "")).acknowledgement().path("deck").path("deckId").stringValue(null));
        Deck deck = new Deck(owner, id, null, null);
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        assertThat(json(get("/public/decks/" + code, null)).path("memberCount").intValue()).isZero();
        JsonNode items = json(get("/public/decks/" + code + "/items", null));
        assertThat(items.path("items")).isEmpty();
        assertThat(items.path("total").intValue()).isZero();
        assertThat(items.path("nextCursor").isNull()).isTrue();
        assertThat(json(get("/public/decks/" + code + "/exercises", null)).path("exercises")).isEmpty();
        problem(get("/public/decks/" + code + "/items/" + UUID.randomUUID(), null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void pagesAreCursorBoundedAndBoundToThePublishedRevision() {
        Deck deck = fixtures.deck(owner, "Много");
        List<UUID> members = new ArrayList<>(List.of(deck.material().member()));
        for (int index = 0; index < 4; index++) members.add(fixtures.study().addMaterial(owner, deck.id(), "m" + index, "x").member());
        String code = fixtures.publishAt(deck, DeckVisibility.LINK);

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = json(get("/public/decks/" + code + "/items?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), other));
            assertThat(page.path("total").intValue()).isEqualTo(5);
            page.path("items").forEach(entry -> seen.add(UUID.fromString(entry.path("memberKey").stringValue(null))));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").stringValue(null);
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(members);

        String firstCursor = json(get("/public/decks/" + code + "/items?limit=1", null)).path("nextCursor").stringValue(null);
        for (String bad : new String[] {"limit=0", "limit=101", "limit=x", "limit=1&limit=2", "cursor=!!", "cursor=" + firstCursor + "&cursor=" + firstCursor}) {
            problem(get("/public/decks/" + code + "/items?" + bad, null), 400, "INVALID_REQUEST");
        }
        problem(get("/public/decks/" + code + "/exercises?cursor=" + firstCursor, null), 400, "INVALID_REQUEST");
        problem(get("/public/decks/" + code + "/items/not-a-uuid", null), 400, "INVALID_REQUEST");
        assertThat(get("/public/decks/" + code + "/items?limit=100", null).statusCode()).isEqualTo(200);

        // a republication invalidates the cursors of the previous one
        decks.save(owner, deck.id(), Long.parseLong(decks.read(owner, deck.id()).path("rowVersion").stringValue(null)),
                new DeckCommand(UUID.randomUUID(), "Много 2", ""));
        fixtures.publishHead(deck.id());
        problem(get("/public/decks/" + code + "/items?cursor=" + firstCursor, null), 412, "VERSION_CONFLICT");
        assertThat(json(get("/public/decks/" + code + "/items?limit=1", null)).path("nextCursor").stringValue(null)).isNotEqualTo(firstCursor);
    }

    @Test
    void aPublishedCopyServesTheExercisesItInheritedThroughTheLineage() {
        Deck source = fixtures.deck(UUID.randomUUID(), "Источник с упражнением");
        SharedScopeFixture shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        SharedScopeFixture.Copy copy = shared.copy(source.id(), UUID.randomUUID());
        String code = fixtures.publishAt(new Deck(copy.owner(), copy.deckId(), null, null), DeckVisibility.LINK);

        JsonNode page = json(get("/public/decks/" + code + "/exercises", null));
        assertThat(page.path("total").intValue()).isEqualTo(1);
        assertThat(page.path("exercises").get(0).path("exerciseId").stringValue(null)).isEqualTo(source.exercise().toString());
        assertThat(page.toString()).doesNotContain(LibraryFixtures.SECRET_ANSWER).doesNotContain(LibraryFixtures.SECRET_REFERENCE);
    }

    @Test
    void readingOneDecksPublishedRevisionNeverExposesRowsOfAnotherDeckOfTheSameLineage() {
        SharedScopeFixture shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        SharedScopeFixture.Scenario scenario = shared.fork();
        // after the fork the author edits the shared material and adds a new one; the copy has neither
        UUID authorRevision = shared.editSource(scenario, "author edit after the fork");
        UUID authorOnly = shared.create(scenario.author(), scenario.source(), shared.document("author only")).member();
        Deck copy = new Deck(scenario.copyOwner(), scenario.copyDeck(), null, null);
        String copyCode = fixtures.publishAt(copy, DeckVisibility.LINK);
        Deck source = new Deck(scenario.author(), scenario.source(), null, null);
        String sourceCode = fixtures.publishAt(source, DeckVisibility.LINK);

        JsonNode copyItems = json(get("/public/decks/" + copyCode + "/items", null));
        assertThat(copyItems.path("total").intValue()).isEqualTo(1);
        assertThat(copyItems.path("items").get(0).path("memberKey").stringValue(null)).isEqualTo(scenario.material().member().toString());
        assertThat(copyItems.path("items").get(0).path("itemRevisionId").stringValue(null)).isEqualTo(scenario.material().revision().toString());
        JsonNode copyItem = json(get("/public/decks/" + copyCode + "/items/" + scenario.material().member(), null));
        assertThat(copyItem.path("itemRevisionId").stringValue(null)).isEqualTo(scenario.material().revision().toString());
        assertThat(copyItem.path("document").toString()).contains("one").doesNotContain("author edit after the fork");
        problem(get("/public/decks/" + copyCode + "/items/" + authorOnly, null), 404, "RESOURCE_NOT_FOUND");
        assertThat(json(get("/public/decks/" + copyCode, null)).path("memberCount").intValue()).isEqualTo(1);

        // the author's code serves the author's revision, with both materials
        JsonNode sourceItems = json(get("/public/decks/" + sourceCode + "/items", null));
        assertThat(sourceItems.path("total").intValue()).isEqualTo(2);
        assertThat(json(get("/public/decks/" + sourceCode + "/items/" + scenario.material().member(), null)).path("itemRevisionId").stringValue(null))
                .isEqualTo(authorRevision.toString());
        assertThat(get("/public/decks/" + sourceCode + "/items/" + authorOnly, null).statusCode()).isEqualTo(200);

        // and the copy's owner edits his own material later: still the published revision for others
        UUID copyEdit = shared.editCopy(scenario, "copy edit after publication");
        assertThat(copyEdit).isNotEqualTo(scenario.material().revision());
        assertThat(json(get("/public/decks/" + copyCode + "/items/" + scenario.material().member(), null)).path("document").toString())
                .doesNotContain("copy edit after publication");
    }

    @Test
    void movingAPublicDeckToLinkChangesTheLinkOverHttp() {
        Deck deck = fixtures.deck(owner, "Ротация");
        String oldCode = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        assertThat(json(get("/public/decks/" + oldCode, null)).path("slug").stringValue(null)).isEqualTo("rotatsiya");
        String newCode = fixtures.level(deck, DeckVisibility.LINK);
        assertThat(newCode).isNotEqualTo(oldCode);
        for (String suffix : new String[] {"", "/items", "/exercises", "/items/" + deck.material().member()}) {
            problem(get("/public/decks/" + oldCode + suffix, null), 404, "RESOURCE_NOT_FOUND");
            problem(get("/public/decks/" + oldCode + suffix, other), 404, "RESOURCE_NOT_FOUND");
            assertThat(get("/public/decks/" + newCode + suffix, null).statusCode()).as(suffix).isEqualTo(200);
        }
        JsonNode summary = json(get("/public/decks/" + newCode, other));
        assertThat(summary.path("visibility").stringValue(null)).isEqualTo("LINK");
        assertThat(summary.propertyNames()).doesNotContain("slug");
        // raising it again keeps the new link
        assertThat(fixtures.level(deck, DeckVisibility.PUBLIC)).isEqualTo(newCode);
        assertThat(json(get("/public/decks/" + newCode, null)).path("access").stringValue(null)).isEqualTo("PUBLIC");
    }

    @Test
    void anInvalidBearerIsAlwaysUnauthorizedNeverAGuest() {
        Deck deck = fixtures.deck(owner, "Токены");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        String path = "/public/decks/" + code;
        for (String bearer : new String[] {"garbage", "a.b.c", HttpIdentityFixture.reader(other).substring(0, 100) + "tampered"}) {
            problem(HttpIdentityFixture.send(port, "GET", path, bearer, null, Map.of()), 401, "AUTHENTICATION_REQUIRED");
            problem(HttpIdentityFixture.send(port, "GET", path + "/items", bearer, null, Map.of()), 401, "AUTHENTICATION_REQUIRED");
        }
        // an account that Identity no longer knows is rejected like on the private API
        UUID revoked = UUID.randomUUID();
        HttpIdentityFixture.revoke(revoked);
        try {
            problem(get(path, revoked), 401, "AUTHENTICATION_REQUIRED");
        } finally {
            HttpIdentityFixture.restore(revoked);
        }
        assertThat(get(path, revoked).statusCode()).isEqualTo(200);
        // a valid token without the read scope is refused like on the private API
        problem(HttpIdentityFixture.send(port, "GET", path, HttpIdentityFixture.token(other, "learning.write"), null, Map.of()), 403, "ACCESS_DENIED");
        // tokens travel in the header only: a query parameter or a cookie is ignored, so the request is a guest's
        HttpResponse<String> query = HttpIdentityFixture.send(port, "GET", path + "?access_token=" + HttpIdentityFixture.reader(owner), null, null, Map.of());
        assertThat(json(query).path("access").stringValue(null)).isEqualTo("PUBLIC");
        HttpResponse<String> cookie = HttpIdentityFixture.send(port, "GET", path, null, null, Map.of("Cookie", "access_token=" + HttpIdentityFixture.reader(owner)));
        assertThat(json(cookie).path("access").stringValue(null)).isEqualTo("PUBLIC");
        // a valid token identifies the account
        assertThat(json(get(path, owner)).path("access").stringValue(null)).isEqualTo("OWNER");
    }

    @Test
    void onlyGetAndHeadAreServedAndNothingIsStoredOnTheClient() {
        Deck deck = fixtures.deck(owner, "Методы");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        String path = "/public/decks/" + code;
        HttpResponse<String> head = HttpIdentityFixture.send(port, "HEAD", path, null, null, Map.of());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            for (String token : new String[] {null, HttpIdentityFixture.reader(owner), HttpIdentityFixture.reader(other)}) {
                HttpResponse<String> response = HttpIdentityFixture.send(port, method, path, token, "{}", Map.of());
                assertThat(response.statusCode()).as(method + " " + (token == null ? "guest" : "account")).isBetween(401, 405);
                assertThat(response.headers().firstValue("set-cookie")).isEmpty();
            }
        }
        assertThat(get(path, null).headers().firstValue("set-cookie")).isEmpty();
        assertThat(get(path, owner).headers().firstValue("set-cookie")).isEmpty();
    }

    private static JsonNode contract() {
        try {
            java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
            while (!java.nio.file.Files.exists(root.resolve("contracts/decks/public-read.json"))) root = root.getParent();
            return JSON.readTree(java.nio.file.Files.readString(root.resolve("contracts/decks/public-read.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new java.util.TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    @Test
    void theContractFixtureHasExactlyTheShapesOfTheRealAnswers() {
        JsonNode contract = contract();
        Deck deck = fixtures.deck(owner, "Контракт");
        String publicCode = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        Deck linked = fixtures.deck(owner, "Контракт по ссылке");
        String linkCode = fixtures.publishAt(linked, DeckVisibility.LINK);
        Deck invited = fixtures.deck(owner, "Контракт для своих");
        String inviteCode = fixtures.publishAt(invited, DeckVisibility.INVITE);
        publications.grant(owner, invited.id(), grantee, GrantRole.VIEWER);

        assertThat(names(json(get("/public/decks/" + publicCode, null)))).isEqualTo(names(contract.path("summary").path("public")));
        assertThat(names(json(get("/public/decks/" + linkCode, null)))).isEqualTo(names(contract.path("summary").path("link")));
        assertThat(names(json(get("/public/decks/" + inviteCode, grantee)))).isEqualTo(names(contract.path("summary").path("invitedGrantee")));
        JsonNode page = json(get("/public/decks/" + publicCode + "/items", null));
        assertThat(names(page)).isEqualTo(names(contract.path("items").path("page").path("response")));
        assertThat(names(page.path("items").get(0))).isEqualTo(names(contract.path("items").path("page").path("response").path("items").get(0)));
        assertThat(names(json(get("/public/decks/" + publicCode + "/items/" + deck.material().member(), null))))
                .isEqualTo(names(contract.path("items").path("document").path("response")));
        JsonNode exercises = json(get("/public/decks/" + publicCode + "/exercises", null));
        assertThat(names(exercises)).isEqualTo(names(contract.path("exercises").path("page").path("response")));
        assertThat(names(exercises.path("exercises").get(0))).isEqualTo(names(contract.path("exercises").path("page").path("response").path("exercises").get(0)));
        // problems: same members as the fixture
        JsonNode notFound = json(get("/public/decks/" + PublicCodes.next(new java.security.SecureRandom()), null));
        assertThat(names(notFound)).isEqualTo(names(contract.path("problems").path("notFound").path("body")));
        JsonNode inviteOnly = json(get("/public/decks/" + inviteCode, other));
        assertThat(names(inviteOnly)).isEqualTo(names(contract.path("problems").path("inviteOnly").path("body")));
        assertThat(inviteOnly.path("code").stringValue(null)).isEqualTo(contract.path("problems").path("inviteOnly").path("body").path("code").stringValue(null));
        // the constants the clients rely on
        JsonNode constants = contract.path("constants");
        assertThat(constants.path("codeLength").intValue()).isEqualTo(PublicCodes.LENGTH);
        assertThat(constants.path("pageMax").intValue()).isEqualTo(PublicCursor.MAX_PAGE);
        assertThat(constants.path("slugMaxLength").intValue()).isEqualTo(DeckSlug.MAX_LENGTH);
        assertThat(constants.path("promptMaxCodePoints").intValue()).isEqualTo(ExercisePrompts.MAX_CODE_POINTS);
        assertThat(constants.path("pageDefault").intValue()).isEqualTo(20);
        for (String example : List.of("public", "link", "invitedGrantee")) {
            JsonNode summary = contract.path("summary").path(example);
            assertThat(PublicCodes.valid(summary.path("code").stringValue(null))).isTrue();
            assertThat(summary.has("slug")).isEqualTo(summary.path("visibility").stringValue(null).equals("PUBLIC"));
            assertThat(constants.path("accessLevels").valueStream().map(JsonNode::stringValue)).contains(summary.path("access").stringValue(null));
        }
        assertThat(contract.path("summary").path("public").path("slug").stringValue(null))
                .isEqualTo(DeckSlug.of(contract.path("summary").path("public").path("title").stringValue(null)));
        assertThat(contract.path("summary").path("public").path("slug").stringValue(null).length()).isLessThanOrEqualTo(DeckSlug.MAX_LENGTH);
        JsonNode first = contract.path("items").path("page").path("response");
        JsonNode last = contract.path("items").path("page").path("lastPage");
        assertThat(first.path("items").get(0).path("ordinal").intValue() + 1).isEqualTo(last.path("items").get(0).path("ordinal").intValue());
        assertThat(first.path("total").intValue()).isEqualTo(last.path("total").intValue());
        assertThat(first.path("nextCursor").isString()).isTrue();
        assertThat(last.path("nextCursor").isNull()).isTrue();
        assertThat(contract.toString()).doesNotContain("deckId", "deckRevisionId", "deckVersion", "answerKey", "accepted");
    }

    @Test
    void requestsAreCountedByRouteAndOutcome() {
        Deck deck = fixtures.deck(owner, "Метрики");
        String code = fixtures.publishAt(deck, DeckVisibility.INVITE);
        double ok = counter("summary", "ok");
        double notFound = counter("summary", "not_found");
        double inviteOnly = counter("summary", "invite_only");
        assertThat(get("/public/decks/" + code, owner).statusCode()).isEqualTo(200);
        assertThat(get("/public/decks/" + code, other).statusCode()).isEqualTo(403);
        assertThat(get("/public/decks/" + PublicCodes.next(new java.security.SecureRandom()), other).statusCode()).isEqualTo(404);
        assertThat(counter("summary", "ok")).isEqualTo(ok + 1);
        assertThat(counter("summary", "invite_only")).isEqualTo(inviteOnly + 1);
        assertThat(counter("summary", "not_found")).isEqualTo(notFound + 1);
    }

    private double counter(String route, String outcome) {
        var counter = meters.find("mnema_public_deck_requests_total").tags("route", route, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
