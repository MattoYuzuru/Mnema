-- Generation sessions, artifacts, steps and events (contracts/generation, architecture sections 3-5). Every table is
-- owner-scoped with composite foreign keys (as V10 draft_media_ref), so a row can never point into another owner's
-- session. Revisions, events and provenance are append-only; sessions, artifacts, slots and steps change under their own
-- row lock or compare-and-set. Rows hold identifiers, enums, native-v1 documents and the user's own spec: no keys, no
-- provider responses, no account data beyond the owner id.

-- pg_trgm drives the duplicate-title check and the top-K outline of large decks (item_preview titles). It is optional:
-- where it cannot be created (a role without the privilege) the generation module detects its absence at run time and
-- skips both, so a failure here must not stop the migration.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_trgm is unavailable (%): similar-title warnings and top-K outlines are skipped', SQLERRM;
END
$$;

CREATE TABLE app_learning.generation_session (
    session_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('MATERIALS', 'EXERCISES', 'REVISE_ITEM', 'REVISE_EXERCISE')),
    state TEXT NOT NULL CHECK (state IN ('PLANNING', 'PLAN_READY', 'RUNNING', 'REVIEW', 'CLOSED', 'CANCELLED', 'EXPIRED')),
    end_reason TEXT CHECK (end_reason IS NULL OR end_reason IN ('USER_CANCELLED', 'PLAN_FAILED', 'EXPIRED')),
    -- The immutable spec as the client sent it (strictly validated); bodies are at most 64 KiB.
    spec JSONB NOT NULL CHECK (octet_length(spec::text) BETWEEN 2 AND 65536),
    -- The initial-batch reservation of the usage ledger: opaque, no foreign key (usage owns what it names).
    reservation_id UUID,
    -- Bumps with every transaction that changes the session (state, pointers, activity); the ETag of reads.
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    -- Allocated under this row's lock (events.json): the cursor of the per-session event log.
    last_event_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_event_seq >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    last_activity_at TIMESTAMPTZ NOT NULL CHECK (isfinite(last_activity_at)),
    -- last activity + learning.generation.session-retention; also bounds the media holds of the session.
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    UNIQUE (session_id, owner_id),
    FOREIGN KEY (deck_id, owner_id) REFERENCES app_learning.deck(deck_id, owner_id)
);
CREATE INDEX generation_session_owner_recent
    ON app_learning.generation_session(owner_id, last_activity_at DESC, session_id DESC);
CREATE INDEX generation_session_deck_recent
    ON app_learning.generation_session(owner_id, deck_id, last_activity_at DESC, session_id DESC);
-- Active-session admission and the notification center's activeWork read only the live states of one owner.
CREATE INDEX generation_session_owner_active ON app_learning.generation_session(owner_id)
    WHERE state IN ('PLANNING', 'PLAN_READY', 'RUNNING', 'REVIEW');
CREATE INDEX generation_session_running_reservation ON app_learning.generation_session(session_id)
    WHERE state = 'RUNNING' AND reservation_id IS NOT NULL;
CREATE INDEX generation_session_expiry ON app_learning.generation_session(expires_at);

-- The pinned sources (immutable): a note by row version, a material by revision. No foreign key to the pinned rows:
-- a note may be edited or deleted later, which is exactly what the pin detects.
CREATE TABLE app_learning.generation_session_source (
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    ordinal SMALLINT NOT NULL CHECK (ordinal BETWEEN 0 AND 19),
    role TEXT NOT NULL CHECK (role IN ('SOURCE', 'STYLE_EXAMPLE')),
    type TEXT NOT NULL CHECK (type IN ('NOTE', 'ITEM')),
    note_id UUID,
    note_row_version BIGINT CHECK (note_row_version IS NULL OR note_row_version >= 0),
    member_key UUID,
    item_revision_id UUID,
    PRIMARY KEY (session_id, ordinal),
    CHECK ((type = 'NOTE' AND note_id IS NOT NULL AND note_row_version IS NOT NULL
                AND member_key IS NULL AND item_revision_id IS NULL)
        OR (type = 'ITEM' AND note_id IS NULL AND note_row_version IS NULL
                AND member_key IS NOT NULL AND item_revision_id IS NOT NULL)),
    CHECK (role = 'SOURCE' OR type = 'ITEM'),
    FOREIGN KEY (session_id, owner_id) REFERENCES app_learning.generation_session(session_id, owner_id) ON DELETE CASCADE
);
CREATE INDEX generation_session_source_note ON app_learning.generation_session_source(note_id) WHERE note_id IS NOT NULL;

