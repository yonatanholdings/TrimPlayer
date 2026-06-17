BEGIN;

-- All-time per-bucket running totals (5 rows: intro/outro/ad/silence/speed).
CREATE TABLE IF NOT EXISTS community_impact (
    skip_type   TEXT PRIMARY KEY,
    total_ms    BIGINT NOT NULL DEFAULT 0
);

-- One row per client: powers the all-time contributor count and the
-- incremental community average playback speed.
CREATE TABLE IF NOT EXISTS community_clients (
    client_id        TEXT PRIMARY KEY,
    first_ts         BIGINT NOT NULL,
    last_avg_speed   REAL                 -- NULL until the client reports one
);

-- Single-row global scalars, so contributor count + speed mean are O(1) reads
-- (no COUNT/AVG scan). Exactly one row, id = TRUE.
CREATE TABLE IF NOT EXISTS community_scalars (
    id             BOOLEAN PRIMARY KEY DEFAULT TRUE,
    contributors   BIGINT NOT NULL DEFAULT 0,
    speed_sum      DOUBLE PRECISION NOT NULL DEFAULT 0,  -- sum of per-client avg speeds
    speed_n        BIGINT NOT NULL DEFAULT 0             -- clients with a speed
);
INSERT INTO community_scalars (id) VALUES (TRUE) ON CONFLICT DO NOTHING;

-- Per-day per-bucket totals; trailing windows sum the last N days.
CREATE TABLE IF NOT EXISTS community_impact_daily (
    day         DATE NOT NULL,
    skip_type   TEXT NOT NULL,
    total_ms    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (day, skip_type)
);

-- Per-day distinct-client set -> active_contributors over a window. Deduped per
-- (day, client) so COUNT(DISTINCT) over the window is cheap.
CREATE TABLE IF NOT EXISTS community_active_daily (
    day         DATE NOT NULL,
    client_id   TEXT NOT NULL,
    PRIMARY KEY (day, client_id)
);
CREATE INDEX IF NOT EXISTS idx_active_daily_day ON community_active_daily (day);

-- Precomputed window cache (one row per chip label: 7d/30d/90d/1y). Rebuilt by
-- a periodic refresh (api_pg.refresh_community_windows) so the endpoint never
-- runs the per-window distinct-counts on the request path.
CREATE TABLE IF NOT EXISTS community_windows (
    label                TEXT PRIMARY KEY,
    ads_ms               BIGINT NOT NULL DEFAULT 0,
    silence_ms           BIGINT NOT NULL DEFAULT 0,
    speed_ms             BIGINT NOT NULL DEFAULT 0,
    intro_ms             BIGINT NOT NULL DEFAULT 0,
    outro_ms             BIGINT NOT NULL DEFAULT 0,
    active_contributors  BIGINT NOT NULL DEFAULT 0,
    refreshed_at         BIGINT NOT NULL DEFAULT 0  -- unix ms
);

COMMIT;
