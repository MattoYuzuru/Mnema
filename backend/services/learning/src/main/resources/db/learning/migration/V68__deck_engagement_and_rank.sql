-- Community/3 (#450): deck_engagement (the incremental metrics of direct copies), rank generations and the anomaly flags of the owner console
-- (architecture sections 10, 12 and 14; RFC 11.1, 11.2 and 15.1).
--
-- deck_engagement: ONE row per (SOURCE deck, account, kind). deck_id is deck_fork.upstream_deck_id, so a metric counts DIRECT copies only (a copy of a copy
--   counts for its direct source, never for the root); account_id is the copier/learner and is never the source's owner (the writers refuse it). No FK: the row
--   is a metric, not a part of a deck's life (a deleted copy keeps counting for the person; the deck purge of #409 deletes by deck_id and by account_id).
--   FORK    written by the copy command in the copy transaction (the first copy of an account counts, a deleted and re-made copy is the same person: ON CONFLICT never moves the
--           date or a known weight). first_at = last_at = the copy; trust 1.0, or 0.25 when the copier was not mnema_trusted at the copy (Identity: a confirmed address, that is
--           a verified e-mail or any federated sign-in, and an account of 3 days); new_account = the copier's account was younger than 48 h at the copy (the anomaly query).
--           A claim that Identity did not send at all (an Identity that predates it: the deploy order of the two services is not guaranteed) is stored as NULL, not as false:
--           trust NULL weighs 0.25 in the rank and new_account NULL is never "new" for the anomaly query; a later copy request by the same account, made when the claims are
--           there, fills the NULLs (and only the NULLs: ON CONFLICT ... DO UPDATE ... WHERE trust IS NULL). A malformed claim is false.
--   STUDY   written by the completion of a SCHEDULED study session of a live copy (detached copies count: they are still a direct copy): first_at on insert,
--           last_at = the latest completion, study_days = distinct local study dates (bumped only when the date moves forward, so a retry is idempotent),
--           last_study_date = the latest of them.
--   The primary key serves every write (one probe) and the per-deck read of the rank job; the rank job and the anomaly job scan the table in full once an hour / a day
--   (H: about 300 000 rows, seconds; see the guide), which is why there is no index for them.
--   deck_engagement_account (account_id): the future account purge (#409) and nothing else.
--
-- rank_generation / deck_rank: the hourly snapshot of the rank. A generation is built by ONE job transaction (rows first, completed_at last, so a reader that
--   asks for completed generations never sees a half-built one) and the previous generations live 24 hours after their completion (contracts/community/catalog.json,
--   rankGenerationRetention), so a cursor of the browse keeps working across the switch. deck_rank has a row for EVERY public_deck row of the generation: the counts of a
--   deck below the catalogue threshold are still shown on its author's page. Only LISTED decks are ranked: rank, learning_rank are NULL (and pop_pct is 0) for the others.
--   rank           1 = best by (score DESC, deck_id DESC); learning_rank 1 = most learning now, then by rank; pop_pct = percent_rank of the score among listed decks (0..1).
--   added_count, learning_now_count, activated_count are RAW (the display rule, "from 10, two significant digits", is applied by AudienceCounts when a card is built).
--   topic_id, parent_topic_id, listed are denormalized from public_deck at generation time so that the browse indexes below are self-contained.
--   Indexes (all partial WHERE listed, because the browse reads listed decks only; the queries of Community/2 carry the same predicate):
--     deck_rank_popular      (generation, rank)                                 popular browse: WHERE generation = $g AND listed AND rank > $after ORDER BY rank LIMIT n
--     deck_rank_learning     (generation, learning_rank)                        «Учат сейчас» browse, the same shape on learning_rank
--     deck_rank_topic        (generation, topic_id, score DESC, deck_id DESC)   a topic filter in the popular order (rank IS this order, so a keyset on (score, deck_id) or on rank both work)
--     deck_rank_parent_topic (generation, parent_topic_id, score DESC, deck_id DESC)   the parent-topic filter and the parent shelves
--   The primary key (generation, deck_id) serves the counts of a page of cards and the ON DELETE CASCADE of the 24 h retention.
--
-- catalog_anomaly: the flags of the owner console (a burst of copies by accounts younger than 48 h). A handful of rows, listed by a full scan; the primary key serves the
--   upsert of the daily job. public_code, title and owner_id are the last known ones, kept so that a flagged deck that left the catalogue is still listed.
--
-- admin_audit: the console journal gets the action COMMUNITY_ANOMALY_DISMISS.
--
-- Load, zero: all three tables empty; the hourly job inserts an empty generation (milliseconds); the hot paths add one primary-key probe of deck_fork per
--   completed SCHEDULED session. Load, H (5 000 public decks, 300 000 engagement rows): one INSERT ... SELECT per hour over the engagement table (measured in
--   CatalogRankScaleIntegrationTest, numbers in the guide); 25 generations of 5 000 rows are kept (125 000 rows with their five indexes).

SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.deck_engagement (
    deck_id UUID NOT NULL,
    account_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('FORK', 'STUDY')),
    first_at TIMESTAMPTZ NOT NULL CHECK (isfinite(first_at)),
    last_at TIMESTAMPTZ NOT NULL CHECK (isfinite(last_at) AND last_at >= first_at),
    study_days INTEGER NOT NULL DEFAULT 0 CHECK (study_days >= 0),
    last_study_date DATE,
    trust REAL CHECK (trust IS NULL OR (trust > 0 AND trust <= 1)),
    new_account BOOLEAN,
    PRIMARY KEY (deck_id, account_id, kind),
    CONSTRAINT deck_engagement_shape CHECK (CASE kind
        WHEN 'FORK' THEN study_days = 0 AND last_study_date IS NULL
        ELSE trust IS NULL AND new_account IS NULL AND study_days >= 1 AND last_study_date IS NOT NULL END)
);

-- TODO(account-deletion task #409; owner: Learning's account-purge epic): the purge of an account must delete its deck_engagement rows (WHERE account_id = :account).
-- The source's metrics then lose that person, which is the intended effect. Also see "Retention" in the Learning guide.
CREATE INDEX deck_engagement_account ON app_learning.deck_engagement(account_id);

CREATE TABLE app_learning.rank_generation (
    generation BIGINT PRIMARY KEY CHECK (generation >= 1),
    started_at TIMESTAMPTZ NOT NULL CHECK (isfinite(started_at)),
    completed_at TIMESTAMPTZ CHECK (completed_at IS NULL OR (isfinite(completed_at) AND completed_at >= started_at)),
    deck_count INTEGER CHECK (deck_count IS NULL OR deck_count >= 0),
    CONSTRAINT rank_generation_complete CHECK ((completed_at IS NULL) = (deck_count IS NULL))
);

CREATE TABLE app_learning.deck_rank (
    generation BIGINT NOT NULL REFERENCES app_learning.rank_generation(generation) ON DELETE CASCADE,
    deck_id UUID NOT NULL,
    score DOUBLE PRECISION NOT NULL CHECK (score >= 0),
    rank INTEGER CHECK (rank >= 1),
    learning_rank INTEGER CHECK (learning_rank >= 1),
    pop_pct REAL NOT NULL CHECK (pop_pct >= 0 AND pop_pct <= 1),
    added_count INTEGER NOT NULL CHECK (added_count >= 0),
    learning_now_count INTEGER NOT NULL CHECK (learning_now_count >= 0),
    activated_count INTEGER NOT NULL CHECK (activated_count >= 0),
    topic_id TEXT,
    parent_topic_id TEXT,
    listed BOOLEAN NOT NULL,
    PRIMARY KEY (generation, deck_id),
    CONSTRAINT deck_rank_ranked_when_listed CHECK (listed = (rank IS NOT NULL) AND listed = (learning_rank IS NOT NULL))
);

CREATE INDEX deck_rank_popular ON app_learning.deck_rank(generation, rank) WHERE listed;
CREATE INDEX deck_rank_learning ON app_learning.deck_rank(generation, learning_rank) WHERE listed;
CREATE INDEX deck_rank_topic ON app_learning.deck_rank(generation, topic_id, score DESC, deck_id DESC) WHERE listed;
CREATE INDEX deck_rank_parent_topic ON app_learning.deck_rank(generation, parent_topic_id, score DESC, deck_id DESC) WHERE listed;

CREATE TABLE app_learning.catalog_anomaly (
    deck_id UUID PRIMARY KEY,
    public_code TEXT NOT NULL CHECK (public_code ~ '^[1-9A-HJ-NP-Za-km-z]{10}$'),
    title TEXT NOT NULL,
    owner_id UUID NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL CHECK (isfinite(detected_at)),
    window_forks INTEGER NOT NULL CHECK (window_forks >= 1),
    new_share NUMERIC(5, 4) NOT NULL CHECK (new_share >= 0 AND new_share <= 1),
    dismissed_at TIMESTAMPTZ CHECK (dismissed_at IS NULL OR (isfinite(dismissed_at) AND dismissed_at >= detected_at))
);

ALTER TABLE app_learning.admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE app_learning.admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('EVENT_CREATE','EVENT_REPLACE','EVENT_DELETE','PROMO_CREATE','PROMO_ENABLE','PROMO_DISABLE','COMPLAINT_DECIDE','COMPLAINT_UNHIDE','COMMUNITY_ANOMALY_DISMISS'));

-- BACKFILL BEGIN (FORK)
-- The copies that exist now. No identity claims are known for the past: trust and new_account are NULL (unknown), so every one weighs 0.25 in the rank and none is "new"
-- for the anomaly query, and a later copy request by the same account fills them in. One grouped pass over deck_fork (a handful of rows in production), never the source's own copies.
INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account)
SELECT f.upstream_deck_id, f.owner_id, 'FORK', min(f.forked_at), min(f.forked_at), NULL, NULL
  FROM app_learning.deck_fork f
  JOIN app_learning.deck u ON u.deck_id = f.upstream_deck_id AND u.owner_id <> f.owner_id
 GROUP BY f.upstream_deck_id, f.owner_id
ON CONFLICT (deck_id, account_id, kind) DO NOTHING;
-- BACKFILL END (FORK)

-- BACKFILL BEGIN (STUDY)
-- The completed SCHEDULED sessions of live copies, one row per (source, learner): distinct local dates and the span of completion times. The join starts from deck_fork
-- (few rows) and reaches study_session through its (account_id, deck_id, created_at) index, so the pass is bounded by the sessions of copies and not by all sessions.
INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, study_days, last_study_date)
SELECT f.upstream_deck_id, s.account_id, 'STUDY', min(s.completed_at), max(s.completed_at), count(DISTINCT s.local_study_date)::integer, max(s.local_study_date)
  FROM app_learning.deck_fork f
  JOIN app_learning.deck u ON u.deck_id = f.upstream_deck_id AND u.owner_id <> f.owner_id
  JOIN app_learning.study_session s ON s.account_id = f.owner_id AND s.deck_id = f.deck_id
 WHERE f.released_at IS NULL AND s.mode = 'SCHEDULED' AND s.status = 'COMPLETE' AND s.completed_at IS NOT NULL
 GROUP BY f.upstream_deck_id, s.account_id
ON CONFLICT (deck_id, account_id, kind) DO NOTHING;
-- BACKFILL END (STUDY)
