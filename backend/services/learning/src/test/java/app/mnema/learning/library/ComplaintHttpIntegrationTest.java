package app.mnema.learning.library;

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
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/15 (#437) over the real servlet stack and a real PostgreSQL: filing a complaint about a deck or a profile, the owner console (queue, detail, picker,
 * decisions, journal), hiding by moderation and the legal takedown by lineage as the source, an unchanged copy and an edited copy see it, with the shapes of
 * {@code contracts/moderation/complaints.json}. The database is shared by the whole test JVM, so every assertion is about the rows of this test's own ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComplaintHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode FIXTURE = fixture();
    private static final UUID CONSOLE = UUID.randomUUID();

    @LocalServerPort private int port;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private LibraryFixtures fixtures;
    private SharedScopeFixture shared;

    private final UUID owner = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.admin.owner-id", CONSOLE::toString);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        registry.add("learning.community.complaints.reporter-per-day", () -> 4);
        registry.add("learning.community.profile-lookups.instance-per-window", () -> 1_000);
        registry.add("learning.community.profile-lookups.complaints-per-window", () -> 1_000);
        registry.add("learning.community.profile-lookups.owner-per-window", () -> 1_000);
        registry.add("learning.community.profile-lookups.reporter-per-window", () -> 1_000);
    }

    @BeforeEach
    void fixtures() {
        fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    private static JsonNode fixture() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/moderation/complaints.json"))) root = root.getParent();
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/moderation/complaints.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---- helpers ----

    private record Published(Deck deck, String code) { UUID id() { return deck.id(); } }

    private Published published(DeckVisibility level) {
        Deck deck = fixtures.deck(owner, "Колода " + UUID.randomUUID());
        return new Published(deck, fixtures.publishAt(deck, level));
    }

    /** A published deck whose material can be edited by the lineage fixture (it was created by it) and which has one free-response exercise. */
    private Published editable(DeckVisibility level) {
        UUID deckId = shared.deck(owner, "Колода " + UUID.randomUUID());
        SharedScopeFixture.Material material = shared.create(owner, deckId, shared.document("one"));
        StudyFixtures.Material host = new StudyFixtures.Material(owner, deckId, material.member(), material.revision(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        StudyFixtures study = fixtures.study();
        JsonNode exercise = study.publish(host, study.freeResponse(host, StudyFixtures.blocks(StudyFixtures.text("Что такое память?")),
                StudyFixtures.blocks(StudyFixtures.text(LibraryFixtures.SECRET_REFERENCE)), LibraryFixtures.SECRET_ANSWER), LibraryFixtures.OBJECTIVE_TITLE);
        Deck deck = new Deck(owner, deckId, host, UUID.fromString(exercise.path("exerciseId").stringValue(null)));
        return new Published(deck, fixtures.publishAt(deck, level));
    }

    /**
     * The owner of a taken-down material cannot read it (410) and so cannot open it in the editor, but he can replace it: the list still gives its ordinal and the
     * revision it holds, which is all a save needs. The new revision is not taken down.
     */
    private UUID replaceWithoutReading(UUID account, UUID deck, UUID member, UUID expected, JsonNode document) {
        int ordinal = -1;
        for (JsonNode row : ok(asUser("GET", "/decks/" + deck + "/items", account, null), 200).path("items")) {
            if (member.toString().equals(row.path("memberKey").stringValue(null))) ordinal = row.path("ordinal").intValue();
        }
        assertThat(ordinal).isGreaterThanOrEqualTo(0);
        JsonNode head = decks.read(account, deck);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", expected.toString()).put("expectedOrdinal", ordinal);
        body.set("document", document.deepCopy());
        JsonNode change = items.publish(account, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                app.mnema.learning.catalog.item.ItemPublicationCommand.readSave(new java.io.ByteArrayInputStream(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), member))
                .acknowledgement().path("changes").get(0);
        return UUID.fromString(change.path("itemRevisionId").stringValue(null));
    }

    private HttpResponse<String> call(String method, String path, String bearer, String body) {
        return HttpIdentityFixture.send(port, method, path, bearer, body, Map.of());
    }

    private HttpResponse<String> asUser(String method, String path, UUID account, String body) {
        return call(method, path, account == null ? null : HttpIdentityFixture.reader(account), body);
    }

    private HttpResponse<String> admin(String method, String path, String body) {
        return call(method, path, HttpIdentityFixture.adminToken(CONSOLE), body);
    }

    private HttpResponse<String> complain(String code, UUID reporter, String reason, String text) {
        ObjectNode body = JSON.createObjectNode().put("reason", reason);
        if (text != null) body.put("text", text);
        return asUser("POST", "/public/decks/" + code + "/complaints", reporter, body.toString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    private JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json(response);
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    private static Set<String> fixtureNames(String... path) {
        JsonNode node = FIXTURE;
        for (String step : path) node = node.path(step);
        Set<String> result = new TreeSet<>();
        node.forEach(value -> result.add(value.stringValue(null)));
        return result;
    }

    private UUID complaintOf(UUID reporter, UUID deck) {
        return jdbc.sql("SELECT complaint_id FROM app_learning.content_complaint WHERE reporter_id = :reporter AND deck_id = :deck")
                .param("reporter", reporter).param("deck", deck).query(UUID.class).single();
    }

    private long complaints(UUID reporter) {
        return jdbc.sql("SELECT count(*) FROM app_learning.content_complaint WHERE reporter_id = :reporter").param("reporter", reporter).query(Long.class).single();
    }

    private long notifications(UUID account, String kind) {
        return jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id = :owner AND kind = :kind").param("owner", account).param("kind", kind)
                .query(Long.class).single();
    }

    private long journal(UUID complaint, String action) {
        return jdbc.sql("SELECT count(*) FROM app_learning.content_takedown_journal WHERE complaint_id = :id AND action = :action").param("id", complaint)
                .param("action", action).query(Long.class).single();
    }

    private ObjectNode decision(String decision) { return JSON.createObjectNode().put("decision", decision); }

    private UUID firstMember(Published deck) { return deck.deck().material().member(); }

    // ---- filing ----

    @Test
    void aSignedInReaderFilesAComplaintAndARepeatChangesNothing() {
        Published deck = published(DeckVisibility.PUBLIC);
        UUID reporter = UUID.randomUUID();
        HttpResponse<String> first = complain(deck.code(), reporter, "COPYRIGHT", "Это моя иллюстрация, права не передавались.");
        assertThat(ok(first, 202)).isEqualTo(FIXTURE.path("filing").path("answer"));
        assertThat(first.headers().firstValue("cache-control")).hasValue("private, no-store");
        // the same answer, nothing written, the first reason stands
        assertThat(ok(complain(deck.code(), reporter, "SPAM", null), 202)).isEqualTo(FIXTURE.path("filing").path("answer"));
        assertThat(complaints(reporter)).isEqualTo(1);
        var row = jdbc.sql("SELECT reason, reporter_text, status, decision, public_code, due_at - created_at AS review FROM app_learning.content_complaint WHERE reporter_id = :reporter")
                .param("reporter", reporter).query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), String.valueOf(rs.getString(4)), rs.getString(5),
                        rs.getString(6))).single();
        assertThat(row.subList(0, 5)).containsExactly("COPYRIGHT", "Это моя иллюстрация, права не передавались.", "OPEN", "null", deck.code());
        assertThat(row.get(5)).as("due in 30 days").contains("30 days");
        assertThat(jdbc.sql("SELECT retain_until > created_at + interval '2 years 364 days' FROM app_learning.content_complaint WHERE reporter_id = :reporter")
                .param("reporter", reporter).query(Boolean.class).single()).as("retained 3 years").isTrue();
        // nobody is told: not the reporter, not the author
        assertThat(notifications(reporter, "DECK_HIDDEN") + notifications(owner, "DECK_HIDDEN")).isZero();
        // a blank text is the same as no text; the example without text is accepted
        UUID other = UUID.randomUUID();
        assertThat(ok(complain(deck.code(), other, "SPAM", "   "), 202)).isEqualTo(FIXTURE.path("filing").path("answer"));
        assertThat(jdbc.sql("SELECT reporter_text FROM app_learning.content_complaint WHERE reporter_id = :r").param("r", other).query(String.class).optional()).isEmpty();
    }

    @Test
    void filingNeedsASignedInAccountWithTheWriteScope() {
        Published deck = published(DeckVisibility.PUBLIC);
        String path = "/public/decks/" + deck.code() + "/complaints";
        String body = JSON.createObjectNode().put("reason", "SPAM").toString();
        assertThat(call("POST", path, null, body).statusCode()).isEqualTo(401);
        assertThat(call("POST", path, HttpIdentityFixture.token(UUID.randomUUID(), "learning.read"), body).statusCode()).isEqualTo(403);
        assertThat(call("POST", path, "not-a-token", body).statusCode()).isEqualTo(401);
        // the profile route is on the private chain: the same
        assertThat(call("POST", "/public/profiles/somebody/complaints", null, body).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/public/profiles/somebody/complaints", HttpIdentityFixture.token(UUID.randomUUID(), "learning.read"), body).statusCode()).isEqualTo(403);
        // the routes offer POST only
        assertThat(asUser("GET", path, UUID.randomUUID(), null).statusCode()).isIn(404, 405);
    }

    @Test
    void onlyADeckTheReporterCanReadRightNowCanBeReported() {
        UUID reporter = UUID.randomUUID();
        Published invite = published(DeckVisibility.INVITE);
        Published link = published(DeckVisibility.LINK);
        Published privateDeck = published(DeckVisibility.PUBLIC);
        fixtures.level(privateDeck.deck(), DeckVisibility.PRIVATE);
        Published granted = published(DeckVisibility.INVITE);
        publications.grant(owner, granted.id(), reporter, GrantRole.VIEWER);
        Published rotated = published(DeckVisibility.PUBLIC);
        fixtures.level(rotated.deck(), DeckVisibility.LINK);
        List<String> notFound = new ArrayList<>();
        for (String code : List.of(invite.code(), privateDeck.code(), rotated.code(), "AAAAAAAAAA", "not-a-code")) {
            HttpResponse<String> response = complain(code, reporter, "SPAM", null);
            assertThat(response.statusCode()).as(code).isEqualTo(404);
            assertThat(json(response).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
            notFound.add(code);
        }
        assertThat(complaints(reporter)).isZero();
        // a LINK deck and a deck the reporter holds a grant for can be reported
        assertThat(complain(link.code(), reporter, "ILLEGAL", null).statusCode()).isEqualTo(202);
        assertThat(complain(granted.code(), reporter, "OTHER", null).statusCode()).isEqualTo(202);
        // the author cannot report his own deck
        HttpResponse<String> own = complain(link.code(), owner, "SPAM", null);
        assertThat(own.statusCode()).isEqualTo(400);
        assertThat(complaints(owner)).isZero();
        assertThat(notFound).hasSize(5);
    }

    @Test
    void theBodyIsStrictAndNeverEchoed() {
        Published deck = published(DeckVisibility.PUBLIC);
        UUID reporter = UUID.randomUUID();
        String path = "/public/decks/" + deck.code() + "/complaints";
        String secret = "ТЕКСТ-ЖАЛОБЫ-СЕКРЕТ";
        List<String> bad = List.of(
                "{}", "[]", "\"x\"", "", "{\"reason\":\"SPAM\",\"extra\":1}", "{\"reason\":\"spam\"}", "{\"reason\":\"UNKNOWN\"}", "{\"reason\":1}",
                "{\"reason\":\"SPAM\",\"text\":5}", "{\"reason\":\"SPAM\",\"text\":null}", "{\"reason\":\"SPAM\",\"text\":\"" + "я".repeat(1001) + "\"}",
                "{\"reason\":\"SPAM\",\"text\":\"" + secret + "\\u0000\"}", "{\"reason\":\"SPAM\",\"text\":\"" + secret + "\\u0007\"}",
                // Unicode format characters: a bidi override, a zero-width space, the byte order mark, a soft hyphen
                "{\"reason\":\"SPAM\",\"text\":\"" + secret + "\\u202e\"}", "{\"reason\":\"SPAM\",\"text\":\"a\\u200bb " + secret + "\"}",
                "{\"reason\":\"SPAM\",\"text\":\"\\ufeff" + secret + "\"}", "{\"reason\":\"SPAM\",\"text\":\"" + secret + "\\u00ad\"}",
                "{\"reason\":\"SPAM\",\"reason\":\"OTHER\"}", "{\"text\":\"" + secret + "\"}", "{\"reason\":\"SPAM\"," + "\"text\":\"" + "x".repeat(5000) + "\"}");
        for (String body : bad) {
            HttpResponse<String> response = asUser("POST", path, reporter, body);
            assertThat(response.statusCode()).as(body.length() > 80 ? body.substring(0, 80) : body).isEqualTo(400);
            assertThat(response.body()).doesNotContain(secret);
        }
        assertThat(asUser("POST", path, reporter, "{\"reason\":\"SPAM\"}").statusCode()).isEqualTo(202);
        // exactly 1000 code points is allowed; a line feed and a tab are text
        assertThat(complain(published(DeckVisibility.PUBLIC).code(), reporter, "OTHER", "я".repeat(1000)).statusCode()).isEqualTo(202);
        assertThat(complain(published(DeckVisibility.PUBLIC).code(), reporter, "OTHER", "строка\n\tдве").statusCode()).isEqualTo(202);
        assertThat(complaints(reporter)).isEqualTo(3);
    }

    @Test
    void aReporterFilesAtMostFourNewComplaintsADayAndARepeatIsNotCounted() {
        UUID reporter = UUID.randomUUID();
        List<Published> targets = List.of(published(DeckVisibility.PUBLIC), published(DeckVisibility.PUBLIC), published(DeckVisibility.PUBLIC),
                published(DeckVisibility.PUBLIC), published(DeckVisibility.PUBLIC));
        for (int index = 0; index < 4; index++) assertThat(complain(targets.get(index).code(), reporter, "SPAM", null).statusCode()).isEqualTo(202);
        HttpResponse<String> limited = complain(targets.get(4).code(), reporter, "SPAM", null);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(json(limited).path("code").stringValue(null)).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.headers().firstValue("retry-after").orElseThrow())).isBetween(1L, 86_400L);
        // a silent repeat of an open complaint is answered before the limit
        assertThat(complain(targets.get(0).code(), reporter, "SPAM", null).statusCode()).isEqualTo(202);
        assertThat(complaints(reporter)).isEqualTo(4);
    }

    @Test
    void concurrentFilingsOfOneReporterNeverDuplicateAComplaintOrExceedTheDailyLimit() throws Exception {
        Published deck = published(DeckVisibility.PUBLIC);
        UUID same = UUID.randomUUID();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Integer>> repeats = new ArrayList<>();
            for (int index = 0; index < 8; index++) repeats.add(executor.submit(() -> complain(deck.code(), same, "SPAM", null).statusCode()));
            for (var each : repeats) assertThat(each.get()).isEqualTo(202);
        }
        assertThat(complaints(same)).as("eight identical filings are one complaint").isEqualTo(1);
        UUID busy = UUID.randomUUID();
        List<Published> targets = new ArrayList<>();
        for (int index = 0; index < 9; index++) targets.add(published(DeckVisibility.PUBLIC));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (Published target : targets) results.add(executor.submit(() -> complain(target.code(), busy, "SPAM", null).statusCode()));
            int accepted = 0;
            int limited = 0;
            for (var each : results) {
                int status = each.get();
                if (status == 202) accepted++;
                else if (status == 429) limited++;
            }
            assertThat(accepted).isEqualTo(4);
            assertThat(limited).isEqualTo(5);
        }
        assertThat(complaints(busy)).isEqualTo(4);
    }

    @Test
    void aProfileIsReportedThroughIdentityAndAnUnknownOrHiddenLoginIsTheSameNotFound() {
        UUID target = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        String login = "reported-" + UUID.randomUUID().toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(login, target);
        HttpIdentityFixture.publicLogin("self-" + reporter.toString().substring(0, 8), reporter);
        String body = JSON.createObjectNode().put("reason", "PERSONAL_DATA").put("text", "На странице мои данные").toString();
        assertThat(ok(asUser("POST", "/public/profiles/" + login + "/complaints", reporter, body), 202)).isEqualTo(FIXTURE.path("filing").path("answer"));
        assertThat(ok(asUser("POST", "/public/profiles/" + login.toUpperCase(java.util.Locale.ROOT) + "/complaints", reporter, body), 202)).isNotNull();
        var row = jdbc.sql("SELECT target_kind, profile_account_id, profile_username, deck_id, status FROM app_learning.content_complaint WHERE reporter_id = :r")
                .param("r", reporter).query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), String.valueOf(rs.getString(4)), rs.getString(5))).list();
        assertThat(row).as("the repeat in another letter case is silent").hasSize(1);
        assertThat(row.getFirst()).containsExactly("PROFILE", target.toString(), login, "null", "OPEN");
        // unknown, malformed and hidden are one answer; your own profile is 400
        HttpIdentityFixture.hideLogin(login);
        HttpResponse<String> unknown = asUser("POST", "/public/profiles/" + login + "/complaints", UUID.randomUUID(), body);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(asUser("POST", "/public/profiles/nobody-" + UUID.randomUUID() + "/complaints", reporter, body).statusCode()).isEqualTo(404);
        assertThat(asUser("POST", "/public/profiles/a/complaints", reporter, body).statusCode()).isEqualTo(404);
        assertThat(asUser("POST", "/public/profiles/self-" + reporter.toString().substring(0, 8) + "/complaints", reporter, body).statusCode()).isEqualTo(400);
        // Identity is down: the answer of the grant lookup (Identity limiting lookups is 429 with the budget window's wait, ProfileLookupBudgetHttpIntegrationTest)
        assertThat(asUser("POST", "/public/profiles/" + HttpIdentityFixture.BROKEN_LOGIN + "/complaints", reporter, body).statusCode()).isEqualTo(503);
        assertThat(complaints(reporter)).isEqualTo(1);
    }

    // ---- the console ----

    @Test
    void onlyTheConsoleOwnerOnTheAdminClientReachesTheComplaintRoutes() {
        Published deck = published(DeckVisibility.PUBLIC);
        UUID reporter = UUID.randomUUID();
        assertThat(complain(deck.code(), reporter, "SPAM", null).statusCode()).isEqualTo(202);
        UUID id = complaintOf(reporter, deck.id());
        List<String[]> routes = List.of(new String[] {"GET", "/admin/complaints"}, new String[] {"GET", "/admin/complaints/journal"},
                new String[] {"GET", "/admin/complaints/" + id}, new String[] {"GET", "/admin/complaints/" + id + "/units?kind=ITEM"},
                new String[] {"POST", "/admin/complaints/" + id + ":decide"}, new String[] {"POST", "/admin/complaints/" + id + ":unhide"});
        for (String[] route : routes) {
            String body = route[0].equals("POST") && route[1].endsWith(":decide") ? decision("REJECTED").toString() : null;
            String label = route[0] + " " + route[1];
            assertThat(call(route[0], route[1], null, body).statusCode()).as(label).isEqualTo(401);
            // a learner, the owner account on the learner client and a token without the write scope are all 403
            assertThat(asUser(route[0], route[1], reporter, body).statusCode()).as(label).isEqualTo(403);
            assertThat(asUser(route[0], route[1], CONSOLE, body).statusCode()).as(label + " (mnema-web)").isEqualTo(403);
            assertThat(call(route[0], route[1], HttpIdentityFixture.token(CONSOLE, "learning.read", "mnema-admin-web"), body).statusCode())
                    .as(label + " (read scope)").isIn(200, 403);
        }
        assertThat(ok(call("GET", "/admin/complaints/" + id, HttpIdentityFixture.adminToken(CONSOLE), null), 200).path("status").stringValue(null)).isEqualTo("OPEN");
        // the complaint stays open: nothing above decided it
        assertThat(jdbc.sql("SELECT status FROM app_learning.content_complaint WHERE complaint_id = :id").param("id", id).query(String.class).single()).isEqualTo("OPEN");
    }

    @Test
    void theQueueListsOpenComplaintsOldestDeadlineFirstInKeysetPages() {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Published deck = published(DeckVisibility.PUBLIC);
            UUID reporter = UUID.randomUUID();
            assertThat(complain(deck.code(), reporter, "COPYRIGHT", null).statusCode()).isEqualTo(202);
            ids.add(complaintOf(reporter, deck.id()));
        }
        // the whole open queue in pages of 2: ours appear in creation order (deadline order), each exactly once
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = ok(admin("GET", "/admin/complaints?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), null), 200);
            assertThat(names(page)).containsExactly("items", "nextCursor");
            for (JsonNode item : page.path("items")) {
                assertThat(names(item)).isEqualTo(fixtureNames("queue", "itemProperties"));
                assertThat(item.path("status").stringValue(null)).isEqualTo("OPEN");
                assertThat(item.path("overdue").booleanValue(false)).isFalse();
                seen.add(item.path("complaintId").stringValue(null));
            }
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").stringValue(null);
        } while (cursor != null);
        assertThat(seen).doesNotHaveDuplicates().containsSubsequence(ids.stream().map(UUID::toString).toList());
        // the deck title of the queue comes with the item
        JsonNode first = ok(admin("GET", "/admin/complaints?limit=100", null), 200).path("items").get(0);
        assertThat(first.path("targetKind").stringValue(null)).isIn("DECK", "PROFILE");
        // strictness
        for (String bad : List.of("/admin/complaints?status=ALL", "/admin/complaints?limit=0", "/admin/complaints?limit=101", "/admin/complaints?limit=x",
                "/admin/complaints?cursor=!", "/admin/complaints?cursor=AAAA", "/admin/complaints?extra=1", "/admin/complaints?limit=1&limit=2",
                "/admin/complaints/journal?status=OPEN", "/admin/complaints/not-a-uuid")) {
            assertThat(admin("GET", bad, null).statusCode()).as(bad).isEqualTo(400);
        }
        assertThat(admin("GET", "/admin/complaints/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void anOverdueComplaintSaysSoAndTheQueueOrdersByDeadline() {
        Published late = published(DeckVisibility.PUBLIC);
        Published recent = published(DeckVisibility.PUBLIC);
        UUID lateReporter = UUID.randomUUID();
        UUID recentReporter = UUID.randomUUID();
        assertThat(complain(recent.code(), recentReporter, "SPAM", null).statusCode()).isEqualTo(202);
        assertThat(complain(late.code(), lateReporter, "SPAM", null).statusCode()).isEqualTo(202);
        UUID lateId = complaintOf(lateReporter, late.id());
        UUID recentId = complaintOf(recentReporter, recent.id());
        // the filing time is the database row's: move the late complaint back 40 days (the guard trigger freezes columns only on UPDATE of other columns, so this
        // fixture removes it for the statement)
        jdbc.sql("ALTER TABLE app_learning.content_complaint DISABLE TRIGGER content_complaint_guard").update();
        try {
            jdbc.sql("UPDATE app_learning.content_complaint SET created_at = created_at - interval '40 days', due_at = due_at - interval '40 days' WHERE complaint_id = :id")
                    .param("id", lateId).update();
        } finally {
            jdbc.sql("ALTER TABLE app_learning.content_complaint ENABLE TRIGGER content_complaint_guard").update();
        }
        JsonNode late1 = ok(admin("GET", "/admin/complaints/" + lateId, null), 200);
        assertThat(late1.path("overdue").booleanValue(false)).isTrue();
        assertThat(ok(admin("GET", "/admin/complaints/" + recentId, null), 200).path("overdue").booleanValue(true)).isFalse();
        List<String> order = new ArrayList<>();
        ok(admin("GET", "/admin/complaints?limit=100", null), 200).path("items").forEach(item -> order.add(item.path("complaintId").stringValue(null)));
        assertThat(order.indexOf(lateId.toString())).isGreaterThanOrEqualTo(0).isLessThan(order.indexOf(recentId.toString()));
        // an overdue complaint is still open, so deciding it works as usual
        assertThat(ok(admin("POST", "/admin/complaints/" + lateId + ":decide", decision("REJECTED").toString()), 200).path("status").stringValue(null)).isEqualTo("DECIDED");
    }

    @Test
    void rejectingClosesTheComplaintOnceWithAJournalRowAndAnAuditRow() {
        Published deck = published(DeckVisibility.PUBLIC);
        UUID reporter = UUID.randomUUID();
        assertThat(complain(deck.code(), reporter, "SPAM", null).statusCode()).isEqualTo(202);
        UUID id = complaintOf(reporter, deck.id());
        JsonNode rejected = ok(admin("POST", "/admin/complaints/" + id + ":decide", decision("REJECTED").toString()), 200);
        assertThat(names(rejected)).isEqualTo(fixtureNames("complaint", "properties"));
        assertThat(rejected.path("status").stringValue(null)).isEqualTo("DECIDED");
        assertThat(rejected.path("decision").stringValue(null)).isEqualTo("REJECTED");
        assertThat(rejected.path("decidedBy").stringValue(null)).isEqualTo(CONSOLE.toString());
        assertThat(rejected.path("changed").booleanValue(false)).isTrue();
        assertThat(names(rejected.path("deck"))).isEqualTo(fixtureNames("complaint", "deckProperties"));
        assertThat(names(rejected.path("takedowns"))).isEqualTo(fixtureNames("complaint", "takedownsProperties"));
        assertThat(rejected.path("profile").isNull()).isTrue();
        assertThat(rejected.path("deck").path("hiddenByModeration").booleanValue(true)).isFalse();
        // the same decision again is the same answer without a second journal row; another decision is a 409
        JsonNode again = ok(admin("POST", "/admin/complaints/" + id + ":decide", decision("REJECTED").toString()), 200);
        assertThat(again.path("changed").booleanValue(true)).isFalse();
        assertThat(journal(id, "REJECTED")).isEqualTo(1);
        HttpResponse<String> conflict = admin("POST", "/admin/complaints/" + id + ":decide", decision("HIDDEN").toString());
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json(conflict).path("code").stringValue(null)).isEqualTo("COMPLAINT_DECIDED");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE resource_id = :id AND action = 'COMPLAINT_DECIDE' AND actor_account_id = :actor")
                .param("id", id).param("actor", CONSOLE).query(Long.class).single()).isEqualTo(1);
        // nothing changed for readers; the reporter and the author were told nothing
        assertThat(asUser("GET", "/public/decks/" + deck.code(), null, null).statusCode()).isEqualTo(200);
        assertThat(notifications(reporter, "DECK_HIDDEN") + notifications(owner, "DECK_HIDDEN") + notifications(owner, "DECK_CONTENT_TAKEN_DOWN")).isZero();
        // the DECIDED list has it now, and a fresh complaint by the same reporter is a new row
        JsonNode decided = ok(admin("GET", "/admin/complaints?status=DECIDED&limit=100", null), 200);
        List<String> decidedIds = new ArrayList<>();
        decided.path("items").forEach(item -> decidedIds.add(item.path("complaintId").stringValue(null)));
        assertThat(decidedIds).contains(id.toString());
        assertThat(complain(deck.code(), reporter, "SPAM", null).statusCode()).isEqualTo(202);
        assertThat(complaints(reporter)).isEqualTo(2);
        // wrong decisions for the target are 400, an unknown decision too
        UUID again2 = jdbc.sql("SELECT complaint_id FROM app_learning.content_complaint WHERE reporter_id = :r AND status = 'OPEN'").param("r", reporter).query(UUID.class).single();
        for (String bad : List.of("ACCOUNT_REFERRED", "NONSENSE")) {
            assertThat(admin("POST", "/admin/complaints/" + again2 + ":decide", decision(bad).toString()).statusCode()).as(bad).isEqualTo(400);
        }
        for (String body : List.of("{}", "{\"decision\":\"REJECTED\",\"units\":[]}", "{\"decision\":\"REJECTED\",\"hide\":true}", "[]")) {
            assertThat(admin("POST", "/admin/complaints/" + again2 + ":decide", body).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(jdbc.sql("SELECT status FROM app_learning.content_complaint WHERE complaint_id = :id").param("id", again2).query(String.class).single()).isEqualTo("OPEN");
    }

    @Test
    void hidingTurnsEveryPublicRouteIntoAnUnknownCodeAndLeavesTheOwnerAndTheCopies() {
        Published deck = published(DeckVisibility.PUBLIC);
        UUID reporter = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        Copy copy = shared.copy(deck.id(), UUID.randomUUID());
        assertThat(complain(deck.code(), reporter, "ILLEGAL", null).statusCode()).isEqualTo(202);
        UUID id = complaintOf(reporter, deck.id());
        String member = firstMember(deck).toString();
        List<String> routes = List.of("/public/decks/" + deck.code(), "/public/decks/" + deck.code() + "/items", "/public/decks/" + deck.code() + "/items/" + member,
                "/public/decks/" + deck.code() + "/exercises");
        for (String route : routes) assertThat(asUser("GET", route, viewer, null).statusCode()).as(route).isEqualTo(200);
        long versionBefore = jdbc.sql("SELECT row_version FROM app_learning.deck_publication WHERE deck_id = :d").param("d", deck.id()).query(Long.class).single();
        long eventsBefore = jdbc.sql("SELECT count(*) FROM app_learning.deck_publication_event WHERE deck_id = :d").param("d", deck.id()).query(Long.class).single();

        JsonNode hidden = ok(admin("POST", "/admin/complaints/" + id + ":decide", decision("HIDDEN").toString()), 200);
        assertThat(hidden.path("decision").stringValue(null)).isEqualTo("HIDDEN");
        assertThat(hidden.path("deck").path("hiddenByModeration").booleanValue(false)).isTrue();
        assertThat(hidden.path("deck").path("hiddenAt").isNull()).isFalse();

        // every public route is the answer of an unknown code, for a guest and for an account alike
        String unknown = asUser("GET", "/public/decks/AAAAAAAAAA", viewer, null).body().replaceAll("\"instance\":\"[^\"]*\"", "");
        for (String route : routes) {
            for (UUID who : new UUID[] {viewer, null}) {
                HttpResponse<String> response = asUser("GET", route, who, null);
                assertThat(response.statusCode()).as(route).isEqualTo(404);
                assertThat(json(response).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
            }
        }
        assertThat(unknown).contains("RESOURCE_NOT_FOUND");
        // a complaint, an access request and an invitation cannot find the deck either
        assertThat(complain(deck.code(), UUID.randomUUID(), "SPAM", null).statusCode()).isEqualTo(404);
        assertThat(asUser("POST", "/public/decks/" + deck.code() + "/access-requests", viewer, null).statusCode()).isEqualTo(404);
        // the owner still reads and edits: the public route answers him, his own routes work, and he gets the notice and the notification
        assertThat(asUser("GET", "/public/decks/" + deck.code(), owner, null).statusCode()).isEqualTo(200);
        assertThat(asUser("GET", "/decks/" + deck.id(), owner, null).statusCode()).isEqualTo(200);
        JsonNode notice = ok(asUser("GET", "/decks/" + deck.id() + "/moderation", owner, null), 200);
        assertThat(names(notice)).isEqualTo(fixtureNames("ownerNotice", "properties"));
        assertThat(notice.path("hiddenByModeration").booleanValue(false)).isTrue();
        assertThat(notice.path("takenDown").path("materials").intValue(-1)).isZero();
        assertThat(notifications(owner, "DECK_HIDDEN")).isGreaterThanOrEqualTo(1);
        // a copy made before is untouched and its owner sees no notice
        assertThat(asUser("GET", "/decks/" + copy.deckId() + "/items/" + member, copy.owner(), null).statusCode()).isEqualTo(200);
        assertThat(ok(asUser("GET", "/decks/" + copy.deckId() + "/moderation", copy.owner(), null), 200).path("hiddenByModeration").booleanValue(true)).isFalse();
        // the publication row moved on (the open editor gets a 412) and the catalog outbox has an event
        assertThat(jdbc.sql("SELECT row_version FROM app_learning.deck_publication WHERE deck_id = :d").param("d", deck.id()).query(Long.class).single()).isEqualTo(versionBefore + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_publication_event WHERE deck_id = :d").param("d", deck.id()).query(Long.class).single()).isEqualTo(eventsBefore + 1);
        // a stranger's notice request is the opaque 404
        assertThat(asUser("GET", "/decks/" + deck.id() + "/moderation", viewer, null).statusCode()).isEqualTo(404);

        // changing the level or the code never clears the flag
        fixtures.level(deck.deck(), DeckVisibility.LINK);
        fixtures.level(deck.deck(), DeckVisibility.PUBLIC);
        String code = jdbc.sql("SELECT public_code FROM app_learning.deck_publication WHERE deck_id = :d").param("d", deck.id()).query(String.class).single();
        assertThat(asUser("GET", "/public/decks/" + code, viewer, null).statusCode()).isEqualTo(404);

        // unhide: explicit, idempotent, journaled
        JsonNode restored = ok(admin("POST", "/admin/complaints/" + id + ":unhide", null), 200);
        assertThat(restored.path("changed").booleanValue(false)).isTrue();
        assertThat(restored.path("deck").path("hiddenByModeration").booleanValue(true)).isFalse();
        assertThat(asUser("GET", "/public/decks/" + code, viewer, null).statusCode()).isEqualTo(200);
        // the deck is not hidden now: a second unhide is a 409, and writes nothing
        HttpResponse<String> notHidden = admin("POST", "/admin/complaints/" + id + ":unhide", null);
        assertThat(notHidden.statusCode()).isEqualTo(409);
        assertThat(json(notHidden).path("code").stringValue(null)).isEqualTo("COMPLAINT_NOT_HIDDEN");
        assertThat(journal(id, "HIDDEN")).isEqualTo(1);
        assertThat(journal(id, "UNHIDDEN")).isEqualTo(1);
        // hiding again by the same complaint is the repeat of its decision: the flag flips back on and a notification would be new
        assertThat(ok(admin("POST", "/admin/complaints/" + id + ":decide", decision("HIDDEN").toString()), 200).path("changed").booleanValue(false)).isTrue();
        assertThat(asUser("GET", "/public/decks/" + code, viewer, null).statusCode()).isEqualTo(404);
    }

    @Test
    void onlyAComplaintDecidedHiddenCanUnhideAndOnlyWhileTheDeckIsHidden() {
        Published deck = published(DeckVisibility.PUBLIC);
        UUID open = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        UUID taken = UUID.randomUUID();
        UUID hiding = UUID.randomUUID();
        for (UUID reporter : List.of(open, rejected, taken, hiding)) assertThat(complain(deck.code(), reporter, "SPAM", null).statusCode()).isEqualTo(202);
        UUID openId = complaintOf(open, deck.id());
        UUID rejectedId = complaintOf(rejected, deck.id());
        UUID takenId = complaintOf(taken, deck.id());
        UUID hidingId = complaintOf(hiding, deck.id());
        ok(admin("POST", "/admin/complaints/" + rejectedId + ":decide", decision("REJECTED").toString()), 200);
        ObjectNode takeDown = decision("TAKEN_DOWN");
        takeDown.putArray("units").addObject().put("kind", "ITEM").put("key", firstMember(deck).toString()).putNull("revisionId");
        takeDown.put("hide", true);
        ok(admin("POST", "/admin/complaints/" + takenId + ":decide", takeDown.toString()), 200);
        assertThat(ok(asUser("GET", "/decks/" + deck.id() + "/moderation", owner, null), 200).path("hiddenByModeration").booleanValue(false)).isTrue();
        // an open complaint, a rejected one and one that took content down (even with hide) do not unhide: the deck stays hidden
        for (UUID wrong : List.of(openId, rejectedId, takenId)) {
            HttpResponse<String> response = admin("POST", "/admin/complaints/" + wrong + ":unhide", null);
            assertThat(response.statusCode()).as(wrong.toString()).isEqualTo(409);
            assertThat(json(response).path("code").stringValue(null)).isEqualTo("COMPLAINT_NOT_HIDDEN");
        }
        assertThat(ok(asUser("GET", "/decks/" + deck.id() + "/moderation", owner, null), 200).path("hiddenByModeration").booleanValue(false)).isTrue();
        // a complaint decided HIDDEN does, although the deck was already hidden when it was decided; then the deck is open and the next unhide is a 409
        assertThat(ok(admin("POST", "/admin/complaints/" + hidingId + ":decide", decision("HIDDEN").toString()), 200).path("changed").booleanValue(false)).isTrue();
        assertThat(ok(admin("POST", "/admin/complaints/" + hidingId + ":unhide", null), 200).path("deck").path("hiddenByModeration").booleanValue(true)).isFalse();
        assertThat(admin("POST", "/admin/complaints/" + hidingId + ":unhide", null).statusCode()).isEqualTo(409);
        assertThat(admin("POST", "/admin/complaints/" + UUID.randomUUID() + ":unhide", null).statusCode()).isEqualTo(404);
        assertThat(journal(hidingId, "UNHIDDEN")).isEqualTo(1);
        assertThat(journal(openId, "UNHIDDEN") + journal(rejectedId, "UNHIDDEN") + journal(takenId, "UNHIDDEN")).isZero();
    }

    @Test
    void aHiddenInvitationDeckCannotBeEnteredByALinkEitherButItsOwnerCan() {
        Published deck = published(DeckVisibility.INVITE);
        UUID grantee = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER);
        JsonNode invite = ok(asUser("POST", "/decks/" + deck.id() + "/invites", owner, "{\"maxUses\":5,\"expiresInDays\":7}"), 201);
        String token = invite.path("token").stringValue(null);
        assertThat(complain(deck.code(), grantee, "SPAM", null).statusCode()).isEqualTo(202);
        UUID id = complaintOf(grantee, deck.id());
        ok(admin("POST", "/admin/complaints/" + id + ":decide", decision("HIDDEN").toString()), 200);
        String redeem = JSON.createObjectNode().put("token", token).toString();
        assertThat(asUser("POST", "/invites:redeem", stranger, redeem).statusCode()).isEqualTo(404);
        assertThat(asUser("POST", "/invites:redeem", grantee, redeem).statusCode()).as("a grantee cannot read a hidden deck").isEqualTo(404);
        assertThat(asUser("GET", "/public/decks/" + deck.code(), grantee, null).statusCode()).isEqualTo(404);
        assertThat(ok(asUser("POST", "/invites:redeem", owner, redeem), 200).path("code").stringValue(null)).isEqualTo(deck.code());
        // the unaffected reporter path stays closed too
        assertThat(complain(deck.code(), reporter, "SPAM", null).statusCode()).isEqualTo(404);
        assertThat(ok(admin("POST", "/admin/complaints/" + id + ":unhide", null), 200).path("changed").booleanValue(false)).isTrue();
        assertThat(ok(asUser("POST", "/invites:redeem", stranger, redeem), 200).path("code").stringValue(null)).isEqualTo(deck.code());
    }

    @Test
    void aLegalTakedownBlocksTheNamedRevisionsInTheSourceAndEveryCopyThatStillHoldsThem() {
        Published deck = editable(DeckVisibility.PUBLIC);
        UUID reporter = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        UUID member = firstMember(deck);
        String memberText = member.toString();
        UUID itemRevision = deck.deck().material().itemRevision();
        UUID exercise = deck.deck().exercise();
        // an unchanged copy, and a copy whose learner replaced the material (a new revision)
        Copy unchanged = shared.copy(deck.id(), UUID.randomUUID());
        Copy edited = shared.copy(deck.id(), UUID.randomUUID());
        UUID editedRevision = shared.save(edited.owner(), edited.deckId(), member, itemRevision, shared.document("текст, который ученик написал сам"));
        assertThat(editedRevision).isNotEqualTo(itemRevision);
        assertThat(complain(deck.code(), reporter, "COPYRIGHT", "Материал скопирован из моей книги").statusCode()).isEqualTo(202);
        UUID id = complaintOf(reporter, deck.id());
        String publicItem = "/public/decks/" + deck.code() + "/items/" + memberText;

        // before: everybody reads
        assertThat(asUser("GET", publicItem, viewer, null).statusCode()).isEqualTo(200);
        assertThat(asUser("GET", "/decks/" + deck.id() + "/items/" + memberText, owner, null).statusCode()).isEqualTo(200);
        assertThat(asUser("GET", "/decks/" + unchanged.deckId() + "/items/" + memberText, unchanged.owner(), null).statusCode()).isEqualTo(200);

        // the picker shows the published units with takenDown false
        JsonNode picker = ok(admin("GET", "/admin/complaints/" + id + "/units?kind=ITEM", null), 200);
        assertThat(names(picker)).isEqualTo(fixtureNames("units", "properties"));
        assertThat(picker.path("total").intValue(-1)).isEqualTo(1);
        JsonNode unit = picker.path("units").get(0);
        assertThat(names(unit)).isEqualTo(fixtureNames("units", "itemUnitProperties"));
        assertThat(unit.path("key").stringValue(null)).isEqualTo(memberText);
        assertThat(unit.path("revisionId").stringValue(null)).isEqualTo(itemRevision.toString());
        assertThat(unit.path("takenDown").booleanValue(true)).isFalse();
        JsonNode exercisePicker = ok(admin("GET", "/admin/complaints/" + id + "/units?kind=EXERCISE&limit=10", null), 200);
        JsonNode exerciseUnit = exercisePicker.path("units").get(0);
        assertThat(names(exerciseUnit)).isEqualTo(fixtureNames("units", "exerciseUnitProperties"));
        assertThat(exerciseUnit.path("key").stringValue(null)).isEqualTo(exercise.toString());

        // the decision: the material by its published revision (null) and the exercise by its explicit revision
        ObjectNode body = decision("TAKEN_DOWN");
        ArrayNode units = body.putArray("units");
        units.addObject().put("kind", "ITEM").put("key", memberText).putNull("revisionId");
        // the exercise too by "the published one": both kinds are resolved in one pass each
        units.addObject().put("kind", "EXERCISE").put("key", exercise.toString()).putNull("revisionId");
        JsonNode decided = ok(admin("POST", "/admin/complaints/" + id + ":decide", body.toString()), 200);
        assertThat(names(decided)).isEqualTo(fixtureNames("complaint", "properties"));
        assertThat(decided.path("decision").stringValue(null)).isEqualTo("TAKEN_DOWN");
        assertThat(decided.path("takedowns").path("count").intValue(-1)).isEqualTo(2);
        List<String> named = new ArrayList<>();
        decided.path("takedowns").path("items").forEach(row -> named.add(row.path("unitKind").stringValue(null) + "/" + row.path("revisionId").stringValue(null)));
        assertThat(named).containsExactlyInAnyOrder("ITEM/" + itemRevision, "EXERCISE/" + exerciseUnit.path("revisionId").stringValue(null));
        assertThat(names(decided.path("takedowns").path("items").get(0))).isEqualTo(fixtureNames("complaint", "takedownProperties"));
        assertThat(decided.path("deck").path("hiddenByModeration").booleanValue(true)).as("hide was not asked").isFalse();

        // public reader: 410 on the item, the entry stays in the lists with the marker and no content
        HttpResponse<String> gone = asUser("GET", publicItem, viewer, null);
        assertThat(gone.statusCode()).isEqualTo(410);
        JsonNode problem = json(gone);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("CONTENT_UNAVAILABLE");
        assertThat(problem.path("type").stringValue(null)).isEqualTo(FIXTURE.path("readers").path("gone").path("type").stringValue(null));
        JsonNode publicItems = ok(asUser("GET", "/public/decks/" + deck.code() + "/items", viewer, null), 200);
        assertThat(publicItems.path("items").get(0).path("unavailable").booleanValue(false)).isTrue();
        assertThat(publicItems.path("items").get(0).path("title").stringValue(null)).isEmpty();
        assertThat(publicItems.path("total").intValue(-1)).isEqualTo(1);
        JsonNode publicExercises = ok(asUser("GET", "/public/decks/" + deck.code() + "/exercises", viewer, null), 200);
        assertThat(publicExercises.path("exercises").get(0).path("unavailable").booleanValue(false)).isTrue();
        assertThat(publicExercises.path("exercises").get(0).path("prompt").stringValue(null)).isEmpty();
        assertThat(publicExercises.toString()).doesNotContain("Что такое память?");

        // the source deck itself, and the unchanged copy, are blocked for the unit; the list keeps the row
        for (var holder : List.of(Map.entry(deck.id(), owner), Map.entry(unchanged.deckId(), unchanged.owner()))) {
            String base = "/decks/" + holder.getKey();
            assertThat(asUser("GET", base + "/items/" + memberText, holder.getValue(), null).statusCode()).as(base).isEqualTo(410);
            JsonNode list = ok(asUser("GET", base + "/items", holder.getValue(), null), 200);
            assertThat(list.path("items").get(0).path("unavailable").booleanValue(false)).isTrue();
            assertThat(list.path("items").get(0).path("title").stringValue(null)).isEmpty();
            assertThat(list.path("items").get(0).path("memberKey").stringValue(null)).isEqualTo(memberText);
            assertThat(asUser("GET", base + "/exercises/" + exercise, holder.getValue(), null).statusCode()).as(base + " exercise").isEqualTo(410);
            JsonNode exerciseList = ok(asUser("GET", base + "/exercises", holder.getValue(), null), 200);
            assertThat(exerciseList.path("exercises").get(0).path("unavailable").booleanValue(false)).isTrue();
            assertThat(exerciseList.toString()).doesNotContain(LibraryFixtures.OBJECTIVE_TITLE);
            JsonNode notice = ok(asUser("GET", base + "/moderation", holder.getValue(), null), 200);
            assertThat(notice.path("takenDown").path("materials").intValue(-1)).as(base).isEqualTo(1);
            assertThat(notice.path("takenDown").path("exercises").intValue(-1)).as(base).isEqualTo(1);
            assertThat(notice.path("hiddenByModeration").booleanValue(true)).isFalse();
        }
        // the copy whose learner edited the unit holds another revision: unaffected, and its notice counts only the exercise it still holds
        JsonNode editedRead = ok(asUser("GET", "/decks/" + edited.deckId() + "/items/" + memberText, edited.owner(), null), 200);
        assertThat(editedRead.path("itemRevisionId").stringValue(null)).isEqualTo(editedRevision.toString());
        JsonNode editedList = ok(asUser("GET", "/decks/" + edited.deckId() + "/items", edited.owner(), null), 200);
        assertThat(editedList.path("items").get(0).has("unavailable")).isFalse();
        assertThat(editedList.path("items").get(0).path("title").stringValue(null)).isNotEmpty();
        JsonNode editedNotice = ok(asUser("GET", "/decks/" + edited.deckId() + "/moderation", edited.owner(), null), 200);
        assertThat(editedNotice.path("takenDown").path("materials").intValue(-1)).isZero();
        assertThat(editedNotice.path("takenDown").path("exercises").intValue(-1)).isEqualTo(1);
        // a revision of the source that was not named is not blocked: the author saves a new one
        UUID fresh = replaceWithoutReading(owner, deck.id(), member, itemRevision, shared.document("исправленный автором текст"));
        assertThat(ok(asUser("GET", "/decks/" + deck.id() + "/items/" + memberText, owner, null), 200).path("itemRevisionId").stringValue(null)).isEqualTo(fresh.toString());

        // the author is told, with counts; the reporter and the owners of copies are not
        assertThat(notifications(owner, "DECK_CONTENT_TAKEN_DOWN")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT params->>'materialCount' || '/' || (params->>'exerciseCount') FROM app_learning.notification WHERE owner_id = :o AND kind = 'DECK_CONTENT_TAKEN_DOWN'")
                .param("o", owner).query(String.class).single()).isEqualTo("1/1");
        assertThat(notifications(reporter, "DECK_CONTENT_TAKEN_DOWN") + notifications(unchanged.owner(), "DECK_CONTENT_TAKEN_DOWN")).isZero();
        assertThat(notifications(owner, "DECK_HIDDEN")).isZero();

        // the picker now says takenDown; the detail lists the rows; the journal has one TAKEN_DOWN row with 2 units
        JsonNode after = ok(admin("GET", "/admin/complaints/" + id + "/units?kind=ITEM", null), 200);
        assertThat(after.path("units").get(0).path("takenDown").booleanValue(false)).isTrue();
        assertThat(journal(id, "TAKEN_DOWN")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT unit_count FROM app_learning.content_takedown_journal WHERE complaint_id = :id").param("id", id).query(Integer.class).single()).isEqualTo(2);
        JsonNode journalPage = ok(admin("GET", "/admin/complaints/journal?limit=100", null), 200);
        boolean found = false;
        for (JsonNode entry : journalPage.path("items")) {
            assertThat(names(entry)).isEqualTo(fixtureNames("journal", "itemProperties"));
            if (entry.path("complaintId").stringValue(null).equals(id.toString())) {
                found = true;
                assertThat(entry.path("action").stringValue(null)).isEqualTo("TAKEN_DOWN");
                assertThat(entry.path("unitCount").intValue(-1)).isEqualTo(2);
                assertThat(entry.path("actorId").stringValue(null)).isEqualTo(CONSOLE.toString());
            }
        }
        assertThat(found).isTrue();

        // the same decision again adds nothing; another decision is 409; more units add rows
        assertThat(ok(admin("POST", "/admin/complaints/" + id + ":decide", body.toString()), 200).path("changed").booleanValue(true)).isFalse();
        assertThat(journal(id, "TAKEN_DOWN")).isEqualTo(1);
        assertThat(admin("POST", "/admin/complaints/" + id + ":decide", decision("HIDDEN").toString()).statusCode()).isEqualTo(409);
        ObjectNode more = decision("TAKEN_DOWN");
        more.putArray("units").addObject().put("kind", "ITEM").put("key", memberText).put("revisionId", fresh.toString());
        more.put("hide", true);
        JsonNode added = ok(admin("POST", "/admin/complaints/" + id + ":decide", more.toString()), 200);
        assertThat(added.path("changed").booleanValue(false)).isTrue();
        assertThat(added.path("takedowns").path("count").intValue(-1)).isEqualTo(3);
        assertThat(added.path("deck").path("hiddenByModeration").booleanValue(false)).isTrue();
        assertThat(asUser("GET", "/decks/" + deck.id() + "/items/" + memberText, owner, null).statusCode()).isEqualTo(410);
        assertThat(asUser("GET", "/public/decks/" + deck.code(), viewer, null).statusCode()).isEqualTo(404);
        assertThat(notifications(owner, "DECK_HIDDEN")).isEqualTo(1);
        assertThat(journal(id, "TAKEN_DOWN")).isEqualTo(2);
        assertThat(journal(id, "HIDDEN")).isEqualTo(1);
        // takedown rows are immutable
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.sql("DELETE FROM app_learning.content_takedown WHERE complaint_id = :id").param("id", id).update()))
                .hasMessageContaining("immutable");
    }

    @Test
    void aTakedownNamesOnlyRevisionsOfTheDecksOwnLineageAndOnlyDecksCanBeTakenDown() {
        Published deck = published(DeckVisibility.PUBLIC);
        Published other = published(DeckVisibility.PUBLIC);
        UUID reporter = UUID.randomUUID();
        assertThat(complain(deck.code(), reporter, "ILLEGAL", null).statusCode()).isEqualTo(202);
        UUID id = complaintOf(reporter, deck.id());
        String path = "/admin/complaints/" + id + ":decide";
        UUID member = firstMember(deck);
        ObjectNode foreign = decision("TAKEN_DOWN");
        foreign.putArray("units").addObject().put("kind", "ITEM").put("key", other.deck().material().member().toString())
                .put("revisionId", other.deck().material().itemRevision().toString());
        ObjectNode unknownRevision = decision("TAKEN_DOWN");
        unknownRevision.putArray("units").addObject().put("kind", "ITEM").put("key", member.toString()).put("revisionId", UUID.randomUUID().toString());
        ObjectNode unknownKey = decision("TAKEN_DOWN");
        unknownKey.putArray("units").addObject().put("kind", "ITEM").put("key", UUID.randomUUID().toString()).putNull("revisionId");
        ObjectNode wrongKind = decision("TAKEN_DOWN");
        wrongKind.putArray("units").addObject().put("kind", "EXERCISE").put("key", member.toString()).put("revisionId", deck.deck().material().itemRevision().toString());
        ObjectNode empty = decision("TAKEN_DOWN");
        empty.putArray("units");
        ObjectNode noUnits = decision("TAKEN_DOWN");
        ObjectNode strange = decision("TAKEN_DOWN");
        strange.putArray("units").addObject().put("kind", "DECK").put("key", member.toString());
        ObjectNode extra = decision("TAKEN_DOWN");
        extra.putArray("units").addObject().put("kind", "ITEM").put("key", member.toString()).put("note", "x");
        for (ObjectNode bad : List.of(foreign, unknownRevision, unknownKey, wrongKind, empty, noUnits, strange, extra)) {
            assertThat(admin("POST", path, bad.toString()).statusCode()).as(bad.toString()).isEqualTo(400);
        }
        assertThat(jdbc.sql("SELECT status FROM app_learning.content_complaint WHERE complaint_id = :id").param("id", id).query(String.class).single()).isEqualTo("OPEN");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_takedown WHERE complaint_id = :id").param("id", id).query(Long.class).single()).isZero();
        // a PROFILE complaint takes neither HIDDEN nor TAKEN_DOWN and the picker is for decks
        UUID target = UUID.randomUUID();
        String login = "picker-" + UUID.randomUUID().toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(login, target);
        assertThat(asUser("POST", "/public/profiles/" + login + "/complaints", reporter, "{\"reason\":\"SPAM\"}").statusCode()).isEqualTo(202);
        UUID profile = jdbc.sql("SELECT complaint_id FROM app_learning.content_complaint WHERE profile_account_id = :a").param("a", target).query(UUID.class).single();
        for (String bad : List.of("HIDDEN", "TAKEN_DOWN")) {
            assertThat(admin("POST", "/admin/complaints/" + profile + ":decide", decision(bad).toString()).statusCode()).as(bad).isEqualTo(400);
        }
        assertThat(admin("GET", "/admin/complaints/" + profile + "/units?kind=ITEM", null).statusCode()).isEqualTo(400);
        assertThat(admin("POST", "/admin/complaints/" + profile + ":unhide", null).statusCode()).isEqualTo(400);
        JsonNode detail = ok(admin("GET", "/admin/complaints/" + profile, null), 200);
        assertThat(detail.path("deck").isNull()).isTrue();
        assertThat(names(detail.path("profile"))).isEqualTo(fixtureNames("complaint", "profileProperties"));
        // the account is referred: nothing else changes
        JsonNode referred = ok(admin("POST", "/admin/complaints/" + profile + ":decide", decision("ACCOUNT_REFERRED").toString()), 200);
        assertThat(referred.path("decision").stringValue(null)).isEqualTo("ACCOUNT_REFERRED");
        assertThat(journal(profile, "ACCOUNT_REFERRED")).isEqualTo(1);
        // units of a deck with nothing published and the invalid kind
        assertThat(admin("GET", "/admin/complaints/" + id + "/units?kind=NOPE", null).statusCode()).isEqualTo(400);
        assertThat(admin("GET", "/admin/complaints/" + id + "/units", null).statusCode()).isEqualTo(400);
        ObjectNode defaults = decision("TAKEN_DOWN");
        defaults.putArray("units").addObject().put("kind", "ITEM").put("key", member.toString()).putNull("revisionId");
        JsonNode good = ok(admin("POST", path, defaults.toString()), 200);
        assertThat(good.path("takedowns").path("items").get(0).path("revisionId").stringValue(null)).isEqualTo(deck.deck().material().itemRevision().toString());
    }

    @Test
    void theJournalAndTheComplaintsOfADeletedDeckOutliveIt() {
        Published deck = published(DeckVisibility.PUBLIC);
        UUID reporter = UUID.randomUUID();
        assertThat(complain(deck.code(), reporter, "SPAM", null).statusCode()).isEqualTo(202);
        UUID id = complaintOf(reporter, deck.id());
        jdbc.sql("UPDATE app_learning.deck SET deleted_at = clock_timestamp() WHERE deck_id = :d").param("d", deck.id()).update();
        JsonNode detail = ok(admin("GET", "/admin/complaints/" + id, null), 200);
        assertThat(detail.path("deck").path("live").booleanValue(true)).isFalse();
        assertThat(detail.path("deck").path("publicCode").stringValue(null)).isEqualTo(deck.code());
        // a rejected decision still works on the snapshot
        assertThat(ok(admin("POST", "/admin/complaints/" + id + ":decide", decision("REJECTED").toString()), 200).path("status").stringValue(null)).isEqualTo("DECIDED");
        // history cannot be rewritten or erased before its retention ends
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.sql("DELETE FROM app_learning.content_complaint WHERE complaint_id = :id").param("id", id).update()))
                .hasMessageContaining("retention");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.sql("UPDATE app_learning.content_complaint SET decision = 'HIDDEN' WHERE complaint_id = :id").param("id", id).update()))
                .hasMessageContaining("Invalid complaint transition");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.sql("UPDATE app_learning.content_takedown_journal SET action = 'HIDDEN' WHERE complaint_id = :id").param("id", id).update()))
                .hasMessageContaining("append-only");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.sql("TRUNCATE app_learning.content_takedown_journal").update())).hasMessageContaining("append-only");
    }
}
