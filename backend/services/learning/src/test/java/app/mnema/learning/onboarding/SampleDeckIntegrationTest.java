package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.ForkWorlds;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The onboarding sample deck over the real application, servlet stack and PostgreSQL (OB-201): an editorial account builds and publishes the template with the production
 * routes, the test names it ({@link SampleDeckTestConfiguration}) and accounts ask for their copy. The real executor materializes the copies. Subclasses share the context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.runtime.roles=all", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
        "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
        "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S",
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z",
        "learning.community.forks.enabled=true", "learning.community.public-routes.enabled=true", "learning.community.events.enabled=true",
        "learning.community.for-you.enabled=true", "spring.datasource.hikari.maximum-pool-size=16"})
@Import(SampleDeckTestConfiguration.class)
abstract class SampleDeckIntegrationTest extends PostgresIntegrationTest {
    protected static final Map<String, String> NONE = Map.of();

    @LocalServerPort protected int port;
    @Autowired protected DeckService decks;
    @Autowired protected ItemService items;
    @Autowired protected ExerciseService exercises;
    @Autowired protected StudySessionService sessions;
    @Autowired protected AttemptService attempts;
    @Autowired protected MediaCatalog media;
    @Autowired protected ImmutableStorage storage;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected ManifestPages manifests;
    @Autowired protected ApplicationContext context;
    protected ForkWorlds worlds;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void worlds() {
        worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        SampleDeckTestConfiguration.template(context, null);
    }

    // ---- the template ----

    /** The editorial account's deck, published at LINK with materials and exercises, named the template and marked (as a restart with its id configured does). */
    protected Source template(int materials, int exercises) {
        Source source = worlds.source("LINK", materials, exercises);
        SampleDeckTestConfiguration.template(context, source.deck());
        return source;
    }

    protected String purpose(UUID deck) {
        return jdbc.sql("SELECT purpose FROM app_learning.deck WHERE deck_id = :d").param("d", deck).query(String.class).single();
    }

    // ---- http ----

    protected HttpResponse<String> post(UUID account, JsonNode... body) {
        String text = body.length > 0 ? body[0].toString() : ForkWorlds.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).toString();
        return HttpIdentityFixture.send(port, "POST", "/onboarding/sample-deck", HttpIdentityFixture.reader(account), text, NONE);
    }

    protected HttpResponse<String> post(UUID account, UUID commandId) {
        return post(account, ForkWorlds.JSON.createObjectNode().put("commandId", commandId.toString()));
    }

    protected HttpResponse<String> onboarding(UUID account, Map<String, String> headers) {
        return HttpIdentityFixture.send(port, "GET", "/onboarding", HttpIdentityFixture.reader(account), null, headers);
    }

    protected JsonNode sampleDeck(UUID account) {
        HttpResponse<String> response = onboarding(account, NONE);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return ForkWorlds.json(response).path("sampleDeck");
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return ForkWorlds.json(response);
    }

    protected static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    /** The deck id of a successful sample request (201 or 200). */
    protected UUID sample(UUID account) {
        HttpResponse<String> response = post(account);
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
        return UUID.fromString(json(response).path("sampleDeck").path("deckId").stringValue(null));
    }

    protected long liveSamples(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck WHERE owner_id = :a AND purpose = 'SAMPLE' AND deleted_at IS NULL").param("a", account).query(Long.class).single();
    }

    protected long count(String sql, Object... params) {
        var query = jdbc.sql(sql);
        for (int index = 0; index < params.length; index++) query = query.param("p" + index, params[index]);
        return query.query(Long.class).single();
    }

    protected void delete(UUID owner, UUID deck) {
        long version = Long.parseLong(decks.read(owner, deck).path("rowVersion").stringValue(null));
        decks.delete(owner, deck, version);
    }
}
