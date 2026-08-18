\set ON_ERROR_STOP on

COPY (
    SELECT
        namespace.nspname || '.' || class.relname AS object_name,
        pg_total_relation_size(class.oid) AS total_bytes,
        pg_relation_size(class.oid) AS table_bytes,
        pg_indexes_size(class.oid) AS index_bytes
    FROM pg_class AS class
    JOIN pg_namespace AS namespace ON namespace.oid = class.relnamespace
    WHERE class.relkind IN ('r', 'p', 'm')
      AND namespace.nspname IN ('auth', 'app_user', 'app_core', 'app_media', 'app_import')
    ORDER BY pg_total_relation_size(class.oid) DESC, object_name
) TO STDOUT WITH (FORMAT CSV, HEADER true);
