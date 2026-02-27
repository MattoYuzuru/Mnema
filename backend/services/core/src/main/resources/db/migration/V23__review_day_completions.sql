CREATE TABLE IF NOT EXISTS app_core.review_day_completions
(
    user_id            UUID        NOT NULL,
    review_day         DATE        NOT NULL,
    first_completed_at TIMESTAMPTZ NOT NULL,
    last_completed_at  TIMESTAMPTZ NOT NULL,
    completions_count  INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, review_day)
);

CREATE INDEX IF NOT EXISTS review_day_completions_review_day_idx
    ON app_core.review_day_completions (review_day);

COMMENT ON TABLE app_core.review_day_completions IS 'Tracks how many times a user fully completed a review queue per review day.';
COMMENT ON COLUMN app_core.review_day_completions.user_id IS 'User identifier.';
COMMENT ON COLUMN app_core.review_day_completions.review_day IS 'Review day bucket (time zone + cutoff aware, computed in service layer).';
COMMENT ON COLUMN app_core.review_day_completions.first_completed_at IS 'Timestamp of the first completion in this review day.';
COMMENT ON COLUMN app_core.review_day_completions.last_completed_at IS 'Timestamp of the latest completion in this review day.';
COMMENT ON COLUMN app_core.review_day_completions.completions_count IS 'How many queue completion events happened in this review day.';
