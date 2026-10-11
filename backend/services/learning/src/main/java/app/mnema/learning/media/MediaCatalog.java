package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Transactional identities, references and owner authorization; object transfer lives outside this boundary. */
@Service
public class MediaCatalog {
    /** Upper bound of distinct assets one exercise revision may pin. */
    public static final int MAX_EXERCISE_ASSETS = 32;

    private final JdbcClient jdbc;
    private final MediaSettings settings;

    public MediaCatalog(JdbcClient jdbc, MediaSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** Reserve a server-selected identity before any bytes are transferred. */
    @Transactional
    public UUID reserve(UUID owner, UUID uploadIntent, Origin origin) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(uploadIntent, "uploadIntentId");
        if (origin == null) throw new InvalidRequestException();
        UUID asset = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,created_at,updated_at) "
                        + "VALUES (:asset,:owner,:intent,:origin,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (owner_id,upload_intent_id) DO NOTHING")
                .param("asset", asset).param("owner", owner).param("intent", uploadIntent)
                .param("origin", origin.name().toLowerCase(java.util.Locale.ROOT)).update();
        Reservation reservation = jdbc.sql("SELECT asset_id,origin FROM app_learning.media_asset "
                        + "WHERE owner_id=:owner AND upload_intent_id=:intent")
                .param("owner", owner).param("intent", uploadIntent)
                .query((row, ignored) -> new Reservation((UUID) row.getObject("asset_id"), row.getString("origin")))
                .single();
        if (!reservation.origin().equals(origin.name().toLowerCase(java.util.Locale.ROOT))) {
            throw new IdempotencyConflictException();
        }
        return reservation.assetId();
    }

    /** A stale worker generation must never replace bytes from a newer upload attempt. */
    @Transactional
    public boolean ready(UUID asset, long generation, UUID verifiedBlob) {
        if (generation < 0) throw new InvalidRequestException();
        UuidPolicy.requireEntityId(asset, "assetId");
        UuidPolicy.requireEntityId(verifiedBlob, "blobId");
        return jdbc.sql("UPDATE app_learning.media_asset SET state='READY', source_blob_id=:blob, "
                        + "owner_hold_until=CURRENT_TIMESTAMP + (:holdSeconds * interval '1 second'), "
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE asset_id=:asset AND generation=:generation "
                        + "AND state IN ('VERIFYING','PROCESSING')")
                .param("asset", asset).param("generation", generation).param("blob", verifiedBlob)
                .param("holdSeconds", settings.unattachedReadyHold().toSeconds()).update() == 1;
    }

    /**
     * #237 must call this inside the transaction that inserts the item revision. The reference belongs to the revision
     * (the lineage); {@code asset_owner_id} is the owner of the asset, which is not the actor's when a copy keeps an inherited asset.
     *
     * <p>An attached asset is the actor's own, or one that the head revisions of THIS deck already reference (a copy's owner who edits a material keeps the
     * author's image). The check runs before the head moves, so the revision being replaced still counts; an asset only the replaced revision held and that
     * an earlier change of the same command already dropped is refused (fail closed).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachRevision(UUID actor, UUID deck, UUID member, UUID revision, Collection<Reference> references) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(member, "memberKey");
        UuidPolicy.requireEntityId(revision, "revisionId");
        // The revision just written by this deck's command: found through the deck's scope (primary key), origin checked.
        UUID scope = jdbc.sql("SELECT r.reuse_scope_id FROM app_learning.deck d JOIN app_learning.item_revision r "
                        + "ON r.reuse_scope_id=d.reuse_scope_id AND r.member_key=:member AND r.revision_id=:revision "
                        + "WHERE d.deck_id=:deck AND r.deck_id=d.deck_id AND r.owner_id=:owner")
                .param("deck", deck).param("member", member).param("revision", revision).param("owner", actor)
                .query(UUID.class).optional().orElseThrow(ResourceNotFoundException::new);
        Map<UUID, UUID> owners = validateReferences(actor, deck, references);
        for (Reference reference : references) jdbc.sql("INSERT INTO app_learning.content_media_ref "
                        + "(deck_id,reuse_scope_id,member_key,revision_id,node_id,asset_owner_id,asset_id) "
                        + "VALUES (:deck,:scope,:member,:revision,:node,:owner,:asset)")
                .param("deck", deck).param("scope", scope).param("member", member).param("revision", revision)
                .param("node", reference.nodeId()).param("owner", owners.get(reference.assetId()))
                .param("asset", reference.assetId()).update();
    }

    /** Pin every media block of every slot atomically with one immutable exercise revision. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachExerciseRevision(UUID actor, UUID deck, UUID exercise, UUID revision,
                                       Collection<ExerciseAsset> assets) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(exercise, "exerciseId");
        UuidPolicy.requireEntityId(revision, "revisionId");
        if (assets == null || assets.isEmpty() || assets.size() > MAX_EXERCISE_ASSETS
                || assets.stream().anyMatch(java.util.Objects::isNull)) {
            throw new InvalidRequestException();
        }
        // The revision is a lineage row; it is attachable by the account whose deck wrote it: the revision's origin deck is this deck and the deck is the actor's.
        if (!jdbc.sql("SELECT 1 FROM app_learning.deck d "
                        + "JOIN app_learning.exercise_revision r ON r.reuse_scope_id=d.reuse_scope_id "
                        + "AND r.exercise_id=:exercise AND r.revision_id=:revision AND r.deck_id=d.deck_id "
                        + "WHERE d.deck_id=:deck AND d.owner_id=:owner")
                .param("deck", deck).param("exercise", exercise).param("revision", revision)
                .param("owner", actor).query(Integer.class).optional().isPresent()) {
            throw new ResourceNotFoundException();
        }
        var kinds = new java.util.TreeMap<UUID, Kind>();
        for (ExerciseAsset asset : assets) {
            // One logical asset has one kind inside an exercise revision.
            if (kinds.putIfAbsent(asset.assetId(), asset.kind()) != null) throw new InvalidRequestException();
        }
        Map<UUID, UUID> owners = validateAssets(actor, deck, kinds.keySet());
        kinds.forEach((asset, kind) -> requireDeclaredKind(asset, kind));
        kinds.forEach((asset, kind) -> jdbc.sql("INSERT INTO app_learning.exercise_media_ref "
                        + "(deck_id,reuse_scope_id,exercise_id,exercise_revision_id,asset_owner_id,asset_id,media_kind) "
                        + "SELECT d.deck_id,d.reuse_scope_id,:exercise,:revision,:owner,:asset,:kind "
                        + "FROM app_learning.deck d WHERE d.deck_id=:deck")
                .param("deck", deck).param("exercise", exercise).param("revision", revision)
                .param("owner", owners.get(asset)).param("asset", asset).param("kind", kind.column()).update());
    }

    /**
     * A READY asset whose verified source is not the declared kind is rejected at publication. Assets that are
     * still uploading stay allowed: readiness is re-checked at selection, pair-check and submit.
     */
    private void requireDeclaredKind(UUID asset, Kind kind) {
        String mime = jdbc.sql("SELECT b.mime_type FROM app_learning.media_asset a "
                        + "JOIN app_learning.media_blob b ON b.blob_id=a.source_blob_id "
                        + "WHERE a.asset_id=:asset AND a.state='READY'")
                .param("asset", asset).query(String.class).optional().orElse(null);
        if (mime != null && !mime.startsWith(kind.column() + "/")) throw new InvalidRequestException();
    }

    /**
     * An issued presentation can be assessed only while every pinned asset is READY and its verified
     * source really is the declared kind; a READY image must never be played as audio.
     */
    @Transactional(readOnly = true)
    public boolean exerciseMediaReady(UUID actor, UUID deck, UUID exercise, UUID revision) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(exercise, "exerciseId");
        UuidPolicy.requireEntityId(revision, "revisionId");
        // readiness is a property of the lineage revision; the deck only names the scope and the actor must own it. Fail closed:
        // an unknown deck is not ready.
        return jdbc.sql("SELECT COALESCE((SELECT app_learning.exercise_media_ready(d.reuse_scope_id,:exercise,:revision) "
                        + "FROM app_learning.deck d WHERE d.deck_id=:deck AND d.owner_id=:owner),FALSE)")
                .param("deck", deck).param("exercise", exercise).param("revision", revision)
                .param("owner", actor).query(Boolean.class).single();
    }

    /**
     * #238 must call this inside the transaction that saves the draft document. A draft holds the assets of its owner (the hold keeps an unsaved upload
     * from collection). An asset the draft's deck inherits is validated against the deck's heads and NOT held by the draft: the lineage references of the
     * revisions that carry it already keep it alive, and the hold row cannot name two owners.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceDraft(UUID actor, UUID draft, Collection<Reference> references) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(draft, "draftId");
        UUID deck = jdbc.sql("SELECT deck_id FROM app_learning.editing_draft WHERE draft_id=:draft "
                        + "AND owner_id=:owner AND expires_at>CURRENT_TIMESTAMP FOR UPDATE")
                .param("draft", draft).param("owner", actor).query(UUID.class).optional()
                .orElseThrow(ResourceNotFoundException::new);
        // Validate first so a foreign asset cannot evict an authorized draft's current holds.
        Map<UUID, UUID> owners = validateReferences(actor, deck, references);
        jdbc.sql("DELETE FROM app_learning.draft_media_ref WHERE draft_id=:draft AND owner_id=:owner")
                .param("draft", draft).param("owner", actor).update();
        for (Reference reference : references) {
            if (!actor.equals(owners.get(reference.assetId()))) continue;
            jdbc.sql("INSERT INTO app_learning.draft_media_ref "
                            + "(draft_id,node_id,owner_id,asset_id) VALUES (:draft,:node,:owner,:asset)")
                    .param("draft", draft).param("node", reference.nodeId())
                    .param("owner", actor).param("asset", reference.assetId()).update();
        }
    }

    /**
     * Return a storage locator only after checking who may read the asset (see {@link Grant}). The ORIGINAL (no variant) is the owner's alone; a copy's owner
     * reads the derived variants of the assets his heads reference.
     */
    @Transactional(readOnly = true)
    public BlobLocation resolve(UUID actor, UUID asset, UUID variant) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(asset, "assetId");
        if (variant == null) return locate(Grant.OWNER, actor, null, asset, null).orElseThrow(ResourceNotFoundException::new);
        UuidPolicy.requireEntityId(variant, "variantId");
        return locate(Grant.OWNER, actor, null, asset, variant)
                .or(() -> locate(Grant.COPY, actor, null, asset, variant)).orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * Read-only playback projection. READY bytes still pass through the canonical reachability check. The owner also receives the original; the owner of
     * a copy that holds the asset in its heads receives the derived variants only ({@code original} is {@code null}).
     */
    @Transactional(readOnly = true)
    public PlaybackDescriptor playback(UUID actor, UUID asset) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(asset, "assetId");
        AssetVersion version = jdbc.sql("SELECT a.state,a.generation,(a.owner_id=:actor) AS owned FROM app_learning.media_asset a "
                        + "WHERE a.asset_id=:asset AND (a.owner_id=:actor OR " + Grant.COPY.predicate + ")")
                .param("asset", asset).param("actor", actor)
                .query((row, ignored) -> new AssetVersion(row.getString("state"), row.getLong("generation"), row.getBoolean("owned")))
                .optional().orElseThrow(ResourceNotFoundException::new);
        if (!"READY".equals(version.state())) return new PlaybackDescriptor(version.state(), null, null, null);
        Grant grant = version.owned() ? Grant.OWNER : Grant.COPY;
        return describe(grant, actor, null, asset, version);
    }

    /**
     * The playback of an asset of a PUBLISHED deck for a viewer {@code DeckAccess} already admitted (guests included): the asset must be in the deck's
     * {@code published_media} set (fail closed: no row, no answer), and only derived variants are returned, never the original. The caller owns the access
     * decision; this method owns the media half of it.
     */
    @Transactional(readOnly = true)
    public PlaybackDescriptor publishedPlayback(UUID deck, UUID asset) {
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(asset, "assetId");
        AssetVersion version = jdbc.sql("SELECT a.state,a.generation FROM app_learning.media_asset a "
                        + "WHERE a.asset_id=:asset AND " + Grant.PUBLISHED.predicate)
                .param("asset", asset).param("deck", deck)
                .query((row, ignored) -> new AssetVersion(row.getString("state"), row.getLong("generation"), false))
                .optional().orElseThrow(ResourceNotFoundException::new);
        if (!"READY".equals(version.state())) return new PlaybackDescriptor(version.state(), null, null, null);
        return describe(Grant.PUBLISHED, null, deck, asset, version);
    }

    private PlaybackDescriptor describe(Grant grant, UUID actor, UUID deck, UUID asset, AssetVersion version) {
        List<VariantIdentity> variants = jdbc.sql("SELECT variant_id,purpose,profile FROM app_learning.media_variant "
                        + "WHERE asset_id=:asset AND asset_generation=:generation ORDER BY purpose,profile")
                .param("asset", asset).param("generation", version.generation())
                .query((row, ignored) -> new VariantIdentity((UUID) row.getObject("variant_id"),
                        row.getString("purpose"), row.getString("profile"))).list();
        UUID playbackVariant = variants.stream().filter(value -> "playback".equals(value.purpose()))
                .map(VariantIdentity::id).findFirst().orElseThrow(MediaStorageUnavailableException::new);
        UUID posterVariant = variants.stream().filter(value -> "poster".equals(value.purpose())
                        || "thumbnail".equals(value.purpose()))
                .map(VariantIdentity::id).findFirst().orElse(null);
        BlobLocation playable = locate(grant, actor, deck, asset, playbackVariant).orElseThrow(ResourceNotFoundException::new);
        BlobLocation poster = posterVariant == null ? null
                : locate(grant, actor, deck, asset, posterVariant).orElseThrow(ResourceNotFoundException::new);
        // The original is the owner's alone: a derived variant is all a copy's owner or a guest ever receives.
        BlobLocation original = grant == Grant.OWNER
                ? locate(grant, actor, deck, asset, null).orElseThrow(ResourceNotFoundException::new) : null;
        return new PlaybackDescriptor("READY", playable, poster, original);
    }

    /**
     * The one statement that turns "this reader may use this asset" into a blob. {@code variant == null} is the verified source and is allowed for
     * {@link Grant#OWNER} only: a programming error for any other grant, so no path can serve an original to a non-owner.
     */
    private java.util.Optional<BlobLocation> locate(Grant grant, UUID actor, UUID deck, UUID asset, UUID variant) {
        if (variant == null && grant != Grant.OWNER) throw new IllegalArgumentException("Only the owner reads the original");
        String sql = variant == null
                ? "SELECT b.blob_id,b.object_key,b.byte_length,b.mime_type FROM app_learning.media_asset a "
                    + "JOIN app_learning.media_blob b ON b.blob_id=a.source_blob_id "
                    + "WHERE a.asset_id=:asset AND a.state='READY' AND " + grant.predicate
                : "SELECT b.blob_id,b.object_key,b.byte_length,b.mime_type FROM app_learning.media_asset a "
                    + "JOIN app_learning.media_variant v ON v.asset_id=a.asset_id "
                    + "AND v.asset_generation=a.generation AND v.variant_id=:variant "
                    + "JOIN app_learning.media_blob b ON b.blob_id=v.blob_id "
                    + "WHERE a.asset_id=:asset AND a.state='READY' AND " + grant.predicate;
        var query = jdbc.sql(sql).param("asset", asset);
        if (grant.usesActor) query = query.param("actor", actor);
        if (grant == Grant.PUBLISHED) query = query.param("deck", deck);
        if (variant != null) query = query.param("variant", variant);
        return query.query((row, ignored) -> new BlobLocation((UUID) row.getObject("blob_id"),
                row.getString("object_key"), row.getLong("byte_length"), row.getString("mime_type"))).optional();
    }

    /** Tombstone only assets whose owner hold elapsed and which no content or draft still reaches. */
    @Transactional
    public int expireUnattached(int limit) {
        if (limit < 1 || limit > 1_000) throw new InvalidRequestException();
        List<UUID> candidates = jdbc.sql("SELECT a.asset_id FROM app_learning.media_asset a "
                        + "WHERE a.state='READY' AND a.owner_hold_until<CURRENT_TIMESTAMP "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.content_media_ref r WHERE r.asset_id=a.asset_id) "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.exercise_media_ref r WHERE r.asset_id=a.asset_id) "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.draft_media_ref r "
                        + "JOIN app_learning.editing_draft d ON d.draft_id=r.draft_id "
                        + "WHERE r.asset_id=a.asset_id AND d.expires_at>CURRENT_TIMESTAMP) "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.generation_media_hold r JOIN app_learning.generation_session s "
                        + "ON s.session_id=r.session_id WHERE r.asset_id=a.asset_id AND s.expires_at>CURRENT_TIMESTAMP) "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.media_manifest_asset_ref r "
                        + "JOIN app_learning.media_manifest m ON m.manifest_id=r.manifest_id "
                        + "WHERE r.asset_id=a.asset_id AND m.owner_id=a.owner_id "
                        + "AND m.expires_at>CURRENT_TIMESTAMP) "
                        + "ORDER BY a.owner_hold_until,a.asset_id LIMIT :limit FOR UPDATE OF a SKIP LOCKED")
                .param("limit", limit).query(UUID.class).list();
        for (UUID candidate : candidates) {
            jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                            + "WHERE asset_id=:asset AND state='READY'")
                    .param("asset", candidate).update();
        }
        return candidates.size();
    }

    private Map<UUID, UUID> validateReferences(UUID actor, UUID deck, Collection<Reference> references) {
        if (references == null || references.size() > 1_000) throw new InvalidRequestException();
        var nodes = new HashSet<UUID>();
        var assets = new TreeSet<UUID>();
        for (Reference reference : references) {
            if (reference == null || !nodes.add(reference.nodeId())) throw new InvalidRequestException();
            assets.add(reference.assetId());
        }
        return validateAssets(actor, deck, assets);
    }

    /**
     * Every asset must be the actor's own (not deleted or rejected, and held or still reachable), or an asset the head revisions of {@code deck} reference
     * (an inherited one; not locked for update, the key-share lock of the reference's foreign key is enough). Returns the owner of each asset: the owner the
     * reference row names.
     */
    private Map<UUID, UUID> validateAssets(UUID actor, UUID deck, Collection<UUID> assets) {
        var owners = new java.util.HashMap<UUID, UUID>();
        // Stable lock ordering avoids deadlocks when documents and exercises reuse the same assets.
        for (UUID asset : assets) {
            boolean own = jdbc.sql("SELECT 1 FROM app_learning.media_asset a WHERE a.asset_id=:asset "
                            + "AND a.owner_id=:owner AND a.state NOT IN ('DELETED','REJECTED') AND "
                            + "(a.state<>'READY' OR a.owner_hold_until>CURRENT_TIMESTAMP "
                            + "OR EXISTS (SELECT 1 FROM app_learning.content_media_ref r WHERE r.asset_id=a.asset_id) "
                            + "OR EXISTS (SELECT 1 FROM app_learning.exercise_media_ref r WHERE r.asset_id=a.asset_id) "
                            + "OR EXISTS (SELECT 1 FROM app_learning.draft_media_ref r "
                            + "JOIN app_learning.editing_draft d ON d.draft_id=r.draft_id "
                            + "WHERE r.asset_id=a.asset_id AND d.expires_at>CURRENT_TIMESTAMP) "
                            + "OR EXISTS (SELECT 1 FROM app_learning.generation_media_hold r JOIN app_learning.generation_session s "
                            + "ON s.session_id=r.session_id WHERE r.asset_id=a.asset_id AND s.expires_at>CURRENT_TIMESTAMP)) "
                            + "FOR UPDATE OF a")
                    .param("asset", asset).param("owner", actor)
                    .query(Integer.class).optional().isPresent();
            if (own) {
                owners.put(asset, actor);
                continue;
            }
            UUID owner = jdbc.sql("SELECT a.owner_id FROM app_learning.media_asset a WHERE a.asset_id=:asset "
                            + "AND a.state NOT IN ('DELETED','REJECTED') AND " + IN_HEADS_OF_DECK + " FOR SHARE OF a")
                    .param("asset", asset).param("deck", deck).query(UUID.class).optional()
                    .orElseThrow(ResourceNotFoundException::new);
            owners.put(asset, owner);
        }
        return owners;
    }

    /**
     * An asset is reachable from a deck's head revisions through a material reference or an exercise pin. The join order is PINNED by the statement, not
     * left to the planner: the references of the asset (a subquery fenced with {@code OFFSET 0}) drive a lateral lookup of the decks (also fenced) and only then
     * does the head get a primary-key probe. A planner that flattened it could start from everything the viewer owns, which is proportional to his whole library.
     */
    private static String heads(String deckLookup) {
        return "(EXISTS (SELECT 1 FROM (SELECT r.reuse_scope_id,r.member_key,r.revision_id FROM app_learning.content_media_ref r "
                + "WHERE r.asset_id=a.asset_id OFFSET 0) r " + deckLookup
                + "JOIN app_learning.deck_head_item h ON h.deck_id=d.deck_id AND h.member_key=r.member_key AND h.revision_id=r.revision_id) "
                + "OR EXISTS (SELECT 1 FROM (SELECT r.reuse_scope_id,r.exercise_id,r.exercise_revision_id FROM app_learning.exercise_media_ref r "
                + "WHERE r.asset_id=a.asset_id OFFSET 0) r " + deckLookup
                + "JOIN app_learning.deck_head_exercise h ON h.deck_id=d.deck_id AND h.exercise_id=r.exercise_id "
                + "AND h.revision_id=r.exercise_revision_id))";
    }

    /** A live deck {@code :deck} of the lineage of the reference (the attach path names its deck). */
    private static final String THE_DECK = "JOIN LATERAL (SELECT d.deck_id FROM app_learning.deck d WHERE d.reuse_scope_id=r.reuse_scope_id "
            + "AND d.deck_id=:deck AND d.deleted_at IS NULL OFFSET 0) d ON TRUE ";
    /** Any live deck of the lineage that the actor owns (a copy's owner holds an inherited asset in HIS heads). */
    private static final String ACTOR_DECKS = "JOIN LATERAL (SELECT d.deck_id FROM app_learning.deck d WHERE d.reuse_scope_id=r.reuse_scope_id "
            + "AND d.owner_id=:actor AND d.deleted_at IS NULL OFFSET 0) d ON TRUE ";
    static final String IN_HEADS_OF_DECK = heads(THE_DECK);
    static final String IN_HEADS_OF_ACTOR = heads(ACTOR_DECKS);

    /** Why a reader may use an asset: the whole authorization of media, in one place (the SQL fragment is the alias {@code a} of {@code media_asset}). */
    private enum Grant {
        /** The asset is the actor's own and still reachable (hold, content, exercise, draft, generation or manifest). The only grant that reads the original. */
        OWNER("a.owner_id=:actor AND (a.owner_hold_until>CURRENT_TIMESTAMP OR EXISTS "
                + "(SELECT 1 FROM app_learning.content_media_ref r WHERE r.asset_id=a.asset_id) OR EXISTS "
                + "(SELECT 1 FROM app_learning.exercise_media_ref r WHERE r.asset_id=a.asset_id) OR EXISTS "
                + "(SELECT 1 FROM app_learning.draft_media_ref r JOIN app_learning.editing_draft d "
                + "ON d.draft_id=r.draft_id WHERE r.asset_id=a.asset_id AND d.expires_at>CURRENT_TIMESTAMP) "
                + "OR EXISTS (SELECT 1 FROM app_learning.generation_media_hold r JOIN app_learning.generation_session s "
                + "ON s.session_id=r.session_id WHERE r.asset_id=a.asset_id AND s.expires_at>CURRENT_TIMESTAMP) "
                + "OR EXISTS (SELECT 1 FROM app_learning.media_manifest_asset_ref r "
                + "JOIN app_learning.media_manifest m ON m.manifest_id=r.manifest_id "
                + "WHERE r.asset_id=a.asset_id AND m.owner_id=a.owner_id "
                + "AND m.expires_at>CURRENT_TIMESTAMP))", true),
        /** The actor owns a live deck of the asset's lineage whose head revisions reference it (a copy's inherited media). Derived variants only. */
        COPY(IN_HEADS_OF_ACTOR, true),
        /** The deck's published revision references it ({@code published_media}); the caller has already proved the viewer may read the deck. Derived variants only. */
        PUBLISHED("EXISTS (SELECT 1 FROM app_learning.published_media pm WHERE pm.deck_id=:deck AND pm.asset_id=a.asset_id)", false);

        private final String predicate;
        private final boolean usesActor;

        Grant(String predicate, boolean usesActor) {
            this.predicate = predicate;
            this.usesActor = usesActor;
        }
    }

    public record Reference(UUID nodeId, UUID assetId) {
        public Reference {
            UuidPolicy.requireEntityId(nodeId, "nodeId");
            UuidPolicy.requireEntityId(assetId, "assetId");
        }
    }

    public record BlobLocation(UUID blobId, String objectKey, long byteLength, String mimeType) { }

    public record PlaybackDescriptor(String state, BlobLocation playable, BlobLocation poster,
                                     BlobLocation original) { }

    private record AssetVersion(String state, long generation, boolean owned) { }
    private record VariantIdentity(UUID id, String purpose, String profile) { }

    private record Reservation(UUID assetId, String origin) { }

    public enum Origin { UPLOAD, RECORDING, IMPORT }

    /** What an author declares an asset to be; readiness verifies the stored source agrees. */
    public enum Kind {
        IMAGE, AUDIO, VIDEO;

        String column() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** A media block's asset and declared kind inside one exercise revision. */
    public record ExerciseAsset(UUID assetId, Kind kind) {
        public ExerciseAsset {
            UuidPolicy.requireEntityId(assetId, "assetId");
            java.util.Objects.requireNonNull(kind, "kind");
        }
    }
}
