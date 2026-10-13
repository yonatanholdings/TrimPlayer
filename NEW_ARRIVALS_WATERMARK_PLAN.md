# New Arrivals — the Last-Seen Watermark

**Goal:** answer "what showed up since I last looked?" without reintroducing
per-episode triage state. Closes the gap left by the Inbox deprecation
(`QUEUE_INBOX_DEPRECATION_PLAN.md`), which removed the Inbox screen, badge and
`isNew` flag but shipped only a 2-item read-only Home rail in their place.

**Reported by users:** feedback thread #16 ("I very much miss having the Inbox,
showing only new episodes, that I could then either queue or remove") plus one
email. Both land on the risk the deprecation plan itself flagged and deferred:
*"users who triage the inbox — acceptable pre-launch; revisit only if beta
feedback demands."*

---

## 1. What is actually missing today

Measured against the current tree (post `c8f2941b1`):

| Inbox capability | Today |
|---|---|
| Screen listing only new arrivals | none — `InboxSection` shows `NUM_EPISODES = 2` with an **empty** `FeedItemFilter()`, so it is the 2 newest by pubdate *including played ones* |
| Count of pending arrivals | none — `InboxSection` sets `numNewItemsLabel` to `GONE`; the subscription badge now counts the whole unplayed backlog (`SHOW_UNPLAYED`), which answers "how much do I owe", not "what's new" |
| Dismiss without playing | none — `RemoveFromInboxSwipeAction` and the bulk "Remove all from inbox" are gone; the nearest gesture is mark-played |
| Per-item "untriaged" state | `isNew` is dead. Only `FeedItem.updateFromOther:159` still calls `setNew()` (an existing item that gains an enclosure), and such an item lands in a state no screen lists and no action clears |

## 2. The model

**One global watermark.** A single epoch-millis preference, `lastSeenPubDate`.

```
new arrival  ==  feed is subscribed
             AND FeedItems.pubDate > lastSeenPubDate
             AND FeedItems.read != PLAYED
             AND item is in no playlist        (see §3.2)
```

Nothing per-episode is stored. The boundary moves; episodes do not change state.

### Why this fits playlists-only

- No new per-item lifecycle, which is the thing D1–D3 deliberately tore out.
- No schema change in Phase 1 — `pubDate` is already indexed
  (`CREATE_INDEX_FEEDITEMS_PUBDATE`), so the predicate is index-backed.
- Syncs as a single reserved pref row, no backend or protocol change (§5).
- Degrades honestly: a fresh install starts the watermark at install time, so
  the list is empty rather than showing the entire back catalogue as "new".

### What it cannot do — state this in the UI copy

A watermark is one boundary, so it **cannot** express "I dealt with #1 and #3
but want #2 to stay pending." Advancing it marks everything older as seen.
Per-item escapes are the two gestures thread #16 actually asked for — queue it,
or dismiss it (§3.2, §3.3). Anything beyond that needs per-item state, i.e. the
Inbox again.

## 3. Design decisions

### 3.1 Watermark advances explicitly, never silently on scroll

- `markAllSeen()` sets `lastSeenPubDate = System.currentTimeMillis()`.
- Triggered by an explicit "Mark all as seen" action in the arrivals screen's
  toolbar, and on leaving that screen **only if** the user has seen the whole
  list (last item bound). Do *not* advance on mere screen open — the Inbox's
  value was surviving an interrupted triage session.
- Never advance it during a feed refresh. A refresh is what creates arrivals.

### 3.2 "Queue it" is a triage gesture

Excluding items that sit in any playlist is what makes the list a work queue
rather than a feed: adding an episode to Up Next or any named queue drops it
off the arrivals list, because it is now scheduled. Reuses the same shape as
the queued filter, minus the default-playlist restriction:

```sql
FeedItems._id NOT IN (SELECT feeditem FROM PlaylistItems WHERE feeditem IS NOT NULL)
```

The `IS NOT NULL` guard is required: `PlaylistItems.feeditem` has no NOT NULL
constraint and a single NULL makes `NOT IN` evaluate to NULL for every row.
(Same trap as `FeedItemFilterQuery`, commit `4e52e60c6`.)

### 3.3 "Remove it" is mark-played — and that is safe here

Verified against `DBWriter.markItemPlayed` / `PodDBAdapter.setFeedItemRead`:
the varargs path writes **only** `KEY_READ`, then calls
`removeFromQueueSynchronous`. For a typical arrival — not queued, not
downloaded — that means:

