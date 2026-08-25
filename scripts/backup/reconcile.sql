\set ON_ERROR_STOP on

SET TIME ZONE 'UTC';
SET datestyle TO 'ISO, YMD';
SET intervalstyle TO 'postgres';
SET extra_float_digits TO 3;
SET bytea_output TO 'hex';

SELECT format(
    $query$
        SELECT
            'table'::text AS kind,
            %L::text AS object_name,
            count(*)::numeric AS row_count,
            coalesce(sum((('x' || substr(row_hash, 1, 16))::bit(64)::bigint)::numeric), 0) AS checksum_left,
            coalesce(sum((('x' || substr(row_hash, 17, 16))::bit(64)::bigint)::numeric), 0) AS checksum_right
        FROM (
            SELECT md5(row_to_json(source_row)::text) AS row_hash
            FROM %I.%I AS source_row
        ) AS row_hashes
    $query$,
    namespace.nspname || '.' || class.relname,
    namespace.nspname,
    class.relname
)
FROM pg_class AS class
JOIN pg_namespace AS namespace ON namespace.oid = class.relnamespace
WHERE class.relkind IN ('r', 'p', 'm')
  AND namespace.nspname IN ('auth', 'app_user', 'app_core', 'app_media', 'app_import')
ORDER BY namespace.nspname, class.relname
\gexec
