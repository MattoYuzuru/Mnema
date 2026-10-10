-- Share/7 (#429): deck access levels, the public code of a deck and the grants of invited readers
-- (community decks architecture sections 3, 6 and 9).
--
-- deck_publication: one row per deck that ever left «private»; NO ROW MEANS PRIVATE. It is a table of its own because deck_head_guard forbids
--   any change of a deck column other than the head, so a visibility change can never be an UPDATE of deck and never touches deck.row_version
--   (an open editor does not get a 412 when the owner changes who can read).
--   visibility: PRIVATE (a deck that went back), LINK (anyone with the link, guests included), INVITE (the owner and granted accounts), PUBLIC.
--   public_code: 10 characters of the Bitcoin base58 alphabet from a CSPRNG (no 0, O, I, l), the ONLY entry point of a non-owner. It is rotated
--     whenever the visibility moves to a MORE RESTRICTIVE level (PUBLIC > LINK > INVITE > PRIVATE), so every old link stops working at once. UNIQUE: the lookup of
--     a request is one probe of this index.
--   published_revision_id: the revision of the deck that non-owners read. NULL until the first publication (Share/8 adds the command); the
--     FK ties it to a revision of THIS deck. A deck without it is invisible to non-owners at every level.
--   row_version: compare-and-set token of the publication row (visibility, rotation, later the publication itself).
-- deck_access_grant: the accounts an owner invited to read an INVITE deck. VIEWER is the only role in use; EDITOR is reserved by the CHECK and
--   rejected by the code until co-authors exist.
SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.deck_publication (
    deck_id UUID PRIMARY KEY REFERENCES app_learning.deck(deck_id),
    visibility TEXT NOT NULL CHECK (visibility IN ('PRIVATE', 'LINK', 'INVITE', 'PUBLIC')),
    public_code TEXT NOT NULL CHECK (public_code ~ '^[1-9A-HJ-NP-Za-km-z]{10}$'),
    code_rotated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(code_rotated_at)),
    published_revision_id UUID,
    published_at TIMESTAMPTZ CHECK (published_at IS NULL OR isfinite(published_at)),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    CONSTRAINT deck_publication_code_key UNIQUE (public_code),
    CONSTRAINT deck_publication_revision_fkey FOREIGN KEY (deck_id, published_revision_id)
        REFERENCES app_learning.deck_revision(deck_id, revision_id),
    CHECK ((published_revision_id IS NULL) = (published_at IS NULL))
);

-- The identity of the row and its version sequence are not negotiable: a deck never changes, the version only grows by one.
CREATE FUNCTION app_learning.deck_publication_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deck_id <> OLD.deck_id OR NEW.created_at <> OLD.created_at OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'Invalid deck publication transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deck_publication_guard BEFORE UPDATE ON app_learning.deck_publication
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_publication_guard();

CREATE TABLE app_learning.deck_access_grant (
    deck_id UUID NOT NULL REFERENCES app_learning.deck(deck_id),
    grantee_id UUID NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('VIEWER', 'EDITOR')),
    granted_by UUID NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(granted_at)),
    PRIMARY KEY (deck_id, grantee_id)
);
-- "the decks shared with me" (invitation lists, Share/13): newest first, keyset-paged
CREATE INDEX deck_access_grant_grantee ON app_learning.deck_access_grant(grantee_id, granted_at DESC, deck_id DESC);
