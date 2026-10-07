-- Editorial product updates belong to Learning; no account data crosses the Identity boundary.
CREATE TABLE app_learning.product_event (
    event_id UUID PRIMARY KEY,
    title TEXT NOT NULL CHECK (char_length(title) BETWEEN 1 AND 160 AND title = btrim(title)),
    body_markdown TEXT NOT NULL CHECK (char_length(body_markdown) BETWEEN 1 AND 16000),
    event_date DATE NOT NULL CHECK (event_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ CHECK (published_at IS NULL OR isfinite(published_at)),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at) AND updated_at >= created_at),
    CHECK (NOT published OR published_at IS NOT NULL)
);
CREATE INDEX product_event_public_timeline ON app_learning.product_event(event_date DESC, event_id DESC)
    WHERE published;
CREATE INDEX product_event_editorial_timeline ON app_learning.product_event(event_date DESC, event_id DESC);
