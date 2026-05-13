# TrimPlayer "Wow Effect" — Task Plan

Two features. The first (segment timeline visualization) drives the bigger emotional payoff — users *see* the intelligence happening. The second (per-type skip toggles) is table-stakes parity but cheap to add once #1 is in.

Tasks below are sized in dev-days. Each is independent unless noted; #1 and #2 can be parallelized across two people.

---

## Feature 1 — Segment timeline visualization

Show colored bands on the playback seek bar for intro / ad / outro. Plus a brief toast on each auto-skip and a polished end-of-episode summary.

### 1.1  Render segment bands on the seek bar (3-5 d)

**Why:** the headline visual. Users see the trimmed segments before, during, and after auto-skip — proves the feature is real.

**Scope:**
- Extend or wrap `app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/ChapterSeekBar.java` (already overlays chapter markers — good base for adding TrimSegment overlays).
- Pull segments from `PlaybackService.currentSegments` (already maintained — see `PlaybackService.java:202`). Expose via a getter or a new event the fragment can listen to.
- Draw a thin colored band (4-6 dp tall) above or below the seek track:
  - `intro` → green
  - `ad` → red/orange
  - `outro` → blue
- Position bands using `local_start`/`local_end` from `TrimClient.Segment` (`playback/service/src/main/java/de/danoeh/antennapod/playback/service/trim/TrimClient.java:85-89`). Map to seek-bar pixel coords using current episode duration.
- Handle resize (orientation change), updates when segments arrive async (`getSegments` callback at `PlaybackService.java:383`), and the empty state (no segments → render normally).

**Files to touch:**
- `app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/ChapterSeekBar.java` (extend or subclass)
- `app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/AudioPlayerFragment.java` (wire segments → seek bar)
- `playback/service/.../PlaybackService.java` (expose `currentSegments` to UI; add an event when they change)
- New layout / style resources for the band colors

**Polish to plan for:** no clutter when 5+ segments overlap in a tiny visual area; chapters and segments should not collide visually; theme-aware colors (light/dark).

### 1.2  In-line skip toast (0.5 d)

**Why:** confirms *each* skip in real time. "Skipped 24 s ad" feels magical; silent skipping feels confusing.

**Scope:**
- In `PlaybackService.java` around the auto-skip block (`PlaybackService.java:2335-2345` area, where `pos >= startMs && pos < endMs` triggers a seek), post a `Toast.makeText(...)` on the main thread with the segment type and duration.
- One toast per segment per playback (use the existing `skippedSegmentIndices` set at `PlaybackService.java:211` to avoid repeats on scrubbing).
- New string resource `auto_skip_toast` in `ui/i18n/src/main/res/values/strings.xml`.

### 1.3  End-of-episode summary polish (0.5 d)

**Why:** the *cumulative* "you saved 3:36 today" is the most quotable moment.

**Scope:**
- The toast already exists at `PlaybackService.java:1474-1480` (`episode_skip_summary_toast`). Audit the wording, format the time as `m:ss` / `h:mm:ss`, optionally show segment-type breakdown ("3:36 saved · 1 intro, 2 ads, 1 outro").
- Consider promoting from a Toast to a snackbar with an "Undo" affordance for users who didn't want the skip (better UX, more durable).
- Strings to update: `ui/i18n/src/main/res/values/strings.xml`.

---

## Feature 2 — Per-type skip toggles

Three independent switches (Auto-skip intros, Auto-skip ads, Auto-skip outros). Reads from SharedPreferences in the existing skip loop.

### 2.1  Add three preferences (0.5 d)

**Scope:**
- New prefs in `storage/preferences/.../UserPreferences.java` next to `getTrimServerUrl`/`setTrimServerUrl`:
  - `getTrimSkipIntros()` (default `true`)
  - `getTrimSkipAds()` (default `true`)
  - `getTrimSkipOutros()` (default `true`)
- Each: `prefs.getBoolean(KEY, true)` and a setter.

### 2.2  Wire them into the skip loop (0.5 d)

**Scope:**
- In `PlaybackService.java` (the auto-skip iteration), check the relevant pref before issuing a `seekTo`. Skip only when the corresponding type is enabled.
- Don't update `skippedSegmentIndices` when the toggle disables a skip — that way if the user toggles back mid-episode, the segment can still be skipped.

### 2.3  Add a UI surface for the toggles (0.5-1 d)

**Two options, pick one:**

- **A)** Add to existing playback preferences screen (`app/src/main/java/de/danoeh/antennapod/ui/screen/preferences/PlaybackPreferencesFragment.java`, plus `ui/preferences/src/main/res/xml/preferences_playback.xml`). Cheaper, lives with related settings.
- **B)** Surface as quick-access switches in the `PlaybackControlsDialog` (`app/src/main/java/de/danoeh/antennapod/ui/screen/playback/PlaybackControlsDialog.java`) so they're one tap away during playback. More visible, slightly more UI work.

Recommend **A first**, **B as a follow-up** if telemetry shows users hunting for the setting.

---

## Suggested order

1. **Day 1:** 2.1 + 2.2 (toggles + wiring) — fastest win, ships independently.
2. **Day 1 (parallel):** 1.2 (skip toast) + 1.3 (summary polish) — small visible improvements that don't depend on the seek bar work.
3. **Days 2-6:** 1.1 (timeline bands) — the headline feature; finish polish before merging.
4. **Day 6:** 2.3 (toggle UI) — once toggles are functional, expose them in settings.

Total: ~7 dev-days for one engineer, ~4 calendar days for two parallelizing.

---

## Out of scope (flagged but not in this plan)

- Transcript-based intro/outro extension (semantic boundaries — would have caught the canonical-179 "17 s vs 12 s" case where VAD couldn't). Bigger change touching backend refinement pipeline. Worth doing if intro/outro accuracy complaints recur.
- Crowd-sourced segment voting (thumbs up/down on detected segments). Requires backend endpoints for feedback aggregation.
- Per-podcast saved-time leaderboard ("you've saved 12 h on Huberman Lab"). Needs persistent stats schema.
