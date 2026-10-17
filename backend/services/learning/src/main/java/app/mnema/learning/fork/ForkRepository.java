package app.mnema.learning.fork;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.mnema.learning.library.DeckPurpose;

import java.util.Optional;
import java.util.UUID;

/**
 * The rows of a copy: the O(1) creation statements, the one-live-copy lookup, the owner's reads and the two small commands (detach, replace a purged job). Every statement
 * that answers an account starts from the {@code deck} row with the owner predicate, so a stranger's deck id is simply absent.
 */
@Repository
class ForkRepository {
    private final JdbcClient jdbc;

    ForkRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Serializes ALL the copy commands of one account (transaction-scoped): the window and the cap below are counts, and two parallel requests for different
     * sources would otherwise both read the old count and overshoot it. Taken before either count; the unique index {@code deck_fork_live} is the net under the pair rule.
     */
    void lockActor(UUID owner) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "fork-actor:" + owner).query().listOfRows();
    }

    /** The creations of the account inside the sliding window (released copies included) and how long until the oldest of them leaves it. */
    record Window(int count, long retryAfterSeconds) { }

    Window window(UUID owner, java.time.Duration length) {
        return jdbc.sql("""
                SELECT count(*)::integer AS spent,
                       COALESCE(ceil(extract(epoch FROM (min(forked_at) + (:millis * interval '1 millisecond') - statement_timestamp())))::bigint, 0) AS retry
                  FROM app_learning.deck_fork WHERE owner_id = :owner AND forked_at > statement_timestamp() - (:millis * interval '1 millisecond')
                """).param("owner", owner).param("millis", length.toMillis())
                .query((row, ignored) -> new Window(row.getInt("spent"), row.getLong("retry"))).single();
    }

    /**
     * Freezes the publication of the source until the end of the transaction (a publication or a level change waits for it), and returns the published revision.
     * Two statements on purpose, like the publication command: the lock is taken on the publication row alone.
     */
    Optional<UUID> lockPublished(UUID source) {
        return jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id = :deck FOR SHARE")
                .param("deck", source).query(UUID.class).optional();
    }

    /** The published revision of a source as it is copied: what the copy's revision 0 repeats. */
    record Published(UUID revisionId, String title, String description, UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount) { }

    Published published(UUID source, UUID revision) {
        return jdbc.sql("""
                SELECT revision_id, title, description, members_root_id, exercises_root_id, member_count, exercise_count
                  FROM app_learning.deck_revision WHERE deck_id = :deck AND revision_id = :revision
                """).param("deck", source).param("revision", revision)
                .query((row, ignored) -> new Published(row.getObject("revision_id", UUID.class), row.getString("title"), row.getString("description"),
                        row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class),
                        row.getInt("member_count"), row.getInt("exercise_count"))).single();
    }

    /** The owner's live copy of the source, with whether it is still unfinished. */
    record Live(UUID deckId, boolean preparing) { }

    Optional<Live> live(UUID owner, UUID source) {
        return jdbc.sql("""
                SELECT f.deck_id, f.materialized_at IS NULL AS preparing
                  FROM app_learning.deck_fork f JOIN app_learning.deck d ON d.deck_id = f.deck_id
                 WHERE f.owner_id = :owner AND f.upstream_deck_id = :source AND f.released_at IS NULL AND d.deleted_at IS NULL
                """).param("owner", owner).param("source", source)
                .query((row, ignored) -> new Live(row.getObject("deck_id", UUID.class), row.getBoolean("preparing"))).optional();
    }

    /** How many live copies of the account are not ready yet (the cap; bounded by the partial index). */
    int unfinished(UUID owner) {
        return jdbc.sql("""
                SELECT count(*) FROM app_learning.deck_fork f JOIN app_learning.deck d ON d.deck_id = f.deck_id
                 WHERE f.owner_id = :owner AND f.materialized_at IS NULL AND f.released_at IS NULL AND d.deleted_at IS NULL
                """).param("owner", owner).query(Integer.class).single();
    }

    /**
     * How many copies of ALL accounts are not ready yet, counted up to {@code limit} (the global cap): the predicate is exactly that of the partial index
     * {@code deck_fork_unfinished}, so this is an index(-only) scan of at most {@code limit} entries whatever the table holds. {@code released_at} is set by the trigger that
     * deletes a copy, so a deleted copy is not counted and no join to {@code deck} is needed.
     */
    int unfinishedTotal(int limit) {
        return jdbc.sql("""
                SELECT count(*) FROM (SELECT 1 FROM app_learning.deck_fork WHERE materialized_at IS NULL AND released_at IS NULL LIMIT :limit) bounded
                """).param("limit", limit).query(Integer.class).single();
    }

    void insertDeck(UUID deck, UUID owner, UUID scope, UUID genesis, DeckPurpose purpose) {
        jdbc.sql("""
                INSERT INTO app_learning.deck(deck_id, owner_id, reuse_scope_id, head_revision_id, row_version, created_at, purpose)
                VALUES (:deck, :owner, :scope, :genesis, 0, statement_timestamp(), :purpose)
                """).param("deck", deck).param("owner", owner).param("scope", scope).param("genesis", genesis).param("purpose", purpose.name()).update();
    }

    /** The sample template as the system copy reads it: its lineage, public code and published revision. */
    record Template(UUID deckId, UUID scopeId, String code, UUID publishedRevisionId) { }

    /**
     * The sample template when it is ready to be copied: a live deck marked {@code SAMPLE_TEMPLATE} that is not hidden by moderation, published at {@code LINK} with a published
     * revision. One statement, so {@link #templateReady} and the copy agree. The publication row is locked {@code FOR SHARE} when {@code lock} is set (a republication or a level
     * change waits for the copy's transaction, like the ordinary copy).
     */
    Optional<Template> template(UUID deck, boolean lock) {
        return jdbc.sql("""
                SELECT d.deck_id, d.reuse_scope_id, p.public_code, p.published_revision_id
                  FROM app_learning.deck d JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id
                 WHERE d.deck_id = :deck AND d.deleted_at IS NULL AND d.purpose = 'SAMPLE_TEMPLATE'
                   AND p.visibility = 'LINK' AND p.published_revision_id IS NOT NULL AND NOT p.hidden_by_moderation
                """ + (lock ? " FOR SHARE OF p" : "")).param("deck", deck)
                .query((row, ignored) -> new Template(row.getObject("deck_id", UUID.class), row.getObject("reuse_scope_id", UUID.class), row.getString("public_code"),
                        row.getObject("published_revision_id", UUID.class))).optional();
    }

    void insertGenesis(UUID deck, UUID genesis, UUID scope, UUID owner, UUID command, Published source, UUID membersPin, UUID exercisesPin) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_revision(deck_id, revision_id, reuse_scope_id, owner_id, sequence, command_id, title, description,
                    created_at, members_root_id, exercises_root_id, members_pin_id, exercises_pin_id, member_count, exercise_count)
                VALUES (:deck, :genesis, :scope, :owner, 0, :command, :title, :description, statement_timestamp(), :members, :exercises,
                    :membersPin, :exercisesPin, :memberCount, :exerciseCount)
                """).param("deck", deck).param("genesis", genesis).param("scope", scope).param("owner", owner).param("command", command)
                .param("title", source.title()).param("description", source.description()).param("members", source.membersRootId())
                .param("exercises", source.exercisesRootId()).param("membersPin", membersPin).param("exercisesPin", exercisesPin)
                .param("memberCount", source.memberCount()).param("exerciseCount", source.exerciseCount()).update();
    }

    void insertFork(UUID deck, UUID scope, UUID owner, UUID upstream, String code, UUID base, UUID job) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_fork(deck_id, reuse_scope_id, owner_id, upstream_deck_id, upstream_code, base_revision_id, forked_at, job_id)
                VALUES (:deck, :scope, :owner, :upstream, :code, :base, statement_timestamp(), :job)
                """).param("deck", deck).param("scope", scope).param("owner", owner).param("upstream", upstream).param("code", code)
                .param("base", base).param("job", job).update();
    }

    /** The owner's live copy with its job and flags; absent for a foreign, deleted or ordinary deck. */
    record Own(UUID deckId, UUID jobId, boolean materialized, boolean detached, boolean autoUpdate, String jobState) { }

    Optional<Own> own(UUID owner, UUID deck) {
        return jdbc.sql("""
                SELECT f.deck_id, f.job_id, f.materialized_at IS NOT NULL AS materialized, f.detached_at IS NOT NULL AS detached, f.auto_update, j.state AS job_state
                  FROM app_learning.deck d JOIN app_learning.deck_fork f ON f.deck_id = d.deck_id
                  LEFT JOIN app_learning.learning_job j ON j.job_id = f.job_id
                 WHERE d.owner_id = :owner AND d.deck_id = :deck AND d.deleted_at IS NULL
                """).param("owner", owner).param("deck", deck)
                .query((row, ignored) -> new Own(row.getObject("deck_id", UUID.class), row.getObject("job_id", UUID.class), row.getBoolean("materialized"),
                        row.getBoolean("detached"), row.getBoolean("auto_update"), row.getString("job_state"))).optional();
    }

    /** Sets the detach time once; false when the copy was already detached (the call is idempotent either way). */
    boolean detach(UUID deck) {
        return jdbc.sql("UPDATE app_learning.deck_fork SET detached_at = statement_timestamp() WHERE deck_id = :deck AND detached_at IS NULL")
                .param("deck", deck).update() == 1;
    }

    /**
     * Stores the owner's auto-update setting on a live copy that is ready and attached; false when no such row changed (the copy is not ready, is detached, or already has the value).
     * The predicate repeats the transition guard of V64, so a concurrent detach turns into "no row" and the service answers its own 409 instead of a database error.
     */
    boolean setAutoUpdate(UUID deck, boolean value) {
        return jdbc.sql("""
                UPDATE app_learning.deck_fork SET auto_update = :value
                 WHERE deck_id = :deck AND auto_update <> :value AND materialized_at IS NOT NULL AND detached_at IS NULL AND released_at IS NULL
                """).param("deck", deck).param("value", value).update() == 1;
    }

    /**
     * The re-link: points an attached live copy at {@code code}, the public code that now opens its source, when it stored another one. The guard of V64 accepts exactly the source's
     * current code, so the statement can never point a copy at another deck's code; zero rows means the copy already holds this code (or is detached).
     */
    boolean relink(UUID deck, String code) {
        return jdbc.sql("""
                UPDATE app_learning.deck_fork SET upstream_code = :code
                 WHERE deck_id = :deck AND upstream_code <> :code AND detached_at IS NULL AND released_at IS NULL
                """).param("deck", deck).param("code", code).update() == 1;
    }

    /** Points an unfinished copy at a freshly enqueued job (the previous one was purged by retention). */
    void replaceJob(UUID deck, UUID job) {
        jdbc.sql("UPDATE app_learning.deck_fork SET job_id = :job WHERE deck_id = :deck AND materialized_at IS NULL")
                .param("deck", deck).param("job", job).update();
    }

    /** The viewer's live copy of exactly this source ({@code myCopy}); one probe of {@code deck_scope_owner}. */
    Optional<Live> myCopy(UUID viewer, UUID scope, UUID source) {
        return jdbc.sql("""
                SELECT d.deck_id, f.materialized_at IS NULL AS preparing
                  FROM app_learning.deck d JOIN app_learning.deck_fork f ON f.deck_id = d.deck_id
                 WHERE d.reuse_scope_id = :scope AND d.owner_id = :viewer AND d.deleted_at IS NULL
                   AND f.upstream_deck_id = :source AND f.released_at IS NULL
                """).param("scope", scope).param("viewer", viewer).param("source", source)
                .query((row, ignored) -> new Live(row.getObject("deck_id", UUID.class), row.getBoolean("preparing"))).optional();
    }

    /** Whether the copy is not materialized yet; the owner predicate makes a stranger's id simply "not preparing" (the route then answers its own 404). */
    boolean preparing(UUID owner, UUID deck) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM app_learning.deck_fork f JOIN app_learning.deck d ON d.deck_id = f.deck_id
                                WHERE f.deck_id = :deck AND d.owner_id = :owner AND d.deleted_at IS NULL AND f.materialized_at IS NULL)
                """).param("deck", deck).param("owner", owner).query(Boolean.class).single();
    }
}
