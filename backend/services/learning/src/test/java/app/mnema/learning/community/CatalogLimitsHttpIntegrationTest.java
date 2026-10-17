package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What protects the instance from the expensive reads of the catalogue: a statement or a transaction that runs out of time is a {@code 503 PUBLIC_READ_BUSY} with {@code Retry-After}, counted
 * as {@code outcome=timeout} and logged as nothing worse than that (no ERROR with a stack trace per hit); at most {@code max-concurrent-searches} text searches run at once and the next is
 * refused before it takes a connection while the cheap reads still answer; a cached facets answer opens no transaction; the read is one REPEATABLE READ, read-only transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogLimitsHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @MockitoSpyBean private CatalogQueries queries;
    @Autowired private CatalogService service;
    @Autowired private JdbcClient jdbc;
    @Autowired private MeterRegistry meters;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.catalog.enabled", () -> true);
        registry.add("learning.community.catalog.query-timeout", () -> "PT1S");
        registry.add("learning.community.catalog.max-concurrent-searches", () -> 1);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.coarse-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    private HttpResponse<String> get(String path) {
        return HttpIdentityFixture.send(port, "GET", path, null, null, Map.of());
    }

    private double count(String route, String outcome) {
        var counter = meters.find("mnema_public_deck_requests_total").tag("route", route).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aReadThatRunsOutOfTimeIsA503WithRetryAfterCountedAsTimeoutAndNeverAnErrorLine() {
        service.invalidate();
        Mockito.doAnswer(call -> {
            jdbc.sql("SELECT pg_sleep(3)").query().listOfRows();
            return call.callRealMethod();
        }).when(queries).counts();
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        double before = count("catalog_facets", "timeout");
        HttpResponse<String> response;
        try {
            response = get("/public/community/facets");
        } finally {
            root.detachAppender(appender);
            Mockito.reset(queries);
        }
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(JSON.readTree(response.body()).path("code").stringValue(null)).isEqualTo("PUBLIC_READ_BUSY");
        assertThat(response.headers().firstValue("retry-after")).contains("1");
        assertThat(count("catalog_facets", "timeout")).isEqualTo(before + 1);
        assertThat(appender.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR));
        // the next read is served: nothing stayed broken
        assertThat(get("/public/community/facets").statusCode()).isEqualTo(200);
    }

    @Test
    void theNextSearchIsRefusedWhileOneRunsAndTheCheapReadsStillAnswer() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(call -> {
            inside.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(queries).searchPage(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
        try {
            CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> get("/public/community/decks?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9"));
            assertThat(inside.await(20, TimeUnit.SECONDS)).isTrue();
            double before = count("catalog_decks", "busy");
            HttpResponse<String> refused = get("/public/community/decks?q=sql");
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(503);
            assertThat(JSON.readTree(refused.body()).path("code").stringValue(null)).isEqualTo("PUBLIC_READ_BUSY");
            assertThat(refused.headers().firstValue("retry-after")).contains("1");
            assertThat(count("catalog_decks", "busy")).isEqualTo(before + 1);
            // a browse and the facets are not searches
            assertThat(get("/public/community/decks?sort=new").statusCode()).isEqualTo(200);
            assertThat(get("/public/community/facets").statusCode()).isEqualTo(200);
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            release.countDown();
            Mockito.reset(queries);
        }
        // the place is given back: a search answers again
        assertThat(get("/public/community/decks?q=sql").statusCode()).isEqualTo(200);
    }

    @Test
    void aCachedFacetsAnswerOpensNoTransactionAndTheReadIsOneRepeatableReadReadOnlyTransaction() {
        service.invalidate();
        AtomicReference<List<String>> seen = new AtomicReference<>();
        Mockito.doAnswer(call -> {
            seen.set(List.of(jdbc.sql("SHOW transaction_isolation").query(String.class).single(), jdbc.sql("SHOW transaction_read_only").query(String.class).single()));
            return call.callRealMethod();
        }).when(queries).counts();
        try {
            long timers = timerCount("catalog_facets");
            assertThat(get("/public/community/facets").statusCode()).isEqualTo(200);
            assertThat(seen.get()).containsExactly("repeatable read", "on");
            assertThat(timerCount("catalog_facets")).isEqualTo(timers + 1);
            double ok = count("catalog_facets", "ok");
            assertThat(get("/public/community/facets").statusCode()).isEqualTo(200);
            assertThat(timerCount("catalog_facets")).as("the second answer came from the cache: no transaction").isEqualTo(timers + 1);
            assertThat(count("catalog_facets", "ok")).as("and is still counted").isEqualTo(ok + 1);
            Mockito.verify(queries, Mockito.times(1)).counts();
        } finally {
            Mockito.reset(queries);
        }
    }

    private long timerCount(String route) {
        var timer = meters.find("mnema_catalog_request_seconds").tag("route", route).timer();
        return timer == null ? 0 : timer.count();
    }
}
