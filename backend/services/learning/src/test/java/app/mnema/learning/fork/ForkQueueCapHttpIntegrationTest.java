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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.UUID;

import static app.mnema.learning.fork.ForkWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The global cap of unfinished copies ({@code learning.community.forks.max-unfinished-total}, here 2): the queue of the materialization job is shared by every account, so
 * past the cap a NEW copy is {@code 503 FORKS_BUSY} with {@code Retry-After: 30}, an existing copy is still answered, the refused command is not recorded (the same command succeeds
 * when a place frees), and the count is the partial index {@code deck_fork_unfinished}. No executor runs, so the copies stay preparing. The cap counts the whole database, so the test
 * has a database of its own (the shared test database holds the unfinished copies of other classes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.community.forks.max-unfinished-total=2", "learning.community.forks.max-preparing=100", "learning.community.forks.max-per-window=1000"})
@Import(ForkQueueCapHttpIntegrationTest.DatabaseConfiguration.class)
class ForkQueueCapHttpIntegrationTest extends PostgresIntegrationTest {
    private static final String DATABASE = createDatabase("fork_queue_cap_" + UUID.randomUUID().toString().replace("-", ""));

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        DynamicPropertyRegistrar isolatedDatabase() {
            // Registrars run after inherited dynamic methods and before the DataSource/Flyway are instantiated.
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE);
                registry.add("spring.flyway.url", () -> DATABASE);
            };
        }
    }

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
    @Autowired private ForkRepository repository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
    }

    @Test
    void aNewCopyPastTheGlobalCapIsBusyAnExistingOneIsStillAnsweredAndAFreedPlaceAdmitsTheSameCommand() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).startsWith("fork_queue_cap_");
        ForkWorlds worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        Source source = worlds.source("LINK", 1, 0);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID firstCopy = worlds.forked(first, source.code());
        worlds.forked(second, source.code());
        assertThat(repository.unfinishedTotal(1_000)).isEqualTo(2);

        UUID command = UUID.randomUUID();
        HttpResponse<String> busy = worlds.fork(third, command, source.code());
        assertThat(busy.statusCode()).as(busy.body()).isEqualTo(503);
        JsonNode problem = json(busy);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("FORKS_BUSY");
        assertThat(busy.headers().firstValue("retry-after")).hasValue("30");
        assertThat(problem.path("retryAfter").longValue()).isEqualTo(30);
        // the problem is the one of the contract: same members, same values
        JsonNode contract = ForkWorlds.forksContract().path("problems").path("busy");
        assertThat(contract.path("status").intValue()).isEqualTo(503);
        assertThat(problem.propertyNames()).containsExactlyInAnyOrderElementsOf(contract.path("body").propertyNames());
        for (String member : new String[] {"type", "title", "status", "detail", "code", "retryAfter"}) {
            assertThat(problem.path(member)).as(member).isEqualTo(contract.path("body").path(member));
        }
        assertThat(contract.path("headers").path("Retry-After").stringValue(null)).isEqualTo("30");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :o").param("o", third).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck WHERE owner_id = :o").param("o", third).query(Long.class).single()).isZero();

        // an existing copy is answered at the cap: no new work
        HttpResponse<String> existing = worlds.fork(first, source.code());
        assertThat(existing.statusCode()).isEqualTo(200);
        assertThat(json(existing).path("existing").booleanValue()).isTrue();

        // a deleted copy frees its place at once, and the refused command, never recorded, now succeeds
        assertThat(worlds.send("DELETE", "/decks/" + firstCopy, first, null, "\"0\"").statusCode()).isEqualTo(204);
        assertThat(repository.unfinishedTotal(1_000)).isEqualTo(1);
        HttpResponse<String> admitted = worlds.fork(third, command, source.code());
        assertThat(admitted.statusCode()).as(admitted.body()).isEqualTo(201);
        assertThat(repository.unfinishedTotal(1_000)).isEqualTo(2);
        assertThat(worlds.fork(UUID.randomUUID(), source.code()).statusCode()).isEqualTo(503);
    }

    @Test
    void theCountIsBoundedByTheCapAndServedByThePartialIndex() {
        // 'limit' stops the count at the cap whatever the table holds
        assertThat(repository.unfinishedTotal(1)).isLessThanOrEqualTo(1);
        // with sequential scans discouraged the planner picks deck_fork_unfinished: the predicate of the query is exactly the index's
        String plan = new TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN SELECT count(*) FROM (SELECT 1 FROM app_learning.deck_fork WHERE materialized_at IS NULL AND released_at IS NULL LIMIT 200) bounded")
                    .query(String.class).list());
        });
        assertThat(plan).contains("deck_fork_unfinished").doesNotContain("Seq Scan");
    }
}
