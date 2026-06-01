# Trimplayer Android — Spotify migration integration plan

Companion to `trimplayer-android-migration-brief.md`. The brief defines
*what* we're shipping; this plan defines *how* we'll build it on top of
the existing `PortcastImporter` pipeline, informed by the WebView spike
we ran on 2026-05-31.

## 0. What the spike proved

The debug spike (`app/src/main/java/.../migration/SpotifyMigrationActivity.java`
behind a `BuildConfig.DEBUG`-gated pref) fetched a real Spotify library
end-to-end through a WebView. Concretely:

- **Sign-in works** under a desktop Chrome UA
  (`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36`).
  The default mobile WebView UA hits Spotify's "open in app or close"
  wall and never lets sign-in proceed.
- **Token capture is fast.** `tokenSource: "hook-bootstrap"` —
  `sessionStorage` had both the Bearer and the Client-Token on the very
  first read after `onPageStarted` injected the hook. No race, no
  fallback path needed.
- **Pathfinder still accepts the Chrome extension's constants.** The
  persisted-query hash, the `Podcasts & Shows` filter id, and the
  `libraryV3` operation name all returned a real library page.
- **The bridged payload is the exact shape `lib/portcast.js#buildDocument`
  expects** —
  `{me, savedShows: [{added_at, show:{id, name, publisher, images}}], savedEpisodes: []}`.
  Drop-in.

Inferred consequences:
- No `WebViewClient.shouldInterceptRequest` HTML injection needed.
  `onPageStarted` + `evaluateJavascript` is enough.
- The "EME: No supported keysystem" / `PlaybackError` console messages
  are DRM-for-playback errors. We don't play audio. They're harmless
  noise; the brief's risk section should call them out so future-you
  doesn't chase them.
- The desktop UA serves a slightly different SPA: `/collection/podcasts`
  immediately redirects to `/collection/tracks`. The library sidebar
  still fires libraryV3 from `/collection/tracks`, so the export works,
  but the URL gate that enables the Fetch button must accept any
  `/collection/*` URL, not just `/podcasts`.
- `me.id` came back null. The desktop SPA doesn't expose
  `document.getElementById("session")` the way the mobile-web variant
  does. `owner` is optional in PortCast, so this is cosmetic — but the
  v2 implementation should either add a profile pathfinder probe or
  accept a null `me.id` and not show "Imported from <name>'s Spotify"
  copy.

## 1. Milestones

Land in this order. Each milestone is independently testable and
shippable behind the feature flag.

| #  | Milestone                                  | User-visible? | Depends on        |
| -- | ------------------------------------------ | ------------- | ----------------- |
| M1 | Spotify show-ID resolver                   | No            | —                 |
| M2 | `PortcastImporter` accepts platformRefs-only subs | No   | M1 (runtime)      |
| M3 | Manifest intent-filter for tap-to-import   | Yes (v1)      | M2                |
| M4 | "Coming from Spotify?" entry + sheet       | Yes (v1)      | M2, M3            |
| M5 | Production `SpotifyMigrationActivity` (v2) | Yes (v2)      | M1, M2            |
| M6 | Result screen with manual-search affordance| Yes           | M2                |
| M7 | Remote feature flag for the resolver path  | No            | —                 |

M1–M2 unlock both v1 and v2. M3–M4 ship v1. M5–M6 ship v2. M7 should
land alongside M1 so we never deploy the resolver without a kill-switch.

The spike (`SpotifyMigrationActivity` in `de.danoeh.antennapod.migration`)
is the foundation for M5. Its assets and Bridge are reusable verbatim
(see §4 below).

## 2. The resolver (M1)

This is the genuinely-new work. Everything else is wiring.

### 2.1 Inputs and outputs

Input: a Spotify-sourced `PortFeed` with `platformRefs:
["spotify:show:<id>"]`, `title`, `author`, `imageUrl`. No `feedUrl`.

Output: one of
- `Resolved(feedUrl, confidence, source)` — feed URL we'll subscribe to.
- `Unresolvable(reason)` — surface in the result screen (§6).

### 2.2 Architecture

A new module `storage/importexport/.../spotify/SpotifyShowResolver.java`
(or its own small Gradle module if we want to share with v2 later — but
not yet; the importexport module already has the right dependency graph).

