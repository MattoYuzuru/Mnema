package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobTestAccess;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.update.apply.ApplyService;
import app.mnema.learning.update.auto.AutoUpdateSettings;
import app.mnema.learning.update.auto.PacedFanoutConfig;
import app.mnema.learning.update.auto.AutoUpdateFanout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The ONE application context of the automatic-update tests (Updates/6 part B, #445), shared by {@link AutoUpdateIntegrationTest}, {@link UpdateHistoryIntegrationTest} and
 * {@link AutoUpdateScaleIntegrationTest} so that the suite caches a single extra Spring context: the automatic mode on, the combined publication above 20 units a job, no process
 * executor (the jobs are run by hand around the real handlers, so every claim happens in a known order), and a fan-out whose pace is a test clock and a test allowance (10 000 a
 * minute unless a test lowers it). The settings of the fan-out are the defaults (slices of 25, pages of 200).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.updates.auto.enabled=true", "learning.updates.sync-max-units=20", "learning.updates.slice-size=10", "learning.community.forks.slice-size=10",
                "learning.jobs.sweep-interval=PT5M", "spring.datasource.hikari.maximum-pool-size=20"})
@Import({PacedFanoutConfig.class, AutoUpdateContext.Counting.class})
abstract class AutoUpdateContext extends PostgresIntegrationTest {
    /** The allowance of the fan-out, per rolling minute, that the test clock paces. */
    static final AtomicInteger ALLOWANCE = PacedFanoutConfig.ALLOWANCE;
    /** The statements prepared on the pool's connections (counted at the JDBC boundary). */
    static final AtomicLong STATEMENTS = new AtomicLong();

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
                if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement") || method.getName().equals("prepareCall")) STATEMENTS.incrementAndGet();
                return call(method, connection, args);
            });
        }

        private static Object call(Method method, Object target, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    @LocalServerPort protected int port;
    @Autowired protected ApplicationContext context;
    @Autowired protected LearningJobs jobs;
    @Autowired protected AutoUpdateFanout fanout;
    @Autowired protected ApplyService applies;
    @Autowired protected PacedFanoutConfig.MovingClock clock;
    /** A spy on the real settings: a test can switch the kill switch off for its own duration ({@code doReturn(false).when(settings).enabled()}); it is reset after every test. */
    @MockitoSpyBean protected AutoUpdateSettings settings;
    @Autowired protected DeckService decks;
    @Autowired protected ItemService items;
    @Autowired protected ExerciseService exercises;
    @Autowired protected StudySessionService sessions;
    @Autowired protected MediaCatalog media;
    @Autowired protected ImmutableStorage storage;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected ManifestPages manifests;
    @Autowired protected UpdatePlanService service;
    protected PlanWorlds worlds;
    protected ApplyWorlds apply;
    protected AutoWorlds auto;
    protected JobTestAccess hand;

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
        apply = new ApplyWorlds(worlds, manifests, service);
        auto = new AutoWorlds(worlds, apply, transactions);
        auto.clearQueues();
        hand = JobTestAccess.around(context, context.getBeansOfType(JobHandler.class).values().toArray(new JobHandler[0]));
        ALLOWANCE.set(10_000);
        // a minute that nothing has used
        clock.advance(Duration.ofMinutes(2));
    }

    @AfterEach
    void close() {
        hand.close();
        ALLOWANCE.set(10_000);
    }
}
