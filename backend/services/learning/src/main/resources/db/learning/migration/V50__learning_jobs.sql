-- Learning jobs (#428, Share/6): one generic durable background job executor (community-decks architecture section 2, "Исполнитель задач").
--
-- learning_job: one row per unit of background work. A job is identified by (queue, dedupe_key): enqueueing the same pair again is idempotent and
--   returns the existing job (the caller owns the key: a command id, or "<deck>:<revision>" for a derived job). A queue is a resource class with its own
--   bounded concurrency, lease and retry policy in configuration (learning.jobs.<queue>.*); the name is validated here only for shape.
--   state: QUEUED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED, with RUNNING -> QUEUED again for a retry (backoff in run_after) or a lost lease.
--   account_id: the subject of the per-account limit; NULL is a system job (no limit).
--   payload: the immutable input of the job, an object, bounded. progress: the opaque cursor of the handler, written by the executor in the same
--     transaction as the handler's slice and fenced by lease_token, so a crash resumes from the last committed slice and a stale worker changes nothing.
--     progress_count is the one figure the UI shows ("Готовим…"). The JSON text of jsonb is a little longer than the compact form the application
--     bounds to 16 KiB, so the table bounds are 32 KiB.
--   attempts counts consecutive failed attempts (a failed slice, a lost lease); a slice that commits progress, or the end of the job, resets it to 0, and
--     the job ends FAILED when a failure makes it reach max_attempts (the policy at enqueue time). A claim that could not even start a slice is given back
--     without counting. last_error is a short code, never content, a message or a token.
--   cancel_requested: set on a RUNNING job; the executor ends it CANCELLED before the next slice. A QUEUED job is cancelled at once.
--   lease_token / lease_until: set while RUNNING (both or neither). Every write of a running job is conditional on the token.
CREATE TABLE app_learning.learning_job (
    job_id UUID PRIMARY KEY DEFAULT uuidv7(),
    queue TEXT NOT NULL CHECK (queue ~ '^[a-z][a-z0-9_.-]{0,62}$'),
    dedupe_key TEXT NOT NULL CHECK (char_length(dedupe_key) BETWEEN 1 AND 200),
    account_id UUID,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 32768),
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 100),
    run_after TIMESTAMPTZ NOT NULL CHECK (isfinite(run_after)),
    lease_token UUID,
    lease_until TIMESTAMPTZ CHECK (lease_until IS NULL OR isfinite(lease_until)),
    progress JSONB CHECK (progress IS NULL OR (jsonb_typeof(progress) = 'object' AND octet_length(progress::text) <= 32768)),
    progress_count BIGINT NOT NULL DEFAULT 0 CHECK (progress_count >= 0),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    last_error TEXT CHECK (last_error IS NULL OR last_error ~ '^[A-Za-z0-9_.-]{1,64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (isfinite(updated_at)),
    finished_at TIMESTAMPTZ CHECK (finished_at IS NULL OR isfinite(finished_at)),
    UNIQUE (queue, dedupe_key),
    CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((state IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL))
);

-- per-account concurrency of one per queue, enforced by the database: a second claim of the same account in the same queue loses
CREATE UNIQUE INDEX learning_job_account_running ON app_learning.learning_job(queue, account_id) WHERE state = 'RUNNING' AND account_id IS NOT NULL;
-- the due claim and the queue-age gauge read the head of this index
CREATE INDEX learning_job_due ON app_learning.learning_job(queue, run_after, job_id) WHERE state = 'QUEUED';
-- the sweeper of lost leases
CREATE INDEX learning_job_lease ON app_learning.learning_job(lease_until) WHERE state = 'RUNNING';
-- retention of terminal jobs, oldest first (the job id is the tiebreak of the batch order)
CREATE INDEX learning_job_finished ON app_learning.learning_job(finished_at, job_id) WHERE state IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

-- a job that becomes claimable wakes the workers of another process (the hint of V39; the sweeper stays the source of truth)
CREATE TRIGGER learning_job_queued AFTER INSERT OR UPDATE OF state ON app_learning.learning_job
    FOR EACH ROW WHEN (NEW.state = 'QUEUED') EXECUTE FUNCTION app_learning.notify_work('mnema_learning_jobs');
