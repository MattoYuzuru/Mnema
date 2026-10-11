package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/14 on the real stack: the card of a public deck and the one generic card of everything else (byte for byte, the answer is not an oracle), the author
 * only when Identity has the card and never at the price of the preview, hostile and oversized texts, the slug redirect hint, caching and the profile card.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicMetaHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGIN = "https://mnema.app";

    @LocalServerPort private int port;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private MeterRegistry meters;
    private LibraryFixtures fixtures;
    private final UUID owner = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        registry.add("learning.identity.author-card-timeout", () -> "300ms");
    }

    @BeforeEach
    void fixtures() {
        fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        HttpIdentityFixture.authorCardFailure(0, 0);
    }

    @AfterEach
    void reset() { HttpIdentityFixture.authorCardFailure(0, 0); }

    private HttpResponse<String> get(String path) { return HttpIdentityFixture.send(port, "GET", path, null, null, Map.of()); }

    private Deck bare(String title, String description) {
        UUID id = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), title, description)).acknowledgement().path("deck").path("deckId").stringValue(null));
        return new Deck(owner, id, null, null);
    }

    private static JsonNode json(HttpResponse<String> response) {
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    @Test
    void aPublicDeckGivesItsTitleDescriptionCanonicalAddressAndAuthor() {
        HttpIdentityFixture.authorCard(owner, "anna.k", "Анна", "Люблю языки");
        Deck deck = fixtures.deck(owner, "Испанские глаголы");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);

        HttpResponse<String> response = get("/public/meta/decks/" + code);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        assertThat(response.headers().firstValue("cache-control")).hasValue("public, max-age=300");
        JsonNode body = json(response);
        assertThat(List.copyOf(body.propertyNames())).containsExactly("kind", "title", "description", "canonical", "robots", "image", "author");
        assertThat(body.path("kind").stringValue(null)).isEqualTo("DECK");
        assertThat(body.path("title").stringValue(null)).isEqualTo("Испанские глаголы");
        assertThat(body.path("description").stringValue(null)).isEqualTo("Описание Испанские глаголы");
        assertThat(body.path("canonical").stringValue(null)).isEqualTo(ORIGIN + "/d/" + code + "/ispanskie-glagoly");
        assertThat(body.path("robots").stringValue(null)).isEqualTo("index,follow");
        assertThat(body.path("image").stringValue(null)).isEqualTo(ORIGIN + "/assets/og-image.png");
        assertThat(body.path("author").path("username").stringValue(null)).isEqualTo("anna.k");
        assertThat(body.path("author").path("displayName").stringValue(null)).isEqualTo("Анна");
        // the card is a few fields: no bio, no owner id, no counts, no code beyond the canonical address
        assertThat(response.body()).doesNotContain("Люблю языки", owner.toString());

        HttpResponse<String> head = get("/public/meta/decks/" + code + "/head");
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("content-type")).hasValue("text/plain;charset=UTF-8");
        // reachable on the application origin through /api: shown as text, never run
        assertThat(head.headers().firstValue("content-security-policy")).hasValue("default-src 'none'; sandbox");
        assertThat(head.headers().firstValue("x-mnema-preview")).hasValue("card");
        assertThat(head.headers().firstValue("x-mnema-canonical")).hasValue("/d/" + code + "/ispanskie-glagoly");
        assertThat(head.headers().firstValue("cache-control")).hasValue("public, max-age=300");
        assertThat(head.headers().firstValue("x-robots-tag")).isEmpty();
        assertThat(head.body()).contains("<title>Испанские глаголы — Mnema</title>", "<link rel=\"canonical\" href=\"" + ORIGIN + "/d/" + code + "/ispanskie-glagoly\">",
                "<meta name=\"robots\" content=\"index,follow\">", "Автор: Анна (@anna.k). Описание Испанские глаголы", "<meta name=\"author\" content=\"Анна (@anna.k)\">");
        assertThat(meters.find("mnema_public_deck_requests_total").tags("route", "meta", "outcome", "ok").counter().count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void everythingThatIsNotPublicGivesOneGenericCardIdenticalByte() {
        HttpIdentityFixture.authorCard(owner, "anna.k", "Анна", null);
        Deck link = fixtures.deck(owner, "Секретная ссылка");
        Deck invite = fixtures.deck(owner, "Секретное приглашение");
        Deck privateAgain = fixtures.deck(owner, "Секретная приватная");
        Deck noRevision = fixtures.deck(owner, "Секретная без публикации");
        Deck deleted = fixtures.deck(owner, "Секретная удалённая");
        Deck rotated = fixtures.deck(owner, "Секретная старая ссылка");
        String linkCode = fixtures.publishAt(link, DeckVisibility.LINK);
        String inviteCode = fixtures.publishAt(invite, DeckVisibility.INVITE);
        fixtures.publishAt(privateAgain, DeckVisibility.PUBLIC);
        String privateCode = fixtures.level(privateAgain, DeckVisibility.PRIVATE);
        String noRevisionCode = fixtures.level(noRevision, DeckVisibility.PUBLIC);
        String deletedCode = fixtures.publishAt(deleted, DeckVisibility.PUBLIC);
        decks.delete(owner, deleted.id(), Long.parseLong(decks.read(owner, deleted.id()).path("rowVersion").stringValue(null)));
        String rotatedOld = fixtures.publishAt(rotated, DeckVisibility.PUBLIC);
        fixtures.level(rotated, DeckVisibility.PRIVATE);
        String unknown = PublicCodes.next(new java.security.SecureRandom());

        List<String> codes = List.of(linkCode, inviteCode, privateCode, noRevisionCode, deletedCode, rotatedOld, unknown, "short", "0OIl0OIl0O", "AAAAAAAAAAAAAAAAAAAAAAAA");
        String firstJson = null;
        String firstHead = null;
        for (String code : codes) {
            HttpResponse<String> response = get("/public/meta/decks/" + code);
            HttpResponse<String> head = get("/public/meta/decks/" + code + "/head");
            assertThat(response.statusCode()).as(code).isEqualTo(200);
            assertThat(head.statusCode()).as(code).isEqualTo(200);
            assertThat(head.headers().firstValue("x-mnema-canonical")).as(code).isEmpty();
            assertThat(head.headers().firstValue("x-mnema-preview")).as(code).hasValue("generic");
            assertThat(head.headers().firstValue("content-security-policy")).as(code).hasValue("default-src 'none'; sandbox");
            assertThat(head.headers().firstValue("x-robots-tag")).as(code).hasValue("noindex");
            assertThat(response.headers().firstValue("cache-control")).as(code).hasValue("public, max-age=300");
            assertThat(response.body()).as(code).doesNotContain("Секретн", "anna.k", "Анна");
            if (firstJson == null) {
                firstJson = response.body();
                firstHead = head.body();
            }
            assertThat(response.body()).as(code).isEqualTo(firstJson);
            assertThat(head.body()).as(code).isEqualTo(firstHead);
        }
        JsonNode generic = json(get("/public/meta/decks/" + unknown));
        assertThat(generic.path("kind").stringValue(null)).isEqualTo("GENERIC");
        assertThat(generic.path("robots").stringValue(null)).isEqualTo("noindex");
        assertThat(generic.path("canonical").isNull()).isTrue();
        assertThat(generic.path("author").isNull()).isTrue();
        assertThat(generic.path("title").stringValue(null)).isEqualTo("Mnema — учиться, понимать, помнить");
        assertThat(firstHead).contains("<meta name=\"robots\" content=\"noindex\">").doesNotContain("canonical", "og:url", "/d/");
        // a signed-in owner gets the guest's card too: the answer never depends on a token
        HttpResponse<String> asOwner = HttpIdentityFixture.send(port, "GET", "/public/meta/decks/" + linkCode, HttpIdentityFixture.reader(owner), null, Map.of());
        assertThat(asOwner.body()).isEqualTo(firstJson);
        assertThat(meters.find("mnema_public_deck_requests_total").tags("route", "meta", "outcome", "generic").counter().count()).isGreaterThanOrEqualTo(codes.size());
    }

    @Test
    void aHostileTitleAndDescriptionArePlainTextInTheJsonAndEscapedInTheFragment() {
        String hostile = "\"><script>alert(1)</script><!--# include virtual=\"/x\" -->";
        Deck deck = bare(hostile, "<img src=x onerror=alert(1)> & 'x' \"y\"");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);

        JsonNode body = json(get("/public/meta/decks/" + code));
        assertThat(body.path("title").stringValue(null)).isEqualTo(hostile);
        assertThat(body.path("canonical").stringValue(null)).isEqualTo(ORIGIN + "/d/" + code + "/script-alert-1-script-include-virtual-x");

        String head = get("/public/meta/decks/" + code + "/head").body();
        assertThat(head).doesNotContain("<script", "<!--", "<img", "\"><", "onerror=alert(1)>");
        assertThat(head).contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;&lt;!--# include virtual=&quot;/x&quot; --&gt;");
        for (String line : head.split("\n")) {
            assertThat(line).matches("<title>[^<>\"]*</title>|<meta (name|property)=\"[a-z:_]+\" content=\"[^<>\"]*\">|<link rel=\"canonical\" href=\"[^<>\"]*\">");
        }
    }

    @Test
    void longTextsAreCutOnAWordBoundaryAndBlankOnesFallBack() {
        Deck longDeck = bare("Очень длинное название ".repeat(8).strip(), "слово ".repeat(300).strip());
        JsonNode body = json(get("/public/meta/decks/" + fixtures.publishAt(longDeck, DeckVisibility.PUBLIC)));
        assertThat(body.path("title").stringValue(null)).hasSizeLessThanOrEqualTo(121).endsWith("…").doesNotContain("Оче…");
        assertThat(body.path("description").stringValue(null)).hasSizeLessThanOrEqualTo(201).endsWith("слово…").doesNotContain("\n");

        Deck blank = bare("Только название", "");
        JsonNode fallback = json(get("/public/meta/decks/" + fixtures.publishAt(blank, DeckVisibility.PUBLIC)));
        assertThat(fallback.path("kind").stringValue(null)).isEqualTo("DECK");
        assertThat(fallback.path("title").stringValue(null)).isEqualTo("Только название");
        assertThat(fallback.path("description").stringValue(null)).isEqualTo("Колода на Mnema.");
    }

    @Test
    void bidirectionalControlsOfATitleAndOfAnAuthorNeverReachTheCard() {
        UUID bidiOwner = UUID.randomUUID();
        HttpIdentityFixture.authorCard(bidiOwner, "bidi.one", "Автор\u202E", null);
        UUID id = UUID.fromString(decks.create(bidiOwner, new DeckCommand(UUID.randomUUID(), "Название\u202E\u2066 колоды", "Описание\u200F")).acknowledgement()
                .path("deck").path("deckId").stringValue(null));
        String code = fixtures.publishAt(new Deck(bidiOwner, id, null, null), DeckVisibility.PUBLIC);
        JsonNode body = json(get("/public/meta/decks/" + code));
        assertThat(body.path("title").stringValue(null)).isEqualTo("Название колоды");
        assertThat(body.path("description").stringValue(null)).isEqualTo("Описание");
        assertThat(body.path("author").path("displayName").stringValue(null)).isEqualTo("Автор");
        assertThat(get("/public/meta/decks/" + code + "/head").body()).doesNotContain("\u202E", "\u2066", "\u200F");
    }

    @Test
    void theAuthorIsPresentOnlyWithACardAndNeverBlocksThePreview() {
        Deck deck = bare("Без автора", "Описание");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        // no card (no consent, no login): no author
        JsonNode none = json(get("/public/meta/decks/" + code));
        assertThat(none.path("author").isNull()).isTrue();
        assertThat(get("/public/meta/decks/" + code + "/head").body()).doesNotContain("name=\"author\"", "Автор:");

        // a card without a display name shows the login only
                UUID otherOwner = UUID.randomUUID();
        HttpIdentityFixture.authorCard(otherOwner, "bob_b", null, null);
        UUID id = UUID.fromString(decks.create(otherOwner, new DeckCommand(UUID.randomUUID(), "Чужая", "Описание")).acknowledgement().path("deck").path("deckId").stringValue(null));
        String otherCode = fixtures.publishAt(new Deck(otherOwner, id, null, null), DeckVisibility.PUBLIC);
        JsonNode login = json(get("/public/meta/decks/" + otherCode));
        assertThat(login.path("author").path("username").stringValue(null)).isEqualTo("bob_b");
        assertThat(login.path("author").path("displayName").isNull()).isTrue();
        assertThat(get("/public/meta/decks/" + otherCode + "/head").body()).contains("Автор: @bob_b. Описание");

        // Identity failing or too slow: the deck card is complete, minus the author
        UUID unlucky = UUID.randomUUID();
        HttpIdentityFixture.authorCard(unlucky, "late.one", "Опоздавший", null);
        UUID lateDeck = UUID.fromString(decks.create(unlucky, new DeckCommand(UUID.randomUUID(), "Поздняя", "Описание")).acknowledgement().path("deck").path("deckId").stringValue(null));
        String lateCode = fixtures.publishAt(new Deck(unlucky, lateDeck, null, null), DeckVisibility.PUBLIC);
        for (int[] failure : new int[][] {{503, 0}, {200, 1500}}) {
            HttpIdentityFixture.authorCardFailure(failure[0], failure[1]);
            long started = System.nanoTime();
            HttpResponse<String> response = get("/public/meta/decks/" + lateCode);
            assertThat(response.statusCode()).as(failure[0] + "/" + failure[1]).isEqualTo(200);
            assertThat(json(response).path("kind").stringValue(null)).isEqualTo("DECK");
            assertThat(json(response).path("author").isNull()).isTrue();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_200);
        }
        HttpIdentityFixture.authorCardFailure(0, 0);
    }

    @Test
    void aPublicDeckAnswersItsCanonicalPathForTheWebServerToCompareAndNothingElseDoes() {
        Deck deck = fixtures.deck(owner, "Испанские глаголы");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        // one answer for every requested address: the web server compares the path it was asked for with this one and redirects on a difference
        String canonical = "/d/" + code + "/ispanskie-glagoly";
        assertThat(get("/public/meta/decks/" + code + "/head").headers().firstValue("x-mnema-canonical")).hasValue(canonical);
        // a query parameter changes nothing: there is no per-slug answer, so a cache in front can key it by the code alone
        assertThat(get("/public/meta/decks/" + code + "/head?slug=wrong").headers().firstValue("x-mnema-canonical")).hasValue(canonical);
        assertThat(get("/public/meta/decks/" + code + "/head?slug=wrong").body()).isEqualTo(get("/public/meta/decks/" + code + "/head").body());
        // HEAD is the cheap form of the same question: the headers, no body
        HttpResponse<String> head = HttpIdentityFixture.send(port, "HEAD", "/public/meta/decks/" + code + "/head", null, null, Map.of());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("x-mnema-canonical")).hasValue(canonical);
        assertThat(head.body()).isEmpty();

        Deck link = fixtures.deck(owner, "Ссылка");
        String linkCode = fixtures.publishAt(link, DeckVisibility.LINK);
        assertThat(get("/public/meta/decks/" + linkCode + "/head").headers().firstValue("x-mnema-canonical")).isEmpty();
        assertThat(get("/public/meta/decks/" + linkCode + "/head").headers().firstValue("x-robots-tag")).hasValue("noindex");
    }

    @Test
    void aProfileHasACardOnlyWhereIdentityPublishesOne() {
        HttpIdentityFixture.authorCard(UUID.randomUUID(), "profile.anna", "Анна", "Люблю языки <b>и</b> книги");
        HttpResponse<String> response = get("/public/meta/profiles/Profile.Anna");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).hasValue("public, max-age=300");
        JsonNode body = json(response);
        assertThat(body.path("kind").stringValue(null)).isEqualTo("PROFILE");
        assertThat(body.path("title").stringValue(null)).isEqualTo("Анна");
        assertThat(body.path("description").stringValue(null)).isEqualTo("Люблю языки <b>и</b> книги");
        assertThat(body.path("canonical").stringValue(null)).isEqualTo(ORIGIN + "/u/profile.anna");
        assertThat(body.path("robots").stringValue(null)).isEqualTo("index,follow");
        assertThat(get("/public/meta/profiles/Profile.Anna/head").body()).contains("<title>Анна — Mnema</title>", "&lt;b&gt;и&lt;/b&gt;").doesNotContain("<b>");

        String genericJson = get("/public/meta/decks/" + PublicCodes.next(new java.security.SecureRandom())).body();
        for (String login : List.of("nobody.here", "ab", "bad%20name")) {
            HttpResponse<String> generic = get("/public/meta/profiles/" + login);
            assertThat(generic.statusCode()).as(login).isEqualTo(200);
            assertThat(generic.body()).as(login).isEqualTo(genericJson);
        }
        // Identity down: the generic card, not an error
        HttpIdentityFixture.authorCardFailure(503, 0);
        assertThat(get("/public/meta/profiles/other.name").body()).isEqualTo(genericJson);
    }

    @Test
    void onlyReadsAreServedAndNothingElseIsAnOffer() {
        Deck deck = bare("Чтение", "Описание");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            int status = HttpIdentityFixture.send(port, method, "/public/meta/decks/" + code, null, "{}", Map.of()).statusCode();
            assertThat(status).as(method).isIn(401, 403, 405);
        }
        // an invalid bearer is refused by the chain like on every public route, never silently a guest
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/meta/decks/" + code, "garbage", null, Map.of()).statusCode()).isEqualTo(401);
    }
}
