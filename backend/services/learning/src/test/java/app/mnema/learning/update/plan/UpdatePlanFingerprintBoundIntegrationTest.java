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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/3 (#442): {@code learning.updates.plan.max-fingerprints} bounds the pairs of contents a plan compares. Past the bound the contents are assumed to differ, so a unit both
 * sides changed to the same content is a CONFLICT instead of SAFE (the conservative answer), in the order of the author's manifest, and the same state always gives the same plan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S", "learning.updates.plan.max-fingerprints=1",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanFingerprintBoundIntegrationTest extends PostgresIntegrationTest {
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
    void pastTheBoundTheContentsAreAssumedToDifferInTheOrderOfTheAuthorsManifest() {
        Source source = worlds.source("LINK", 4, 0);
        Copy copy = worlds.copy(source);
        for (int index = 0; index < 3; index++) {
            worlds.editText(source.author(), source.deck(), source.materials().get(index).member(), "общий текст " + index);
            worlds.editText(copy.owner(), copy.deck(), source.materials().get(index).member(), "общий текст " + index);
        }
        worlds.publish(source.author(), source.deck(), "LINK");
        UpdatePlan plan = service.plan(copy.owner(), copy.deck());
        // the same content on both sides is SAFE for the first pair compared and a conflict for the others
        List<UnitClass> classes = plan.units().stream().map(PlanUnit::unitClass).toList();
        assertThat(classes).containsExactly(UnitClass.SAFE, UnitClass.CONFLICT, UnitClass.CONFLICT);
        assertThat(plan.units().get(0).key()).isEqualTo(source.materials().get(0).member());
        assertThat(plan.units().get(1).reason()).isEqualTo(Reason.BOTH_CHANGED);
        assertThat(service.plan(copy.owner(), copy.deck()).units()).isEqualTo(plan.units());
    }
}
