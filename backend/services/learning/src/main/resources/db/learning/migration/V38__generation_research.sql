-- Web research of a material (#299, AI-18).
--
-- generation_research: what the RESEARCH step of one artifact found, written once by the step when it ends and replaced by a retry's new step. One row per
-- artifact; the row of an artifact whose research found nothing (or was skipped) exists too, with requests = 0 or results = [], so a reader can tell
-- "researched, found nothing" (a row) from "no research" (no row).
--   requests: the paid provider requests that were answered; the debit of the step is WEB_SEARCH_QUERY x requests.
--   results:  the numbered results in [n] order (n is the position, 1-based): [{n, url, title, snippet, date, provider, queryIndex}] where snippet is at
--             most 300 characters, provider is YANDEX, PERPLEXITY or STUB and the whole document is at most 64 KiB. These are pointers: page text, saved
--             copies and anything else a provider returns are never stored. The allowlist of the compiler and the sources of the proposal are built from them.
-- The row goes with its artifact (and so with its session).
CREATE TABLE app_learning.generation_research (
    artifact_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    requests INTEGER NOT NULL CHECK (requests BETWEEN 0 AND 50),
    results JSONB NOT NULL CHECK (jsonb_typeof(results) = 'array' AND jsonb_array_length(results) <= 100 AND octet_length(results::text) <= 65536),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    FOREIGN KEY (artifact_id, session_id, owner_id)
        REFERENCES app_learning.generation_artifact(artifact_id, session_id, owner_id) ON DELETE CASCADE
);
CREATE INDEX generation_research_session ON app_learning.generation_research(session_id);
