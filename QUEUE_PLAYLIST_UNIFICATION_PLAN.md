# Queue ⇄ Playlists Unification Plan

**Verdict first: yes, this merge makes sense — and two of the three layers are
already merged.** The account-sync model (backend) and the web player treat the
Queue as nothing more than the named queue `"default"`: one table
(`account_queue` keyed `(account_id, queue_name, episode_url)`), one store, one
UI family. Android is the only place where "Queue" and "Playlists" are two
separate storage systems, two UI stacks, and two sync code paths. Unifying
Android onto the playlist model deletes duplication, gives the Queue every
playlist feature for free (auto-add rules, cover collages, one consistent
reorder UX), and makes the phone match the mental model the web already ships.

The risk is concentrated in one place: AntennaPod's `Queue` table is wired into
the playback engine, auto-download, widgets, Android Auto, notifications, and
~25 read/write call sites (measured: 13 files call `getQueue`/`getQueueIDList`,
14 call `add/remove/move/clearQueue`, 11 touch `QueueEvent`, 9 use queue prefs).
The plan therefore **keeps the queue API as a facade and swaps only the storage
underneath it** — no call-site rewrites in the playback path.

---

## 1. Target model

- One concept: **playlists**. The Queue is the *default playlist* — pinned
  first, undeletable, not renamable, displayed with the localized name "Queue".
- Storage (Android): `PlaylistItems` is the single episode-list table. The
  default playlist is a `Playlists` row flagged `is_default=1`.
- Sync: unchanged wire format. The default playlist syncs as `queue_name =
  'default'`; named playlists sync by name. **No backend or web migration at
  all** — the server has been in the target shape since named queues shipped
  (account_queue PK `(account_id, queue_name, episode_url)`).
- Playback: "advance within the active playlist context; the default context is
  the default playlist" — one rule replaces today's queue-with-playlist-override
  special case in `PlaybackService.getNextItem` (line ~1954).
- Every playlist feature applies to the Queue: auto-add rules (+ unplayed
  backfill), collage card, totals. Every queue feature eventually generalizes:
  drag/lock/keep-sorted (already per-screen), enqueue-position preference stays
  a default-playlist behavior initially.

## 2. Current state by layer

| Layer | Queue | Playlists | Merged? |
|---|---|---|---|
| Backend (`account_queue`) | `queue_name='default'` | `queue_name=<name>` + `__queue__`/`__queue_rule__` markers | **YES** (since 2026-07-16) |
| Web store/UI | `DEFAULT_QUEUE` in the same keyed map, same QueueView chips | same | **YES** |
| PortCast | `queue` field = default queue | `com.trimplayer.playlists` §10 extension | **YES** (field-compatible both apps) |
| Android storage | `Queue` table (`id`=position, `feeditem`, `feed`) | `Playlists` + `PlaylistItems` (DB 3150000) | **NO** |
| Android sync worker | `diffQueue`/`applyQueue` path | `diffPlaylists`/`applyPlaylists` path | **NO** (two paths, one wire) |
| Android UI | QueueFragment (drag, lock, sort, swipe) | PlaylistsFragment grid + PlaylistFragment | **NO** |
| Playback advance | queue order | `activePlaylistId` override, falls back to queue | **HALF** |

## 3. Key design decisions

1. **Facade, not rewrite.** `DBReader.getQueue()`, `getQueueIDList()`,
   `DBWriter.addQueueItem/At/removeQueueItem/moveQueueItem/clearQueue` keep
   their signatures; implementations retarget to `PlaylistItems` rows of the
   default playlist. `ItemEnqueuePositionCalculator` (enqueue at
   front/back/after-current) and keep-sorted re-sorting move inside the facade
   untouched. Zero changes in playback/widget/Auto call sites.
