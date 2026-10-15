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
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/6 part A (#445), the load of the badge: a page of the owner's decks with copies costs the same number of statements whatever the number of copies, with or without
 * decisions, because the badge is computed from the resolution the read model already makes and one EXISTS inside the deck statement. The statements are counted at the JDBC boundary
 * (every {@code prepareStatement}, {@code createStatement} and {@code prepareCall} of a pool connection) and the numbers are logged (they are quoted in the guide).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.community.forks.max-preparing=100", "learning.community.forks.max-per-window=1000"})
class ForkReadScaleIntegrationTest extends PostgresIntegrationTest {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ForkReadScaleIntegrationTest.class);
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
    private ForkWorlds worlds;

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

    private record Measure(int items, int badges, long statements, double millis) { }

    /** A warm list of the account's decks: the statements of one call and the best of three timings. */
    private Measure list(UUID account) {
        measured = Thread.currentThread();
        decks.list(account, "50", null);
        long statements = 0;
        double best = Double.MAX_VALUE;
        JsonNode page = null;
        for (int round = 0; round < 3; round++) {
            long before = STATEMENTS.get();
            long started = System.nanoTime();
            page = decks.list(account, "50", null);
            best = Math.min(best, (System.nanoTime() - started) / 1_000_000.0);
            statements = STATEMENTS.get() - before;
        }
        int badges = 0;
        for (JsonNode item : page.path("items")) if (item.path("fork").path("updateAvailable").booleanValue(false)) badges++;
        return new Measure(page.path("items").size(), badges, statements, best);
    }

    private UUID account(int copies, int withBadge) {
        UUID account = UUID.randomUUID();
        for (int index = 0; index < copies; index++) {
            Source source = worlds.source("LINK", 1, 0);
            UUID copy = worlds.forked(account, source.code());
            worlds.markReady(copy);
            if (index < withBadge) {
                if (index % 2 == 0) worlds.republish(source, "LINK");
                else worlds.pend(copy);
            }
        }
        return account;
    }

    @Test
    void aPageOfCopiesCostsTheSameFewStatementsWhateverTheNumberOfCopiesAndBadges() {
        UUID none = UUID.randomUUID();
        for (int index = 0; index < 5; index++) worlds.shared.deck(none, "Обычная " + index);
        Measure ordinary = list(none);
        Measure few = list(account(2, 1));
        Measure many = list(account(40, 25));
        LOG.info("ordinary decks: {}; 2 copies: {}; 40 copies: {}", ordinary, few, many);
        assertThat(many.items()).isEqualTo(40);
        assertThat(many.badges()).isEqualTo(25);
        assertThat(few.badges()).isEqualTo(1);
        assertThat(ordinary.badges()).isZero();
        // the page, the sources by code and (only for an invitation deck) the grants: no query per copy
        assertThat(few.statements()).isLessThanOrEqualTo(3);
        assertThat(many.statements()).as("40 copies cost what 2 cost").isEqualTo(few.statements());
        assertThat(ordinary.statements()).as("a page without copies asks nothing of the sources").isEqualTo(few.statements() - 1);
    }

    @Test
    void theBadgeOfACopyWithManyDecisionsIsStillOneProbe() {
        UUID account = UUID.randomUUID();
        Source source = worlds.source("LINK", 1, 0);
        UUID copy = worlds.forked(account, source.code());
        worlds.markReady(copy);
        for (int index = 0; index < 2_000; index++) worlds.keepMine(copy);
        Measure decided = list(account);
        assertThat(decided.badges()).isZero();
        worlds.pend(copy);
        Measure pending = list(account);
        LOG.info("a copy with 2 000 decided units: {}; with one more pending: {}", decided, pending);
        assertThat(pending.badges()).isEqualTo(1);
        assertThat(pending.statements()).isEqualTo(decided.statements());
    }
}
