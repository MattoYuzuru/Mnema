package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestDiff;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.update.plan.PlanWorlds.Big;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import app.mnema.learning.update.plan.PlanWorlds.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/3 (#442), the load of the plan: a copy of 1 000 materials, the author changes 10 and then 100 of them. The statements of one plan are counted at the JDBC boundary (every
 * {@code prepareStatement}, {@code createStatement} and {@code prepareCall} of a connection of the pool), the pages of the frontier diff are asked of the diff itself, and the time
 * is the best of three warm runs (reported, never asserted). The numbers are logged (they are quoted in the guide) and the statements and pages are bounded: the cost follows the number of changes, never the size of the deck, and an
 * up-to-date copy costs two root comparisons and a handful of statements.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=200", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanScaleIntegrationTest extends PostgresIntegrationTest {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UpdatePlanScaleIntegrationTest.class);
    static final AtomicLong STATEMENTS = new AtomicLong();
    /** The thread whose statements count: the test's own, which makes the measured call; background jobs prepare statements at their own pace. */
    private static volatile Thread measured;

    /** Counts the statements prepared on the pool's connections: a JDK proxy over the DataSource and over each connection it hands out. */
    @TestConfiguration
    static class Counting {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource source)) return bean;
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        Object result = call(method, source, args);
                        if (method.getName().equals("getConnection") && result instanceof Connection connection) return counting(connection);
                        return result;
                    });
                }
            };
        }

        private static Connection counting(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (Thread.currentThread() == measured && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement") || method.getName().equals("prepareCall"))) STATEMENTS.incrementAndGet();
                return call(method, connection, args);
            });
        }

        private static Object call(Method method, Object target, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
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
    @Autowired private ManifestDiff diff;
    @Autowired private UpdatePlanService service;
    private PlanWorlds worlds;

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
        worlds = new PlanWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private record Measure(int units, long statements, double millis, int pagesRead, int levels) { }

    /** The plan of the copy, warm: the statements of one call, the best of three timings, and the pages the frontier diff reads. */
    private Measure measure(Source source, Copy copy) {
        measured = Thread.currentThread();
        service.plan(copy.owner(), copy.deck());
        double best = Double.MAX_VALUE;
        long statements = 0;
        int units = 0;
        for (int run = 0; run < 3; run++) {
            long before = STATEMENTS.get();
            long started = System.nanoTime();
            units = service.plan(copy.owner(), copy.deck()).units().size();
            best = Math.min(best, (System.nanoTime() - started) / 1_000_000.0);
            statements = STATEMENTS.get() - before;
        }
        record Roots(UUID scope, UUID members, int count) { }
        Roots base = jdbc.sql("SELECT r.reuse_scope_id,r.members_root_id,r.member_count FROM app_learning.deck_fork f JOIN app_learning.deck_revision r ON r.deck_id=f.upstream_deck_id "
                + "AND r.revision_id=f.base_revision_id WHERE f.deck_id=:d").param("d", copy.deck())
                .query((row, ignored) -> new Roots(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getInt(3))).single();
        Roots published = jdbc.sql("SELECT r.reuse_scope_id,r.members_root_id,r.member_count FROM app_learning.deck_publication p JOIN app_learning.deck_revision r "
                + "ON r.deck_id=p.deck_id AND r.revision_id=p.published_revision_id WHERE p.deck_id=:d").param("d", source.deck())
                .query((row, ignored) -> new Roots(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getInt(3))).single();
        Result changes = diff.members(base.scope(), base.members(), base.count(), published.members(), published.count());
        return new Measure(units, statements, best, changes.stats().pagesRead(), changes.stats().levels());
    }

    @Test
    void thePlanCostFollowsTheChangesAndNeverTheSizeOfTheDeck() {
        Big big = worlds.source("LINK", 5, 0, 995);
        Source source = big.source();
        List<UUID> members = new ArrayList<>();
        source.materials().forEach(material -> members.add(material.member()));
        members.addAll(big.bulk());
        assertThat(members).hasSize(1000);
        Copy quiet = worlds.copy(source);
        Copy busy = worlds.copy(source);

        Measure upToDate = measure(source, quiet);
        assertThat(upToDate.units()).isZero();
        assertThat(upToDate.pagesRead()).as("two equal roots: no page is read").isZero();

        // 10 changes, spread over the deck
        for (int index = 0; index < 10; index++) worlds.editText(source.author(), source.deck(), members.get(index * 97), "автор " + index);
        worlds.publish(source.author(), source.deck(), "LINK");
        Measure ten = measure(source, quiet);
        assertThat(ten.units()).isEqualTo(10);

        // 100 changes
        for (int index = 0; index < 100; index++) {
            if (index % 10 == 0 && index / 10 < 10) continue;
            worlds.editText(source.author(), source.deck(), members.get((index * 9 + 3) % 1000 == (index / 10) * 97 ? (index * 9 + 4) % 1000 : (index * 9 + 3) % 1000), "автор ещё " + index);
        }
        worlds.publish(source.author(), source.deck(), "LINK");
        Measure hundred = measure(source, quiet);
        assertThat(hundred.units()).isBetween(90, 100);

        // 100 changes of the author met by 100 changes of the learner: every unit is a conflict and every pair of documents is fingerprinted
        for (int index = 0; index < 100; index++) worlds.editText(busy.owner(), busy.deck(), members.get((index * 9 + 3) % 1000), "ученик " + index);
        Measure conflicts = measure(source, busy);

        // an up-to-date copy made after the publication
        Copy fresh = worlds.copy(source);
        Measure freshMeasure = measure(source, fresh);
        assertThat(freshMeasure.units()).isZero();

        LOG.info("update plan load, 1000 materials: up-to-date {}; fresh copy {}; 10 changes {}; 100 changes {}; 100 conflicts {}", upToDate, freshMeasure, ten, hundred, conflicts);
        assertThat(upToDate.statements()).isLessThanOrEqualTo(12);
        assertThat(freshMeasure.statements()).isLessThanOrEqualTo(12);
        assertThat(ten.statements()).as("a few statements per level, per lane and per page batch, not per unit").isLessThanOrEqualTo(40);
        assertThat(hundred.statements()).isLessThanOrEqualTo(60);
        assertThat(ten.pagesRead()).isLessThanOrEqualTo(80);
        assertThat(hundred.pagesRead()).isLessThanOrEqualTo(120);
        // the conflicts cost documents, bounded by the plan's pair budget (200 pairs of materials, about 24 statements each)
        assertThat(conflicts.statements()).isLessThanOrEqualTo(200L * 30 + 60);
    }
}
