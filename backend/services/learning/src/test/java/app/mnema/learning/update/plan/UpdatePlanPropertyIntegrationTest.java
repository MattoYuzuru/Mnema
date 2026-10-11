package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.update.plan.PlanWorlds.Authored;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/3 (#442), a property-style proof on a copy of 200 materials and 6 exercises: the author and the learner each change a random part of it (seeded; the failing seed is in the
 * message), and the classes of the plan must be exactly what a brute-force three-way comparison gives. The brute force reads EVERY manifest of the base, the published revision and
 * the copy's head in full, resolves every entry to its lineage revision and compares the documents (and exercises) read through the services, with its own copy of the table of
 * architecture section 7; it shares no code with the plan, which reads only the pages of the frontier diff and fingerprints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=200", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanPropertyIntegrationTest extends PostgresIntegrationTest {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UpdatePlanPropertyIntegrationTest.class);

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

    /** The table of the architecture, written again for the check: a = author, b = base, c = copy; null is absent. */
    private enum Expected { NOTHING, NEW, SAFE, CONFLICT_BOTH, CONFLICT_LEARNER_DELETED, DELETED }

    private static Expected expected(UUID b, UUID a, UUID c, boolean sameContent) {
        if (java.util.Objects.equals(a, c) || java.util.Objects.equals(a, b)) return Expected.NOTHING;
        if (a == null) return Expected.DELETED;
        if (c == null) return b == null ? Expected.NEW : Expected.CONFLICT_LEARNER_DELETED;
        if (c.equals(b)) return Expected.SAFE;
        return sameContent ? Expected.SAFE : Expected.CONFLICT_BOTH;
    }

    private record Roots(UUID scope, UUID members, int memberCount) { }

    private Roots roots(UUID deck, UUID revision) {
        return jdbc.sql("SELECT reuse_scope_id,members_root_id,member_count FROM app_learning.deck_revision WHERE deck_id=:d AND revision_id=:r").param("d", deck).param("r", revision)
                .query((row, ignored) -> new Roots(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getInt(3))).single();
    }

    /** Every entry of a members manifest, resolved to the lineage revision its descriptor names. */
    private Map<UUID, UUID> readAll(Roots roots) {
        Map<UUID, UUID> result = new HashMap<>();
        for (int at = 0; at < roots.memberCount(); at += 100) {
            for (Entry entry : manifests.members(roots.scope(), roots.members(), roots.memberCount(), at, Math.min(100, roots.memberCount() - at))) {
                UUID revision = jdbc.sql("SELECT revision_id FROM app_learning.item_revision WHERE reuse_scope_id=:s AND member_key=:k AND descriptor_root_id=:d")
                        .param("s", roots.scope()).param("k", entry.key()).param("d", entry.target().objectId()).query(UUID.class).single();
                assertThat(result.put(entry.key(), revision)).as("a key twice in one manifest").isNull();
            }
        }
        return result;
    }

    private JsonNode document(UUID owner, UUID deck, UUID member, UUID revision) {
        return items.read(owner, deck, member, revision).path("document");
    }

    @Test
    void theClassesOfARandomUpdateOfA200MaterialCopyAreThoseOfABruteForceThreeWayComparison() {
        for (long seed : new long[] {20261011L, 7L}) run(seed);
    }

    private void run(long seed) {
        Random random = new Random(seed);
        Big big = worlds.source("LINK", 10, 6, 190);
        Source source = big.source();
        List<UUID> members = new ArrayList<>();
        source.materials().forEach(material -> members.add(material.member()));
        members.addAll(big.bulk());
        assertThat(members).hasSize(200);
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID learner = copy.owner();
        UUID mine = copy.deck();
        UUID base = worlds.base(mine);

        // ---- what the author does ----
        Map<UUID, String> authorText = new HashMap<>();
        for (int index = 0; index < members.size(); index++) {
            UUID member = members.get(index);
            boolean hasExercise = index < source.exercises().size();
            double dice = random.nextDouble();
            if (dice < 0.16) {
                String text = "автор " + index + " " + random.nextInt(1000);
                worlds.editText(author, deck, member, text);
                authorText.put(member, text);
            } else if (dice < 0.22 && !hasExercise) {
                worlds.deleteMaterial(author, deck, member);
            }
        }
        List<UUID> authorsNew = new ArrayList<>();
        for (int index = 0; index < 3; index++) authorsNew.add(worlds.study.addMaterial(author, deck, "новый " + index, "материал").member());
        Map<UUID, String> authorQuestion = new HashMap<>();
        for (int index = 0; index < source.exercises().size(); index++) {
            double dice = random.nextDouble();
            Authored previous = source.exercises().get(index);
            if (dice < 0.35) {
                String question = "Вопрос автора " + index;
                worlds.revise(author, deck, source.materials().get(index), previous, question, null);
                authorQuestion.put(previous.exerciseId(), question);
            } else if (dice < 0.5) {
                worlds.deleteExercise(author, deck, previous.exerciseId());
            }
        }
        worlds.publish(author, deck, "LINK");

        // ---- what the learner does ----
        for (int index = 0; index < members.size(); index++) {
            UUID member = members.get(index);
            boolean hasExercise = index < source.exercises().size();
            boolean authorEdited = authorText.containsKey(member);
            double dice = random.nextDouble();
            if (authorEdited) {
                if (dice < 0.3) worlds.editText(learner, mine, member, authorText.get(member));
                else if (dice < 0.6) worlds.editText(learner, mine, member, "ученик " + index);
                else if (dice < 0.7 && !hasExercise) worlds.deleteMaterial(learner, mine, member);
            } else if (dice < 0.18) {
                worlds.editText(learner, mine, member, "ученик " + index);
            } else if (dice < 0.23 && !hasExercise && itemExists(mine, member)) {
                worlds.deleteMaterial(learner, mine, member);
            }
        }
        for (int index = 0; index < source.exercises().size(); index++) {
            Authored previous = source.exercises().get(index);
            double dice = random.nextDouble();
            String sameAsAuthor = authorQuestion.get(previous.exerciseId());
            if (sameAsAuthor != null && dice < 0.3) worlds.revise(learner, mine, source.materials().get(index), previous, sameAsAuthor, null);
            else if (dice < 0.6) worlds.revise(learner, mine, source.materials().get(index), previous, "Вопрос ученика " + index, null);
        }

        // ---- the brute force: every manifest in full, every document and exercise read ----
        UUID published = worlds.published(deck);
        Map<UUID, UUID> b = readAll(roots(deck, base));
        Map<UUID, UUID> a = readAll(roots(deck, published));
        Map<UUID, UUID> c = readAll(roots(mine, worlds.headRevisionOf(mine)));
        assertThat(c).as("the heads of the copy are its head manifest").isEqualTo(jdbc.sql("SELECT member_key,revision_id FROM app_learning.deck_head_item WHERE deck_id=:d")
                .param("d", mine).query((row, ignored) -> Map.entry(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        Map<UUID, Expected> wanted = new TreeMap<>();
        java.util.Set<UUID> keys = new java.util.TreeSet<>();
        keys.addAll(b.keySet());
        keys.addAll(a.keySet());
        keys.addAll(c.keySet());
        for (UUID key : keys) {
            UUID bRev = b.get(key);
            UUID aRev = a.get(key);
            UUID cRev = c.get(key);
            boolean same = aRev != null && cRev != null && !aRev.equals(cRev) && !cRev.equals(bRev) && !aRev.equals(bRev)
                    && document(author, deck, key, aRev).equals(document(learner, mine, key, cRev));
            Expected found = expected(bRev, aRev, cRev, same);
            if (found != Expected.NOTHING) wanted.put(key, found);
        }

        UpdatePlan plan = service.plan(learner, mine);
        Map<UUID, Expected> actual = new TreeMap<>();
        for (PlanUnit unit : plan.units()) {
            if (unit.kind() != UnitKind.MATERIAL) continue;
            actual.put(unit.key(), switch (unit.unitClass()) {
                case NEW -> Expected.NEW;
                case SAFE -> Expected.SAFE;
                case CONFLICT -> unit.reason() == Reason.DELETED_BY_YOU ? Expected.CONFLICT_LEARNER_DELETED : Expected.CONFLICT_BOTH;
                case DELETED -> Expected.DELETED;
                case ASK_AGAIN -> throw new AssertionError("no decision exists, seed " + seed);
            });
        }
        assertThat(actual).as("material classes, seed " + seed).isEqualTo(wanted);
        java.util.EnumMap<Expected, Integer> counts = new java.util.EnumMap<>(Expected.class);
        wanted.values().forEach(value -> counts.merge(value, 1, Integer::sum));
        assertThat(counts.keySet()).as("the generator reached every class, seed " + seed).containsExactlyInAnyOrder(Expected.NEW, Expected.SAFE, Expected.CONFLICT_BOTH,
                Expected.CONFLICT_LEARNER_DELETED, Expected.DELETED);
        LOG.info("update plan property seed {}: {} of {} materials, {} units", seed, counts, keys.size(), plan.units().size());

        // exercises: the raw class by the same table; a unit that is NEW or SAFE may only be turned into a conflict by its dependencies
        Map<UUID, UUID> exerciseBase = exerciseManifest(deck, base);
        Map<UUID, UUID> exercisePublished = exerciseManifest(deck, published);
        Map<UUID, UUID> exerciseHead = exerciseManifest(mine, worlds.headRevisionOf(mine));
        java.util.Set<UUID> exerciseKeys = new java.util.TreeSet<>();
        exerciseKeys.addAll(exerciseBase.keySet());
        exerciseKeys.addAll(exercisePublished.keySet());
        exerciseKeys.addAll(exerciseHead.keySet());
        Map<UUID, PlanUnit> exerciseUnits = new LinkedHashMap<>();
        plan.units().stream().filter(unit -> unit.kind() == UnitKind.EXERCISE).forEach(unit -> exerciseUnits.put(unit.key(), unit));
        for (UUID key : exerciseKeys) {
            UUID bRev = exerciseBase.get(key);
            UUID aRev = exercisePublished.get(key);
            UUID cRev = exerciseHead.get(key);
            boolean same = aRev != null && cRev != null && !aRev.equals(cRev) && !cRev.equals(bRev) && !aRev.equals(bRev)
                    && learnerRelevant(author, deck, key, aRev).equals(learnerRelevant(learner, mine, key, cRev));
            Expected found = expected(bRev, aRev, cRev, same);
            PlanUnit unit = exerciseUnits.get(key);
            if (found == Expected.NOTHING) {
                assertThat(unit).as("exercise " + key + " seed " + seed).isNull();
                continue;
            }
            assertThat(unit).as("exercise " + key + " seed " + seed).isNotNull();
            switch (found) {
                case NEW -> assertThat(unit.unitClass()).isEqualTo(UnitClass.NEW);
                case SAFE -> assertThat(unit.unitClass()).isEqualTo(UnitClass.SAFE);
                case DELETED -> assertThat(unit.unitClass()).isEqualTo(UnitClass.DELETED);
                case CONFLICT_BOTH -> {
                    assertThat(unit.unitClass()).isEqualTo(UnitClass.CONFLICT);
                    assertThat(unit.reason()).isEqualTo(Reason.BOTH_CHANGED);
                }
                default -> throw new AssertionError("an exercise is never deleted by the learner here");
            }
        }
        assertThat(plan.units()).extracting(unit -> unit.unitClass().ordinal()).as("class order").isSorted();
        assertThat(plan.summary().fixes()).isEqualTo((int) plan.units().stream().filter(unit -> unit.unitClass() == UnitClass.SAFE).count());
    }

    private boolean itemExists(UUID deck, UUID member) { return worlds.itemHead(deck, member) != null; }

    /** exercise id -> revision of an exercises manifest, resolved by descriptor. */
    private Map<UUID, UUID> exerciseManifest(UUID deck, UUID revision) {
        record Row(UUID scope, UUID root, int count) { }
        Row row = jdbc.sql("SELECT reuse_scope_id,exercises_root_id,exercise_count FROM app_learning.deck_revision WHERE deck_id=:d AND revision_id=:r").param("d", deck).param("r", revision)
                .query((result, ignored) -> new Row(result.getObject(1, UUID.class), result.getObject(2, UUID.class), result.getInt(3))).single();
        Map<UUID, UUID> result = new HashMap<>();
        for (int at = 0; at < row.count(); at += 100) {
            for (Entry entry : manifests.exercises(row.scope(), row.root(), row.count(), at, Math.min(100, row.count() - at))) {
                result.put(entry.key(), jdbc.sql("SELECT revision_id FROM app_learning.exercise_revision WHERE reuse_scope_id=:s AND exercise_id=:k AND descriptor_root_id=:d")
                        .param("s", row.scope()).param("k", entry.key()).param("d", entry.target().objectId()).query(UUID.class).single());
            }
        }
        return result;
    }

    private JsonNode learnerRelevant(UUID owner, UUID deck, UUID exercise, UUID revision) {
        JsonNode read = exercises.read(owner, deck, exercise, revision);
        var value = PlanWorlds.JSON.createObjectNode().put("type", read.path("type").stringValue(null)).put("enabled", read.path("enabled").booleanValue(false));
        value.set("content", read.path("content"));
        value.set("answerKey", read.path("answerKey"));
        value.set("evaluatorPolicy", read.path("evaluatorPolicy"));
        return value;
    }
}
