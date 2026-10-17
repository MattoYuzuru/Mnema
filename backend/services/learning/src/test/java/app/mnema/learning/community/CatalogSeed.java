package app.mnema.learning.community;

import app.mnema.learning.library.AliasNormalizer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synthetic rows of the catalogue for the tests of the routes: {@code public_deck} rows and rank generations written directly, so that a test controls every column (the projector and
 * the rank job have their own tests). The foreign keys of {@code public_deck} are not checked while a row is written ({@code session_replication_role = replica}); the routes under test
 * read the columns and never the decks behind them. {@code title_norm} is computed like the projector does.
 */
final class CatalogSeed {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a test says about a deck; everything else gets a plausible value. */
    static final class Spec {
        String title = "Колода";
        String description = "Описание колоды";
        String topic;
        String contentLanguage;
        String targetLanguage;
        String level;
        List<String> tags = List.of();
        int items = 20;
        int exercises = 5;
        int mediaMask;
        int mechanicsMask;
        UUID owner = UUID.randomUUID();
        Instant firstListedAt = Instant.parse("2026-09-01T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-10-01T00:00:00Z");
        String code;

        Spec title(String value) { title = value; return this; }
        Spec description(String value) { description = value; return this; }
        Spec topic(String value) { topic = value; return this; }
        Spec content(String value) { contentLanguage = value; return this; }
        Spec target(String value) { targetLanguage = value; return this; }
        Spec level(String value) { level = value; return this; }
        Spec tags(String... value) { tags = List.of(value); return this; }
        Spec items(int value) { items = value; return this; }
        Spec exercises(int value) { exercises = value; return this; }
        Spec media(int value) { mediaMask = value; return this; }
        Spec mechanics(int value) { mechanicsMask = value; return this; }
        Spec owner(UUID value) { owner = value; return this; }
        Spec listedAt(Instant value) { firstListedAt = value; return this; }
        Spec updatedAt(Instant value) { updatedAt = value; return this; }
        Spec code(String value) { code = value; return this; }
    }

    /** A written row: the deck, its code, and whether the threshold lists it. */
    record Deck(UUID id, String code, UUID owner, boolean listed) { }

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    CatalogSeed(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    static Spec deck(String title) { return new Spec().title(title); }

    static String code() {
        char[] code = new char[10];
        for (int index = 0; index < code.length; index++) code[index] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        return new String(code);
    }

    Deck insert(Spec spec) {
        return insertAll(List.of(spec)).getFirst();
    }

    List<Deck> insertAll(List<Spec> specs) {
        return transaction.execute(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            return specs.stream().map(this::write).toList();
        });
    }

    private Deck write(Spec spec) {
        UUID id = UUID.randomUUID();
        String code = spec.code == null ? code() : spec.code;
        String parent = spec.topic == null ? null : jdbc.sql("SELECT parent_id FROM app_learning.topic WHERE topic_id = :t").param("t", spec.topic).query(String.class).optional().orElse(null);
        boolean listed = spec.items >= 10 && spec.exercises >= 1 && !spec.description.isBlank();
        jdbc.sql("""
                INSERT INTO app_learning.public_deck(deck_id, public_code, owner_id, published_revision_id, title, description, topic_id, parent_topic_id, content_language, target_language, level,
                                                     tags, item_count, exercise_count, media_mask, mechanics_mask, first_listed_at, content_updated_at, title_norm, projected_at)
                VALUES (:id, :code, :owner, :revision, :title, :description, :topic, :parent, :content, :target, :level, CAST(:tags AS text[]), :items, :exercises, :media, :mechanics,
                        :firstListed, :updated, :norm, CURRENT_TIMESTAMP)
                """).param("id", id).param("code", code).param("owner", spec.owner).param("revision", UUID.randomUUID()).param("title", spec.title).param("description", spec.description)
                .param("topic", spec.topic).param("parent", parent).param("content", spec.contentLanguage).param("target", spec.targetLanguage).param("level", spec.level)
                .param("tags", "{" + String.join(",", spec.tags.stream().map(tag -> "\"" + tag.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()) + "}")
                .param("items", spec.items).param("exercises", spec.exercises).param("media", spec.mediaMask).param("mechanics", spec.mechanicsMask)
                .param("firstListed", listed ? Timestamp.from(spec.firstListedAt) : null).param("updated", Timestamp.from(spec.updatedAt)).param("norm", AliasNormalizer.normalize(spec.title)).update();
        return new Deck(id, code, spec.owner, listed);
    }

    /** What a test says about a deck's rank in a generation (the order is the caller's: rank 1 is the best). */
    record Rank(UUID deck, int rank, int learningRank, double score, double popularity, int added, int learningNow) { }

    /** Writes a COMPLETED generation holding exactly these ranks; the topic columns are copied from the deck rows like the rank job does. */
    void generation(long generation, Instant completedAt, List<Rank> ranks) {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("INSERT INTO app_learning.rank_generation(generation, started_at, completed_at, deck_count) VALUES (:g, :at, :at, :n)")
                    .param("g", generation).param("at", Timestamp.from(completedAt)).param("n", ranks.size()).update();
            for (Rank rank : ranks) {
                jdbc.sql("""
                        INSERT INTO app_learning.deck_rank(generation, deck_id, score, rank, learning_rank, pop_pct, added_count, learning_now_count, activated_count, topic_id, parent_topic_id, listed)
                        SELECT :g, d.deck_id, :score, CASE WHEN d.listed THEN :rank END, CASE WHEN d.listed THEN :learningRank END, CASE WHEN d.listed THEN :pop ELSE 0 END, :added, :learning, 0,
                               d.topic_id, d.parent_topic_id, d.listed FROM app_learning.public_deck d WHERE d.deck_id = :deck
                        """).params(Map.of("g", generation, "score", rank.score(), "rank", rank.rank(), "learningRank", rank.learningRank(), "pop", (float) rank.popularity(),
                        "added", rank.added(), "learning", rank.learningNow(), "deck", rank.deck())).update();
            }
        });
    }

    /** Ranks the listed decks in the order given (best first), with the given popularity percentile and audience for each; unlisted decks get rows without a rank. */
    void rankInOrder(long generation, Instant completedAt, List<Deck> best) {
        int[] position = {0};
        generation(generation, completedAt, best.stream().map(deck -> {
            int rank = ++position[0];
            return new Rank(deck.id(), rank, rank, 1.0 / rank, best.size() == 1 ? 1.0 : 1.0 - (rank - 1.0) / (best.size() - 1.0), 0, 0);
        }).toList());
    }
}