- no playback-history row (`KEY_LAST_PLAYED_TIME_HISTORY` untouched, so the
  `showInHistory` filter won't pick it up),
- the dequeue is a no-op,
- no auto-delete exposure, which is driven by download state.

So mark-played is a clean dismiss for arrivals specifically. It is *not* clean
for queued or downloaded episodes — which is fine, because §3.2 already
excludes anything in a playlist.

### 3.4 Phase 1 is a global count only

Per-feed badges that mean "new in this feed" need per-feed watermarks. Deferred
to Phase 2 (§6) because it costs a DB migration and N synced rows for a
secondary surface. Phase 1 leaves the subscription badge on `SHOW_UNPLAYED`.

## 4. Implementation

### 4.1 Storage — `UserPreferences`

```java
private static final String PREF_LAST_SEEN_PUBDATE = "prefLastSeenPubDate";

/** Epoch millis; arrivals are episodes published after this. */
public static long getLastSeenPubDate() { ... }   // default: first-run stamp
public static void setLastSeenPubDate(long millis) { ... }   // monotonic: max()
```

`setLastSeenPubDate` must clamp to `max(current, new)`. A watermark that can go
backwards resurrects already-seen arrivals, and §5's merge depends on it.

Seed on first run with `System.currentTimeMillis()` so an existing library does
not all read as new. **Not** in `PreferenceUpgrader`: `upgrade()` returns early
on `oldVersion == -1` ("New installation"), which is exactly the case needing a
seed. Seed lazily in the getter instead — on a missing key, write `now` and
return it — so the stamp lands on first access however the install arrived.

### 4.2 Query — `FeedItemFilterQuery`

Add a `FeedItemFilter.NEW_ARRIVALS` property. `storage:database` already
depends on `storage:preferences` (`storage/database/build.gradle:20`), so
`generateFrom` can read the watermark at query-build time.

`keyPubDate` is a new local alongside the existing ones, over
`PodDBAdapter.KEY_PUBDATE`:

```java
if (filter.showNewArrivals) {
    statements.add(keyPubDate + " > " + UserPreferences.getLastSeenPubDate() + " ");
    statements.add(keyRead + " != " + FeedItem.PLAYED + " ");
    statements.add(keyItemId + " NOT IN (" + inAnyPlaylist + ") ");
}
```

Making it a filter property — rather than a bespoke `DBReader` method — is what
makes the screen and the count nearly free: `getEpisodes(offset, limit, filter,
sortOrder)` and `getTotalEpisodeCount(filter)` both already take a filter.

### 4.3 Screen — a thin `EpisodesListFragment` subclass

`AllEpisodesFragment` shows the pattern: subclasses override `loadData()`,
`loadMoreData()`, `loadTotalItemCount()` and `getFilter()`. The deleted
`InboxFragment` was 159 lines of exactly this and is recoverable from
`c8f2941b1^` as a starting shape.

Needs: toolbar "Mark all as seen", swipe actions defaulting to
add-to-playlist / mark-played, and the standard multi-select bar.

### 4.4 Home rail — fix `InboxSection` rather than add a section

It already owns the slot and the `TAG` that users' section-order prefs key on:

- `NUM_EPISODES` 2 → 5,
- `new FeedItemFilter()` → `new FeedItemFilter(FeedItemFilter.NEW_ARRIVALS)`,
- un-hide `numNewItemsLabel`, fed by `getTotalEpisodeCount`,
- `handleMoreClick()` → the new arrivals screen instead of `AllEpisodesFragment`,
- retitle from `trim_recent_title` to a "New episodes" string.

### 4.5 Entry point

Give it the nav slot the Inbox vacated. The deprecation plan handed the freed
bottom-nav slot to Playlists (§7 choice 2, plus the "Third bottom-nav slot
freed" risk row), so arrivals should reach for the drawer and the Home rail's
"More" link rather than reopening that decision.

## 5. Sync — one reserved pref row, no backend change

`TrimClient.PrefChange` is `{ String rss_url; Float playback_rate; boolean
deleted; long client_ts; }`. Prefs carry only a float payload, so follow the
Up Next alias precedent (`TrimSyncWorker.UP_NEXT_TITLE_KEY`, D3) and encode the
value **in the key**:

```
__seen__:<epochMillis>
```

Exact, unlike `playback_rate` — a float's 24-bit mantissa gives ~128 s
granularity at current epoch values, which is survivable for a watermark but
needlessly lossy when the key costs nothing.

**Merge with `max()`, not last-writer-wins.** Every other pref is LWW by
push-then-apply ordering (`applyPrefs`' comment at `TrimSyncWorker:518`), but a
watermark is monotonic: taking the max means a device that was offline while
you triaged elsewhere never resurrects arrivals you already cleared. §4.1's
clamp gives this for free on both the apply and the push side.

Server and web need no changes. Web can read the same row later if it wants the
same rail.

## 6. Phases

### P1 — watermark + arrivals list (~1–1.5 days)
§4.1 storage and seeding, §4.2 filter, §4.3 screen, §4.4 Home rail, §5 sync.
Tests: filter SQL (arrival vs. played vs. playlisted vs. pre-watermark),
`max()` clamp, first-run seeding, `__seen__` round-trip through `applyPrefs`.

### P2 — per-feed arrivals *(optional, only if badges matter)*
`FeedPreferences.lastSeenPubDate` column, DB `3180000` (current `VERSION` is
`3170000`; follow the `oldVersion < 3170000` block's shape). Switches the
subscription badge from `SHOW_UNPLAYED` to a per-feed arrivals count and adds
a per-feed "mark all seen". Costs a migration and one synced row per feed.

### P3 — tidy-up
Retire `FeedItem.NEW` properly: drop the `setNew()` call at
`FeedItem.updateFromOther:159` (its items are currently unreachable), the
constant, `FeedCounter.SHOW_NEW`, and the `isNew` column — the `isNew` half of
the original plan's D4, now unblocked because nothing user-facing wants it.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Watermark advances too eagerly, arrivals vanish unread | §3.1: explicit action, or end-of-list only; never on open or on refresh |
| Fresh install shows the whole back catalogue | §4.1 first-run seeding to now |
| User expects per-item triage and the boundary surprises them | §2 limitation stated in UI copy; queue/dismiss are the per-item escapes |
| Clock skew across devices moves the boundary oddly | `max()` merge (§5) makes skew only ever *lose* arrivals in the forward direction, never resurrect them; acceptable |
| "New" diverges from the badge's "unplayed" meaning | Expected in P1 — two different questions. P2 reconciles the badge if it matters |

## 8. Open product choices

1. Does queueing an episode remove it from arrivals (§3.2)? **Recommended yes** —
   it is what makes the list finishable.
2. Is the entry point drawer-only, or does arrivals take a bottom-nav slot back
   from Playlists? **Recommended drawer + Home rail** (§4.5).
3. Is P2 worth a migration, or does the badge stay on unplayed? **Recommended
   defer** until someone asks about per-feed counts specifically.
