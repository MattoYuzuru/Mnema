package app.mnema.learning.library;

import app.mnema.learning.catalog.content.ItemPreviews;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;

/** The bulkhead, the one-statement title batch, the read-only transaction and the title cache written outside it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicDeckReadPathHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @MockitoSpyBean private PublishedContentRepository content;
    @MockitoSpyBean private ItemPreviews previews;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.coarse-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.overflow-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.max-concurrent", () -> 2);
    }

    private HttpResponse<String> get(String path) {
        return HttpIdentityFixture.send(port, "GET", path, null, null, Map.of());
    }

    private String publish(int materials) {
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        Deck deck = fixtures.deck(UUID.randomUUID(), "Путь чтения");
        for (int index = 1; index < materials; index++) fixtures.study().addMaterial(deck.owner(), deck.id(), "m" + index, "x");
        return fixtures.publishAt(deck, DeckVisibility.PUBLIC);
    }

    @Test
    void theBulkheadRefusesTheExcessReadAtOnceWithA503AndReleasesItsPermit() throws Exception {
        String code = publish(1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            inside.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            return invocation.callRealMethod();
        }).when(content).itemRevisions(any(), anyCollection(), anyCollection());
        try {
            CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> get("/public/decks/" + code + "/items"));
            assertThat(inside.await(20, TimeUnit.SECONDS)).isTrue();

            // every public route is refused while the single permit is held, before any lookup (the unknown code proves it: not a 404)
            for (String path : List.of("/public/decks/" + code, "/public/decks/" + code + "/exercises", "/public/decks/AAAAAAAAAA/items",
                    "/public/decks/" + code + "/items/" + UUID.randomUUID())) {
                HttpResponse<String> busy = get(path);
                assertThat(busy.statusCode()).as(path + " " + busy.body()).isEqualTo(503);
                assertThat(busy.headers().firstValue("retry-after")).contains("1");
                assertThat(busy.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
                assertThat(busy.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
                JsonNode body = JSON.readTree(busy.body());
                assertThat(body.path("code").stringValue(null)).isEqualTo("PUBLIC_READ_BUSY");
                assertThat(body.path("retryAfter").longValue()).isEqualTo(1);
                assertThat(busy.body()).doesNotContain("Путь чтения");
            }
            // guests hold at most max-concurrent - 1 places: the last one is still free for a signed-in viewer
            HttpResponse<String> account = HttpIdentityFixture.send(port, "GET", "/public/decks/" + code, HttpIdentityFixture.reader(UUID.randomUUID()), null, Map.of());
            assertThat(account.statusCode()).as(account.body()).isEqualTo(200);
            // the owner's private API is not behind the public bulkhead
            assertThat(HttpIdentityFixture.send(port, "GET", "/decks", HttpIdentityFixture.reader(UUID.randomUUID()), null, Map.of()).statusCode()).isEqualTo(200);

            release.countDown();
            assertThat(slow.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            release.countDown();
            Mockito.reset(content);
        }
        // the permit came back, also after a refusal inside the bulkhead (404) and a failure (500)
        assertThat(get("/public/decks/" + code).statusCode()).isEqualTo(200);
        assertThat(get("/public/decks/AAAAAAAAAA").statusCode()).isEqualTo(404);
        Mockito.doThrow(new IllegalStateException("boom")).when(content).itemRevisions(any(), anyCollection(), anyCollection());
        try {
            assertThat(get("/public/decks/" + code + "/items").statusCode()).isEqualTo(500);
        } finally {
            Mockito.reset(content);
        }
        assertThat(get("/public/decks/" + code + "/items").statusCode()).isEqualTo(200);
    }

    @Test
    void theTitleCacheWriteHoldsThePermitSoItIsBoundedByTheBulkheadToo() throws Exception {
        String code = publish(3);
        CountDownLatch inStore = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            inStore.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            return invocation.callRealMethod();
        }).when(content).storeTitles(any(), anyList());
        try {
            CompletableFuture<HttpResponse<String>> writing = CompletableFuture.supplyAsync(() -> get("/public/decks/" + code + "/items"));
            assertThat(inStore.await(20, TimeUnit.SECONDS)).isTrue();
            // the page is read, the cache is being written, and the permit is still held: a second guest is refused
            HttpResponse<String> busy = get("/public/decks/" + code);
            assertThat(busy.statusCode()).as(busy.body()).isEqualTo(503);
            assertThat(JSON.readTree(busy.body()).path("code").stringValue(null)).isEqualTo("PUBLIC_READ_BUSY");
            release.countDown();
            HttpResponse<String> page = writing.get(20, TimeUnit.SECONDS);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(page.body()).path("items")).hasSize(3);
        } finally {
            release.countDown();
            Mockito.reset(content);
        }
        assertThat(get("/public/decks/" + code).statusCode()).isEqualTo(200);
    }

    @Test
    void thePageTitlesComeFromOneStatementAndAreCachedAfterTheReadOutsideItsTransaction() throws Exception {
        String code = publish(6);
        List<Boolean> readOnlyAtCacheRead = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> readOnlyAtStore = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean storeInsideReadTransaction = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            readOnlyAtCacheRead.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(content).cachedTitles(any(), anyCollection(), anyCollection());
        Mockito.doAnswer(invocation -> {
            readOnlyAtStore.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) storeInsideReadTransaction.set(true);
            return invocation.callRealMethod();
        }).when(content).storeTitles(any(), anyList());
        try {
            JsonNode first = JSON.readTree(get("/public/decks/" + code + "/items?limit=100").body());
            assertThat(first.path("items")).hasSize(6);
            // one cache statement for the whole page, no per-item title read, one derivation per miss, one batch of stores
            Mockito.verify(content, Mockito.times(1)).cachedTitles(any(), anyCollection(), anyCollection());
            Mockito.verify(previews, Mockito.never()).title(any(), any(), any());
            Mockito.verify(previews, Mockito.atMost(6)).derive(any(), any());
            Mockito.verify(content, Mockito.times(1)).storeTitles(any(), anyList());
            assertThat(readOnlyAtCacheRead).containsExactly(true);
            assertThat(readOnlyAtStore).containsExactly(false);
            assertThat(storeInsideReadTransaction).isFalse();

            // the second read finds every title in the cache: nothing is derived and nothing is stored
            Mockito.clearInvocations(content, previews);
            JsonNode second = JSON.readTree(get("/public/decks/" + code + "/items?limit=100").body());
            assertThat(second).isEqualTo(first);
            Mockito.verify(content, Mockito.times(1)).cachedTitles(any(), anyCollection(), anyCollection());
            Mockito.verify(previews, Mockito.never()).derive(any(), any());
            Mockito.verify(content, Mockito.never()).storeTitles(any(), anyList());
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning.item_preview p JOIN app_learning.item_revision r ON r.reuse_scope_id = p.reuse_scope_id "
                    + "AND r.member_key = p.member_key AND r.revision_id = p.revision_id JOIN app_learning.deck_publication d ON d.public_code = :code AND d.deck_id = r.deck_id")
                    .param("code", code).query(Long.class).single()).isEqualTo(6);
        } finally {
            Mockito.reset(content, previews);
        }
    }

    @Test
    void aFailedCacheWriteDoesNotFailAPageThatWasRead() throws Exception {
        String code = publish(2);
        Mockito.doThrow(new IllegalStateException("cache is down")).when(content).storeTitles(any(), anyList());
        try {
            HttpResponse<String> response = get("/public/decks/" + code + "/items");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).path("items")).hasSize(2);
        } finally {
            Mockito.reset(content);
        }
        assertThat(JSON.readTree(get("/public/decks/" + code + "/items").body()).path("items")).hasSize(2);
    }
}
