-- Recs/1 (#456). Personal events have no content/query columns and no immutable-storage FK.
SET LOCAL lock_timeout = '5s';
CREATE TABLE app_learning.catalog_event (
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp() CHECK (isfinite(occurred_at)),
    event_id UUID NOT NULL DEFAULT uuidv7(),
    account_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    published_revision_id UUID NOT NULL,
    topic_id TEXT,
    target_language TEXT,
    kind TEXT NOT NULL CHECK (kind IN ('DETAIL_VIEW','SEARCH_CLICK','FORK','STUDY_START','DROP')),
    surface TEXT NOT NULL CHECK (surface IN ('catalog','search','for-you','author','detail','copy','study','decks')),
    position INTEGER CHECK (position BETWEEN 1 AND 1000),
    rank_version TEXT NOT NULL CHECK (rank_version IN ('catalog-v1','recs-v1')),
    PRIMARY KEY (occurred_at,event_id)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX catalog_event_account_time ON app_learning.catalog_event(account_id, occurred_at DESC);
-- Exactly the current and previous twelve UTC calendar months, plus two prepared months.
DO $$ DECLARE m DATE; BEGIN
    FOR i IN -12..2 LOOP
        m := (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + make_interval(months => i))::date;
        EXECUTE format('CREATE TABLE app_learning.catalog_event_%s PARTITION OF app_learning.catalog_event FOR VALUES FROM (%L) TO (%L)',
            to_char(m,'YYYYMM'), m::text || 'T00:00:00Z', (m + interval '1 month')::date::text || 'T00:00:00Z');
    END LOOP;
END $$;
CREATE TABLE app_learning.deck_exposure_day (
    day DATE NOT NULL,
    deck_id UUID NOT NULL,
    surface TEXT NOT NULL CHECK (surface IN ('catalog','search','for-you','author','detail','copy','study','decks')),
    rank_version TEXT NOT NULL CHECK (rank_version IN ('catalog-v1','recs-v1')),
    impressions BIGINT NOT NULL CHECK (impressions > 0),
    PRIMARY KEY (day, deck_id, surface, rank_version)
);

-- Telemetry retries have a seven-day horizon; do not retain personal event receipts forever.
CREATE INDEX command_receipt_community_events_expiry ON app_learning.command_receipt(created_at,command_id) WHERE command_scope='community.events';