Resolver fan-out, per show, in priority order:

1. **TrimBrain shim** (recommended primary).
   `POST https://api.trimplayer.com/resolve/spotify` with
   `{spotifyShowId, title, author}`. Backend can:
   - cache hot results (the long tail of repeat-resolves across users
     is cheap to memoize),
   - swap the underlying provider without an Android release,
     including the title/author search algorithm,
   - emit telemetry on hit/miss rates without instrumenting the client.

   The Android client treats this as the source of truth; if it
   returns 200 with a `feedUrl`, we use it.

2. **PodcastIndex fallback** (in-app, only if TrimBrain is unreachable
   or the user has flagged us as offline). PodcastIndex does NOT have a
   documented `byspotifyid` endpoint. The fallback strategy is search
   on `title + " " + author`, then accept the top hit only if:
   - normalized Jaro-Winkler similarity on title ≥ 0.9 AND
   - normalized Jaro-Winkler similarity on author ≥ 0.85.

   Threshold tuning happens against a logged sample after first release
   — see §9.

3. **Otherwise** → `Unresolvable`. Surface title/author/imageUrl in the
   result screen.

### 2.3 Concurrency budget

- Max **8 concurrent** resolver calls.
- Per-call hard timeout **5 s**.
- Total per-import budget **2 min**. Reset on retry.
- All results cached for the lifetime of the import attempt keyed by
  `spotify:show:<id>` so retries don't re-spend the budget.
- On timeout or 5xx, mark the show unresolvable rather than aborting
  the whole import.

A 200-show library at one-call-at-a-time × 5 s timeout is 17 minutes.
8-wide × 5 s puts the worst case at ~2 min. The user can sit through
that with a progress bar; they cannot sit through 17.

### 2.4 Backend (TrimBrain) endpoint sketch

Out of scope for this Android plan, but documenting the contract so
the Android side can be built against a stub:

```
POST /resolve/spotify
Authorization: Bearer <trimbrain-app-token>
Content-Type: application/json

{ "spotifyShowId": "7Fj0XEuUQLUqoMZQdsLXqp",
  "title": "Acquired",
  "author": "Ben Gilbert and David Rosenthal" }

200 OK
{ "feedUrl": "https://feeds.transistor.fm/acquired",
  "confidence": 0.99,
  "source": "podcastindex-cache" }

404 Not Found
{ "reason": "no-match" }

503 Service Unavailable     (telling client to fall through to local fallback)
```

Until the backend ships, the Android client uses the PodcastIndex
fallback directly and logs match scores so we have data to tune
against.

## 3. `PortcastImporter` changes (M2)

The existing importer (`storage/importexport/.../PortcastImporter.java`)
is mostly complete. The Spotify path needs three targeted changes;
nothing else moves.

### 3.1 Accept platformRefs-only subscriptions

Today, `parseSubscription` rejects any subscription without a
`feedUrl`:

```java
String feedUrl = sub.optString("feedUrl", "");
if (TextUtils.isEmpty(feedUrl)) return null; // PortcastImporter.java:242
```

Replace that early-return with: keep the subscription if either
`feedUrl` is present **or** `platformRefs` is non-empty. Extend
`PortFeed`:

```java
public String subscriptionId;          // from the doc; stable across exports
public List<String> platformRefs = new ArrayList<>();
public boolean needsResolution;        // true when feedUrl is empty
```

### 3.2 Resolve in `previewImport`

Before conflict detection (currently `PortcastImporter.java:165` onward),
iterate `preview.feeds` and, for each `pf.needsResolution`, call the
resolver. On `Resolved`, set `pf.feedUrl`. On `Unresolvable`, move the
entry to a new `preview.unresolvableFeeds` list and skip it from the
subscribe phase.

`previewImport` is already off-main-thread (called via Rx
`Schedulers.io()` in `ImportExportPreferencesFragment.importFromPortcast`),
so the resolver's blocking I/O is fine here.

Surface progress through a callback on `previewImport` so the UI can
show "Looking up 187 of 200 shows…" — this is the long phase for a
big library. New signature:

```java
public static ImportPreview previewImport(
    Context context,
    InputStream stream,
    @Nullable ProgressCallback callback) throws Exception;
```

Existing callers pass `null`.

### 3.3 Dedupe by `subscriptionId`

