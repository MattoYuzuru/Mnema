-- Community/1 (#448): public_deck, the catalogue projection of the community decks (architecture sections 3, 10, 12 and 14; RFC 9.1, 9.2 and 15.1).
--
-- public_deck: ONE row per deck that is PUBLIC, has a published revision, is not hidden by moderation and is not deleted; there is no row for any other deck, so
--   "is in the catalogue or on an author's page" is "has a row" and lowering the level, hiding or deleting removes the row. It is built ONLY from the PUBLISHED
--   revision (never the head) by the background job catalog.project, never in the publication transaction: the publication command writes its outbox row
--   (deck_publication_event) and one job row, the job reads the CURRENT state of the deck (not the event), so jobs of one deck may run in any order or twice
--   and still converge. The by-link view of a deck does not read this table at all.
--   public_code           the current public code (a rotation means the level was lowered, which deletes the row anyway).
--   published_revision_id the revision every other column is derived from; the composite FK keeps it a real revision of the deck.
--   title, description    of the published revision. topic_id is a leaf of the topic directory; parent_topic_id its parent, denormalized so that the
--                         parent-topic filter and the parent shelves are one equality (a topic is re-parented only by a data edit; a rebuild repairs the rows).
--   content_language, target_language, level, tags    copied from deck_publication (the same closed vocabularies, same CHECKs on the source).
--   item_count, exercise_count    deck_revision.member_count / exercise_count: O(1), no manifest is read for them.
--   media_mask    bit 1 image, 2 audio, 4 video: the media of the published MATERIALS (published_media joined to the verified source of the READY asset,
--                 mime type image/*, audio/*, video/*). Exercise-only media is not shown to anybody but a copy's owner (Share/9), so it sets no bit.
--   mechanics_mask  bit 1 LISTENING (an exercise that references an AUDIO asset), 2 MATCHING (MATCH, CATEGORIZE), 4 WRITTEN (FREE_RESPONSE, CLOZE),
--                 8 CHOICE, 16 ORDER; SELF_CHECK sets no bit. Computed from the published EXERCISES manifest by the job in slices of at most 500 entries and written,
--                 with projected_revision_id, by the slice that finishes the scan; until then it keeps the value of the previous revision (a deck does not flicker
--                 out of a mechanic filter while its new revision is being scanned). The mapping lives in one Java enum (CatalogMechanic), the wire vocabulary of the filters.
--   listed        the catalogue threshold, GENERATED so that no writer can disagree with it: at least 10 materials, at least one exercise and a non-blank description
--                 (PublicationService.NEED_MATERIALS / NEED_EXERCISES). A deck below it keeps its row (the author's page shows it) and is left out of every
--                 catalogue query, which asks WHERE listed (the partial indexes below).
--   first_listed_at  "Новое": the first time the deck became PUBLIC with a published revision = the earliest PUBLIC deck_publication_event of the deck, kept by the row
--                 across re-projections and recomputed from the journal when the row is created again (demotion and promotion keep the date).
--   content_updated_at  "Обновлена": deck_publication.published_at, the time the published revision last moved.
--   title_norm    AliasNormalizer.normalize(title), computed in Java: unaccent() is only STABLE, so it cannot feed a generated column (and one normalization is
--                 shared with the topic aliases).
--   search_ru     STORED tsvector, russian stemming, weights A title, B description, C tags. search_simple the same weights under app_learning.simple_unaccent (V66:
--                 no stemming, marks and «ё» folded: Latin and other scripts). Both are generated, so no writer can leave them stale.
--   projected_revision_id  the published revision whose exercises were scanned for mechanics_mask; NULL until the first scan finished. The job scans only when it
--                 differs from published_revision_id, so a metadata-only republication and a rebuild of an unchanged deck read no manifest.
--   projected_at  when the row last changed.
--
-- Indexes, each with the query that uses it (EXPLAIN tests in PublicDeckProjectionPlanIntegrationTest):
--   public_deck_search_ru / public_deck_search_simple (GIN)  Community/2: search_ru @@ websearch_to_tsquery('russian', q) OR search_simple @@ websearch_to_tsquery('app_learning.simple_unaccent', q).
--   public_deck_title_trgm (GIN gin_trgm_ops)  Community/2: title_norm %> normalized query (word_similarity), the typo tolerance.
--   public_deck_owner (owner_id, content_updated_at DESC, deck_id DESC)  the author's page /u/{username} (Community/6): their decks newest first, listed or not.
--   public_deck_new (first_listed_at DESC, deck_id DESC) WHERE listed  the «Новое» shelf and the sort «Новые» (keyset page of CatalogRepository.listedPage).
--   public_deck_updated (content_updated_at DESC, deck_id DESC) WHERE listed  the sort «Обновлённые» and the «живая» filter (updated within a month).
--   public_deck_topic (topic_id, first_listed_at DESC, deck_id DESC) WHERE listed  the leaf-topic filter and shelf, newest first; the count of listed decks of a topic.
--   public_deck_parent_topic (parent_topic_id, first_listed_at DESC, deck_id DESC) WHERE listed  the same for a parent topic (a chip of the first level).
-- A rank lookup (Community/3) is a primary-key probe of public_deck by deck_id. No index on the facet filters (level, language, size, media, mechanics): they
-- are applied to the candidates of the text search or of a shelf, which are bounded by the index above them.
--
-- deck_publication_event_public (deck_id, occurred_at) WHERE visibility = 'PUBLIC': first_listed_at is min(occurred_at) of a deck's PUBLIC events. The outbox had
--   no index on deck_id at all (it was only appended to), so without this index the statement of the projector would read the whole journal for every deck.
--   Partial because only PUBLIC rows are ever asked for; the table is append-only, so the index costs one entry per public write.
--
-- BACKFILL: decks that are already public have no row. This migration enqueues ONE system job of the queue catalog.rebuild (idempotent by the dedupe key, so a
--   repeated or replayed migration adds nothing); the worker runs it, it walks deck_publication in deck_id order in slices and enqueues catalog.project for every
--   deck that is PUBLIC or has a row. The same job is the repair tool for any divergence of the projection ("Расхождение проекции — перестраиваемая задачей").
--   No Java runs inside the migration. The job is created QUEUED and due now; if no worker runs yet it waits in the table.
--
-- Load. Zero: the table is empty and no job exists apart from the one backfill job, which walks an empty index range; a publication adds one outbox row and one
--   job row. Scenario H (5k public decks, 300 materials on average, one viral deck with 10^5 units): the table is about 5k rows of well under 4 KiB each (the vectors
--   dominate, tens of KiB for the longest descriptions), indexes included about 30 MiB; a publication is one outbox row plus one job row, the projector reads O(1) rows
--   for the metadata, O(published assets) for the media bits and, only when the published REVISION changed, O(exercises) in slices of 500 (the viral deck: 200 slices).
--   At roughly 5k publications and republications a day (an upper estimate for H) that is 10k small rows a day in the journal and the job table (7 days of finished jobs
--   are kept: about 35k rows, under 50 MiB), well inside a single PostgreSQL.
SET LOCAL lock_timeout = '5s';

-- array_to_string(text[], text) is only STABLE because the output function of an ARBITRARY element type may depend on settings. For text[] the output function is
-- the identity (the element text itself), which depends on nothing, so this wrapper is declared IMMUTABLE and may feed a generated column. The tags are at most five
-- strings of at most 32 characters (publication_tags_valid), joined by a space so that each tag stays its own token for the stemmer.
CREATE FUNCTION app_learning.catalog_tags_text(tags TEXT[]) RETURNS TEXT LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT array_to_string(tags, ' ');
$$;

CREATE TABLE app_learning.public_deck (
    deck_id UUID PRIMARY KEY REFERENCES app_learning.deck(deck_id),
    public_code TEXT NOT NULL CHECK (public_code ~ '^[1-9A-HJ-NP-Za-km-z]{10}$'),
    owner_id UUID NOT NULL,
    published_revision_id UUID NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    topic_id TEXT REFERENCES app_learning.topic(topic_id),
    parent_topic_id TEXT REFERENCES app_learning.topic(topic_id),
    content_language TEXT CHECK (content_language IS NULL OR content_language ~ '^[a-z]{2,3}$'),
    target_language TEXT CHECK (target_language IS NULL OR target_language ~ '^[a-z]{2,3}$'),
    level TEXT CHECK (level IS NULL OR level IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    tags TEXT[] NOT NULL DEFAULT '{}' CHECK (app_learning.publication_tags_valid(tags)),
    item_count INTEGER NOT NULL CHECK (item_count >= 0),
    exercise_count INTEGER NOT NULL CHECK (exercise_count >= 0),
    media_mask SMALLINT NOT NULL DEFAULT 0 CHECK (media_mask BETWEEN 0 AND 7),
    mechanics_mask SMALLINT NOT NULL DEFAULT 0 CHECK (mechanics_mask BETWEEN 0 AND 31),
    listed BOOLEAN GENERATED ALWAYS AS (item_count >= 10 AND exercise_count >= 1 AND btrim(description) <> '') STORED,
    first_listed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(first_listed_at)),
    content_updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(content_updated_at)),
    title_norm TEXT NOT NULL,
    search_ru TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('russian', title), 'A') || setweight(to_tsvector('russian', description), 'B')
        || setweight(to_tsvector('russian', app_learning.catalog_tags_text(tags)), 'C')) STORED,
    search_simple TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('app_learning.simple_unaccent', title), 'A') || setweight(to_tsvector('app_learning.simple_unaccent', description), 'B')
        || setweight(to_tsvector('app_learning.simple_unaccent', app_learning.catalog_tags_text(tags)), 'C')) STORED,
    projected_revision_id UUID,
    projected_at TIMESTAMPTZ NOT NULL CHECK (isfinite(projected_at)),
    FOREIGN KEY (deck_id, published_revision_id) REFERENCES app_learning.deck_revision(deck_id, revision_id),
    FOREIGN KEY (deck_id, projected_revision_id) REFERENCES app_learning.deck_revision(deck_id, revision_id)
);

CREATE INDEX public_deck_search_ru ON app_learning.public_deck USING gin (search_ru);
CREATE INDEX public_deck_search_simple ON app_learning.public_deck USING gin (search_simple);
CREATE INDEX public_deck_title_trgm ON app_learning.public_deck USING gin (title_norm app_learning.gin_trgm_ops);
CREATE INDEX public_deck_owner ON app_learning.public_deck(owner_id, content_updated_at DESC, deck_id DESC);
CREATE INDEX public_deck_new ON app_learning.public_deck(first_listed_at DESC, deck_id DESC) WHERE listed;
CREATE INDEX public_deck_updated ON app_learning.public_deck(content_updated_at DESC, deck_id DESC) WHERE listed;
CREATE INDEX public_deck_topic ON app_learning.public_deck(topic_id, first_listed_at DESC, deck_id DESC) WHERE listed;
CREATE INDEX public_deck_parent_topic ON app_learning.public_deck(parent_topic_id, first_listed_at DESC, deck_id DESC) WHERE listed;

CREATE INDEX deck_publication_event_public ON app_learning.deck_publication_event(deck_id, occurred_at) WHERE visibility = 'PUBLIC';

-- The backfill job (see above). max_attempts 5 is the default of a queue policy (JobSettings); the dedupe key makes the insert idempotent. The V50 trigger wakes a worker.
INSERT INTO app_learning.learning_job(queue, dedupe_key, account_id, payload, state, max_attempts, run_after)
VALUES ('catalog.rebuild', 'v67-backfill', NULL, '{"reason": "v67-backfill"}', 'QUEUED', 5, CURRENT_TIMESTAMP)
ON CONFLICT (queue, dedupe_key) DO NOTHING;