CREATE TABLE app_learning.generation_artifact (
    artifact_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    target_kind TEXT NOT NULL CHECK (target_kind IN ('ITEM', 'EXERCISE')),
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 59),
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'GENERATING', 'PROPOSED', 'REVISING', 'FAILED', 'REJECTED', 'STALE',
        'PUBLISHED', 'HANDED_OFF')),
    error_code TEXT CHECK (error_code IS NULL OR error_code IN ('INVALID_OUTPUT', 'REFUSAL', 'SOURCE_UNAVAILABLE',
        'PROVIDER_UNAVAILABLE', 'USAGE_LIMIT', 'ESTIMATE_EXCEEDED', 'DEADLINE_EXCEEDED', 'CANCELLED', 'PLAN_FAILED')),
    repin_status TEXT CHECK (repin_status IS NULL OR repin_status IN ('AUTO_REPINNED', 'NEEDS_USER_DECISION')),
    -- Preview title of the current revision (the pager); empty until a revision exists.
    title TEXT NOT NULL DEFAULT '' CHECK (char_length(title) <= 240),
    current_revision_id UUID,
    revision_count INTEGER NOT NULL DEFAULT 0 CHECK (revision_count BETWEEN 0 AND 30),
    -- The artifact-scoped counter of BLOCKS_APPENDED.generation: it grows whenever the draft restarts.
    draft_generation INTEGER NOT NULL DEFAULT 0 CHECK (draft_generation >= 0),
    -- The pins this artifact was generated from: [{type, noteId, noteRowVersion} | {type, memberKey, itemRevisionId}].
    source_refs JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(source_refs) = 'array'),
    publication_command_id UUID,
    published_ref JSONB,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    UNIQUE (session_id, ordinal),
    UNIQUE (artifact_id, owner_id),
    UNIQUE (artifact_id, session_id, owner_id),
    CHECK (state <> 'PROPOSED' OR current_revision_id IS NOT NULL),
    FOREIGN KEY (session_id, owner_id) REFERENCES app_learning.generation_session(session_id, owner_id) ON DELETE CASCADE
);
CREATE INDEX generation_artifact_session ON app_learning.generation_artifact(session_id, state);

-- Immutable snapshot of one proposal: a native-v1 document (ITEM) or an exercise command (EXERCISE), at most 30 per
-- artifact and 1 MiB each. The model route and prompt version are internal and never returned.
CREATE TABLE app_learning.generation_artifact_revision (
    revision_id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL,
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    revision_no INTEGER NOT NULL CHECK (revision_no BETWEEN 1 AND 30),
    cause TEXT NOT NULL CHECK (cause IN ('INITIAL', 'EDIT', 'MEDIA', 'REPIN')),
    payload JSONB NOT NULL CHECK (octet_length(payload::text) BETWEEN 2 AND 1048576),
    -- handle ("b3") to node id of the top-level blocks, so an edit keeps node ids where the block type is unchanged.
    handles JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(handles) = 'object'),
    prompt_version TEXT NOT NULL CHECK (prompt_version ~ '^v[0-9]{1,3}$'),
    model_route TEXT NOT NULL CHECK (octet_length(model_route) BETWEEN 1 AND 200),
    validation JSONB NOT NULL DEFAULT '{"warnings": []}',
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    UNIQUE (artifact_id, revision_no),
    UNIQUE (revision_id, artifact_id),
    FOREIGN KEY (artifact_id, session_id, owner_id)
        REFERENCES app_learning.generation_artifact(artifact_id, session_id, owner_id) ON DELETE CASCADE
);
ALTER TABLE app_learning.generation_artifact
    ADD CONSTRAINT generation_artifact_current_revision FOREIGN KEY (current_revision_id, artifact_id)
        REFERENCES app_learning.generation_artifact_revision(revision_id, artifact_id) DEFERRABLE INITIALLY DEFERRED;

