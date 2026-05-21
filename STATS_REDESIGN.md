# Statistics Redesign

## What changed

The Statistics section has been redesigned as a magazine-editorial system:
cream paper background, Instrument Serif numerals, IBM Plex labels,
and vermilion accent color.

### New screens (5 tabs)

| # | Tab | File |
|---|---|---|
| 0 | **Overview** — masthead, YTD hero stat, 26-week heatmap, table of contents | `OverviewStatisticsFragment` |
| 1 | **Subscriptions** — donut chart + per-show ranked list + dossier modal | `SubscriptionStatisticsFragment` (replaced) |
| 2 | **Activity** — episode counters, hour-of-day bars, day-of-week multiples, time saved | `ActivityStatisticsFragment` (new) |
| 3 | **Years** — streamgraph + per-year list with YoY deltas | `YearsStatisticsFragment` (replaced) |
| 4 | **Time Saved** — existing screen retained as tab 4 | `TimeSavedStatisticsFragment` |

### New data queries (PodDBAdapter + DBReader)

- `getByHourCursor()` — 24-bucket listening minutes by hour-of-day (local time)
- `getByDayCursor()` — 7-bucket day-of-week minutes (Sun–Sat)
- `getDailyListeningCursor(from, to)` — daily totals used for heatmap + weekly sparklines
- `getFeedDailyListeningCursor(feedId, from, to)` — per-feed daily totals for dossier sparkline
- `getGlobalEpisodeCountsCursor()` — started / completed / in-progress counts
- `DBReader.getEditorialStats()` — aggregates all of the above into `EditorialStats`
- `DBReader.getFeedDetail(feedId)` — data for the dossier modal

### New chart views (ui/statistics/editorial/)

- `DonutView` — segmented full-circle donut, 1.2dp gaps, butt caps, double hairline ring
- `HeatmapView` — 26×7 calendar grid, 5-step accent ramp
- `HourBarsView` — 24 vertical bars, peak bar in vermilion + PEAK tick
- `DayMultiplesView` — 7 small bar multiples for day-of-week
- `StreamgraphView` — smooth Catmull-Rom area + stroke, hollow circles, PEAK/YTD labels
- `SparklineView` — area + line + endpoint dots (12-week data)

### Design tokens

All colors are in `EditorialTheme.java` (no hardcoded hex in components).

## Recent changes (2026-05-20)

- **Shared ViewModel**: `StatisticsViewModel` scoped to the parent
  `StatisticsFragment` owns the two heavy queries (`getEditorialStats()` and
  `getSkipStatistics()`) and exposes them as `LiveData`. All 5 child tabs
  observe instead of running their own RxJava query on every visit. The
  EventBus `StatisticsEvent` is consumed once by the parent fragment and
  forwarded to `viewModel.refresh()`.

- **Abandoned counter**: `EditorialStats.episodesAbandoned` (started, not
  finished, no play activity in the last 30 days). `getGlobalEpisodeCountsCursor`
  now takes a cutoff arg and returns both `in_progress` (active) and `abandoned`
  separately. Activity tab shows it as a 4th counter; the completion-percent
  was moved into a small caption under Finished.

- **Time Saved redesign**: replaced the AntennaPod-style ProgressBar list with
  editorial layout — masthead strip, serif total numeral, today/week/month
  hangs, horizontal stacked bar showing the 5-way proportion (speed / silence /
  ads / intros / outros), per-type rows with editorial bars, monthly history
  with mini bars + serif numerals.

- **Dark mode**: every editorial layout uses `@color/editorial_*` resources
  defined in `values/colors.xml` and `values-night/colors.xml` (paper inverts
  to deep ink, ink to warm cream, vermilion accent and gold lifted slightly
  for contrast). `EditorialTheme.java` exposes `ink(ctx)` / `paper(ctx)` /
  `vermilion(ctx)` etc. getters; chart views (DonutView, HeatmapView,
  HourBarsView, DayMultiplesView, StreamgraphView, SparklineView) and the
  Years/TimeSaved fragments' programmatic colors all resolve through these.
  Legacy `EditorialTheme.INK` etc. constants kept as deprecated for any
  external callers.

- **Palette colors per feed**: new `FeedColorCache` (in editorial/) extracts
  the vibrant→muted→dominant color from each feed cover via
  Glide + AndroidX Palette and caches in a 64-entry LRU. Subscriptions list
  rows tint their indicator bar with the extracted color; DonutView segments
  do the same on the next `invalidate()` after extraction completes.
  Lightness is floored so near-white covers still render against paper bg.
  Fixed-palette colors remain as the fallback while extraction runs.

- **Filter dialog wired**: the toolbar Filter menu item is now visible and
  opens the existing `StatisticsFilterDialog`. The dialog persists from/to
  and the include-marked-played flag to SharedPreferences and posts a
  `StatisticsEvent`, which the ViewModel observes and refreshes on.

## Open follow-ups

### Filter date range not yet applied to queries
The Filter dialog persists from/to to SharedPreferences and the ViewModel
refreshes when the dialog confirms, but `DBReader.getEditorialStats()` doesn't
yet take a date-range argument — every cursor (`getByHourCursor`,
`getByDayCursor`, `getMonthlyStatisticsCursor`, `getStatisticsCursor`,
`getGlobalEpisodeCountsCursor`, `getDailyListeningCursor`) needs a `WHERE
last_played_time_statistics BETWEEN from AND to` predicate added, and an
`getEditorialStats(long from, long to, boolean includeMarkedPlayed)` overload.
`getDailyListeningCursor` and `getSkipEventStatsCursor` already take from/to;
the others would follow the same pattern.

The oldest-date floor passed to `StatisticsFilterDialog` is currently a
5-year hardcoded look-back — replace with a quick `SELECT MIN(last_played_time_statistics)`
once filtering actually applies, so the spinner shows only months that
contain data.

### Fonts not yet bundled
The design calls for Instrument Serif and IBM Plex Mono. The code falls back to
`Typeface.SERIF` / `Typeface.MONOSPACE` until font files are added:

1. Download from Google Fonts:
   - [Instrument Serif](https://fonts.google.com/specimen/Instrument+Serif) → `InstrumentSerif-Regular.ttf`
   - [IBM Plex Mono](https://fonts.google.com/specimen/IBM+Plex+Mono) → `IBMPlexMono-Regular.ttf`
2. Copy to `ui/statistics/src/main/res/font/` as:
   - `instrument_serif_regular.ttf`
   - `ibm_plex_mono_regular.ttf`
3. Set `FONTS_BUNDLED = true` in `EditorialTheme.java`
