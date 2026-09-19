-- R74-S physical kernel proposed for owner review. NOT an accepted migration.
-- Matches the experiment's UUID object/header + normalized FK-edge representation.
-- Logical Deck/ItemRevision/ACL/command contracts belong to the integration slice.
CREATE TABLE storage_object (
    scope_id uuid NOT NULL,
    object_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('block', 'members', 'content', 'header', 'fragment', 'manifest')),
    level integer NOT NULL CHECK (level BETWEEN -1 AND 8),
    subtree_count bigint NOT NULL CHECK (subtree_count >= 0),
    native_payload jsonb,
    created_epoch bigint NOT NULL,
    PRIMARY KEY (scope_id, object_id),
    CHECK (native_payload IS NULL OR octet_length(native_payload::text) <= 16384)
);

CREATE TABLE storage_edge (
    scope_id uuid NOT NULL,
    parent_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 0 AND 31),
    node_key uuid,
    child_id uuid NOT NULL,
    subtree_count bigint NOT NULL CHECK (subtree_count >= 0),
    PRIMARY KEY (scope_id, parent_id, ordinal),
    FOREIGN KEY (scope_id, parent_id) REFERENCES storage_object(scope_id, object_id) ON DELETE CASCADE,
    FOREIGN KEY (scope_id, child_id) REFERENCES storage_object(scope_id, object_id)
);
CREATE INDEX storage_edge_incoming ON storage_edge(scope_id, child_id);

CREATE TABLE retention_root (
    scope_id uuid NOT NULL,
    name text NOT NULL,
    object_id uuid NOT NULL,
    expires_epoch bigint,
    PRIMARY KEY (scope_id, name),
    FOREIGN KEY (scope_id, object_id) REFERENCES storage_object(scope_id, object_id)
);
CREATE INDEX retention_root_incoming ON retention_root(scope_id, object_id);
CREATE INDEX retention_root_expiry ON retention_root(scope_id, expires_epoch) WHERE expires_epoch IS NOT NULL;

CREATE TABLE gc_candidate (
    scope_id uuid NOT NULL,
    object_id uuid NOT NULL,
    eligible_epoch bigint NOT NULL,
    PRIMARY KEY (scope_id, object_id)
);
CREATE INDEX gc_candidate_ready ON gc_candidate(scope_id, eligible_epoch, object_id);

-- Typed validation before staging:
-- * Blocks contain a valid native node envelope and have level=-1/count=1.
-- * A level=0 content page selects native blocks through stable node keys.
-- * Internal pages select same-kind children exactly one level lower and store
--   their verified subtree counts; no cycles or cross-scope references.
-- * Non-root occupancy is16..32; root is empty leaf, ordinary leaf, or >=2 children.
-- * The measured short-membership fixture selects physical short-content blocks.
--   Production member leaves select the accepted item-revision/content-root seam;
--   its additional bounded envelope/edge must be verified during integration.
-- * Page transport encoding is <=16KiB; normalized edge fanout is the physical bound.
-- *16KiB limits physical objects, not native paragraph/text-node size. The separate
--   large-node experiment stores a native skeleton/header plus ordered text fragments
--   behind a manifest. Header/fragment are leaves; manifest edges are ordinal0 header
--   followed by fragments. Reassembly restores the original semantic IDs and JSON.
--   Physical IDs never become semantic node IDs. Tiny edits reuse untouched fragments.
--   The measured24.6KiB scalar fits six target4KiB fragments; physical cap remains16KiB.
--   This candidate is proposed for owner review, not an accepted production chunker.

-- These cross-row meanings are application validation, not fictitious SQL CHECKs.
-- Persist object+edges+candidate atomically; immutable app roles cannot UPDATE them.
-- Every retained revision also has an indexed FK/root that collection checks.
-- Head/revision/receipt/bounded projection publish in one CAS transaction.
-- Root/edge release must enqueue the former targets in the same transaction.
-- GC selects <=8 indexed candidates; each object has <=32 outgoing edges.
-- FK reference/delete conflicts roll back and retry an entire collection batch.
-- Durable staging leases expire only through <=8-row expiry batches, then grace.
-- Epochs in the experiment are deterministic ticks; production policy is not chosen.
-- No global content-hash API or broad JSONB GIN index is introduced.
