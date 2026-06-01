# Trimplayer Android — Spotify → Trimplayer migration brief

Hand this document to the Android coding agent. It is self-contained:
the agent does not need to read the rest of the PortCast repo to act on
it.

## 0. Context (read this first)

PortCast is an open file format (`application/vnd.portcast+json`, file
extension `.portcast.json`) for moving a listener's podcast data
between apps. Spec: https://portcast.org · Repo:
https://github.com/Trim-Player/PortCast.

The migration we're shipping has one user-visible goal: **a listener
on Spotify ends up on Trimplayer with their library intact, in one
session, with as little manual work as possible.**

There are two delivery surfaces. v1 ships first because it is small
and unblocks acquisition immediately; v2 is the better long-term UX
and reuses ~80% of v1's importer code.

| Surface                     | Who produces the file       | Where the user touches it           | Status          |
| --------------------------- | --------------------------- | ----------------------------------- | --------------- |
| **v1 — File transfer**      | Chrome extension on desktop | Email/Drive/AirDrop to phone, open in Trimplayer | Ship now |
| **v2 — In-app WebView**     | Trimplayer Android itself   | Inside the app, no file leaves device            | Ship next |

The Android-side **importer** (parsing `.portcast.json` and applying
it to the library) is the same code for both surfaces. v2 just
replaces the file source with an in-app fetch.

### 0.1 What already exists in the Android repo

**Do not rebuild the importer from scratch.** The PortCast importer is
already in the tree and wired up end-to-end for "PortCast → PortCast"
round-tripping (i.e. files produced by Trimplayer's own
`PortcastExporter`). Files:

