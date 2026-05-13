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

## Open follow-ups

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

### Cover art colors
Show segments in the donut and per-show rows use a fixed 9-color palette.
For precise brand colors, integrate Palette API (Glide/Coil) to extract dominant
colors from each feed's cover image.

### "Abandoned" counter
The Activity screen shows Started / Finished / In-progress / Completion %.
A true "Abandoned" count (started but never resumed after a certain date) would
require a new query comparing `last_played_time` vs episode age.

### Filter chips (Subscriptions)
The design specifies filter chips (All shows / This year / category tags).
Currently the list is always sorted by all-time hours. Filtering requires
wiring the existing `StatisticsFilterDialog` or a new chip UI into the
`getEditorialStats()` call with appropriate time bounds.
