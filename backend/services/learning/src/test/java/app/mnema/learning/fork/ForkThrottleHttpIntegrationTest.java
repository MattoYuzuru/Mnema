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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static app.mnema.learning.fork.ForkWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-account sliding window on copy creations ({@code learning.community.forks.max-per-window} per {@code window}), here 4 per hour: counted from
 * {@code deck_fork.forked_at} INCLUDING released rows, 429 {@code RATE_LIMITED} with the real {@code Retry-After}, an existing copy still answered, other accounts unaffected,
 * and no overshoot under parallel requests for different sources (the account's advisory lock). No executor runs: the copies stay preparing, so the cap of unfinished copies is
 * raised out of the way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.community.forks.max-per-window=4", "learning.community.forks.max-preparing=100"})
class ForkThrottleHttpIntegrationTest extends PostgresIntegrationTest {
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
    private ForkWorlds worlds;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
    }

    @BeforeEach
    void worlds() {
        worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private List<Source> sources(int count) {
        List<Source> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(worlds.source("LINK", 1, 0));
        return result;
    }

    @Test
    void theFifthCreationInTheWindowIsThrottledDeletedCopiesCountAndTheRetryAfterIsTheRealRemainder() {
        UUID account = UUID.randomUUID();
        List<Source> sources = sources(6);
        List<UUID> copies = new ArrayList<>();
        for (int i = 0; i < 4; i++) copies.add(worlds.forked(account, sources.get(i).code()));

        HttpResponse<String> throttled = worlds.fork(account, sources.get(4).code());
        assertThat(throttled.statusCode()).as(throttled.body()).isEqualTo(429);
        JsonNode problem = json(throttled);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("RATE_LIMITED");
        long header = Long.parseLong(throttled.headers().firstValue("retry-after").orElseThrow());
        assertThat(header).isBetween(3_500L, 3_600L);
        assertThat(problem.path("retryAfter").longValue()).isEqualTo(header);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :o").param("o", account).query(Long.class).single()).isEqualTo(4);

        // an existing copy is still answered (no new work), and other accounts are not limited by this one
        assertThat(worlds.fork(account, sources.get(0).code()).statusCode()).isEqualTo(200);
        assertThat(worlds.fork(UUID.randomUUID(), sources.get(4).code()).statusCode()).isEqualTo(201);

        // deleting a copy gives nothing back: the window counts what was created
        assertThat(worlds.send("DELETE", "/decks/" + copies.getFirst(), account, null, "\"0\"").statusCode()).isEqualTo(204);
        assertThat(worlds.fork(account, sources.get(4).code()).statusCode()).isEqualTo(429);

        // the oldest creation leaves the window: the allowance is back, one at a time
        long oldestAgo = 2 * 3_600;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET forked_at = forked_at - (:s * interval '1 second') WHERE deck_id = :d")
                    .param("s", oldestAgo).param("d", copies.getFirst()).update();
        });
        assertThat(worlds.fork(account, sources.get(4).code()).statusCode()).isEqualTo(201);
        HttpResponse<String> again = worlds.fork(account, sources.get(5).code());
        assertThat(again.statusCode()).isEqualTo(429);
        // the remainder is now that of the next oldest creation (made moments ago), still about an hour
        assertThat(Long.parseLong(again.headers().firstValue("retry-after").orElseThrow())).isBetween(3_500L, 3_600L);
    }

    @Test
    void parallelRequestsForDifferentSourcesCannotOvershootTheWindow() throws Exception {
        UUID account = UUID.randomUUID();
        List<Source> sources = sources(10);
        try (var pool = Executors.newFixedThreadPool(10)) {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> tasks = new ArrayList<>();
            for (Source source : sources) {
                tasks.add(pool.submit(() -> {
                    gate.await();
                    return worlds.fork(account, source.code());
                }));
            }
            gate.countDown();
            int created = 0;
            int throttled = 0;
            for (Future<HttpResponse<String>> task : tasks) {
                HttpResponse<String> response = task.get();
                if (response.statusCode() == 201) created++;
                else if (response.statusCode() == 429) throttled++;
                else throw new AssertionError(response.statusCode() + " " + response.body());
            }
            assertThat(created).isEqualTo(4);
            assertThat(throttled).isEqualTo(6);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :o").param("o", account).query(Long.class).single()).isEqualTo(4);
    }
}
