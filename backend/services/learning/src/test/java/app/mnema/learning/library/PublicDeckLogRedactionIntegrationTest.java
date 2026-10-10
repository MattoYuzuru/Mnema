package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;

/**
 * The public code is the credential of a LINK or INVITE deck's address: whatever fails on the public routes, no log line carries it at the levels the
 * service runs with (INFO and above; the servlet container's own wire logging at DEBUG/TRACE prints every request line and is never enabled in a release).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicDeckLogRedactionIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @MockitoSpyBean private PublishedContentRepository content;
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
    }

    @Test
    void aFailureOnAPublicRouteIsLoggedWithTheRouteTemplateAndNeverTheCode() {
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        Deck deck = fixtures.deck(UUID.randomUUID(), "Журнал");
        String code = fixtures.publishAt(deck, DeckVisibility.LINK);
        Mockito.doThrow(new IllegalStateException("failure that mentions " + code)).when(content).itemRevisions(any(), anyCollection(), anyCollection());
        Mockito.doThrow(new IllegalStateException("failure that mentions " + code)).when(content).exercises(any(), anyCollection(), anyCollection());
        Mockito.doThrow(new IllegalStateException("failure that mentions " + code)).when(content).locate(any(), any(), Mockito.anyInt(), any());

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            List<HttpResponse<String>> failures = List.of(
                    HttpIdentityFixture.send(port, "GET", "/public/decks/" + code + "/items", null, null, Map.of()),
                    HttpIdentityFixture.send(port, "GET", "/public/decks/" + code + "/exercises", null, null, Map.of()),
                    HttpIdentityFixture.send(port, "GET", "/public/decks/" + code + "/items/" + deck.material().member(), null, null, Map.of()));
            for (HttpResponse<String> failure : failures) assertThat(failure.statusCode()).isEqualTo(500);
            // a 404 for an unmapped sub-path and an invalid request are logged by nobody either
            HttpIdentityFixture.send(port, "GET", "/public/decks/" + code + "/nothing-here", null, null, Map.of());
            HttpIdentityFixture.send(port, "GET", "/public/decks/" + code + "/items?limit=0", null, null, Map.of());
        } finally {
            root.detachAppender(appender);
            Mockito.reset(content);
        }
        List<ILoggingEvent> events = List.copyOf(appender.list);
        assertThat(events).isNotEmpty();
        List<String> failureLines = events.stream().filter(event -> event.getFormattedMessage().contains("Unhandled API exception"))
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(failureLines).hasSize(3);
        assertThat(failureLines).anySatisfy(line -> assertThat(line).endsWith("request_path=/api/public/decks/{code}/items"));
        assertThat(failureLines).anySatisfy(line -> assertThat(line).endsWith("request_path=/api/public/decks/{code}/exercises"));
        assertThat(failureLines).anySatisfy(line -> assertThat(line).endsWith("request_path=/api/public/decks/{code}/items/{memberKey}"));
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage()).as(event.getLoggerName()).doesNotContain(code);
            assertThat(String.valueOf(event.getArgumentArray() == null ? "" : java.util.Arrays.toString(event.getArgumentArray()))).doesNotContain(code);
            assertThat(String.valueOf(event.getMDCPropertyMap())).doesNotContain(code);
            if (event.getThrowableProxy() != null) {
                assertThat(String.valueOf(event.getThrowableProxy().getMessage())).as(event.getLoggerName()).doesNotContain(code);
            }
        }
    }
}
