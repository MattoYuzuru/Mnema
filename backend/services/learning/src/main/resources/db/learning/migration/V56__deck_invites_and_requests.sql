-- Share/13 (#435): invitation links and access requests of an «По приглашению» deck (community decks architecture sections 3, 6 and 9).
-- Grants (deck_access_grant) stay as V53 made them; this adds the two ways a person gets one without the owner typing a login.
--
-- deck_invite: one row per invitation link. The token (32 random bytes, base64url) is shown ONCE at creation and never stored: token_hash is its SHA-256
--   digest and the only lookup key (UNIQUE: a redeem is one index probe). max_uses NULL means unlimited, 1 a one-time link; uses is advanced under the row
--   lock by a redeem, and the CHECK is the backstop that makes "concurrent redemptions never exceed max_uses" true even if the code were wrong.
--   expires_at NULL never expires; revoked_at is set once. last_used_at is the time of the last redemption (so the time an exhausted invitation died is known).
--   code_rotated_at is the deck_publication.code_rotated_at the invitation was created under: the "generation" of the link. Any move to a more restrictive level rotates
--   the public code, and an invitation of an older generation no longer opens anything (the canon: when visibility is lowered, links change and old links stop working).
--   A guard trigger freezes everything but uses, last_used_at and revoked_at.
-- deck_access_request: one row per (deck, requester). The row is rewritten, not appended, when a rejected request is repeated after the waiting period,
--   so a requester never has more than one row per deck. status PENDING has no decision time, every other status has one. WITHDRAWN is written by the
--   requester's own withdrawal. revoked_at marks a REJECTED row that came from the owner REVOKING a grant: for 30 days an invitation link cannot let that
--   person back in (the owner can still add them by login).
SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.deck_invite (
    invite_id UUID PRIMARY KEY DEFAULT uuidv7(),
    deck_id UUID NOT NULL REFERENCES app_learning.deck(deck_id),
    token_hash BYTEA NOT NULL CHECK (octet_length(token_hash) = 32),
    max_uses INTEGER CHECK (max_uses IS NULL OR max_uses BETWEEN 1 AND 1000),
    uses INTEGER NOT NULL DEFAULT 0 CHECK (uses >= 0),
    expires_at TIMESTAMPTZ CHECK (expires_at IS NULL OR isfinite(expires_at)),
    revoked_at TIMESTAMPTZ CHECK (revoked_at IS NULL OR isfinite(revoked_at)),
    last_used_at TIMESTAMPTZ CHECK (last_used_at IS NULL OR isfinite(last_used_at)),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    code_rotated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(code_rotated_at)),
    CONSTRAINT deck_invite_token_key UNIQUE (token_hash),
    CONSTRAINT deck_invite_last_use CHECK ((uses = 0) = (last_used_at IS NULL)),
    CONSTRAINT deck_invite_uses_within_max CHECK (max_uses IS NULL OR uses <= max_uses),
    CONSTRAINT deck_invite_expiry_after_creation CHECK (expires_at IS NULL OR expires_at > created_at)
);
-- the owner's list (newest first, keyset) and the active-invitation count
CREATE INDEX deck_invite_deck ON app_learning.deck_invite(deck_id, created_at DESC, invite_id DESC);

CREATE FUNCTION app_learning.deck_invite_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.invite_id <> OLD.invite_id OR NEW.deck_id <> OLD.deck_id OR NEW.token_hash <> OLD.token_hash
       OR NEW.max_uses IS DISTINCT FROM OLD.max_uses OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
       OR NEW.created_at <> OLD.created_at OR NEW.code_rotated_at <> OLD.code_rotated_at OR NEW.uses < OLD.uses
       OR (NEW.last_used_at IS DISTINCT FROM OLD.last_used_at AND NEW.uses = OLD.uses)
       OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
        RAISE EXCEPTION 'Invalid deck invite transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deck_invite_guard BEFORE UPDATE ON app_learning.deck_invite
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_invite_guard();

CREATE TABLE app_learning.deck_access_request (
    deck_id UUID NOT NULL REFERENCES app_learning.deck(deck_id),
    requester_id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    decided_at TIMESTAMPTZ CHECK (decided_at IS NULL OR isfinite(decided_at)),
    revoked_at TIMESTAMPTZ CHECK (revoked_at IS NULL OR isfinite(revoked_at)),
    PRIMARY KEY (deck_id, requester_id),
    CONSTRAINT deck_access_request_revocation CHECK (revoked_at IS NULL OR status = 'REJECTED'),
    CONSTRAINT deck_access_request_decision CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT deck_access_request_decided_after_created CHECK (decided_at IS NULL OR decided_at >= created_at)
);
-- the owner's open requests, newest first (keyset)
CREATE INDEX deck_access_request_pending ON app_learning.deck_access_request(deck_id, created_at DESC, requester_id DESC)
    WHERE status = 'PENDING';
-- a requester's limits: "at most 20 open" and "at most 5 new in 24 hours" are range reads of the requester's own rows
CREATE INDEX deck_access_request_open ON app_learning.deck_access_request(requester_id) WHERE status = 'PENDING';
CREATE INDEX deck_access_request_requester ON app_learning.deck_access_request(requester_id, created_at DESC);
