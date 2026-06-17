# Community Impact — feature spec

Status: **Draft for review** (2026-06-12). No code written yet.

## 1. What this is

A **collective trim-impact** surface: an anonymous, pooled count of how much
listening time the whole TrimPlayer community has reclaimed from advertisers,
silence, intros/outros, and speed-up — shown next to the user's own contribution.

It is a *movement banner*, not a leaderboard and not a discovery feed. No
individual is ever visible to anyone else; only the global pool plus the
viewing user's own numbers are shown.

### Goals (in priority order)
1. **Brand / identity** — a credible "the community reclaimed N years from ads"
   figure that gives TrimPlayer a movement to belong to.
2. **Retention** — the user's own contribution grows and is shown alongside the
   pool ("you + community").
3. **Acquisition / virality** — sets up a shareable "you + community" artifact
   (the share card itself is deferred to v2; see §9).

Explicitly **not** a goal: discovery.

### Decisions already locked
- **Anonymous aggregate only.** No per-user identity stored or shown for the
  pool. The viewing user sees *their own* numbers (already local) but never
  anyone else's.
- **"You + community"** on the same screen.
- **You-vs-community for *every* aspect.** Each metric shows the user's value
  beside the community's — e.g. "your average playback speed **1.62** vs the
  community **1.36**", "you saved **2h** of ads vs the average listener's
  **1.2h**". This is the retention/engagement core, not a footnote.
- **Max number, broken down by type.** Headline = the big collective total, but
  always decomposed into the five buckets so it isn't a black box.
