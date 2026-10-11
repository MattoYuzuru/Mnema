package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.library.LibraryFixtures.Deck;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * The invitation token and the public code are credentials: when the request that carries them FAILS in a way that is logged (a 500), no log line carries
 * them, and the route is logged as a template (the token is in the body and the code in the path).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteLogRedactionIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @MockitoSpyBean private AccessRepository repository;
    @Autowired private DeckService decks;
    @Autowired private DeckPublications publications;
    @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
    }

    @Test
    void aFailingRedeemOrRequestIsLoggedWithTheRouteTemplateAndNeverTheTokenOrTheCode() {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Журнал", "Описание")).acknowledgement().path("deck").path("deckId").stringValue(null));
        Deck deck = new Deck(owner, id, null, null);
        LibraryFixtures fixtures = new LibraryFixtures(decks, null, null, null, null, jdbc, publications);
        String code = fixtures.publishAt(deck, DeckVisibility.INVITE);
        String token = InviteTokens.generate(new SecureRandom());
        Mockito.doThrow(new IllegalStateException("failure that mentions " + token + " and " + code)).when(repository).lockInviteByHash(any());
        Mockito.doThrow(new IllegalStateException("failure that mentions " + code)).when(repository).requestsEnabled(any());

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            UUID account = UUID.randomUUID();
            HttpResponse<String> redeem = HttpIdentityFixture.send(port, "POST", "/invites:redeem", HttpIdentityFixture.reader(account), "{\"token\":\"" + token + "\"}", Map.of());
            HttpResponse<String> ask = HttpIdentityFixture.send(port, "POST", "/public/decks/" + code + "/access-requests", HttpIdentityFixture.reader(account), null, Map.of());
            assertThat(redeem.statusCode()).isEqualTo(500);
            assertThat(ask.statusCode()).isEqualTo(500);
            assertThat(redeem.body()).doesNotContain(token);
        } finally {
            root.detachAppender(appender);
            Mockito.reset(repository);
        }
        List<ILoggingEvent> events = List.copyOf(appender.list);
        List<String> failureLines = events.stream().filter(event -> event.getFormattedMessage().contains("Unhandled API exception")).map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(failureLines).hasSize(2);
        assertThat(failureLines).anySatisfy(line -> assertThat(line).endsWith("request_path=/api/invites:redeem"));
        assertThat(failureLines).anySatisfy(line -> assertThat(line).endsWith("request_path=/api/public/decks/{code}/access-requests"));
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage()).as(event.getLoggerName()).doesNotContain(token).doesNotContain(code);
            assertThat(String.valueOf(event.getArgumentArray() == null ? "" : java.util.Arrays.toString(event.getArgumentArray()))).doesNotContain(token).doesNotContain(code);
            assertThat(String.valueOf(event.getMDCPropertyMap())).doesNotContain(token).doesNotContain(code);
            if (event.getThrowableProxy() != null) {
                assertThat(String.valueOf(event.getThrowableProxy().getMessage())).as(event.getLoggerName()).doesNotContain(token).doesNotContain(code);
            }
        }
    }
}
