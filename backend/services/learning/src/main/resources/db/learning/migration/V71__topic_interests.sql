-- Recs/2–3 (#457–458). Account-keyed serialization, bounded derived profile and private list snapshots.
SET LOCAL lock_timeout = '5s';
CREATE TABLE app_learning.community_interest_profile (
    account_id UUID PRIMARY KEY,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    onboarding_complete BOOLEAN NOT NULL DEFAULT false,
    signals_since TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp() CHECK (isfinite(signals_since)),
    bootstrap_existing BOOLEAN NOT NULL DEFAULT true,
    selected_topics TEXT[] NOT NULL DEFAULT '{}' CHECK (cardinality(selected_topics) <= 20),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp() CHECK (isfinite(last_active_at))
);
CREATE INDEX community_interest_active ON app_learning.community_interest_profile(last_active_at, account_id);
CREATE TABLE app_learning.user_topic_interest (
    account_id UUID NOT NULL REFERENCES app_learning.community_interest_profile(account_id) ON DELETE CASCADE,
    topic_id TEXT NOT NULL,
    weight REAL NOT NULL CHECK (weight > 0 AND weight <= 1),
    PRIMARY KEY (account_id, topic_id)
);
CREATE TABLE app_learning.recommendation_snapshot (
    snapshot_id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL REFERENCES app_learning.community_interest_profile(account_id) ON DELETE CASCADE,
    profile_version BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    query_hash TEXT NOT NULL,
    codes TEXT[] NOT NULL CHECK (cardinality(codes) <= 12),
    candidate_codes TEXT[] NOT NULL CHECK (cardinality(candidate_codes) <= 100),
    explanation_topic TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    UNIQUE (account_id, profile_version, generation, query_hash)
);
CREATE INDEX recommendation_snapshot_expiry ON app_learning.recommendation_snapshot(created_at);
-- V57 deck_fork_live already indexes (owner_id,upstream_deck_id) WHERE released_at IS NULL; reuse it, including detached copies.

-- Account purge (#409) can locate only the new community scopes without a global receipt scan.
CREATE INDEX command_receipt_community_actor ON app_learning.command_receipt(actor_id,created_at) WHERE command_scope IN ('community.events','community.interests');