2. **Default playlist identity = `is_default` column**, not a magic id and not
   a magic name (a user already *can* have a playlist literally named "Queue";
   ids are autoincrement so no fixed id exists). Exactly one row has
   `is_default=1`; code enforces. `NavigationNames`-style lookups go through
   `DBReader.getDefaultPlaylistId()` (cached in `PodDBAdapter`).
3. **Display vs sync name.** The default playlist's `title` column is ignored
   for display (localized "Queue") and for sync (`'default'` on the wire). A
   user playlist named "Queue" or "default" keeps colliding-free behavior:
   `normQueueName` already reserves `'default'`, and display marks the default
   with a pin (⭐/pinned position) rather than by name.
4. **Events: dual-post during transition.** Facade writes post *both*
   `QueueEvent` (11 existing consumers: widgets, Auto, fragments) and
   `PlaylistEvent(defaultId)`. `QueueEvent` removal is Phase 4, only after all
   consumers migrate.
5. **Keep the `Queue` table dormant for one release** after migration (stop
   reading/writing it, don't drop). If a beta regression appears, rollback =
   reinstall previous APK; its data is still there. Drop in the release after.
6. **Auto-add rules become legal on the Queue.** Web removes the
   `DEFAULT_QUEUE` early-return in `addAutoRule` + shows the Auto-add bar on
   the default chip; Android shows "Auto-add new episodes" on the Queue
   screen. Rule markers for the default queue sync as
   `__queue_rule__:default\n<rssUrl>` (parser already tolerates it; today both
   sides just refuse to create them). AntennaPod's per-feed "new episodes →
   add to queue" setting stays as-is (it writes through the facade); no
   auto-conversion — the two mechanisms coexist and converge because playlist
   adds dedup.

## 4. Build plan (phased)

### Phase 0 — Guardrails (0.5 day)
- Unit-test net under the facade surface before touching it:
  `QueueFacadeTest` (Robolectric, `storage:database`): add/addAt/remove/move/
  clear/getQueue ordering, enqueue-location front/back/after-current,
  keep-sorted re-sort, `getNextInQueue` equivalence.
- Snapshot on-device sanity script (adb-driven: enqueue, reorder, advance).

### Phase 1 — Android storage unification (core; ~2 days)
1. **Migration (DB 3160000, `DBUpgrader`):**
   ```sql
   ALTER TABLE Playlists ADD COLUMN is_default INTEGER DEFAULT 0;
   INSERT INTO Playlists (title, is_default) VALUES ('Queue', 1);
   INSERT INTO PlaylistItems (playlist_id, feeditem, feed, position)
     SELECT <newId>, feeditem, feed,
            (SELECT COUNT(*) FROM Queue q2 WHERE q2.id < q.id)   -- rank = position
     FROM Queue q ORDER BY q.id;
   -- Queue table left in place, dormant (Phase 4 drops it).
   ```
   Idempotent: guarded on "no is_default row exists". Fresh installs create the
   empty default playlist in `onCreate`.
2. **Facade** in `PodDBAdapter`/`DBWriter`/`DBReader` (queue functions only);
   `ItemEnqueuePositionCalculator` + keep-sorted logic relocate inside.
   Dual-post events (decision 4).
3. **Playback:** `getNextItem` — default context = default playlist id;
   delete the queue-fallback special case (behavior identical).
4. **Sync worker simplification:** delete `diffQueue`/`applyQueue`;
   `currentPlaylists()` includes the default playlist under the name
   `'default'`; the queue snapshot pref (`prefTrimSyncSnapQueue`) migrates into
   the playlists snapshot on first run (one-time: copy urls under key
   `'default'`, then clear the old pref) so the first post-upgrade sync
   diffs cleanly instead of re-asserting the whole queue.
5. **PortCast:** exporter emits the default playlist as the spec `queue` field
   (NOT in the extension); importer stages spec-`queue` into the default
   playlist. Both already behave this way semantically — only the read/write
   target changes to the facade.
6. Gates + on-device verification (enqueue from episode list, drag in queue,
   finish an episode → auto-advance, widget tap, Android Auto list, watch
   `/queue` POST snapshot unchanged).

### Phase 2 — UI unification (~1 day)
- PlaylistsFragment grid shows the **Queue as the first, pinned card** (its
  collage/count/duration come from the same queries; no delete/rename in its
  overflow; "Auto-add" appears once Phase 3 lands). Tapping it opens the
  existing QueueFragment (keeps lock/sort/swipe-actions muscle memory).
- Bottom nav: "Queue" tab stays (it's the highest-frequency surface) and
  "Playlists" stays in More. Optional later: collapse to one "Playlists" tab
  that opens with the Queue card focused — defer until usage data says so.
- "Add to playlist" bottom sheet gains a pinned "Queue" row at top (replaces
  the separate add-to-queue/remove-from-queue menu pair over time; both remain
  for now).

### Phase 3 — Feature generalization (~0.5–1 day)
- Auto-add rules on the Queue (decision 6): Android dialog on Queue screen,
  web unlock for the default chip, unplayed-backfill offer included.
- Per-playlist keep-sorted/lock: optional, only if wanted — add columns to
  `Playlists`, surface the existing queue toolbar toggles on playlist screens.

### Phase 4 — Cleanup (next release)
- Drop `Queue` table (DB 3170000). Remove `QueueEvent` after migrating its 11
  consumers to `PlaylistEvent(defaultId)`. Remove dual-posting.

## 5. Migrating existing users

**Server-side: nothing to do.** Every existing user's Queue already lives as
`queue_name='default'` rows in `account_queue`; web users see no change.

**Android devices** (the only migration, runs in `DBUpgrader` on first launch
after update):
1. DB migration above copies `Queue` → default playlist, preserving order.
2. Sync journal handoff (Phase 1.4) prevents a diff storm: the first sync after
   upgrade sees identical state and pushes nothing.
3. LWW safety: even if a device pushed a spurious re-assert, rows carry fresh
   `client_ts` upserting identical content — the server converges; other
   devices apply no-ops. Verified pattern (same mechanics as the playlist echo
   applies observed in logcat on 2026-07-17).
4. Users with an *existing* playlist named "Queue": untouched; it remains a
   normal named playlist (syncs as `queue_name='Queue'`), visually distinct
   from the pinned default card.
5. Rollback: previous APK + dormant `Queue` table = pre-migration state, minus
   queue edits made post-upgrade (acceptable for beta; there are no production
   users on multi-playlist builds yet).

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Playback auto-advance regression (hottest path) | Facade keeps `getQueue()` semantics byte-equal; Phase 0 tests pin ordering incl. enqueue-location; on-device advance test before merge |
| Widget/Android Auto stale lists | They consume `QueueEvent` — dual-posting keeps them fed until Phase 4 |
| Sync diff storm after upgrade | Journal handoff (queue snapshot → playlists snapshot under `'default'`) |
| Keep-sorted fights playlist ordering | Keep-sorted stays default-playlist-only until Phase 3 explicitly generalizes it |
| Migration crash mid-copy | Single transaction in `DBUpgrader`; `Queue` table untouched as source of truth for retry |
| Watch (`/queue` snapshot POST) breakage | Reads via `DBReader.getQueue()` facade — unchanged |
| Auto-download episode-cache accounting | Uses `getQueueIDList()` — facade covers |

## 7. Effort

| Phase | Estimate |
|---|---|
| 0 — guardrail tests | 0.5 day |
| 1 — storage + sync + playback | 2 days |
| 2 — UI | 1 day |
| 3 — rules on Queue + generalization | 0.5–1 day |
| 4 — cleanup (next release) | 0.5 day |
| **Total to shippable (P0–P2)** | **~3.5 days** |

Recommended cut: ship P0+P1+P2 together in one beta (storage flip is invisible
if it works, and the pinned Queue card is the user-visible payoff), P3 a week
later, P4 next release.