Add a tiny new mapping `subscriptionId → Feed.id` to `FeedPreferences`
or a dedicated SharedPreferences blob. On import, prefer that mapping
over `feedUrl` equality (the brief §1.2.6 explains why — http/https,
trailing slash, feedburner canonicalization mismatches).

```java
// PortcastImporter.findExistingFeed(...)
Feed byId = subscriptionIdIndex.lookup(pf.subscriptionId);
if (byId != null) return byId;
return feedByUrl(pf.feedUrl);
```

Persist `subscriptionId` on `PortcastSubscribeWorker.subscribeOne(...)`
right after `FeedDatabaseWriter.updateFeed(...)` returns the persisted
`Feed`.

## 4. Promoting the spike to v2 (M5)

The spike activity becomes the production `SpotifyMigrationActivity`.
Path from where it is now to where it needs to be:

### 4.1 Reused as-is

These work; don't touch:

- `app/src/main/assets/spotify_migration/spotify_hook.js`
- `app/src/main/assets/spotify_migration/spotify_fetch.js` (modulo
  §4.4 below)
- WebView config: desktop UA, JS + DOM storage + third-party cookies,
  block deep-link schemes.
- `Bridge`: `@JavascriptInterface` methods receiving JSON strings.
- Hook injection in `onPageStarted` + belt-and-suspenders re-inject in
  `onPageFinished`.

### 4.2 Replace

- Hardcoded English strings → string resources in `ui/i18n` (English
  only per CLAUDE.md).
- Debug `Toast` + `Log.d` payload dump → silent success that hands the
  payload to `buildDocument` and then to
  `PortcastImporter.previewImport`.
- "Got N shows" status → a real progress UI with phases: signing in,
  fetching shows, resolving feeds, applying.
- Bare `WebView` + `Button` → ProgressBar overlays during fetch and
  resolution.

### 4.3 Add

- `WebView.clearHistory()` + `CookieManager.removeAllCookies(null)` at
  Activity start. We don't want a previous session's stale auth state
  silently driving a different user's account into the wrong library.
  After-success cleanup is optional but advisable (privacy: the user
  may not want their Spotify cookie sitting in TrimPlayer's WebView
  storage indefinitely).
- A "Cancel" button visible during the resolver phase. Long phase,
  legitimate user reasons to abort.
- `WebView.loadUrl("about:blank")` in `onPause` to release media
  decoders. Spotify spins up audio decoders even though we don't play
  anything; the EME errors prove it.

### 4.4 `spotify_fetch.js` adjustments

Two small changes from the spike version:

1. Replace the `document.getElementById("session")` `me.id` extraction
   with a profile pathfinder probe, or remove it and accept null. The
   desktop SPA doesn't populate that element. **Recommended:** remove
   it. `owner` is optional in PortCast and we don't need to show
   "Imported from <name>" copy if we don't have it. Simpler.
2. Add a `notSignedIn` early-return triggered by the pathfinder
   response `Exception while fetching data (/me) : User is not authorized`
   (the brief mentions this; the spike confirmed it's how Spotify
   signals unauthenticated). Today the JS treats it as a generic
   pathfinder error.

### 4.5 Running `buildDocument`

Pick one approach — bundle the JS or port to Java.

**Option A (recommended): bundle the JS.** Bundle
`C:/git/PortCast/chrome-extension/lib/portcast.js` as an Android asset.
After the fetcher returns, run a follow-up `evaluateJavascript` that
calls `buildDocument({me, savedShows, savedEpisodes, generatorVersion})`
and bridges the resulting object back. Same Bridge pattern.

Pro: a v0.x bump in `portcast.js` propagates to TrimPlayer without an
Android release (assuming we periodically refresh the bundled asset
via a CI step).

Con: an extra `evaluateJavascript` round-trip.

**Option B: port `buildDocument` to Java.** ~150 lines of pure mapping
logic. No JS at the boundary.

Pro: easier to test in unit tests.

Con: drifts from the JS over time. Two places to keep in sync.

