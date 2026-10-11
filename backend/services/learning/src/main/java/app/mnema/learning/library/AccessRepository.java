package app.mnema.learning.library;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the invitation machinery: invitation links, access requests and the listing of grants (Share/13). Every method takes the time it should use
 * ({@code now} comes from the service's clock, so a test can move it), none opens a transaction, and the owner predicate is the caller's: it runs
 * {@link LibraryRepository#ownsLiveDeck} first. Statements that take a lock say so; they are used inside the caller's transaction only.
 */
@Repository
class AccessRepository {
    /** An invitation joined with its deck, as redeem needs it; locked until the end of the transaction. */
    record RedeemTarget(UUID inviteId, UUID deckId, UUID ownerId, Integer maxUses, int uses, Instant expiresAt, Instant revokedAt,
                        DeckVisibility visibility, String publicCode, boolean published, boolean currentGeneration) { }

    record Grant(UUID granteeId, GrantRole role, Instant grantedAt) { }

    record Request(UUID deckId, UUID requesterId, String status, Instant createdAt, Instant decidedAt, Instant revokedAt) { }

    /** An invitation with its generation: stale when the deck's code was rotated after the invitation was created. */
    private static final String INVITE_SELECT = """
            SELECT i.invite_id, i.deck_id, i.max_uses, i.uses, i.expires_at, i.revoked_at, i.created_at, i.code_rotated_at <> p.code_rotated_at AS stale
              FROM app_learning.deck_invite i JOIN app_learning.deck_publication p ON p.deck_id = i.deck_id
            """;
    private static final RowMapper<Invite> INVITE = (row, ignored) -> new Invite(row.getObject("invite_id", UUID.class),
            row.getObject("deck_id", UUID.class), row.getObject("max_uses", Integer.class), row.getInt("uses"),
            instant(row.getTimestamp("expires_at")), instant(row.getTimestamp("revoked_at")), row.getTimestamp("created_at").toInstant(), row.getBoolean("stale"));
    private static final String REQUEST_COLUMNS = "deck_id, requester_id, status, created_at, decided_at, revoked_at";
    private static final RowMapper<Grant> GRANT = (row, ignored) -> new Grant(row.getObject("grantee_id", UUID.class),
            GrantRole.valueOf(row.getString("role")), row.getTimestamp("granted_at").toInstant());
    private static final RowMapper<Request> REQUEST = (row, ignored) -> new Request(row.getObject("deck_id", UUID.class),
            row.getObject("requester_id", UUID.class), row.getString("status"), row.getTimestamp("created_at").toInstant(),
            instant(row.getTimestamp("decided_at")), instant(row.getTimestamp("revoked_at")));

    private final JdbcClient jdbc;

    AccessRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }

    /** A transaction-scoped advisory lock on a named resource: serializes the commands that must not interleave (the limits are counts). */
    void lock(String resource, UUID id) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) lock").param("key", resource + ":" + id).query(Integer.class).single();
    }

    // ---- invitations ----

    /** The invitations of the deck that can still be redeemed (not revoked, not expired, with uses left, and of the CURRENT code generation). */
    int activeInvites(UUID deck, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM app_learning.deck_invite i JOIN app_learning.deck_publication p ON p.deck_id = i.deck_id AND p.code_rotated_at = i.code_rotated_at
                 WHERE i.deck_id = :deck AND i.revoked_at IS NULL AND (i.expires_at IS NULL OR i.expires_at > :now) AND (i.max_uses IS NULL OR i.uses < i.max_uses)
                """).param("deck", deck).param("now", time(now)).query(Integer.class).single();
    }

    int invites(UUID deck) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck_invite WHERE deck_id = :deck").param("deck", deck).query(Integer.class).single();
    }

    /**
     * Deletes invitations that DIED before {@code cutoff}: the time of death is the revocation, the expiry, the last use of an exhausted invitation, or
     * the last rotation of the code for an invitation of an older generation. The grants they made are not touched.
     */
    int pruneDeadInvites(UUID deck, Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM app_learning.deck_invite i USING app_learning.deck_publication p
                 WHERE i.deck_id = :deck AND p.deck_id = i.deck_id
                   AND ((i.revoked_at IS NOT NULL AND i.revoked_at < :cutoff)
                     OR (i.expires_at IS NOT NULL AND i.expires_at < :cutoff)
                     OR (i.max_uses IS NOT NULL AND i.uses >= i.max_uses AND i.last_used_at < :cutoff)
                     OR (i.code_rotated_at <> p.code_rotated_at AND p.code_rotated_at < :cutoff))
                """).param("deck", deck).param("cutoff", time(cutoff)).update();
    }

    /** Creates the invitation under the deck's CURRENT code generation (copied from its publication row) and returns it. */
    Invite insertInvite(UUID deck, byte[] tokenHash, Integer maxUses, Instant expiresAt, Instant now) {
        UUID id = jdbc.sql("""
                INSERT INTO app_learning.deck_invite(deck_id, token_hash, max_uses, expires_at, created_at, code_rotated_at)
                SELECT p.deck_id, CAST(:hash AS bytea), CAST(:max AS integer), CAST(:expires AS timestamptz), CAST(:now AS timestamptz), p.code_rotated_at
                  FROM app_learning.deck_publication p WHERE p.deck_id = :deck
                RETURNING invite_id
                """).param("deck", deck).param("hash", tokenHash).param("max", maxUses, java.sql.Types.INTEGER)
                .param("expires", time(expiresAt), java.sql.Types.TIMESTAMP).param("now", time(now)).query(UUID.class).single();
        return jdbc.sql(INVITE_SELECT + " WHERE i.invite_id = :id").param("id", id).query(INVITE).single();
    }

    /** Newest first; one more row than {@code limit} tells the caller whether another page exists. */
    List<Invite> pageInvites(UUID deck, AccessCursor after, int limit) {
        return jdbc.sql(INVITE_SELECT + """
                 WHERE i.deck_id = :deck AND (CAST(:after AS timestamptz) IS NULL OR (i.created_at, i.invite_id) < (CAST(:after AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY i.created_at DESC, i.invite_id DESC LIMIT :limit
                """).param("deck", deck).param("after", after == null ? null : time(after.time()), java.sql.Types.TIMESTAMP)
                .param("afterId", after == null ? null : after.id(), java.sql.Types.OTHER).param("limit", limit + 1).query(INVITE).list();
    }

    Optional<Invite> lockInvite(UUID deck, UUID invite) {
        return jdbc.sql(INVITE_SELECT + " WHERE i.deck_id = :deck AND i.invite_id = :invite FOR UPDATE OF i")
                .param("deck", deck).param("invite", invite).query(INVITE).optional();
    }

    Invite revokeInvite(UUID invite, Instant now) {
        jdbc.sql("UPDATE app_learning.deck_invite SET revoked_at = :now WHERE invite_id = :invite").param("now", time(now)).param("invite", invite).update();
        return jdbc.sql(INVITE_SELECT + " WHERE i.invite_id = :invite").param("invite", invite).query(INVITE).single();
    }

    /** Locks the invitation behind a token digest (and nothing else), joined with its live deck and publication. Empty for an unknown digest or a deleted deck. */
    Optional<RedeemTarget> lockInviteByHash(byte[] tokenHash) {
        return jdbc.sql("""
                SELECT i.invite_id, i.deck_id, d.owner_id, i.max_uses, i.uses, i.expires_at, i.revoked_at,
                       COALESCE(p.visibility, 'PRIVATE') AS visibility, p.public_code, p.published_revision_id IS NOT NULL AS published,
                       COALESCE(i.code_rotated_at = p.code_rotated_at, FALSE) AS current_generation
                  FROM app_learning.deck_invite i
                  JOIN app_learning.deck d ON d.deck_id = i.deck_id AND d.deleted_at IS NULL
                  LEFT JOIN app_learning.deck_publication p ON p.deck_id = i.deck_id
                 WHERE i.token_hash = :hash
                   FOR UPDATE OF i
                """).param("hash", tokenHash).query((row, ignored) -> new RedeemTarget(row.getObject("invite_id", UUID.class),
                row.getObject("deck_id", UUID.class), row.getObject("owner_id", UUID.class), row.getObject("max_uses", Integer.class),
                row.getInt("uses"), instant(row.getTimestamp("expires_at")), instant(row.getTimestamp("revoked_at")),
                DeckVisibility.valueOf(row.getString("visibility")), row.getString("public_code"), row.getBoolean("published"),
                row.getBoolean("current_generation"))).optional();
    }

    void consumeInvite(UUID invite, Instant now) {
        jdbc.sql("UPDATE app_learning.deck_invite SET uses = uses + 1, last_used_at = :now WHERE invite_id = :invite").param("now", time(now)).param("invite", invite).update();
    }

    // ---- grants ----

    /** Maximum people with access to one deck. */
    static final int GRANT_LIMIT = 1000;

    /**
     * Serializes everything that ADDS a grant to the deck, so the cap is exact. Lock order of the access code, outermost first: the invitation row,
     * this lock, the request row, the grant row; no path takes them the other way round.
     */
    void lockGrants(UUID deck) { lock("deck.grants", deck); }

    /**
     * Adds a viewer under {@link #lockGrants}.
     *
     * @return whether the grant was created (an existing one is kept as it is and counts against nothing)
     * @throws app.mnema.learning.platform.api.ResourceLimitExceededException the deck already has {@value #GRANT_LIMIT} people with access
     */
    boolean addGrant(UUID deck, UUID grantee, UUID by, Instant now) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.deck_access_grant WHERE deck_id = :deck AND grantee_id = :grantee)")
                .param("deck", deck).param("grantee", grantee).query(Boolean.class).single();
        if (exists) return false;
        int people = jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM app_learning.deck_access_grant WHERE deck_id = :deck LIMIT :cap) held")
                .param("deck", deck).param("cap", GRANT_LIMIT).query(Integer.class).single();
        if (people >= GRANT_LIMIT) {
            throw new app.mnema.learning.platform.api.ResourceLimitExceededException(app.mnema.learning.platform.api.ProblemExtension.limit(GRANT_LIMIT));
        }
        return jdbc.sql("""
                INSERT INTO app_learning.deck_access_grant(deck_id, grantee_id, role, granted_by, granted_at)
                VALUES (:deck, :grantee, 'VIEWER', :by, :now) ON CONFLICT (deck_id, grantee_id) DO NOTHING
                """).param("deck", deck).param("grantee", grantee).param("by", by).param("now", time(now)).update() == 1;
    }

    Grant grant(UUID deck, UUID grantee) {
        return jdbc.sql("SELECT grantee_id, role, granted_at FROM app_learning.deck_access_grant WHERE deck_id = :deck AND grantee_id = :grantee")
                .param("deck", deck).param("grantee", grantee).query(GRANT).single();
    }

    List<Grant> pageGrants(UUID deck, AccessCursor after, int limit) {
        return jdbc.sql("""
                SELECT grantee_id, role, granted_at FROM app_learning.deck_access_grant
                 WHERE deck_id = :deck AND (CAST(:after AS timestamptz) IS NULL OR (granted_at, grantee_id) < (CAST(:after AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY granted_at DESC, grantee_id DESC LIMIT :limit
                """).param("deck", deck).param("after", after == null ? null : time(after.time()), java.sql.Types.TIMESTAMP)
                .param("afterId", after == null ? null : after.id(), java.sql.Types.OTHER).param("limit", limit + 1).query(GRANT).list();
    }

    // ---- requests ----

    boolean requestsEnabled(UUID deck) {
        return jdbc.sql("SELECT COALESCE((SELECT requests_enabled FROM app_learning.deck_publication WHERE deck_id = :deck), TRUE)")
                .param("deck", deck).query(Boolean.class).single();
    }

    Optional<Request> lockRequest(UUID deck, UUID requester) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + " FROM app_learning.deck_access_request WHERE deck_id = :deck AND requester_id = :requester FOR UPDATE")
                .param("deck", deck).param("requester", requester).query(REQUEST).optional();
    }

    /** The requester's open requests on decks that still exist. */
    int openRequests(UUID requester) {
        return jdbc.sql("""
                SELECT count(*) FROM app_learning.deck_access_request r JOIN app_learning.deck d ON d.deck_id = r.deck_id AND d.deleted_at IS NULL
                 WHERE r.requester_id = :requester AND r.status = 'PENDING'
                """).param("requester", requester).query(Integer.class).single();
    }

    /** Creation times of the requester's own requests newer than {@code since}, oldest first (a row an owner's revocation created for a person who never asked is no request). */
    List<Instant> requestTimesSince(UUID requester, Instant since) {
        return jdbc.sql("SELECT created_at FROM app_learning.deck_access_request WHERE requester_id = :requester AND created_at > :since AND (revoked_at IS NULL OR created_at <> revoked_at) ORDER BY created_at")
                .param("requester", requester).param("since", time(since)).query((row, ignored) -> row.getTimestamp("created_at").toInstant()).list();
    }

    /** A new request, or the repeat of an old decided one: the one row of the pair becomes PENDING again. */
    void openRequest(UUID deck, UUID requester, Instant now) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_access_request(deck_id, requester_id, status, created_at)
                VALUES (:deck, :requester, 'PENDING', :now)
                ON CONFLICT (deck_id, requester_id) DO UPDATE SET status = 'PENDING', created_at = EXCLUDED.created_at, decided_at = NULL, revoked_at = NULL
                """).param("deck", deck).param("requester", requester).param("now", time(now)).update();
    }

    /** The open requests of the deck, counted up to {@code cap} rows (an index range, never the whole backlog). */
    int pendingRequests(UUID deck, int cap) {
        return jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM app_learning.deck_access_request WHERE deck_id = :deck AND status = 'PENDING' LIMIT :cap) pending")
                .param("deck", deck).param("cap", cap).query(Integer.class).single();
    }

    List<Request> pagePending(UUID deck, AccessCursor after, int limit) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + """
                  FROM app_learning.deck_access_request
                 WHERE deck_id = :deck AND status = 'PENDING'
                   AND (CAST(:after AS timestamptz) IS NULL OR (created_at, requester_id) < (CAST(:after AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY created_at DESC, requester_id DESC LIMIT :limit
                """).param("deck", deck).param("after", after == null ? null : time(after.time()), java.sql.Types.TIMESTAMP)
                .param("afterId", after == null ? null : after.id(), java.sql.Types.OTHER).param("limit", limit + 1).query(REQUEST).list();
    }

    /** Moves a request that has the {@code from} status to {@code to}; the decision time never precedes the creation time. */
    boolean decide(UUID deck, UUID requester, String from, String to, Instant now) {
        return jdbc.sql("""
                UPDATE app_learning.deck_access_request SET status = :to, decided_at = GREATEST(:now, created_at)
                 WHERE deck_id = :deck AND requester_id = :requester AND status = :from
                """).param("to", to).param("now", time(now)).param("deck", deck).param("requester", requester).param("from", from).update() == 1;
    }

    /**
     * The owner revoked the person's grant: a REJECTED row decided now and marked as a revocation (created if the person never asked), which keeps
     * an invitation link from letting them straight back in for 30 days. Takes the request row lock, so it comes BEFORE the grant is deleted.
     */
    void stickRevocation(UUID deck, UUID person, Instant now) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_access_request(deck_id, requester_id, status, created_at, decided_at, revoked_at)
                VALUES (:deck, :person, 'REJECTED', :now, :now, :now)
                ON CONFLICT (deck_id, requester_id) DO UPDATE SET status = 'REJECTED', decided_at = GREATEST(EXCLUDED.decided_at, deck_access_request.created_at),
                    revoked_at = EXCLUDED.revoked_at
                """).param("deck", deck).param("person", person).param("now", time(now)).update();
    }

    /** The owner added the person on purpose (by login): a revocation no longer stands in the way of their invitation links. */
    void clearRevocation(UUID deck, UUID person) {
        jdbc.sql("UPDATE app_learning.deck_access_request SET revoked_at = NULL WHERE deck_id = :deck AND requester_id = :person AND revoked_at IS NOT NULL")
                .param("deck", deck).param("person", person).update();
    }
}