-- One user instruction on an artifact (edits, AI-11): created now, written by the edit task.
CREATE TABLE app_learning.generation_artifact_turn (
    turn_id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL,
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'APPLIED', 'FAILED', 'CANCELLED')),
    action TEXT NOT NULL CHECK (action IN ('REWRITE', 'IMAGE_SEARCH', 'IMAGE_GENERATE', 'AUDIO_REGENERATE', 'FREE',
        'REMOVE_MEDIA')),
    preset TEXT CHECK (preset IS NULL OR preset IN ('SIMPLER', 'SHORTER', 'EXAMPLE', 'LONGER')),
    instruction TEXT CHECK (instruction IS NULL OR char_length(instruction) <= 2000),
    target_node_ids UUID[] NOT NULL DEFAULT '{}',
    step_id UUID,
    result_revision_id UUID,
    error_code TEXT CHECK (error_code IS NULL OR octet_length(error_code) <= 40),
    -- REMOVE_MEDIA is recorded as a turn but does not count toward the 50 per artifact.
    counts_toward_limit BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    FOREIGN KEY (artifact_id, session_id, owner_id)
        REFERENCES app_learning.generation_artifact(artifact_id, session_id, owner_id) ON DELETE CASCADE
);
CREATE INDEX generation_artifact_turn_artifact ON app_learning.generation_artifact_turn(artifact_id, created_at);

