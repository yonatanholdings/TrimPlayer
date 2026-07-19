# Deprecating Queue & Inbox — Playlists Only

**Goal:** one organizing concept. No Inbox, no special Queue. Every new episode
routes to the appropriate playlist (or nowhere); playback, downloads, widgets,
and the watch all anchor on playlists.

**Context that makes this cheap:** TrimPlayer is pre-launch (no real users to
migrate — repo convention says don't invent back-compat), the Queue is already
just the default playlist under the hood (2026-07-18 unification), the web
player never had an Inbox, and the sync protocol already expresses everything
(named queues + `__queue__` markers + `__queue_rule__` rules). This plan is
therefore mostly *removal* on Android plus small re-anchoring, with zero
backend changes.

Measured coupling (2026-07-19): Inbox/`isNew`/NEW-filter touches 23 files;
`NewEpisodesAction` 12; `EnqueueLocation` 7; auto-download selects candidates
by `FeedItemFilter(NEW)` in `AutomaticDownloadAlgorithm` and cleans up around
queue membership.

---

## 1. Target model

- **Playlists are the only container.** The former Queue survives as one
  ordinary playlist — deletable no, renamable yes (see §3.2) — designated
  **"Up Next"**: the default playback context, the widget/Android Auto/watch
  source, and the default destination suggestion. On the wire it keeps the
  reserved name `default` forever (watch + PortCast + older-build compat);
  display name is a synced alias.
- **Routing replaces the Inbox.** A new episode goes to every playlist whose
  auto-add rule watches its feed (rules already exist, synced, cutoff-safe).
  A feed with no rule routes **nowhere**: the episode exists in the library
  (feed screen, All Episodes, search) with no flag and no triage debt. The
  subscribe-time destination picker is the primary rule-creation moment; its
  "Inbox (default)" option becomes **"Library only"**.
- **"New" stops being a state.** The `isNew` flag, NEW filter, Inbox screen,
  inbox badge, and inbox swipe actions are removed. "What's new" becomes a
  derived view: recently-published episodes of subscribed feeds (query by
  pubDate), surfaced as a Home rail — read-only, no per-item lifecycle.
- **Auto-download re-anchors on playlists**: download the first N
  undownloaded episodes of each playlist (Up Next first), instead of "episodes
  in the inbox". Cache eviction prefers episodes in no playlist and played.

## 2. What exists today (inventory)

| Concept | Android | Web | Server |
|---|---|---|---|
| Inbox | InboxFragment, nav entry + badge, Home "See what's new", `isNew`/`setNew`, NEW filter, RemoveFromInboxSwipeAction, `NewEpisodesAction` (GLOBAL/INBOX/QUEUE), auto-download forces INBOX | — none — | — none — |
| Queue specialness | Queue tab, QueueFragment (lock/sort/swipe), pinned card, undeletable/unrenamable, EnqueueLocation prefs, keep-sorted, widgets/Auto/watch snapshot via `getQueue()` facade | `DEFAULT_QUEUE` pinned chip, single-queue minimal mode, undeletable | reserved name `default`; watch reads it; PortCast `queue` field |
| Routing | PlaylistFeeds rules + subscribe picker + per-feed `NewEpisodesAction` (legacy, device-local, queue-only) | rules + subscribe picker | `__queue_rule__` prefs |

## 3. Design decisions

1. **Rules are the only router.** `NewEpisodesAction` is deleted, not mapped
   onto a hidden setting. Migration converts feeds whose effective action is
   ADD_TO_QUEUE into a rule → Up Next (cutoff = migration time). INBOX/GLOBAL
   feeds get no rule (their episodes stop accumulating anywhere — by design).
2. **Up Next identity.** Keep `is_default` + wire name `default`. Allow
   display rename via a synced alias pref (`__queue_title__` → string), so
   "Up Next"/"Queue"/anything renders consistently on phone + web without
   touching the wire identity the watch and PortCast depend on. Undeletable
   stays (playback and the watch need a guaranteed anchor).
3. **Playback advance** (already unified): active playlist context, falling
   back to Up Next. No change needed beyond renaming surfaces.
4. **EnqueueLocation + keep-sorted/lock** stay Up Next-only features for now
   (they live behind the facade; generalizing is optional later polish).
5. **Pending inbox items at migration:** clear all `isNew` flags. No sweep
   screen — pre-launch, and the Home "Recently published" rail keeps
   discoverability. (Your own device's 77-item inbox simply becomes zero
   badge; episodes remain in All Episodes.)
6. **Auto-download policy** (the one real algorithm change):
   candidates = union over playlists of first `K` undownloaded items
   (K = episode-cache / #playlists, min 1, Up Next weighted 2x), replacing the
   NEW-filter query in `AutomaticDownloadAlgorithm`. Cleanup: eviction order
   played-&-unplaylisted → unplaylisted → played-in-playlist; never evict
   undownloaded-position-holding items of Up Next. `APQueueCleanupAlgorithm`
   generalizes to "in any playlist"; `APCleanupAlgorithm` unchanged.
7. **Notifications** (per-feed "new episode") key on refresh, not inbox — they
   survive unchanged, and tapping one deep-links to the episode (not Inbox).

## 4. Phases

### D0 — Guardrails (0.5 day)
Pin current routing behavior in tests before touching it: rule-routing test
(exists), new tests for `NewEpisodesAction→rule` migration and the
auto-download candidate selection (current NEW-based behavior as baseline,
then flipped with the new policy).

### D1 — Inbox routing retirement (1 day, Android only)
- `FeedDatabaseWriter`: delete the `NewEpisodesAction` switch; new items get
  no flag; rule application (already in place) is the only routing.
- Migration (prefs pass, not DB version): for each feed, if effective action
  was ADD_TO_QUEUE → `addPlaylistAutoFeed(upNextId, feedId, now)`; then clear
  every `isNew` flag (single UPDATE); delete the global + per-feed settings UI.
- Subscribe picker: "Inbox (default)" → "Library only (no playlist)".

### D2 — Inbox UI removal + auto-download re-anchor (1.5–2 days)
- Remove: InboxFragment, nav entry + badge (BottomNavigation badge loader),
  Home "See what's new" section, NEW filter chips, inbox swipe actions +
  their defaults (Inbox swipe prefs migrate to episode-list defaults).
- Add: Home "Recently published" rail (pubDate-derived, read-only, per-feed
  art, tap → episode screen; "Add to playlist" long-press works as anywhere).
- Auto-download: new candidate selection + cleanup per §3.6, behind the
  existing algorithm interfaces so tests swap cleanly.

### D3 — Queue de-specialization (1–1.5 days, Android + web)
- Rename surfaces: bottom-nav "Queue" tab → "Up Next" (opens the same screen);
  Playlists card label from alias; add rename to Up Next overflow (writes the
  synced alias). Keep undeletable.
- Web: chip label from alias; rename option on the default chip; keep the
  single-queue minimal mode (it reads even better once Inbox is gone).
- Watch/widgets/Auto: no code change (facade + `default` wire name persist).
- PortCast: unchanged (`queue` field = Up Next; alias exported as an extension
  field `upNextTitle` for round-trip).

### D4 — Cleanup (next release, folds into the existing P4)
- Drop legacy `Queue` table (DB 3170000) + retire `QueueEvent` dual-post
  (already planned); drop `isNew` column + NEW filter constants; drop
  `NewEpisodesAction` enum + prefs; drop the `named_queues` opt-in gate
  server-side once no pre-gate build exists.

**Total to shippable (D0–D3): ~4–5 days.**

## 5. Migration summary (existing devices/accounts)

| What | Migration |
|---|---|
| Server / web accounts | none — protocol already final |
| Queue contents | none — already the default playlist |
| Feeds set to "add new to queue" | auto-converted to a rule → Up Next (cutoff = now) |
| Feeds set to inbox/global | no rule; episodes appear in library only |
| Pending inbox items | flags cleared once; reachable via All Episodes + new Home rail |
| Inbox swipe prefs | reset to episode-list defaults |
| Watch | unaffected (`default` snapshot unchanged) |

## 6. Risks

| Risk | Mitigation |
|---|---|
| Auto-download regression (candidate set changes) | D0 baseline tests + new-policy tests; policy behind existing interface; episode-cache cap unchanged |
| "Where do new episodes go now?" confusion | subscribe picker is mandatory-visible; Home rail replaces the inbox glance; feed screen unchanged |
| Silent data expectation: users who *triage* the inbox | acceptable pre-launch; Home rail is the successor; revisit only if beta feedback demands a "route everything to one playlist" global default |
| Rename alias drift between devices | alias is a synced pref (LWW like every other pref) |
| Third bottom-nav slot freed (Inbox) | give it to Playlists — resolves the More-menu burial from day one |

## 7. Open product choices (defaults chosen, flag if you disagree)

1. No-rule feeds route **nowhere** (recommended) vs. auto-rule to Up Next for
   every subscribe (recreates inbox-pressure in Up Next; not recommended).
2. Bottom nav becomes Home / **Up Next** / **Playlists** / Subscriptions / More.
3. The Home rail is "Recently published" (pubDate) — not a per-playlist digest.
