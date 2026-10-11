-- Share/9 (#431): the media of someone else's deck and of guests (community decks architecture sections 8, 9 and 12).
--
-- published_media: the set of assets the MATERIALS of the PUBLISHED revision of a deck reference, one row per (deck, asset). Exercise pins are not in it: the public
--   view shows exercises as plain-text prompts and a pin does not say whether an asset is part of the question or of the answer, so exercise media is never offered
--   to a guest (a copy's owner reaches it through the heads of his own deck). The architecture sketch keys it by
--   (deck_id, revision_id, asset_id), which would copy every row at every publication. This is the incremental shape: a row says "the published
--   revision uses this asset" and ref_count (> 0) counts the media references (content nodes) of the published materials that point at
--   it, so the publication command maintains it from the journal range between the previous published sequence and the new head (cost O(references of
--   the changed units), never O(deck)), and a read is one primary-key probe. A downgrade to PRIVATE keeps the rows (the published pointer is kept; the
--   access level decides who may use them). The foreign key to deck_publication means "only a deck that ever left private has a set".
--   Authorization of a non-owner is: DeckAccess grants a level for the deck AND this row exists (fail closed: no row, no access).
--
-- deck_scope_owner: "does the viewer own a live deck of the lineage that holds the asset in its heads" (the copy owner's own media, Share/10 copies share
--   the lineage of their source) starts from the media references of the asset, goes to the viewer's decks of THAT lineage through this index and ends with
--   two primary-key probes of the heads.
--
-- The non-commercial stock media check of Share/8 needs no schema change: its partial index generation_provenance_nc stays (the statement starts from the
--   non-commercial provenance rows and matches each to the references of ITS asset, whoever publishes the deck; see PublicationRepository).
SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.published_media (
    deck_id UUID NOT NULL REFERENCES app_learning.deck_publication(deck_id),
    asset_id UUID NOT NULL REFERENCES app_learning.media_asset(asset_id),
    ref_count INTEGER NOT NULL CHECK (ref_count > 0),
    PRIMARY KEY (deck_id, asset_id)
);

CREATE INDEX deck_scope_owner ON app_learning.deck(reuse_scope_id, owner_id) WHERE deleted_at IS NULL;

-- BACKFILL BEGIN
-- Decks published before this migration (Share/8 shipped first): the published set is the head's references, corrected by the journal after the
-- published revision. For a material changed after that sequence, the head's references are replaced by those of the revision the FIRST change replaced
-- (NULL for a material created after it: nothing). The publication command applies the same correction forward (PublishedMediaRepository).
WITH published AS (
    SELECT p.deck_id, d.reuse_scope_id, r.sequence
      FROM app_learning.deck_publication p
      JOIN app_learning.deck d ON d.deck_id = p.deck_id
      JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
     WHERE p.published_revision_id IS NOT NULL
), changed_items AS (
    SELECT DISTINCT ON (c.deck_id, c.member_key) c.deck_id, pu.reuse_scope_id, c.member_key, c.previous_revision_id AS old_revision
      FROM published pu
      JOIN app_learning.deck_item_change c ON c.deck_id = pu.deck_id AND c.deck_sequence > pu.sequence
     ORDER BY c.deck_id, c.member_key, c.deck_sequence, c.change_ordinal
), counted AS (
    SELECT pu.deck_id, r.asset_id, 1 AS n
      FROM published pu
      JOIN app_learning.deck_head_item h ON h.deck_id = pu.deck_id
      JOIN app_learning.content_media_ref r ON r.reuse_scope_id = h.reuse_scope_id AND r.member_key = h.member_key AND r.revision_id = h.revision_id
    UNION ALL
    SELECT c.deck_id, r.asset_id, -1
      FROM changed_items c
      JOIN app_learning.deck_head_item h ON h.deck_id = c.deck_id AND h.member_key = c.member_key
      JOIN app_learning.content_media_ref r ON r.reuse_scope_id = h.reuse_scope_id AND r.member_key = h.member_key AND r.revision_id = h.revision_id
    UNION ALL
    SELECT c.deck_id, r.asset_id, 1
      FROM changed_items c
      JOIN app_learning.content_media_ref r ON r.reuse_scope_id = c.reuse_scope_id AND r.member_key = c.member_key AND r.revision_id = c.old_revision
)
INSERT INTO app_learning.published_media(deck_id, asset_id, ref_count)
SELECT deck_id, asset_id, sum(n)::integer FROM counted GROUP BY deck_id, asset_id HAVING sum(n) > 0;
-- BACKFILL END