-- One media directive of the current document. The slot key is stable across the revisions of an artifact; asset_id is
-- pre-allocated by the compiler and names a media asset that exists only once a media step has produced it.
CREATE TABLE app_learning.generation_media_slot (
    artifact_id UUID NOT NULL,
    slot_key TEXT NOT NULL CHECK (slot_key ~ '^[a-z][a-z0-9_]{0,31}$'),
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    node_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('AUDIO', 'IMAGE', 'VIDEO')),
    spec JSONB NOT NULL,
    asset_id UUID NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'GENERATING', 'VERIFYING', 'READY', 'FAILED', 'REMOVED')),
    error_code TEXT CHECK (error_code IS NULL OR error_code IN ('PROVIDER_UNAVAILABLE', 'NO_RESULT', 'VERIFICATION_REJECTED',
        'DEADLINE_EXCEEDED', 'USAGE_LIMIT', 'ESTIMATE_EXCEEDED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    PRIMARY KEY (artifact_id, slot_key),
    UNIQUE (asset_id),
    FOREIGN KEY (revision_id, artifact_id)
        REFERENCES app_learning.generation_artifact_revision(revision_id, artifact_id) ON DELETE CASCADE,
    FOREIGN KEY (artifact_id, session_id, owner_id)
        REFERENCES app_learning.generation_artifact(artifact_id, session_id, owner_id) ON DELETE CASCADE
);

-- A hold of the media GC on an asset produced for a proposal, exactly like draft_media_ref: while the session has not
-- expired, the asset is reachable and the GC leaves it alone. Written by the media steps (AI-09, AI-10); the composite
-- foreign key to media_asset keeps it owner-scoped.
CREATE TABLE app_learning.generation_media_ref (
    artifact_id UUID NOT NULL,
    node_id UUID NOT NULL,
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    PRIMARY KEY (artifact_id, node_id),
    FOREIGN KEY (artifact_id, session_id, owner_id)
        REFERENCES app_learning.generation_artifact(artifact_id, session_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (asset_id, owner_id) REFERENCES app_learning.media_asset(asset_id, owner_id)
);
CREATE INDEX generation_media_ref_asset ON app_learning.generation_media_ref(asset_id);

-- The durable job queue (architecture section 4), modelled on MediaProcessingRepository: claim FOR UPDATE SKIP LOCKED,
-- a lease with a fencing token (every result write checks it), heartbeat, backoff, deadline and cancellation.
CREATE TABLE app_learning.generation_step (
    step_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    artifact_id UUID,
    owner_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('PLAN', 'RESEARCH', 'TEXT_DRAFT', 'EDIT', 'TTS', 'IMAGE_GENERATE', 'IMAGE_SEARCH',
        'VIDEO_GENERATE', 'TRANSCRIBE', 'ASSESS')),
    capability TEXT NOT NULL CHECK (capability IN ('TEXT', 'ASSESS', 'TTS', 'IMAGE', 'IMAGE_SEARCH', 'VIDEO', 'SEARCH')),
    state TEXT NOT NULL CHECK (state IN ('WAITING_DEPENDENCIES', 'READY', 'RUNNING', 'WAITING_EXTERNAL', 'SUCCEEDED',
        'FAILED', 'CANCELLED')),
    depends_on UUID[] NOT NULL DEFAULT '{}',
    priority INTEGER NOT NULL DEFAULT 0,
    -- Incremented by every claim; bounds retries. poll_count is for WAITING_EXTERNAL polls only.
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    poll_count INTEGER NOT NULL DEFAULT 0 CHECK (poll_count >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ CHECK (lease_until IS NULL OR isfinite(lease_until)),
    next_attempt_at TIMESTAMPTZ NOT NULL CHECK (isfinite(next_attempt_at)),
    deadline_at TIMESTAMPTZ CHECK (deadline_at IS NULL OR isfinite(deadline_at)),
    started_at TIMESTAMPTZ CHECK (started_at IS NULL OR isfinite(started_at)),
    -- The first claim: the step's lifetime (learning.generation.step.max-lifetime) is counted from it, however often it is retried.
    first_claimed_at TIMESTAMPTZ CHECK (first_claimed_at IS NULL OR isfinite(first_claimed_at)),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    input JSONB NOT NULL DEFAULT '{}' CHECK (octet_length(input::text) <= 65536),
    output_ref TEXT CHECK (output_ref IS NULL OR octet_length(output_ref) <= 200),
    error_code TEXT CHECK (error_code IS NULL OR octet_length(error_code) <= 40),
    external_job_id TEXT CHECK (external_job_id IS NULL OR octet_length(external_job_id) <= 200),
    idempotency_key TEXT NOT NULL UNIQUE CHECK (octet_length(idempotency_key) BETWEEN 1 AND 200),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    CONSTRAINT generation_step_lease_iff_running CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL)),
    FOREIGN KEY (session_id, owner_id) REFERENCES app_learning.generation_session(session_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (artifact_id, session_id, owner_id)
        REFERENCES app_learning.generation_artifact(artifact_id, session_id, owner_id) ON DELETE CASCADE
);
-- The claim query: READY steps that are due, oldest first.
CREATE INDEX generation_step_claim ON app_learning.generation_step(state, next_attempt_at, created_at)
    WHERE state = 'READY';
CREATE INDEX generation_step_lease ON app_learning.generation_step(lease_until) WHERE state = 'RUNNING';
CREATE INDEX generation_step_owner_running ON app_learning.generation_step(owner_id) WHERE state = 'RUNNING';
CREATE INDEX generation_step_session ON app_learning.generation_step(session_id, state);

-- Append-only progress log. seq is allocated under the session row lock (events.json), never by a sequence: a bigserial
-- is assigned before commit, so a poller could pass a value whose lower neighbour commits later and miss it forever.
CREATE TABLE app_learning.generation_event (
    session_id UUID NOT NULL,
    seq BIGINT NOT NULL CHECK (seq >= 1),
    artifact_id UUID,
    type TEXT NOT NULL CHECK (type IN ('ARTIFACT_STATE', 'BLOCKS_APPENDED', 'MEDIA_SLOT_STATE', 'USAGE_UPDATED',
        'SESSION_STATE')),
    payload JSONB NOT NULL CHECK (octet_length(payload::text) <= 32768),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (isfinite(occurred_at)),
    PRIMARY KEY (session_id, seq),
    FOREIGN KEY (session_id) REFERENCES app_learning.generation_session(session_id) ON DELETE CASCADE
);

-- Origin of a published artifact (revision to session, model routes, prompt versions): audit and economics, never shown
-- in the UI. It must outlive the purge of the session, so it has no foreign key to it. Written by approval (AI-05).
CREATE TABLE app_learning.generation_provenance (
    provenance_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    session_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    published_ref JSONB NOT NULL,
    model_routes TEXT[] NOT NULL DEFAULT '{}',
    prompt_versions TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at))
);
CREATE INDEX generation_provenance_owner ON app_learning.generation_provenance(owner_id, created_at);

CREATE FUNCTION app_learning.generation_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Generation % rows are append-only', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER generation_artifact_revision_guard BEFORE UPDATE ON app_learning.generation_artifact_revision
    FOR EACH ROW EXECUTE FUNCTION app_learning.generation_immutable_guard();
CREATE TRIGGER generation_event_guard BEFORE UPDATE ON app_learning.generation_event
    FOR EACH ROW EXECUTE FUNCTION app_learning.generation_immutable_guard();
CREATE TRIGGER generation_provenance_guard BEFORE UPDATE ON app_learning.generation_provenance
    FOR EACH ROW EXECUTE FUNCTION app_learning.generation_immutable_guard();
CREATE TRIGGER generation_session_source_guard BEFORE UPDATE ON app_learning.generation_session_source
    FOR EACH ROW EXECUTE FUNCTION app_learning.generation_immutable_guard();
