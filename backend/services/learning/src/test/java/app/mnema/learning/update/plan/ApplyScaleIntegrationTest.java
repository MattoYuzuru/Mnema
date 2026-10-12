package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.update.plan.PlanWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zero and H of the apply command (community decks architecture section 12), measured on a real PostgreSQL with the defaults and the real executor: a copy of a 300-material deck with
 * 100 exercises applies 100 units in one request; a copy applies 1 000 units as a job. The numbers are printed ({@code apply-scale:}) and quoted in the guide. The asserts check
 * the RESULT, not the time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.jobs.sweep-interval=PT0.5S", "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true",
                "learning.speech.sweep-interval=PT5M", "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S",
                "spring.datasource.hikari.maximum-pool-size=20"})
class ApplyScaleIntegrationTest extends PostgresIntegrationTest {
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
    @Autowired private UpdatePlanService service;
    private PlanWorlds worlds;
    private ApplyWorlds apply;

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
    }

    private static long ms(long started) { return (System.nanoTime() - started) / 1_000_000; }

    @Test
    void aHundredUnitsAreAppliedInOneRequestToACopyOfATripleHundredMaterialDeck() {
        long built = System.nanoTime();
        Big big = worlds.source("LINK", 100, 100, 200);
        Source source = big.source();
        List<UUID> members = source.materials().stream().map(material -> material.member()).toList();
        Copy copy = worlds.copy(source);
        System.out.printf("apply-scale: built a 300-material source with 100 exercises and its copy in %d ms%n", ms(built));
        assertThat(apply.members(copy.deck())).hasSize(300);

        // zero: nothing to apply
        long zero = System.nanoTime();
        HttpResponse<String> nothing = apply.apply(copy, false);
        System.out.printf("apply-scale: zero units (an up-to-date copy): %d ms%n", ms(zero));
        assertThat(nothing.statusCode()).as(nothing.body()).isEqualTo(200);
        assertThat(json(nothing).path("applied").intValue()).isZero();
        assertThat(json(nothing).path("deckRevisionId").isNull()).isTrue();

        // the author changes 60 materials (20..79) and revises 20 exercises (on materials 0..19) with their objectives: 60 + 20 + 20 = 100 units
        long edited = System.nanoTime();
        List<UUID> revisions = apply.saveAll(source.author(), source.deck(), members.subList(20, 80), 20, "changed");
        for (int index = 0; index < 20; index++) {
            worlds.revise(source.author(), source.deck(), source.materials().get(index), source.exercises().get(index), "Вопрос автора " + index, "Цель автора " + index);
        }
        worlds.publish(source.author(), source.deck(), "LINK");
        System.out.printf("apply-scale: the author's 60 saves, 20 exercise revisions and the publication took %d ms%n", ms(edited));

        long planned = System.nanoTime();
        UpdatePlan plan = apply.plan(copy);
        long planMs = ms(planned);
        assertThat(plan.units()).hasSize(100);
        long started = System.nanoTime();
        HttpResponse<String> response = apply.apply(copy, UUID.randomUUID(), plan.planVersion(), true);
        long applyMs = ms(started);
        System.out.printf("apply-scale: H 100 units (60 materials, 20 exercises, 20 objectives) on a 300-material copy with 100 exercises: %d ms in one request (the plan alone: %d ms)%n",
                applyMs, planMs);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode answer = json(response);
        assertThat(answer.path("applied").intValue()).isEqualTo(100);
        assertThat(answer.path("pending").intValue()).isZero();
        assertThat(apply.members(copy.deck())).isEqualTo(apply.publishedMembers(source.deck()));
        for (int index = 0; index < 60; index++) assertThat(worlds.itemHead(copy.deck(), members.get(20 + index))).isEqualTo(revisions.get(index));
        assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
        assertThat(apply.decisions(copy.deck())).isEmpty();
        assertThat(worlds.base(copy.deck())).isEqualTo(worlds.published(source.deck()));
    }

    @Test
    void aThousandUnitsAreAppliedAsAJobInSlicesOfTwoHundredPageEdits() throws InterruptedException {
        Source source = worlds.source("LINK", 1, 0);
        Copy copy = worlds.copy(source);
        Copy partial = worlds.copy(source);
        long built = System.nanoTime();
        worlds.bulk(source.author(), source.deck(), 1_000, "массовый");
        worlds.publish(source.author(), source.deck(), "LINK");
        System.out.printf("apply-scale: the author's 1 000 new materials took %d ms%n", ms(built));
        long planned = System.nanoTime();
        UpdatePlan plan = apply.plan(copy);
        System.out.printf("apply-scale: the plan of 1 000 new materials: %d ms%n", ms(planned));
        assertThat(plan.units()).hasSize(1_000);

        UUID command = UUID.randomUUID();
        long started = System.nanoTime();
        HttpResponse<String> accepted = apply.apply(copy, command, plan.planVersion(), true);
        long acceptedMs = ms(started);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        String state = "QUEUED";
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            state = jdbc.sql("SELECT state FROM app_learning.learning_job WHERE queue='deck.combined.publish' AND dedupe_key=:k").param("k", command.toString()).query(String.class).single();
            if (state.equals("SUCCEEDED") || state.equals("FAILED")) break;
            Thread.sleep(100);
        }
        long total = ms(started);
        System.out.printf("apply-scale: H 1 000 units as a job: the request answered 202 in %d ms; 5 slices of 200 edits and the final transaction took %d ms in all%n", acceptedMs, total);
        assertThat(state).isEqualTo("SUCCEEDED");
        assertThat(apply.members(copy.deck())).hasSize(1_001).isEqualTo(apply.publishedMembers(source.deck()));
        HttpResponse<String> replay = apply.apply(copy, command, plan.planVersion(), true);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(json(replay).path("applied").intValue()).isEqualTo(1_000);
        assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
        assertThat(apply.revisions(copy.deck())).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:d AND change_kind='create'").param("d", copy.deck()).query(Long.class).single())
                .isEqualTo(1_000L);

        // another copy takes 150 of the 1 000 by name and leaves 850 pending: the decisions of the command (850 rows, over 64 KiB with the request) travel in its stored command
        UpdatePlan same = apply.plan(partial);
        ApplyWorlds.Pick[] named = same.units().subList(0, 150).stream().map(unit -> ApplyWorlds.Pick.of(unit.kind(), unit.key(), "ACCEPT")).toArray(ApplyWorlds.Pick[]::new);
        UUID first = UUID.randomUUID();
        long partialStarted = System.nanoTime();
        assertThat(apply.apply(partial, first, same.planVersion(), false, named).statusCode()).isEqualTo(202);
        await(first);
        System.out.printf("apply-scale: 150 units by name as a job with 850 pending rows: %d ms in all%n", ms(partialStarted));
        assertThat(apply.members(partial.deck())).hasSize(151);
        assertThat(apply.decisions(partial.deck())).hasSize(850).allSatisfy((unit, row) -> assertThat(row).startsWith("PENDING"));
        assertThat(apply.plan(partial).units()).hasSize(850);
        UUID second = UUID.randomUUID();
        assertThat(apply.apply(partial, second, apply.version(partial), true).statusCode()).isEqualTo(202);
        await(second);
        assertThat(apply.members(partial.deck())).hasSize(1_001).isEqualTo(apply.publishedMembers(source.deck()));
        assertThat(apply.decisions(partial.deck())).isEmpty();
        assertThat(json(apply.apply(partial, first, same.planVersion(), false, named)).path("pending").intValue()).as("the stored answer of the first command").isEqualTo(850);
    }

    private void await(UUID command) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        String state = "QUEUED";
        while (System.nanoTime() < deadline) {
            state = jdbc.sql("SELECT state FROM app_learning.learning_job WHERE queue='deck.combined.publish' AND dedupe_key=:k").param("k", command.toString()).query(String.class).single();
            if (state.equals("SUCCEEDED") || state.equals("FAILED")) break;
            Thread.sleep(100);
        }
        assertThat(state).isEqualTo("SUCCEEDED");
    }
}
