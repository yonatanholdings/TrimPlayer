# Investigation — 2 reported user issues (2026-07-16)

Both reports arrived as in-app feedback threads (backend `feedback_threads` #12 and #13,
submitted 2026-07-16). This document is the root-cause investigation for TrimPlayer task #26.
Diagnosis was done read-only against the live TrimBrain backend (RDS) per
`TrimPlayer-Unified/backend/CLAUDE.md`.

---

## Issue 1 — "My current MoS episode the intro skipped and returned to the beginning"

- **Thread 12**, category `bug`, client `3f529ca0…`, TrimPlayer **1.0.7-beta10** (= current HEAD),
  Samsung SM-S908E, Android 16.
- The report shipped the attached **playback trail** (`TrimPlaybackLog`). The relevant episode is
  the last one in the trail, a **Masters of Scale / Rapid Response** episode
  (`Rapid Response: How to turn intentions into action, w/Aurora James`, episode id 2733,
  feed `https://rss.art19.com/masters-of-scale`, player duration 2 015 000 ms).

### What the trail shows

```
20:10:54.001 start  ep=Rapid Response… pos=-1                       (auto-advance load)
20:10:57.033 auto-skip intro 4340ms->121949ms                       (trim intro skip → 2:02)
20:10:57.059 start  ep=Rapid Response… pos=121949                   (seek held)
20:10:57.086 start  ep=Rapid Response… pos=121949                   (speed-dance duplicate)
20:18:26.761 start  ep=Rapid Response… pos=-1                       (~7.4 min later: mid-episode RELOAD)
```

The intro skip worked (the seek to 121 949 ms held — two `start pos=121949` lines). The listener
then heard ~7 min. The final line is a **mid-episode reload of the same episode with `pos=-1`**
(`Playable.INVALID_TIME`). In `PlaybackService$onPlaybackStart`, `INVALID_TIME` routes to
`skipIntro(playable)` — i.e. the app is back in "fresh start / decide the intro" state for an
episode the user was already 9 minutes into. That is the user-visible "returned to the beginning":
a reload that dropped back to the top and re-applied intro handling.

### Root cause (class), and why it is not a one-line fix