- **Ads is the featured sub-number.** The total is mostly *speed* (every player
  has speed-up — it's not our differentiator); *ads* is the figure that tells
  the TrimPlayer story, so it is featured in copy and visual emphasis.
- **Actual numbers from launch — no cold-start hiding.** Show the real pool even
  while small; the only special-case is a literal-zero/empty state. (Earlier
  draft gated the collective banner behind a threshold; dropped per decision.)
- **Backend strategy A (incremental running totals).** Maintained in the write
  path; reads are O(1). No periodic full-table scan. (See §4.)

## 2. Placement

A new top-level Settings row **between "Talk to us" and "About"**, inside the
existing `project` `PreferenceCategory`.

- `ui/preferences/src/main/res/xml/preferences.xml` — currently
  `prefSendBugReport` ("Talk to us", `report_bug_title`) → `prefAbout`
  ("About", `about_pref`). Insert `prefCommunityImpact` between them.
- Rationale: the `project` category is the app's *identity / movement* meta
  (contact us, about the app). A community-impact banner belongs there, not in
  the functional Statistics screen.

## 3. Data foundation (mostly already exists)

The pipeline that feeds the time-saved buckets is **already shipping**:

- On-device, `PlaybackService` records every productive skip via
  `DBWriter.recordSkipEvent(itemId, type, durationMs[, ts])` with
  `type ∈ { intro, outro, ad, silence, speed }`
  (`PlaybackService.java:1178/1914/1982/3088`, etc.).
- `TrimEventsUploadWorker` batches new rows to `POST /events` with
  `{ client_id, client_event_id, skip_type, duration_ms, client_ts, … }`.
  Local rows are never deleted; only a high-water mark advances.
- Backend ingests into `client_events` (`schema_pg.sql:225`,
  `api_pg.py:1545` `post_client_events`), deduped on
  `(client_id, client_event_id)`.

So the backend **already holds every skip event with its type and duration,
keyed by an anonymous `client_id`.** For the five time-saved buckets, Community
Impact is pure *read-side*: no payload change, no new client measurement, no
schema change to `client_events`.

The same five buckets already drive the per-user screen
(`DBReader.SkipStatistics` → `TimeSavedStatisticsFragment`), so the personal
"you contributed" block reuses existing local data verbatim. The community
per-user average for each bucket is derived, not stored: `bucket_ms /
contributors`.

### The one new metric: average playback speed
"What speed do you listen at" is **not** a skip bucket and is not recorded today,
so the speed comparison (your 1.62 vs community 1.36) needs a small addition.
Two parts, both light:

1. **Compute it on-device.** Average speed is algebraically
   `s_avg = 1 + speed_saved_ms / wall_clock_listened_ms` — i.e. it falls out of
   the *existing* `speed` bucket plus a wall-clock-listened figure. **Verify at
   build time** whether the stats layer already exposes wall-clock listened time
   (`StatisticsItem.timePlayed` "respects speed, listening twice" — confirm
   whether that's wall-clock or audio-duration before relying on it). If no clean
   source exists, add a tiny `UserPreferences` accumulator updated where playback
   progress is already tracked. Surfaced locally as the user's own number.
2. **Upload one float.** Extend the `POST /events` request *root* (not each
   event) with an optional `avg_playback_speed`. Backend keeps a running average
   (§4). This is the only payload change in the whole feature; it carries no new
   personal data (a single aggregate float, same anonymity as the rest).

### `skip_type` mapping (must be exact)
The five **productive** (time-saving) types: `intro`, `outro`, `ad`, `silence`,
`speed`. The schema comment also mentions `miss` — that is a *missed/false* skip
signal, **not** reclaimed time, and must be **excluded** from all impact totals.
Aggregation whitelists the five explicitly rather than summing all types, so any
future non-saving type can't silently inflate the number.

### 3.5 Tenure-fair comparisons via a user-selectable window
The *collective hero* is all-time cumulative (it's the brand/awe number). But the
*you-vs-community comparison rows* must not be — comparing a 2-year user's
lifetime totals to a day-1 user's is just a tenure ranking. Fix: bound the
comparison to a **trailing window** on *both* sides, so what's compared is recent
behavior, not seniority.

The window is **user-selectable** via a chip/segmented control over a fixed set:
**`7d · 30d · 90d · 1y · All`** (default **30d**). Selecting re-renders both sides
from one payload — no extra requests (§4 ships all windows precomputed; the app's
own per-window value is a local `getSkipBreakdown` call). `All` is the all-time
table set; the *hero* always shows All regardless of the chip. The `speed` row is
the one aspect that does **not** follow the chip — it's an intensive average,
already fair, shown as all-time "typical"; see §5c.

## 4. Backend — `GET /community/impact`

### Response
All time bases in one payload: **all-time** drives the hero/breakdown; the
**`windows`** map drives the user-selectable comparison rows (the app toggles
client-side, no refetch).
```json
{
  "total_ms":   123456789,
  "ads_ms":      23456789,
  "silence_ms":  34567890,
  "speed_ms":    55555555,
  "intro_ms":     6543210,
  "outro_ms":     3333355,
  "contributors":     412,
  "avg_playback_speed": 1.36,

  "windows": {
    "7d":  { "ads_ms": 234567, "silence_ms": 0, "speed_ms": 0, "intro_ms": 0, "outro_ms": 0, "active_contributors": 95 },
    "30d": { "ads_ms": 1234567, "silence_ms": 0, "speed_ms": 0, "intro_ms": 0, "outro_ms": 0, "active_contributors": 180 },
    "90d": { "…": 0, "active_contributors": 260 },
    "1y":  { "…": 0, "active_contributors": 405 }
  },

  "as_of": "2026-06-12T08:00:00Z"
}
```
- **All-time** (`total_ms` + the five flat `*_ms`) → the collective hero +
  breakdown. Cumulative on purpose: it's the brand/awe figure. Also serves the
  **`All`** chip and is the all-time denominator (`contributors`).
- `contributors` = all-time distinct clients — the "N listeners trimming" line.
- `avg_playback_speed` = community mean of clients' average speeds. Kept
  all-time (intensive → already tenure-fair); the speed row ignores the chip and
  is framed as "typical speed" (§5c).
- **`windows`** keyed by chip label (`7d` `30d` `90d` `1y`; `All` uses the
  all-time block, so it isn't repeated here). For the selected window the app
  computes `community_avg_bucket = windows[w].bucket_ms /
  windows[w].active_contributors`, against the user's *own* same-window value
  (local `getSkipBreakdown`). Same window both sides = tenure-neutral.
- `active_contributors` = distinct clients with ≥1 event in that window — the
  correct denominator (dormant users don't dilute the average), and the value the
  app can't derive itself (distinct counts don't sum across days).
- `as_of` = when the cached aggregate was last refreshed.

### Computation & caching — Strategy A + bounded-window rollup
Per `infra_trimbrain_throughput` the 4GB RDS is IOPS-bound, so we never full-scan
per request. **All-time** aggregates stay pure running totals (O(1)). The
**windowed** comparison figures use a per-day rollup so reads are bounded to
`window_days` small rows (≈30×5), never the whole event table.

```sql
-- ALL-TIME, O(1) reads (hero + breakdown) ----------------------------------
-- Per-bucket running totals (5 rows).
CREATE TABLE IF NOT EXISTS community_impact (
    skip_type   TEXT PRIMARY KEY,
    total_ms    BIGINT NOT NULL DEFAULT 0
);

-- Per-client row: powers contributor count + incremental speed average.
CREATE TABLE IF NOT EXISTS community_clients (
    client_id        TEXT PRIMARY KEY,
    first_ts         BIGINT NOT NULL,
    last_avg_speed   REAL                 -- NULL until the client reports one
);

-- Single-row global scalars (contributors, speed sum/count) for O(1) reads
-- without COUNT/AVG scans. One row, id = TRUE.
CREATE TABLE IF NOT EXISTS community_scalars (
    id             BOOLEAN PRIMARY KEY DEFAULT TRUE,
    contributors   BIGINT NOT NULL DEFAULT 0,
    speed_sum      DOUBLE PRECISION NOT NULL DEFAULT 0,  -- Σ per-client avg speed
    speed_n        BIGINT NOT NULL DEFAULT 0             -- clients with a speed
);

-- WINDOWED, bounded reads (tenure-fair comparison rows) ---------------------
-- Per-day per-bucket totals. Endpoint sums the last window_days rows.
CREATE TABLE IF NOT EXISTS community_impact_daily (
    day         DATE NOT NULL,
    skip_type   TEXT NOT NULL,
    total_ms    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (day, skip_type)
);

-- Per-day distinct-client set → active_contributors over the window.
-- Deduped per (day, client) so COUNT(DISTINCT) over the window is cheap.
CREATE TABLE IF NOT EXISTS community_active_daily (
    day         DATE NOT NULL,
    client_id   TEXT NOT NULL,
    PRIMARY KEY (day, client_id)
);
CREATE INDEX IF NOT EXISTS idx_active_daily_day ON community_active_daily (day);
```

Maintenance inside `post_client_events` (per accepted productive event; `day` =
`date(to_timestamp(client_ts/1000))` — see edge note on client-clock skew):
- **All-time:** upsert `community_impact.total_ms += duration_ms`.
- **First time a client_id is ever seen:** insert `community_clients`
  (`ON CONFLICT DO NOTHING`); if inserted, `community_scalars.contributors += 1`.
- **`avg_playback_speed` present (§3):** read client's `last_avg_speed`; update
  `speed_sum += new − COALESCE(old, 0)`, and if `old` NULL `speed_n += 1`; store
  new value. Community mean = `speed_sum / speed_n`, O(1).
- **Windowed:** upsert `community_impact_daily(day, skip_type) total_ms +=`;
  insert `community_active_daily(day, client_id)` `ON CONFLICT DO NOTHING`.

Window precompute — the `windows` map is **built hourly into a cache**, not per
request (the per-window distinct-counts shouldn't run on every open, and the
denominator needn't be real-time). An hourly job fills one cache row per chip
label:
```sql
CREATE TABLE IF NOT EXISTS community_windows (
    label                TEXT PRIMARY KEY,          -- '7d' | '30d' | '90d' | '1y'
    ads_ms BIGINT, silence_ms BIGINT, speed_ms BIGINT,
    intro_ms BIGINT, outro_ms BIGINT,
    active_contributors  BIGINT,
    refreshed_at         BIGINT
);
```
For each label with `N` days:
- buckets: `SUM(total_ms) FROM community_impact_daily WHERE day >= today − N GROUP BY skip_type` (≤ N×5 rows; ≤ ~1825 for 1y).
- `active_contributors`: `COUNT(DISTINCT client_id) FROM community_active_daily WHERE day >= today − N` (bounded to N days, indexed).

Endpoint reads are then O(1): the three all-time tables + the four
`community_windows` rows. **Retention:** keep `*_daily` rows for `1y` + a small
buffer (the longest finite window); the same hourly/daily job prunes older rows,
so `community_impact_daily` stays ≤ ~1825 rows and `community_active_daily` stays
bounded to a year.

### Edge cases
- **Empty / tiny pool:** return the real numbers (even zeros / null speed). Per
  the launch decision we **show actual numbers** — no server-side gating; the app
  only special-cases a literal-zero empty state.
- **Client-clock skew:** `client_ts` is the client's wall clock and can be wrong.
  For the daily `day` bucket, clamp to `received_at` if `client_ts` is in the
  future or absurdly old, so skew can't smear the window or create junk days.
- **Errors:** standard JSON error; app falls back to its last cached value.
- Endpoint is read-only but behind the same `X-Api-Key` as the rest of the API
  (the app already attaches it via `TrimPrefetcher.client()`).

## 5. App — components

### 5a. Settings entry
- `preferences.xml`: add `prefCommunityImpact` (title
  `@string/community_impact_title`, an existing globe/people icon) between
  `prefSendBugReport` and `prefAbout`.
- `MainPreferencesFragment`: add `PREF_COMMUNITY_IMPACT` constant + a click
  handler mirroring the `PREF_STATISTICS` block (`MainPreferencesFragment.java:96`),
  opening `CommunityImpactFragment` into `R.id.settingsContainer` with a
  back-stack entry titled `community_impact_title`.

### 5b. `CommunityImpactClient` (new, in `app/` next to the other Trim clients)
- Mirrors `TrimFeedbackClient`: uses `TrimPrefetcher.client()` (shared pool +
  `X-Api-Key`), base URL from `UserPreferences.getTrimServerUrl()`, path
  `community/impact`.
- Parses the §4 response into a small immutable holder.
- **Caches** the last good response in `UserPreferences` (JSON blob + fetch ts)
  so the screen renders instantly and survives offline; refresh on screen open
  and replace the cache on success.

### 5c. `CommunityImpactFragment` (new, in `ui/statistics`)
Reuses the established visual language of `TimeSavedStatisticsFragment` /
`fragment_time_saved_statistics.xml` (stacked five-segment bar + per-type rows,
editorial serif hero numeral).

The screen is **comparison-first** — every aspect is "you vs the community".
Layout, top to bottom:

1. **Collective hero** — `total_ms` as the big serif number ("reclaimed by the
   community"), with `contributors` as a quiet "N listeners trimming" line.
2. **Ads featured** — a callout sub-number: "of that, **{ads_ms}** was ads."
3. **You-vs-community comparison rows — the core.** Preceded by a **window chip
   selector** (`7d · 30d · 90d · 1y · All`, default `30d`); changing it
   re-renders this whole block client-side from the already-fetched payload — no
   refetch. One row per aspect, each showing the user's value beside the
   community's, with a tiny paired bar or a "1.2× the average" tag. Both sides use
   the selected window, so the compare is tenure-neutral (see §3.5):
   - **Ads skipped** — `you {your value for window w}` vs `avg active listener
     {windows[w].ads_ms / windows[w].active_contributors}` (`All` uses the
     all-time `ads_ms` / `contributors`). Likewise silence / intro / outro /
     speed-time-saved. The user's per-window value comes from local
     `DBReader.getSkipBreakdown(now − w, now)` (already exists; `All` =
     `getSkipStatistics`) — no network on chip change.
   - **Average playback speed** — `you {local s_avg}` vs `community
     {avg_playback_speed}` (the headline example: *you 1.62 · community 1.36*).
     This row **does not follow the chip**: it's an intensive average (already
     tenure-fair) and windowing it needs per-day per-client speed data we don't
     collect in v1. Label it "your typical speed" so its all-time basis reads
     intentionally next to the windowed activity rows.
   *(v2 refinements: windowed speed — add per-day speed samples so this row
   follows the chip too; and/or rate-normalize the buckets, "ads per hour
   listened", if heavy vs light listeners need leveling within a window.)*
4. **Breakdown** — the five-segment stacked bar + per-type rows
   (speed / silence / ads / intro / outro) for the *collective* pool, identical
   idiom to the personal screen, so the big number stays transparent.
5. **"Your contribution"** — the viewing user's own five buckets + total, from
   local `DBReader.getSkipStatistics()` (no network): "you've added
   {your_total} to the {total} above."
6. **Footer** — "Updated {as_of, relative}." + one-line privacy note (§7).

Per the launch decision there is **no cold-start gate**: render the real numbers
from day one. The only special case is a literal-zero/empty pool — show a brief
"be one of the first — the tally grows as listeners trim" line in place of the
collective hero, while the personal block (5) still renders (useful with an
audience of one).

### 5d. Local "your average playback speed"
Computed on-device per §3: prefer `1 + speed_saved_ms / wall_clock_listened_ms`
if a wall-clock-listened figure is available; otherwise a small `UserPreferences`
accumulator. Used for the comparison row (3) and uploaded as `avg_playback_speed`
on the next `/events` batch. **Open at build time:** confirm the wall-clock
source before choosing derive-vs-accumulator.

### 5e. Strings (English only, per project convention)
Add to `ui/i18n/src/main/res/values/strings.xml`:
`community_impact_title`, hero/sub-number/breakdown captions, the
"N listeners trimming" plural, the comparison-row labels ("you" / "community" /
"average listener"), the empty-pool line, and the privacy note.

### 5f. Polish shipped 2026-06-13 ("100x" pass)
Built beyond the v1 baseline:
- **Serif count-up hero** (`EditorialTheme.getSerif`) + **relatable subtitle**
  (`relatablePhrase`: "≈ 9 years of your life, handed back") — the abstract big
  number gets an emotional translation.
- **Comparison = paired you/avg bars on a shared scale + a verdict badge**
  ("1.7× the average" in vermilion / "on par" / "below average") instead of dry
  text. Ads featured in vermilion across hero callout + breakdown.
- **Share card (was deferred v2 → now in v1).** A branded 1080² PNG drawn with
  Canvas (community relatable total + "of it was ads" + the user's own total +
  trimplayer.com w/ UTM), shared via the app's existing FileProvider +
  `ShareCompat` path (mirrors `ui/echo/.../FinalShareScreen`, writes to
  `UserPreferences.getDataFolder`, authority `provider_authority`). This is the
  acquisition lever — the personal number is the viral unit, the collective the
  awe multiplier.

## 6. Credibility / honesty framing
- Headline total for awe; **breakdown always visible** so it's auditable.
- **Ads featured**, because the raw total is dominated by speed-up (undifferentiated).
- Counting matches the personal screen exactly (same buckets, same
  `recordSkipEvent` source) — the community number is just the sum of what every
  user already sees locally, which keeps the two consistent and defensible.

## 7. Privacy posture (and the copy to say it)
- The pool stores **no listening identity** — only anonymized `client_id`,
  skip type, and duration, which already ship today.
- Footer copy, roughly: *"We add up trimmed seconds across everyone — never who
  listened to what."* This is true to the data model and turns the
  anonymous-aggregate constraint into a trust asset.
- No opt-in *prompt* is added here because the underlying upload already exists;
  if a global telemetry opt-out exists, this screen must respect it (the
  collective fetch + personal upload both honor `getTrimServerUrl()` being unset).

## 8. Phasing
1. **Backend** — all-time tables (`community_impact` / `community_clients` /
   `community_scalars`) + windowed rollup (`community_impact_daily` /
   `community_active_daily`) + the `community_windows` cache; incremental
   maintenance in `post_client_events` (all-time totals + contributor count +
   speed running-average + per-day rollup); hourly job to rebuild the four window
   cache rows + prune `*_daily` beyond 1y; accept optional `avg_playback_speed`;
   `GET /community/impact` returning all-time + `windows` map. (be-dev.)
2. **App** — settings entry, `CommunityImpactClient` (+ prefs cache), local
   average-playback-speed metric + its upload, `CommunityImpactFragment` with the
   window chip selector + per-window you-vs-community rows (local via
   `getSkipBreakdown`) + all-time collective hero + personal block. (mobile-dev.)
3. Copy + privacy footer + empty-pool polish.
4. **Share card — DONE** (built 2026-06-13, §5f): branded PNG via Canvas +
   FileProvider/`ShareCompat`. The viral unit.

Backend `refresh_community_windows` is guarded by a transaction-level advisory
lock (`pg_try_advisory_xact_lock`) so concurrent reads don't all rebuild the
window cache (thundering herd); auto-released on commit, pool-safe.

## 9. Deferred / open
- **Window set / default** — `7d · 30d · 90d · 1y · All`, default `30d`. The set
  is a constant; confirm the members + default. Hero stays all-time regardless.
- **Wall-clock-listened source** for local average speed — confirm whether
  `StatisticsItem.timePlayed` is usable or a new accumulator is needed (§5d).
- **Windowed speed (v2):** make the speed row follow the chip via per-day
  per-client speed samples (extra rollup) — out of v1 scope (§5c).
- **Rate-normalized comparisons (v2):** "ads per hour listened" etc., if heavy
  vs light listeners still need leveling within a window (§5c).

## 10. File-touch checklist
**App (this repo)**
- `ui/preferences/src/main/res/xml/preferences.xml` — new `prefCommunityImpact`.
- `app/.../ui/screen/preferences/MainPreferencesFragment.java` — constant + click handler.
- `app/.../CommunityImpactClient.java` — new.
- `ui/statistics/.../CommunityImpactFragment.java` + `fragment_community_impact.xml` — new (reuse time-saved idiom; window chip selector + comparison rows).
- `app/.../TrimEventsUploadWorker.java` — add optional `avg_playback_speed` to the request root.
- local avg-speed metric — derive from stats or a new `UserPreferences` accumulator (§5d).
- `ui/i18n/src/main/res/values/strings.xml` — English strings (incl. chip labels `7d/30d/90d/1y/All`).
- `storage/preferences/.../UserPreferences.java` — cache accessor for last impact payload (+ speed accumulator if needed).

**Backend (`backend-migration/`, deployed off-repo)**
- `schema_pg.sql` — `community_impact`, `community_clients`, `community_scalars`,
  `community_impact_daily`, `community_active_daily`, `community_windows` tables.
- `api_pg.py` — incremental maintenance inside `post_client_events` (all-time bucket
  totals, contributor count, speed running-average, per-day rollup + active set);
  optional `avg_playback_speed` field on `ClientEventsRequest`; new
  `GET /community/impact` (all-time block + `windows` map from the cache);
  hourly job to rebuild `community_windows` + prune `*_daily` beyond 1y;
  (optional) drift-recompute job.
