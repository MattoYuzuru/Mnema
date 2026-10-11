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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/3 (#442): a plan with MORE conflicts than the compute budget allows. 150 materials were changed by the author and by the learner to the same content (SAFE if each pair is
 * compared, about 7 ms a pair); the budget is 0.25 s and the pair bound is raised out of the way, so the time is what stops the comparing. The plan is answered inside the transaction
 * timeout, every unit is either SAFE (compared in time) or the cautious CONFLICT, and some are the latter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=200", "learning.jobs.sweep-interval=PT0.5S", "learning.updates.plan.compute-budget=PT0.25S",
                "learning.updates.plan.max-fingerprints=100000", "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanBudgetIntegrationTest extends PostgresIntegrationTest {
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

    @Test
    void aPlanWithMoreConflictsThanTheBudgetAllowsIsAnsweredInTimeWithTheCautiousClass() {
        Big big = worlds.source("LINK", 1, 0, 149);
        Source source = big.source();
        List<UUID> members = new ArrayList<>();
        source.materials().forEach(material -> members.add(material.member()));
        members.addAll(big.bulk());
        Copy copy = worlds.copy(source);
        for (int index = 0; index < members.size(); index++) {
            worlds.editText(source.author(), source.deck(), members.get(index), "общий " + index);
            worlds.editText(copy.owner(), copy.deck(), members.get(index), "общий " + index);
        }
        worlds.publish(source.author(), source.deck(), "LINK");

        long started = System.nanoTime();
        UpdatePlan plan = service.plan(copy.owner(), copy.deck());
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(millis).as("inside the 10 s transaction, whatever the machine").isLessThan(10_000);
        assertThat(plan.units()).hasSize(150);
        long safe = plan.units().stream().filter(unit -> unit.unitClass() == UnitClass.SAFE).count();
        long cautious = plan.units().stream().filter(unit -> unit.unitClass() == UnitClass.CONFLICT && unit.reason() == Reason.BOTH_CHANGED).count();
        assertThat(safe + cautious).as("nothing else").isEqualTo(150);
        assertThat(cautious).as("the time ran out before the last pair").isPositive();
        // pages are answered too, whatever the budget did
        assertThat(service.page(copy.owner(), copy.deck(), null, "100").path("units")).hasSize(100);
    }
}
