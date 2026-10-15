-- Recs/4: read-only anonymous export. Never selects account, code, revision, query or content.
-- Run only on an independently authorised database; psql -qAt -v from=YYYY-MM-DD -v until=YYYY-MM-DD -f this.sql.
-- Half-open date interval, UTC. Output shape accepted by evaluate-rankers.py --logs.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL TIME ZONE 'UTC';
WITH exposures AS (
    SELECT day::text AS day, rank_version AS "rankVersion", surface, sum(impressions)::bigint AS count
      FROM app_learning.deck_exposure_day
     WHERE day >= :'from'::date AND day < :'until'::date
     GROUP BY day, rank_version, surface
), events AS (
    SELECT (occurred_at AT TIME ZONE 'UTC')::date::text AS day,
           rank_version AS "rankVersion", surface, kind, count(*)::bigint AS count
      FROM app_learning.catalog_event
     WHERE occurred_at >= :'from'::date AT TIME ZONE 'UTC'
       AND occurred_at < :'until'::date AT TIME ZONE 'UTC'
     GROUP BY 1, rank_version, surface, kind
)
SELECT json_build_object(
    'exposures', coalesce((SELECT json_agg(e ORDER BY day, "rankVersion", surface) FROM exposures e), '[]'::json),
    'events', coalesce((SELECT json_agg(e ORDER BY day, "rankVersion", surface, kind) FROM events e), '[]'::json));
COMMIT;
