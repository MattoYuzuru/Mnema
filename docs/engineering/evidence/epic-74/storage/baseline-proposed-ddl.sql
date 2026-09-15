-- Superseded initial sketch; see proposed-ddl.sql for the measured normalized-edge candidate.
-- R74-S review sketch only. NOT a migration, accepted schema, or complete ACL model.
-- IDs below are UUIDs; the benchmark uses narrower bigint surrogates. Measure this
-- encoding before fixing production page-byte limits or making capacity promises.
CREATE TABLE reuse_scope (
    scope_id uuid PRIMARY KEY
);

CREATE TABLE content_block (
    scope_id uuid NOT NULL REFERENCES reuse_scope,
    block_id uuid NOT NULL,
    format_version integer NOT NULL CHECK (format_version > 0),
    payload jsonb NOT NULL,
    checksum bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope_id, block_id),
    CHECK (octet_length(checksum) = 32),
    CHECK (octet_length(payload::text) <= 16384)
);
-- Deliberately no global checksum lookup or broad content GIN index.

CREATE TABLE manifest_page (
    scope_id uuid NOT NULL REFERENCES reuse_scope,
    page_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('content', 'membership', 'exercise', 'eligibility')),
    level smallint NOT NULL CHECK (level BETWEEN 0 AND 8),
    entry_count smallint NOT NULL CHECK (entry_count BETWEEN 0 AND 32),
    subtree_count bigint NOT NULL CHECK (subtree_count >= 0),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope_id, page_id),
    CHECK (octet_length(payload::text) <= 16384)
);
-- The page payload's typed child references/counts, ordering, unique member keys,
-- referenced object scope and semantic block coverage require a validated staging
-- receipt. JSON references are not foreign keys. The final design must explicitly
-- choose validated immutable arrays versus normalized FK edges and benchmark it.

CREATE TABLE item_revision (
    scope_id uuid NOT NULL,
    item_revision_id uuid NOT NULL,
    content_root_id uuid NOT NULL,
    format_version integer NOT NULL CHECK (format_version > 0),
    PRIMARY KEY (scope_id, item_revision_id),
    FOREIGN KEY (scope_id, content_root_id) REFERENCES manifest_page(scope_id, page_id)
);

CREATE TABLE deck_revision_proposal (
    deck_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK (sequence >= 0),
    parent_revision_id uuid,
    scope_id uuid NOT NULL,
    membership_root_id uuid NOT NULL,
    exercise_root_id uuid,
    metadata jsonb NOT NULL,
    command_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    PRIMARY KEY (deck_id, revision_id),
    UNIQUE (deck_id, sequence),
    UNIQUE (deck_id, command_id),
    FOREIGN KEY (deck_id, parent_revision_id) REFERENCES deck_revision_proposal(deck_id, revision_id),
    FOREIGN KEY (scope_id, membership_root_id) REFERENCES manifest_page(scope_id, page_id),
    FOREIGN KEY (scope_id, exercise_root_id) REFERENCES manifest_page(scope_id, page_id),
    CHECK (octet_length(metadata::text) <= 16384)
);

-- Existing platform command receipts/CAS should be integrated rather than copied.
-- A later deck head references its own (deck_id, revision_id). Fork creation writes
-- a new deck + same-deck revision selecting shared roots and records source/base;
-- it never makes a fork head masquerade as a source-deck revision.
-- Sparse progress keys remain (account_id, deck_id, member/objective key).
-- Eligibility ordinal indexes are immutable generation-scoped data, never a
-- synchronous scan over a rare filter. Existing root/namespace can be reused.
-- Operational roles: application INSERT/SELECT on immutable tables; GC DELETE
-- belongs to a distinct bounded collector with protected reachability/retention.
