package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Material;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/9 (#431): {@code published_media}, the set of assets the PUBLISHED revision of a deck references, is maintained by the publication command from the
 * journal range between the previous published sequence and the new head. The first publication inserts every reference of the head; a republication adds
 * the references of changed units and subtracts those of the revisions they replaced (ref_count counts media references, a row at zero is removed); changes
 * made after publishing are invisible until the next publication; a downgrade keeps the rows. The V55 backfill (the same correction written backwards from the
 * head) must give exactly the incrementally maintained table for every deck of the database.
 */
@SpringBootTest
class PublishedMediaIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private PublicationService publications;
    @Autowired private PublishedMediaRepository publishedMedia;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired private PlatformTransactionManager transactions;
    private SharedScopeFixture shared;
    private StudyFixtures study;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void fixtures() {
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
    }

    private static UUID[] pick(java.util.Random random, List<UUID> pool) {
        UUID[] chosen = new UUID[random.nextInt(4)];
        for (int index = 0; index < chosen.length; index++) chosen[index] = pool.get(random.nextInt(pool.size()));
        return chosen;
    }

    private UUID asset() { return study.readyAsset(owner, "image/png"); }

    private UUID sound() { return study.readyAsset(owner, "audio/mpeg"); }

    private long wireVersion(UUID deck) {
        return Long.parseLong(publications.read(owner, deck, false).path("rowVersion").stringValue(null));
    }

    private PublicationCommand command(UUID deck, String visibility, boolean publishHead) {
        var body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", visibility);
        var metadata = body.putObject("metadata");
        metadata.put("topicId", "japanese").put("contentLanguage", "ru").put("targetLanguage", "ja").put("level", "A2").putArray("tags").add("jlpt n5");
        body.put("requestsEnabled", true);
        if (publishHead) body.putObject("publish").put("expectedHeadRevisionId", decks.read(owner, deck).path("revisionId").stringValue(null)).putNull("releaseNote");
        else body.putNull("publish");
        return PublicationCommand.read(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private void publish(UUID deck) { publications.save(owner, deck, wireVersion(deck), command(deck, "LINK", true), false); }

    private void level(UUID deck, String visibility) { publications.save(owner, deck, wireVersion(deck), command(deck, visibility, false), false); }

    private Map<UUID, Integer> set(UUID deck) {
        Map<UUID, Integer> result = new TreeMap<>();
        jdbc.sql("SELECT asset_id, ref_count FROM app_learning.published_media WHERE deck_id = :deck").param("deck", deck)
                .query((row, ignored) -> result.put(row.getObject(1, UUID.class), row.getInt(2))).list();
        return result;
    }

    private static Map<UUID, Integer> of(Object... pairs) {
        Map<UUID, Integer> result = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((UUID) pairs[index], (Integer) pairs[index + 1]);
        return result;
    }

    /** Saves a new revision of a material built with {@code imageDocument}: its images become exactly {@code assets}. */
    private UUID resave(UUID deck, UUID member, UUID expectedRevision, UUID... assets) {
        return MediaFixtures.saveImages(decks, items, owner, deck, member, expectedRevision, assets);
    }

    private UUID exerciseWithAudio(UUID deck, UUID asset) {
        StudyFixtures.Material host = study.addMaterial(owner, deck, "host", "text");
        JsonNode published = study.publish(host, study.freeResponse(host, blocks(audio(asset, "Звук", null), text("Что слышно?")), blocks(), "ответ"));
        return UUID.fromString(published.path("exerciseId").stringValue(null));
    }

    @Test
    void theFirstPublicationInsertsEveryReferenceAndRepublicationsFollowTheChangedUnitsOnly() {
        UUID deck = shared.deck(owner, "Медиа");
        UUID a1 = asset(), a2 = asset(), a3 = sound(), a4 = asset(), a5 = asset(), a6 = asset();
        Material m1 = shared.create(owner, deck, MediaFixtures.imageDocument(a1, a1, a2));
        Material m2 = shared.create(owner, deck, MediaFixtures.imageDocument(a2));
        UUID exercise = exerciseWithAudio(deck, a3);
        assertThat(set(deck)).as("nothing is published yet").isEmpty();

        // first publication: every reference of the head's MATERIALS (each node counts); the exercise's sound is not offered to guests
        publish(deck);
        assertThat(set(deck)).isEqualTo(of(a1, 2, a2, 2));

        // changes after publishing are invisible to the set until the next publication
        UUID m1Revision = resave(deck, m1.member(), m1.revision(), a1, a4);
        Material m3 = shared.create(owner, deck, MediaFixtures.imageDocument(a5));
        MediaFixtures.deleteMaterial(decks, items, owner, deck, m2.member(), m2.revision());
        exercises.delete(owner, deck, exercise, Long.parseLong(decks.read(owner, deck).path("rowVersion").stringValue(null)));
        // a unit created and removed inside the same range adds and subtracts nothing
        Material m4 = shared.create(owner, deck, MediaFixtures.imageDocument(a6));
        MediaFixtures.deleteMaterial(decks, items, owner, deck, m4.member(), m4.revision());
        assertThat(set(deck)).isEqualTo(of(a1, 2, a2, 2));

        publish(deck);
        // m1 replaced (a1 twice, a2) by (a1, a4); m2 deleted (a2); the exercise removed (it never counted); m3 created (a5); m4 never visible (a6)
        assertThat(set(deck)).isEqualTo(of(a1, 1, a4, 1, a5, 1));
        assertThat(m3.member()).isNotNull();
        assertThat(m1Revision).isNotEqualTo(m1.revision());

        // a downgrade keeps the rows (the pointer is kept; the access level decides who uses them), raising again changes nothing
        level(deck, "PRIVATE");
        assertThat(set(deck)).isEqualTo(of(a1, 1, a4, 1, a5, 1));
        level(deck, "INVITE");
        assertThat(set(deck)).isEqualTo(of(a1, 1, a4, 1, a5, 1));

        // an asset that moves from one unit to another between publications keeps its row
        UUID m1Now = resave(deck, m1.member(), m1Revision, a4);
        Material m5 = shared.create(owner, deck, MediaFixtures.imageDocument(a1));
        publish(deck);
        assertThat(set(deck)).isEqualTo(of(a1, 1, a4, 1, a5, 1));
        assertThat(m1Now).isNotNull();
        assertThat(m5.member()).isNotNull();
    }

    @Test
    void aRepeatedCommandIsAReplayAndCountsNothingTwice() {
        UUID deck = shared.deck(owner, "Повтор");
        UUID a1 = asset();
        shared.create(owner, deck, MediaFixtures.imageDocument(a1));
        PublicationCommand command = command(deck, "LINK", true);
        long version = wireVersion(deck);
        assertThat(publications.save(owner, deck, version, command, false).replayed()).isFalse();
        assertThat(publications.save(owner, deck, version, command, false).replayed()).isTrue();
        assertThat(set(deck)).isEqualTo(of(a1, 1));
    }

    private double drifts() {
        var counter = meters.find("mnema_published_media_drift_total").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aSetThatDriftedFromTheJournalIsRebuiltFromTheHeadInTheSameTransactionAndReported() {
        UUID deck = shared.deck(owner, "Дрейф");
        UUID a1 = asset(), a2 = asset();
        Material m = shared.create(owner, deck, MediaFixtures.imageDocument(a1, a2));
        publish(deck);
        double before = drifts();
        // a lost row: the next republication would decrement a row that is not there
        assertThat(jdbc.sql("DELETE FROM app_learning.published_media WHERE deck_id = :deck AND asset_id = :asset").param("deck", deck).param("asset", a1).update()).isOne();
        UUID second = resave(deck, m.member(), m.revision(), a2, a2);
        publish(deck);
        assertThat(set(deck)).as("rebuilt from the head: a2 twice").isEqualTo(of(a2, 2));
        assertThat(drifts()).isEqualTo(before + 1);

        // an over-count: the stored count is higher than the references that exist; the decrement alone would leave it positive
        jdbc.sql("UPDATE app_learning.published_media SET ref_count = 7 WHERE deck_id = :deck AND asset_id = :asset").param("deck", deck).param("asset", a2).update();
        resave(deck, m.member(), second, a2);
        publish(deck);
        assertThat(set(deck)).as("rebuilt from the head: a2 once").isEqualTo(of(a2, 1));
        assertThat(drifts()).isEqualTo(before + 2);

        // an exact set is not reported
        UUID third = resave(deck, m.member(), jdbc.sql("SELECT head.revision_id FROM app_learning.deck_head_item head WHERE head.deck_id = :deck AND head.member_key = :member")
                .param("deck", deck).param("member", m.member()).query(UUID.class).single());
        assertThat(third).isNotNull();
        publish(deck);
        assertThat(set(deck)).isEmpty();
        assertThat(drifts()).isEqualTo(before + 2);

        // the repository reports what it found and repaired
        UUID b1 = asset();
        UUID other = shared.deck(owner, "Дрейф два");
        Material n = shared.create(owner, other, MediaFixtures.imageDocument(b1));
        publish(other);
        long previous = jdbc.sql("SELECT r.sequence FROM app_learning.deck_publication p JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id WHERE p.deck_id = :deck")
                .param("deck", other).query(Long.class).single();
        resave(other, n.member(), n.revision());
        long head = jdbc.sql("SELECT r.sequence FROM app_learning.deck d JOIN app_learning.deck_revision r ON r.deck_id = d.deck_id AND r.revision_id = d.head_revision_id WHERE d.deck_id = :deck")
                .param("deck", other).query(Long.class).single();
        jdbc.sql("DELETE FROM app_learning.published_media WHERE deck_id = :deck").param("deck", other).update();
        int found = new TransactionTemplate(transactions).execute(status -> publishedMedia.advance(other, previous, head));
        assertThat(found).isOne();
        assertThat(set(other)).isEmpty();
    }

    /** What the published set must be right after publishing: the references of the head's materials, counted per asset. */
    private Map<UUID, Integer> fromHead(UUID deck) {
        Map<UUID, Integer> result = new TreeMap<>();
        jdbc.sql("""
                SELECT r.asset_id, count(*)::integer FROM app_learning.deck_head_item h
                  JOIN app_learning.content_media_ref r ON r.reuse_scope_id = h.reuse_scope_id AND r.member_key = h.member_key AND r.revision_id = h.revision_id
                 WHERE h.deck_id = :deck GROUP BY r.asset_id
                """).param("deck", deck).query((row, ignored) -> result.put(row.getObject(1, UUID.class), row.getInt(2))).list();
        return result;
    }

    @Test
    void randomEditPublishAndRemoveSequencesAlwaysEqualARecomputeFromTheHead() {
        double before = drifts();
        for (long seed : new long[] {11L, 20261011L, 987654321L}) {
            java.util.Random random = new java.util.Random(seed);
            UUID deck = shared.deck(owner, "Случайная " + seed);
            List<UUID> pool = new ArrayList<>();
            for (int index = 0; index < 7; index++) pool.add(asset());
            record Live(UUID member, UUID[] revision) { }
            List<Live> live = new ArrayList<>();
            Map<UUID, Integer> published = Map.of();
            boolean everPublished = false;
            for (int step = 0; step < 45; step++) {
                String where = "seed " + seed + " step " + step;
                int choice = random.nextInt(10);
                if (choice < 3 || live.isEmpty()) {
                    Material created = shared.create(owner, deck, MediaFixtures.imageDocument(pick(random, pool)));
                    live.add(new Live(created.member(), new UUID[] {created.revision()}));
                } else if (choice < 6) {
                    Live target = live.get(random.nextInt(live.size()));
                    target.revision()[0] = resave(deck, target.member(), target.revision()[0], pick(random, pool));
                } else if (choice < 7) {
                    Live target = live.remove(random.nextInt(live.size()));
                    MediaFixtures.deleteMaterial(decks, items, owner, deck, target.member(), target.revision()[0]);
                } else if (choice < 9) {
                    publish(deck);
                    everPublished = true;
                    published = fromHead(deck);
                    assertThat(set(deck)).as(where + ": published set equals a recompute from the head").isEqualTo(published);
                } else {
                    // a level change never moves the set
                    if (everPublished) level(deck, random.nextBoolean() ? "PRIVATE" : "INVITE");
                }
                if (everPublished) assertThat(set(deck)).as(where + ": unpublished edits never move the set").isEqualTo(published);
            }
            publish(deck);
            assertThat(set(deck)).as("seed " + seed + ": final").isEqualTo(fromHead(deck));
        }
        assertThat(drifts()).as("the incremental steps were exact, no repair was needed").isEqualTo(before);
    }

    @Test
    void theV55BackfillGivesExactlyTheIncrementallyMaintainedSetForEveryDeck() throws Exception {
        // a deck published, changed after it (so the backfill must undo the journal), a deck published at its head, a never published one
        UUID changed = shared.deck(owner, "Изменённая");
        UUID a1 = asset(), a2 = asset(), a3 = sound(), a4 = asset(), a5 = asset();
        Material m1 = shared.create(owner, changed, MediaFixtures.imageDocument(a1, a2));
        Material m2 = shared.create(owner, changed, MediaFixtures.imageDocument(a2, a2));
        UUID exercise = exerciseWithAudio(changed, a3);
        publish(changed);
        resave(changed, m1.member(), m1.revision(), a4);
        MediaFixtures.deleteMaterial(decks, items, owner, changed, m2.member(), m2.revision());
        exercises.delete(owner, changed, exercise, Long.parseLong(decks.read(owner, changed).path("rowVersion").stringValue(null)));
        Material created = shared.create(owner, changed, MediaFixtures.imageDocument(a5));
        assertThat(created.member()).isNotNull();
        UUID head = shared.deck(owner, "Голова");
        shared.create(owner, head, MediaFixtures.imageDocument(a1, a5));
        publish(head);
        UUID never = shared.deck(owner, "Не опубликована");
        shared.create(owner, never, MediaFixtures.imageDocument(a1));
        assertThat(set(changed)).isEqualTo(of(a1, 1, a2, 3));

        String migration;
        try (var stream = getClass().getResourceAsStream("/db/learning/migration/V55__shared_media.sql")) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String backfill = migration.substring(migration.indexOf("-- BACKFILL BEGIN"), migration.indexOf("-- BACKFILL END"));
        Map<String, Integer> before = new HashMap<>();
        Map<String, Integer> after = new HashMap<>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            collect(before);
            jdbc.sql("DELETE FROM app_learning.published_media").update();
            jdbc.sql(backfill).update();
            collect(after);
            status.setRollbackOnly();
        });
        assertThat(before).as("rows maintained by the publication command").isNotEmpty();
        assertThat(after).isEqualTo(before);
        assertThat(set(never)).isEmpty();
        assertThat(set(head)).isEqualTo(of(a1, 1, a5, 1));
    }

    private PublicationCommand publicCommand(UUID account, UUID deck) {
        var body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", "PUBLIC");
        body.putObject("metadata").put("topicId", "japanese").put("contentLanguage", "ru").put("targetLanguage", "ja").put("level", "A2").putArray("tags").add("jlpt n5");
        body.put("requestsEnabled", true);
        body.putObject("publish").put("expectedHeadRevisionId", decks.read(account, deck).path("revisionId").stringValue(null)).putNull("releaseNote");
        return PublicationCommand.read(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aCopyOwnerCannotMakePublicAnInheritedNonCommercialImageButCanOnceItIsGone() {
        // the provenance row is the AUTHOR's (he added the stock image); the copy's owner publishes a deck whose heads reference the author's asset
        UUID nc = asset();
        jdbc.sql("""
                INSERT INTO app_learning.generation_provenance(provenance_id, owner_id, session_id, artifact_id, revision_id, published_ref, created_at, media)
                VALUES (:id, :owner, :session, :artifact, :revision, '{}'::jsonb, now(), CAST(:media AS jsonb))
                """).param("id", UUID.randomUUID()).param("owner", owner).param("session", UUID.randomUUID()).param("artifact", UUID.randomUUID())
                .param("revision", UUID.randomUUID()).param("media", JSON.createArrayNode().add(JSON.createObjectNode().put("assetId", nc.toString())
                        .put("source", "PIXABAY").put("sourceId", "1").put("license", "CC BY-NC 4.0").put("sourcePageUrl", "https://example.org/1")).toString()).update();
        UUID deck = shared.deck(owner, "Источник NC");
        Material material = shared.create(owner, deck, MediaFixtures.imageDocument(nc));
        var copy = shared.copy(deck, UUID.randomUUID());

        var refused = org.junit.jupiter.api.Assertions.assertThrows(PublicationRequirementsException.class,
                () -> publications.save(copy.owner(), copy.deckId(), 0, publicCommand(copy.owner(), copy.deckId()), true));
        assertThat(refused.failed()).contains("blockedMedia");
        // the state of the copy names the blocked material
        JsonNode state = publications.read(copy.owner(), copy.deckId(), true);
        assertThat(state.path("checklist").path("blockedMedia")).hasSize(1);
        assertThat(state.path("checklist").path("blockedMedia").get(0).path("memberKey").stringValue(null)).isEqualTo(material.member().toString());

        // the copy drops the image: nothing blocks any more
        MediaFixtures.saveImages(decks, items, copy.owner(), copy.deckId(), material.member(), material.revision());
        assertThat(publications.read(copy.owner(), copy.deckId(), true).path("checklist").path("blockedMedia")).isEmpty();
        assertThat(publications.save(copy.owner(), copy.deckId(), 0, publicCommand(copy.owner(), copy.deckId()), true).replayed()).isFalse();
    }

    private void collect(Map<String, Integer> into) {
        jdbc.sql("SELECT deck_id || '/' || asset_id, ref_count FROM app_learning.published_media")
                .query((row, ignored) -> into.put(row.getString(1), row.getInt(2))).list();
    }
}
