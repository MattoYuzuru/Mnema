-- Community/7 (#454): the owner's editorial shelf «Выбор Мнемы» (contracts/community/catalog.json, editorial; architecture section 10; product contract «Сообщество»).
--
-- editorial_pick   the ordered list of decks the owner chose: ONE row per deck (the primary key), 1..24 positions (the contract's editorialMax), each position once. The whole list is
--                  replaced by one command (PUT /api/admin/community/editorial), so a reorder is delete-and-insert inside one transaction under the lock of editorial_shelf; the rows
--                  of decks that stay keep their added_at and added_by. The shelf on the public page reads picks JOINED with public_deck WHERE listed, so a deck that is hidden by
--                  moderation, no longer public, deleted or below the threshold falls out of the shelf by itself and stays here with listed=false in the owner's view; nothing has to be
--                  cleaned up when that happens. ON DELETE CASCADE only so that a future hard delete of a deck (the account purge, #409) is not blocked: decks are soft-deleted today.
-- editorial_shelf  the version of the list for If-Match: exactly one row (the CHECK pins its key), row_version starts at 0 and grows by one with every EFFECTIVE change (a command that
--                  leaves the list as it is changes nothing). It is also the lock that serializes two commands.
-- admin_audit      gets the action COMMUNITY_EDITORIAL_UPDATE; resource_id is the shelf (its constant id), because one command can touch many decks and the journal has one resource.
--
-- No other index: the public shelf walks editorial_pick_position (at most 24 rows) and probes public_deck by its primary key; every other statement of Community/2, /6 and /7 is served by
-- the indexes of V67 and V68 (EXPLAINed in CatalogQueriesPlanTest).
--
-- Load. Zero: two tables with one and no rows, no job. H: the owner edits a list of at most 24 rows a few times a week; the public shelf is a 24-row scan, once per shelves request.
SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.editorial_shelf (
    shelf_id UUID PRIMARY KEY CHECK (shelf_id = '00000000-0000-7000-8000-00000000e001'),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at))
);

INSERT INTO app_learning.editorial_shelf(shelf_id, row_version, updated_at) VALUES ('00000000-0000-7000-8000-00000000e001', 0, CURRENT_TIMESTAMP);

CREATE TABLE app_learning.editorial_pick (
    deck_id UUID PRIMARY KEY REFERENCES app_learning.deck(deck_id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 24),
    added_at TIMESTAMPTZ NOT NULL CHECK (isfinite(added_at)),
    added_by UUID NOT NULL,
    CONSTRAINT editorial_pick_position UNIQUE (position)
);

ALTER TABLE app_learning.admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE app_learning.admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('EVENT_CREATE','EVENT_REPLACE','EVENT_DELETE','PROMO_CREATE','PROMO_ENABLE','PROMO_DISABLE','COMPLAINT_DECIDE','COMPLAINT_UNHIDE','COMMUNITY_ANOMALY_DISMISS',
     'COMMUNITY_EDITORIAL_UPDATE'));