**Decision: Option A.** The cost (round-trip) is trivial; the win
(spec changes don't require Android releases) is real, and the JS
itself is small and well-tested.

### 4.6 v2 entry point

Add a third action to "Coming from Spotify?" (the sheet introduced in
M4 of v1):

```
┌────────────────────────────────────────┐
│ Coming from Spotify?                   │
│                                        │
│ ▸ Sign in to Spotify here              │  ← M5 (v2)
│ ▸ I have a PortCast file from a desktop│  ← M4 (v1)
│ ▸ What gets imported?                  │  ← static info
└────────────────────────────────────────┘
```

## 5. v1 manifest intent-filter (M3)

Lift verbatim from brief §1.2.1, with the corrections already in the
brief:

- Reserved-word fix: package is `.portcast.`, NOT `.import.`.
- `android:host="*"` is required for `pathPattern` to be evaluated.
- `application/octet-stream` and `text/plain` MIME fallbacks.
- Separate `ACTION_SEND` filter for the Gmail share-sheet path.

The receiving activity is a tiny `PortcastImportActivity` that pulls
the URI from `intent.getData()` (or `EXTRA_STREAM` for SEND), opens
it via `ContentResolver.openInputStream`, and reuses the existing Rx
pipeline from `ImportExportPreferencesFragment.importFromPortcast` —
which should be extracted into a shared helper at this point (one
caller in fragment, one in activity, one in v2).

## 6. Result screen (M6)

Three counts:

- **Imported** — subscriptions newly added to the library.
- **Already following** — subscriptions matched against existing feeds.
- **Couldn't match** — `preview.unresolvableFeeds` from M2.

For each "couldn't match" row, show `title / author / imageUrl` (already
populated in the PortCast document) and a "Search" button that opens
the existing add-podcast search UI pre-filled with the title.

A second-pass call to the resolver with a higher PodcastIndex budget
(or a different provider) could land later as a "Try harder" affordance.
Don't build it for v1.

## 7. Remote feature flag (M7)

Already-existing channels:

- TrimBrain config endpoint (preferred — Android already polls it for
  pricing/limits, see `TrimEventsUploadWorker`).
- Firebase Remote Config (already in the app for other flags).

Pick whichever has lower latency to update. A single boolean:
`spotify_migration_enabled`. Default `true`; flip to `false` if either
Spotify breaks the pathfinder constants or PodcastIndex search rate
limits us. The file-transfer importer (M3) is not flag-gated — it can
run even if the resolver path is dead (it'll just produce a lot of
"couldn't match" rows, which is still useful and recoverable).

Optional second flag: `spotify_migration_v2_enabled` (the WebView
flow). Lets us disable v2 while keeping v1's file-transfer path alive
if Spotify ever cracks down on WebView access specifically.

## 8. Telemetry

Off in v1's first cut, then turn on once the flow is stable. Events:

- `spotify_migration_started` (surface: v1_file / v2_webview)
- `spotify_resolver_call` (result: trimbrain_hit / podcastindex_hit /
  no_match / timeout, latencyMs)
- `spotify_migration_finished` (
  imported, alreadyFollowing, unresolvable, elapsedMs, surface)
- `spotify_migration_abandoned` (atPhase: signin / fetch / resolve /
  apply)

All via the existing `TrimEventsUploadWorker` plumbing. **No Spotify
account identifiers, no captured tokens, no podcast titles** in
telemetry — just counts and per-show resolution outcomes.

## 9. Threshold tuning (post-launch)

The PodcastIndex fallback's Jaro-Winkler thresholds are guesses. After
first release:

1. Sample 1000 successful TrimBrain resolutions across all users.
2. Run the same titles+authors through the PodcastIndex fallback as
   if TrimBrain were down.
3. Compare. Tune thresholds so false-positive rate (wrong feed
   subscribed) is < 1% and false-negative rate (unresolvable that
   TrimBrain matched) is < 10%.

Until that data exists, log every fallback's top match score with the
input title/author so we can rebuild the sample from real traffic.

## 10. What we explicitly aren't building

- **Spotify Web API OAuth.** Out of scope. The WebView path doesn't
  need it.
- **Episode-history backfill** (the slow "Spotify Data Export → PortCast"
  document). Brief §4 covers when this becomes relevant.
- **Multi-source merge** (importing the same library from Spotify
  twice, weeks apart, with different completeness assertions). The
  PortCast spec covers it; the existing `PortcastImporter` honors the
  basic dedupe. We don't add merge-conflict UX for this case in v1/v2.
- **Pocket Casts / Apple adapters.** Same architecture, different
  Chrome extension. Future quarter.

## 11. Concrete file list (in dependency order)

Each item shippable on its own.

### M1 — resolver

- New: `storage/importexport/src/main/java/.../spotify/SpotifyShowResolver.java`
- New: `storage/importexport/src/main/java/.../spotify/TrimBrainShim.java`
- New: `storage/importexport/src/main/java/.../spotify/PodcastIndexFallback.java`
- New tests:
  `storage/importexport/src/test/java/.../spotify/SpotifyShowResolverTest.java`
  with a frozen catalog of (title, author) → expected feedUrl pairs.
- Backend: `POST /resolve/spotify` (off-Android, tracked separately).

### M2 — importer changes

- Modify: `storage/importexport/.../PortcastImporter.java`
  - extend `PortFeed`, relax `parseSubscription`, add resolver call
    site in `previewImport`, add `unresolvableFeeds` to `ImportPreview`,
    progress callback.
- Modify: `storage/importexport/.../PortcastSubscribeWorker.java`
  - persist `subscriptionId → Feed.id` mapping.
- New: `storage/importexport/.../SubscriptionIdIndex.java` (the tiny
  mapping table, SharedPreferences-backed).

### M3 — v1 manifest + activity

- Modify: `app/src/main/AndroidManifest.xml` (intent-filters per
  brief §1.2.1).
- New: `app/src/main/java/.../portcast/PortcastImportActivity.java`
- New: `app/src/main/res/layout/portcast_import_activity.xml` (just
  a progress + summary screen).
- Refactor: extract `ImportExportPreferencesFragment.importFromPortcast`
  into a shared helper used by the activity too.

### M4 — onboarding hook

- Modify: `ui/preferences/src/main/res/xml/preferences_import_export.xml`
  (new "Coming from Spotify?" pref).
- Modify: `ImportExportPreferencesFragment.java` (wiring +
  bottom-sheet).
- New strings in `ui/i18n/src/main/res/values/strings.xml`.

### M5 — v2 production WebView

- Move + harden: `app/src/main/java/.../migration/SpotifyMigrationActivity.java`
  (already exists as a debug spike; promote it, strip debug-only code,
  add string resources, add cancel + progress UI, wire the post-fetch
  buildDocument round-trip and the importer hand-off).
- Add: `app/src/main/assets/spotify_migration/portcast.js` (verbatim
  copy of `chrome-extension/lib/portcast.js`, refreshed periodically).
- Modify: the "Coming from Spotify?" sheet to surface the v2 entry.
- Delete: the `BuildConfig.DEBUG` gate on the pref entry; replace with
  the feature-flag gate from M7.

### M6 — result screen

- New: `app/src/main/java/.../portcast/PortcastImportResultActivity.java`
  (or a Fragment if it lives inside the v1 activity).
- New: `app/src/main/res/layout/portcast_import_result_activity.xml`
  with three sections and the unresolvable list.
- New strings.

### M7 — feature flag

- Modify: wherever TrimBrain config is consumed (likely
  `TrimEventsUploadWorker` or a sibling). Add
  `spotify_migration_enabled` boolean + optional
  `spotify_migration_v2_enabled`.

## 12. Rollout

1. Land M1 + M2 + M7 behind the feature flag, default OFF in prod,
   ON in debug. Test internally.
2. Land M3 + M4 + M6. v1 is shippable; flip the flag ON for a small
   beta cohort if the TrimBrain backend's resolution accuracy looks
   good in telemetry. v1's failure modes (long manual-search list) are
   visible to users but recoverable.
3. Land M5. v2 is shippable; ramp via the second flag.
4. Once both surfaces are stable, default the flag ON for everyone.

## 13. Open questions for the next session

- TrimBrain endpoint timing — when can the backend ship `/resolve/spotify`?
  Android can build against a stub until then, but a real endpoint
  unblocks accuracy tuning.
- Manual-search UX inside the result screen — should it reuse the
  existing add-podcast search activity as-is, or do we need a slimmer
  variant for in-flow use? Decide before M6.
- Do we want to expose the WebView's "Cancel" mid-fetch state to the
  user as "Saved partial library"? Probably no — partial state is
  confusing — but worth a sentence in the result screen if we do.
