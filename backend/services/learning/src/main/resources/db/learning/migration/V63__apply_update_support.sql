-- Updates/4 (#443): the apply command of a copy's update plan (community decks architecture section 7).
--
-- 1. combined_publication.final_writes may be 4 MiB (it was 128 KiB for a body of 64 KiB). The apply command writes, in the final transaction of its combined publication, one
--    row of fork_unit_decision (V61) for every unit of the plan it does not take (the units left pending are one row each), so a plan of thousands of units is hundreds of
--    kilobytes of final writes; a command that is a job stores them with its units until the job ends. FinalWrites.MAX_BYTES is the same 4 MiB of body; the column keeps room for
--    the envelope of type and body around it. The text is still stored byte for byte (TEXT, not jsonb).
-- 2. learning_job_combined_owner: the plan route asks, for every read of a copy's plan, for the newest job of the combined publication queue of its owner for that copy
--    (APPLYING while it runs, lastApplyFailed after a failed one). The jobs of one account on this queue are few, so an index by account in time order answers it with a short
--    scan of the account's own rows, whatever the number of jobs of all accounts in the 7 days of retention.
-- 3. fork_apply_log: when the owner's last successful apply command of the copy was written (one row per copy, replaced by the next one). The plan route shows `lastApplyFailed`
--    after a failed job only while no apply of the copy has succeeded since, whether or not that apply created a revision; the final writer of the apply command writes the row in
--    the same transaction as the decisions. It goes with the copy (deck_fork), so the account purge, which deletes deck_fork before deck, takes it with it.
SET LOCAL lock_timeout = '5s';

ALTER TABLE app_learning.combined_publication DROP CONSTRAINT combined_publication_final_writes_check;
ALTER TABLE app_learning.combined_publication ADD CONSTRAINT combined_publication_final_writes_check
    CHECK (final_writes IS NULL OR octet_length(final_writes) <= 4198400);

CREATE INDEX learning_job_combined_owner ON app_learning.learning_job (account_id, created_at DESC, job_id DESC) WHERE queue = 'deck.combined.publish';

CREATE TABLE app_learning.fork_apply_log (
    deck_id UUID PRIMARY KEY REFERENCES app_learning.deck_fork(deck_id) ON DELETE CASCADE,
    applied_at TIMESTAMPTZ NOT NULL CHECK (isfinite(applied_at))
);