This is the **same reload/position-loss class** as the still-open investigation
`project_trimplayer_skip_to_start_auto_investigation` ("streaming episode jumps to 0 mid-play on
its own"). Static review of HEAD confirms the relevant guards are all present and correct in the
user's exact build (beta10):

- **Trim auto-skip seek-regression guard** (`PlaybackService`, `pendingSkipVerify` /
  `trimSeekUnreliable`): detects a non-seekable stream restarting at 0 after a forward `seekTo`,
  disables auto-skip for the episode, and requests a download. Present and armed.
- **Per-index de-dup** (`skippedSegmentIndices`): each segment auto-skips at most once. Present.
- **Resume position restore** (`LocalPSMP.resume`): restores `media.getPosition()` when
  `PREPARED && position > 0`. **This is the only resume-time restore** — if a reinit'd stream lost
  its saved position, resume falls through and ExoPlayer starts at the top.
- **Audio-focus GAIN** resume path calls `mediaPlayer.start()` directly, **bypassing**
  `resume()`'s restore branch.

The trail proves the reload happened, but it does **not** record *why* it reloaded, nor what
position was available at that instant — so the single ambiguous line cannot be resolved to one of:
(a) position was preserved and restore worked (benign), (b) position was dropped and the episode
genuinely replayed from ~0. The prior investigation reached the same wall (guards confirmed in the
installed dex, could not reproduce on a phone because the stream buffer would not drain).

### What this task changed (diagnostics to close the loop)

Because `TrimPlaybackLog` exists precisely to triage this behaviour-bug class from the trail
(there is no crash), the gap is that the trail can't currently disambiguate the two hypotheses.
This task adds high-signal, best-effort log lines on exactly the reload/resume paths:

- `onPlaybackStart` now emits `storedPos=` next to `pos=` — on a `pos=-1` reload this reveals
  whether the `Playable` still remembers the ~9-min position (restorable) or dropped it to ~0.
- `LocalPSMP.resume` logs the restore decision (`resume … mediaPos=… willRestore=…`).
- The audio-focus GAIN resume logs `focus-gain-resume mediaPos=… playerPos=…`.
- `skipIntro` (the manual per-feed preset) logs `skip-intro-preset fromPos=…->…` when it actually
  seeks, so a reload-triggered re-skip from a dropped position is visible.

The **next** occurrence of this report will pin the exact case in one line, at which point the fix
is targeted (e.g. persist/repair the position before reinit, or route focus-gain through the
restore branch). Shipping a speculative restore change now — without knowing which path drops the
position — would be a "would handle this if it ran" patch and is deliberately avoided.

### Note on the MoS segment data (not the cause here, but relevant)

Episode 2733 carries a heavily **bloated** `episode_segments` set (dozens of near-duplicate intro
canonicals covering 0–116 s, plus mid/outro canonicals — the known
`project-trimbrain-canonical-dedup-leak`). The app's runtime dedup collapsed this to a single
clean intro skip (0→122 s), so the bloat did **not** produce the reported symptom. It remains a
backend hygiene issue tracked separately.

---

## Issue 2 — "In Wiser than me … you didn't skip many commercials where Julia reads … protein bar Aloha … shampoo"

- **Thread 13**, category `bug`, client `5aa41603…`, TrimPlayer **1.0.7-beta9**, Samsung SM-S938B,
  Android 16. No crash log (a detection-quality complaint, not a crash).
- Podcast: **Wiser Than Me** (feed `https://feeds.megaphone.fm/LEME2226086440`), 717 episodes in
  the backend.

### Root cause — host-read ads are outside the fingerprint detector's reach

The complaint is specifically about **host-read** ads: Julia Louis-Dreyfus reading product spots
(Aloha protein bar, a shampoo) in her own voice, woven into the show. The backend detects segments
by **audio fingerprint recurrence** — it promotes a segment to `canonical_segments` only when the
*same audio* recurs, offset-aligned, across ≥3 episodes. Confirmed against live data for this feed:
its canonical segments are **`intro` and `outro` only — there is no `ad` segment type present**.
That is expected:

- A host-read spot is **freshly spoken each episode**; there is no identical waveform recurring
  across episodes for the correlator to lock onto.
- Even the ad *copy* varies and is embedded in continuous speech, so there is no clean
  offset-aligned boundary to cluster.

This is the exact limitation captured in `project_trimbrain_ad_detection_gap`: fingerprinting needs
clean recurrence, host-read ads have none, and the alternative (a transcript/keyword path) is
currently **dormant** (0 `transcript_segments` system-wide, English-only keyword list).

### Where the fix lives — out of scope for this repo

There is **no app-side fix**: the TrimPlayer client only plays the segments the backend supplies.
Making Wiser-Than-Me-style host-read ads skippable requires reviving the **transcript pipeline** in
the TrimBrain backend (`TrimPlayer-Unified/backend/`), which is a substantial, separate piece of
work and is **not one of this task's repos**. This issue is therefore an investigation finding, not
a change deliverable here. Recommended follow-up: file a backend task to revive transcript-based ad
detection (biggest lever per `project_trimbrain_ad_detection_gap`), starting with this feed as a
test case.

---

## Summary

| # | Report | Root cause | Actionable here? |
|---|--------|-----------|------------------|
| 1 | MoS intro skipped → returned to beginning | Mid-episode reload dropping playback position (open reload/position-loss class); all HEAD guards present, trigger not yet reproducible | Added diagnostics to pin the trigger on next report; no speculative fix shipped |
| 2 | Wiser Than Me host-read ads not skipped | Host-read ads have no cross-episode fingerprint recurrence → never promoted to `canonical_segments`; transcript path dormant | No — backend (`TrimPlayer-Unified`) transcript pipeline, out of scope |