- `storage/importexport/src/main/java/.../PortcastImporter.java` —
  parse, build `ImportPreview`, conflict-detect against the local DB,
  stash to SharedPreferences. Uses `org.json` (not Moshi /
  kotlinx.serialization — don't introduce a third JSON stack).
- `storage/importexport/src/main/java/.../PortcastSubscribeWorker.java` —
  WorkManager job that subscribes each feed, applies per-feed prefs and
  `com.trimplayer.*` extensions, then chains the state worker.
- `storage/importexport/src/main/java/.../PortcastStateWorker.java` —
  applies episode states + queue after the feed refresh materializes
  items.
- `storage/importexport/src/main/java/.../PortcastExporter.java` —
  round-trip export.
- `app/src/main/java/.../ui/screen/preferences/ImportExportPreferencesFragment.java` —
  entry point. Already off-mainthread (Rx + `Schedulers.io()`),
  already opens the URI via `ContentResolver.openInputStream`,
  already shows a conflict dialog shared with the PodcastAddict
  importer. Routes SAF-picker imports via head-sniffing
  (`text.startsWith("{") && text.contains("\"portcast\"")`).

The v1 Spotify scope is therefore much narrower than "build an
importer." It is: **(a)** a manifest intent-filter so a `.portcast.json`
file from Gmail/Drive opens in Trimplayer; **(b)** a resolver that
turns Spotify-only subscriptions (no `feedUrl`) into something the
existing subscribe worker can consume; **(c)** an "Coming from
Spotify?" affordance inside `ImportExportPreferencesFragment`.

---

## 1. v1 — File-transfer import path

### 1.1 User flow

1. User installs **PortCast Export** from the Chrome Web Store.
2. On `open.spotify.com`, they click the extension and press Export.
3. The extension saves `spotify-<userid>-<date>.portcast.json` to
   their Downloads folder.
4. They transfer the file to their phone (email to themselves, Drive,
   AirDrop-equivalent, USB — out of our scope).
5. On Android, they tap the attachment or share it to Trimplayer.
   Trimplayer is offered in the "Open with" / share sheet. They pick
   Trimplayer.
6. Trimplayer shows a one-screen confirmation:
   _"Import N shows from Spotify? Resume positions for M episodes will
   be applied."_ → Import button.
7. Library is populated. A summary screen reports successes, conflicts
   resolved, and unresolvable items (with a manual-search affordance).

### 1.2 Android changes required

#### 1.2.1 Manifest — register as a `.portcast.json` opener

Today, `.portcast.json` files only enter via SAF picker (the file-head
sniffer in `ImportExportPreferencesFragment.java` already handles
that). Add an `<intent-filter>` so tapping the file from
Gmail/Drive/Files also opens Trimplayer.

Place the filter on a new lightweight router activity (e.g.
`PortcastImportActivity`). **Do NOT** add it to a Launcher activity —
Android will then offer Trimplayer for plain `.json` files in
unrelated apps. **Do NOT** use `import` as a Java package segment;
it's a reserved word.

```xml
<activity
    android:name=".portcast.PortcastImportActivity"
    android:exported="true"
    android:label="@string/portcast_import_label">

  <!-- Tap-to-open: VIEW with content/file URI. -->
  <intent-filter android:label="@string/portcast_import_label">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />

    <!-- Structured-syntax MIME (rare in the wild but cheap to claim). -->
    <data android:scheme="content" />
    <data android:scheme="file"    />
    <data android:mimeType="application/vnd.portcast+json" />
  </intent-filter>

  <!-- Most file managers / email apps attach application/json (or
       octet-stream when MIME is unknown) and rely on the extension.
       Narrow by pathPattern back to .portcast.json so we don't claim
       every JSON file. pathPattern requires host=* to be evaluated. -->
  <intent-filter android:label="@string/portcast_import_label">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />

    <data android:scheme="content" />
    <data android:scheme="file"    />
    <data android:host="*"         />
    <data android:mimeType="application/json"         android:pathPattern=".*\\.portcast\\.json" />
    <data android:mimeType="application/octet-stream" android:pathPattern=".*\\.portcast\\.json" />
    <data android:mimeType="text/plain"               android:pathPattern=".*\\.portcast\\.json" />
  </intent-filter>

  <!-- Share-sheet: SEND with content URI (Gmail "Share attachment"). -->
  <intent-filter android:label="@string/portcast_import_label">
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/json" />
    <data android:mimeType="application/vnd.portcast+json" />
    <data android:mimeType="application/octet-stream" />
  </intent-filter>
</activity>
```

Why the redundancy: each `<data>` attribute is OR'd across the
filter's `<data>` elements, but `mimeType` and `path*` are AND'd
within a single matched URI. `pathPattern` is also silently ignored
unless `android:host` is set. Without `octet-stream`, a non-trivial
fraction of users (file managers that don't recognize the extension)
won't see Trimplayer in the picker at all.

`PortcastImportActivity` should be tiny: pull `intent.getData()` (or
`EXTRA_STREAM` for SEND), call the existing
`PortcastImporter.previewImport(...)` off the main thread, then either
show the existing conflict dialog or launch `executePortcastImport`.
Reuse — don't duplicate — the Rx pipeline in
`ImportExportPreferencesFragment.importFromPortcast(...)`; extract it
into a shared helper if needed.

#### 1.2.2 Read the file from the Intent

Intent data URIs are almost always `content://` on modern Android
(Gmail and Drive proxy through a `FileProvider`). Use
`ContentResolver.openInputStream(uri)`. Do **not** assume a `file://`
path is readable — scoped storage will refuse it.

Caps (to prevent a hostile-file DoS or algorithmic-DoS):

- Reject input over **50 MB** before parsing.
- After parsing, reject documents with more than **10,000
  subscriptions** or **100,000 episode states** with a clear
  user-facing error. The existing importer's dedupe pass builds a
  full in-memory map keyed by GUID/enclosure URL of every feed item
  in the local DB; an attacker-shaped document could blow heap.

#### 1.2.3 Parsing — extend `PortcastImporter`, don't fork it

The existing `PortcastImporter.parseSubscription` requires `feedUrl`
and drops anything else on the floor (see
`PortcastImporter.java:242`). Spotify-sourced subscriptions have NO
`feedUrl`, only `platformRefs: ["spotify:show:<id>"]` — so today they
silently disappear.

Changes to `PortcastImporter`:

- Accept subscriptions with no `feedUrl` if `platformRefs` is
  non-empty. Extend `PortFeed` with `List<String> platformRefs` and a
  `String subscriptionId` (the PortCast field — stable across
  exports, source-independent).
- In the dedupe pass against the local DB, prefer `subscriptionId →
  Feed` (a new mapping we persist) over raw `feedUrl` equality. Raw
  URL equality misses `http`/`https`, trailing-slash, and feedburner
  → final-URL canonicalization differences and produces duplicate
  subscriptions on re-import. Falling back to `feedUrl` is fine; just
  don't lead with it.
- Tolerate unknown top-level fields. `org.json`'s `optX` calls
  already do this; no change needed beyond not throwing on missing
  fields.
- Spec version: existing code logs `"PortCast 0.1.0"`. The Spotify
  exporter emits `"0.2.0"`. Pick one consistent version-acceptance
  policy (accept any `0.x`; warn on `1.x+`) and apply it in one
  place.

The `generator` and `owner` top-level objects are advisory only.
Persist `generator.name` for telemetry / debugging; never display
`owner.email` verbatim in UI.

#### 1.2.4 Resolve `spotify:show:<id>` → feed URL

This is the only genuinely new functional piece for v1.

A Spotify-sourced subscription must be converted to an RSS feed URL
before the existing `PortcastSubscribeWorker` can act on it
(`subscribeOne` constructs `new Feed(pf.feedUrl, null, pf.title)`).

Resolver options, in priority order:

1. **TrimBrain (our own backend) shim.** Cheapest path: add a thin
   `/resolve/spotify` endpoint server-side that wraps whichever
   resolver we settle on. Decouples Android releases from API
   changes, lets us cache hot results, and gives us telemetry on
   resolution failure rates without instrumenting the client. Talks
   to it via `TrimEventsUploadWorker`'s existing HTTP plumbing.
2. **PodcastIndex search.** Free, no auth required for search. Note:
   **PodcastIndex does not have a documented `byspotifyid` endpoint
   as of this writing**. The realistic strategy is a search on
   `title` + `author` (both fields are populated in Spotify-sourced
   subs, see §3.2), then accept the top match only if title + author
   both pass a similarity threshold (e.g. normalized Jaro-Winkler
   ≥0.9 on title AND ≥0.85 on author). Anything below the threshold
   goes on the unresolvable list.
3. **Spotify Web API.** Could give us the show's RSS URL via
   `/shows/{id}`, but the result is gated by market and the field is
   not always populated. Not worth the OAuth complexity unless (1)
   and (2) prove insufficient.

Resolver budget per import:

- Max **8 concurrent** calls, hard timeout **5 s per call**, total
  budget **2 min**. A 200-show library at one-at-a-time × 5 s hangs
  the import for 17 minutes — unacceptable.
- On timeout / error, mark the subscription unresolvable rather than
  failing the whole import.
- Cache results by `spotify:show:<id>` for the lifetime of the import
  attempt so retries don't re-spend the budget.

Resolution happens during preview (before
`executePortcastImport`), so the user can see "187 of 200 shows will
be added; 13 couldn't be matched" before they commit.

#### 1.2.5 Episode-state matching

Today's Spotify extension exports an empty `episodes` array (see the
TODO in `chrome-extension/background.js`). Once it ships episode
states, they will carry `subscriptionRef.platformRefs:
["spotify:show:<id>"]` (no `feedUrl`) and
`platformRefs: ["spotify:episode:<id>"]`. Spotify episode IDs do not
correspond to RSS GUIDs, so exact GUID matching is impossible.

Two-step match:

1. From `subscriptionRef.platformRefs`, look up the resolved
   subscription from §1.2.4.
2. Within that subscription's materialized episodes, fuzzy-match by
   `(publishedAt, title, durationSeconds)`. Tight tolerance:
   publishedAt within ±48 hours, title normalized-similarity ≥0.9,
   duration within ±5 seconds.

`completeness` for `episodes` will be `current-state-only` — per spec
§11.2, **do not delete any existing episode history** Trimplayer
already has. The existing conflict-detection logic (`hasApPlayData`
in `PortcastImporter`) already respects this for the per-episode
case; the only addition is to never interpret an absent-from-import
episode as "play history should be cleared."

#### 1.2.6 Dedupe and idempotency

Already handled in `PortcastImporter` for the round-trip case. For
Spotify-sourced docs, plus the resolver changes above:

- Persist a `subscriptionId → Feed` mapping (new tiny table, or a
  JSON blob on `Feed.preferences`). Use it as the primary dedupe key
  on re-import.
- Honor §11.2 merge rules already in place. **Do NOT** treat the
  `subscriptions:full` completeness assertion as license to
  auto-unsubscribe; the existing code is already additive — leave a
  comment so future-you doesn't "fix" it.

#### 1.2.7 Result screen

Three counts: imported, already-following, unresolvable. The
unresolvable list shows `title / author / imageUrl` from the
PortCast document and offers a manual "Search by title" affordance
(reuse the existing add-podcast search UI). Everything else dumps the
user into "Your Podcasts."

### 1.3 Onboarding hook

Trimplayer doesn't have a multi-step onboarding flow. The natural
home is a new top entry inside
`ImportExportPreferencesFragment` titled **"Coming from Spotify?"**
(string resource, English only — see CLAUDE.md). Tapping it shows a
sheet that:

- illustrates the 3-step Chrome-extension flow,
- links to the Chrome Web Store listing (URL TBD — leave a constant),
- explains how to get the file to the phone (Gmail, Drive,
  AirDrop-equivalent),
- has a "What gets imported?" expander listing: followed shows,
  resume positions for saved episodes. Be explicit that play history
  is NOT exported (Spotify doesn't expose it).

If/when we add a true first-run onboarding flow, surface the same
entry there too.

### 1.4 Feature flag

Gate the entire Spotify-resolver path behind a remote flag (TrimBrain
config endpoint, or a Firebase Remote Config key — whichever already
exists). The resolver depends on third-party endpoints; we need a
kill-switch that doesn't require an app release. The file-transfer
plumbing itself (intent-filter, importer changes) can stay enabled
unconditionally — failures there only surface to users who
deliberately tap a `.portcast.json` file.

---

## 2. v2 — In-app WebView export path

### 2.1 Why this is the better UX

Skip the desktop, the file transfer, and the "Open with" picker
entirely. Inside Trimplayer:

1. User taps "Coming from Spotify?"
2. A WebView opens to `https://accounts.spotify.com/...` for sign-in.
3. After sign-in, the WebView lands on
   `open.spotify.com/collection/podcasts`.
4. Trimplayer's host code, watching the WebView, captures the same
   tokens the Chrome extension captures and runs the same library
   fetch.
5. The library is imported and the WebView dismissed. Total user
   actions: tap + Spotify sign-in.

### 2.2 What to reuse

`chrome-extension/lib/portcast.js` is explicitly platform-agnostic
(see file-header comment: "Reused unchanged inside the Trimplayer
mobile app's WebView"). Pull it in via:

- **Option A (recommended)**: bundle the JS file as an asset; inject
  it into the WebView alongside the page; call
  `buildDocument({me, savedShows, savedEpisodes})` over the JS
  bridge.
- **Option B**: port `portcast.js` to Java/Kotlin. ~150 lines of
  straightforward mapping. Saves the WebView round-trip but doubles
  maintenance.

Option A means a v0.2-spec bump in `portcast.js` propagates without
an Android release.

The fetch logic in `chrome-extension/background.js`
(`fetchSpotifyLibraryInTab` and its pathfinder GraphQL call) is also
reusable verbatim via `WebView.evaluateJavascript`. Same approach the
extension uses for `chrome.scripting.executeScript`.

The host then writes the resulting JSON to a temp file or in-memory
buffer and hands it to the same
`PortcastImporter.previewImport(context, stream)` entrypoint v1
uses. The conflict dialog, subscribe worker, state worker, and
resolver in §1.2.4 all apply unchanged.

### 2.3 WebView config gotchas

- `WebSettings.setJavaScriptEnabled(true)` — required.
- `WebSettings.setDomStorageEnabled(true)` — Spotify uses
  `sessionStorage` / `localStorage` heavily; sign-in breaks without
  this.
- Cookies must persist across the OAuth redirect. Use
  `CookieManager.setAcceptThirdPartyCookies(webView, true)`. Without
  this, Spotify's sign-in loops.
- **Set a desktop Chrome User-Agent.** The default mobile WebView UA
  trips Spotify's "open in app or close" mobile-web wall, which
  never lets sign-in proceed. Verified working as of 2026-05-31:
  `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like
  Gecko) Chrome/124.0.0.0 Safari/537.36`. Version-bump when stable
  Chrome moves. Note: this contradicts what an earlier draft of this
  brief said; the spike confirmed a desktop UA is required.
- Inject the token-capture hook in `WebViewClient.onPageStarted`
  via `evaluateJavascript`. The spike confirmed this is early
  enough — `tokenSource: "hook-bootstrap"` was the first read
  outcome, meaning no race-loss against Spotify's bundle. No
  `shouldInterceptRequest` HTML-injection trickery needed.
- In `shouldOverrideUrlLoading`, let `https://accounts.spotify.com/*`
  and `https://open.spotify.com/*` navigate freely. Block
  `spotify:`, `intent:`, `market:`, `android-app:` schemes so
  Spotify's mobile-web fallback can't punt the user out of the
  WebView. Only intercept https URLs to detect "we've landed back
  on `open.spotify.com`, run the fetch."
- `/collection/podcasts` redirects to `/collection/tracks` under
  the desktop SPA. The library sidebar still fires libraryV3 from
  `/collection/tracks`, so the export works — but the URL gate that
  enables Fetch must accept any `/collection/*` URL, not just
  `/podcasts`.

### 2.4 Risks worth flagging up front

- **Spotify ToS and API churn.** Driving Spotify's web app inside a
  WebView to scrape an authenticated user's library is exactly the
  kind of thing Spotify has historically broken (the December 2025
  endpoint change cited below is one example) and has explicit ToS
  language against. Not a reason not to ship — Pocket Casts and
  Overcast variants do similar — but we need: (a) a remote
  feature-flag (see §1.4) to disable v2 server-side without a
  release, (b) graceful fallback to the v1 file-transfer flow when
  v2 is killed, (c) no Trimplayer branding inside the WebView that
  would make a takedown notice more likely.
- **Re-auth on token expiry.** WebView cookies expire; if a returning
  user re-runs the migration months later, plan for a re-prompt
  rather than a silent failure.

### 2.5 Console noise that is NOT a real problem

Don't chase these — the spike confirmed they're benign:

- `EMEError: No supported keysystem was found` and
  `PlaybackError: No supported keysystem was found` from
  `web-player/vendor~*.js`. Spotify's web player tries to
  initialise Encrypted Media Extensions for DRM-protected audio
  playback. WebView doesn't ship with Widevine integrated the same
  way Chrome does. We don't play audio — only fetch library JSON —
  so these errors fire during page load but don't block anything.
- "Resource was preloaded using link preload but not used"
  warnings about woff2 fonts. Same reason — unused subresources of
  the player UI we're not driving to completion.
- `me.id` coming back null. The desktop SPA doesn't populate
  `document.getElementById("session")` the way mobile-web does.
  PortCast's `owner` block is optional; either add a profile
  pathfinder probe or accept null.

### 2.6 Note about `lib/platforms/spotify.js`

That file was an earlier seed for the mobile-WebView path that
predated the December 2025 Spotify API change. It targets the old
REST endpoints (`/v1/me/shows`), which now 401 against api-partner
tokens. **Do not use it as-is.** The current, working fetch logic
lives in `chrome-extension/background.js`
(`fetchSpotifyLibraryInTab`) — port from there.

---

## 3. The `.portcast.json` shape the importer actually needs to parse

The full spec is in `SPECIFICATION.md` in the PortCast repo. For the
v1 Spotify importer, only these fields appear or matter — and most of
them are already parsed by the existing `PortcastImporter`.

### 3.1 Top-level

```json
{
  "portcast": "0.2.0",
  "generatedAt": "2026-05-29T21:16:27Z",
  "generator": { "name": "...", "version": "...", "url": "..." },
  "owner": { "displayName": "...", "email": "..." },
  "subscriptions": [ ... ],
  "episodes": [ ... ],
  "completeness": [ ... ]
}
```

- `portcast`: SemVer. Accept any `0.x`; warn on `1.x+`.
- `owner`: may be absent. When present, use only to label the import
  ("Imported from Yonatan's Spotify"). Do not persist as a Trimplayer
  user identity.
- `episodes`: may be empty (today's extension exports `[]`).

### 3.2 Subscription (as produced by the Spotify extension)

```json
{
  "subscriptionId": "ff888d1e340f4d1193f652f072d21519",
  "title": "Acquired",
  "author": "Ben Gilbert and David Rosenthal",
  "imageUrl": "https://i.scdn.co/image/...",
  "subscribedAt": "2026-05-23T17:13:51Z",
  "platformRefs": ["spotify:show:7Fj0XEuUQLUqoMZQdsLXqp"],
  "updatedAt": "2026-05-29T21:16:27Z"
}
```

Critical: `platformRefs` is the ONLY identifier. No `feedUrl`, no
`podcastGuid`. Resolution is the importer's job (§1.2.4). The
existing `PortcastImporter.parseSubscription` rejects subscriptions
without `feedUrl` — that check must be relaxed (§1.2.3).

`subscriptionId` is stable across exports from the same source —
prefer it over `feedUrl` for dedupe.

### 3.3 Completeness assertions

```json
"completeness": [
  { "section": "subscriptions", "source": "spotify", "level": "full",
    "capturedAt": "2026-05-29T21:16:27Z" },
  { "section": "episodes", "source": "spotify",
    "level": "current-state-only",
    "capturedAt": "2026-05-29T21:16:27Z",
    "note": "Spotify exposes resume_point only for saved episodes." }
]
```

Apply per spec §11.2:

- `subscriptions:full` → consumer MAY treat absences as
  unsubscribes. We choose not to. Code it that way with a comment so
  future-you doesn't "fix" it.
- `episodes:current-state-only` → consumer MUST NOT delete any
  existing episode history. Resume positions can be applied; play
  events cannot be inferred.

### 3.4 Episode state (when present)

The Spotify extension does not currently emit any. When it does, the
shape is documented at `chrome-extension/lib/portcast.js:63`
(`episodeFromSavedEpisode`). Highlights:

- `subscriptionRef` carries `platformRefs` — match it back to the
  subscription you imported (§1.2.5).
- `status` is `unplayed | in_progress | completed | archived`.
- `positionSeconds` present only when `status === "in_progress"`.

`PortcastImporter.parseEpisode` already handles `status`,
`positionSeconds`, `completedAt`, `lastPlayedAt`. The new piece is
**matching by `subscriptionRef.platformRefs`** (today it only reads
`subscriptionRef.feedUrl`).

---

## 4. Concrete change list (for the implementing agent)

For v1 ship, in dependency order:

1. `PortcastImporter.java`
   - Relax `parseSubscription` to accept `platformRefs`-only
     subscriptions; add `platformRefs` and `subscriptionId` to
     `PortFeed`.
   - Add resolver call site in `previewImport` (before conflict
     detection) that turns each unresolved `PortFeed` into either
     `(resolved feedUrl, kept subscription)` or
     `(unresolvable, recorded for the result screen)`.
   - Extend dedupe lookup to prefer `subscriptionId → Feed`.
   - Bump accepted version range; pick one consistent log line.
2. New module/class for the resolver (`SpotifyShowResolver` or
   similar) with the concurrency/timeout budget in §1.2.4. Behind a
   remote feature flag.
3. Persistence for `subscriptionId → Feed` (small new prefs entry or
   column).
4. `app/src/main/AndroidManifest.xml` — new `PortcastImportActivity`
   plus the intent-filters in §1.2.1.
5. New `PortcastImportActivity` that wraps the existing Rx import
   pipeline (extract the body of
   `ImportExportPreferencesFragment.importFromPortcast` into a
   shared helper).
6. "Coming from Spotify?" entry in
   `ImportExportPreferencesFragment` (§1.3).
7. Result-screen plumbing for the unresolvable list (manual-search
   affordance reusing the existing add-podcast search UI).
8. English strings only (per CLAUDE.md): the new
   `portcast_import_label`, "Coming from Spotify?" copy, the
   what-gets-imported expander text, the result-screen labels.

v2 builds on the same `PortcastImporter` entrypoint — the only net
new code is the WebView host, JS asset bundling, and the bridge that
converts the in-WebView `buildDocument(...)` result into an
`InputStream` for `previewImport`.

## 5. Open questions / things to come back to

- **PodcastIndex resolution accuracy.** The title+author search
  strategy in §1.2.4 needs a measured baseline on a real Spotify
  export before we commit to thresholds. First implementation should
  log the match scores so we can tune.
- **Episode-history backfill.** If/when we ship a "Spotify Data
  Export → PortCast" tool (the slow, full-history out-of-band
  export), the importer must handle two PortCast documents for the
  same user arriving days apart — spec §11 covers the merge rules;
  we'll exercise them then.
- **Manual matching UX.** The unresolvable-list flow should be
  designed once and reused for v1 file imports, v2 in-app imports,
  and any future platform adapter (Pocket Casts, Apple).
- **Telemetry.** None in v1's first cut. Once the flow is stable,
  instrument outcomes via the existing TrimAnalytics path:
  resolution success rate by show, time-to-import, drop-off at each
  step. Trimplayer's analytics owns this — not anything in the
  PortCast document.
