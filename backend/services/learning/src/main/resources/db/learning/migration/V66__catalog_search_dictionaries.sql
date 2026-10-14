-- Community/0 (#447): the text search building blocks of the community catalogue (community decks architecture sections 10 and 15).
--
-- pg_trgm  typo tolerance (word_similarity over the normalized title). V28 made it optional for the generation module; the catalogue needs
--          it, so here it is required. Production installed it in init-database.sh; the check of 2026-10-12 showed that the production
--          cluster (en_US.utf8, libc provider) builds Cyrillic trigrams: show_trgm('Привет') has 7 entries.
-- unaccent the dictionary of simple_unaccent, the second search vector of public_deck (Latin, Greek and similar scripts written with or
--          without marks: «Español» and «espanol» meet; «ё» and «е» meet too).
--
-- Both extensions are trusted (PostgreSQL 13+): a role with CREATE on the database may create them (tests, the local stacks, the black-box
-- harness). The production role app_learning has no such privilege, so the administrator installs them once as the superuser before the
-- release that carries this migration (init-database.sh does it for a new cluster); IF NOT EXISTS then makes these statements no-ops there.
-- A missing extension fails the migration on purpose: the catalogue must not start without its search.
SET LOCAL lock_timeout = '5s';

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA app_learning;
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA app_learning;

-- simple + unaccent: no stemming (any language), marks removed, lower case. The dictionary is named with its schema, so the configuration
-- does not depend on the search_path of the session that uses it.
CREATE TEXT SEARCH CONFIGURATION app_learning.simple_unaccent (COPY = pg_catalog.simple);
ALTER TEXT SEARCH CONFIGURATION app_learning.simple_unaccent
    ALTER MAPPING FOR asciiword, asciihword, hword_asciipart, word, hword, hword_part
    WITH app_learning.unaccent, pg_catalog.simple;
