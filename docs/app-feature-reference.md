# TrimPlayer — Full Feature & Button Reference

TrimPlayer is an Android podcast player forked from AntennaPod whose differentiator is
**automatic trimming**: a backend ("TrimBrain") detects intros, ads, outros, and silence
per-episode; the app overlays those segments on the seek bar and auto-skips them during
playback. Everything below the "Trim system" sections is the inherited podcast-player
chrome, kept and rebranded.

Legend: **[TP]** = TrimPlayer-specific addition; everything else is inherited from the
AntennaPod base.

---

## 1. Top-level navigation

The app is reachable two ways, toggled by **Settings → Interface → Bottom navigation**
[`prefBottomNavigation`]:

- **Navigation drawer** (default) — slides in from the left. Fixed entries come from
  `getVisibleDrawerItemOrder()`; users reorder/hide them via **long-press → Drawer
  preferences**, or **Settings → Interface → Manage drawer items** [`prefHiddenDrawerItems`].
  Standard entries: **Home, Queue, Inbox, Episodes, Subscriptions, Downloads, Playback
  history, Add podcast**, followed by the live **subscription list** (with unread counters,
  collapsible tag folders).
  - **Settings gear** (`nav_settings`) at the bottom → opens Settings.
  - **Long-press a feed** → context menu: Remove all from inbox, Edit tags, Rename,
    Remove/Archive feed, Share, Multi-select.
  - **Long-press a tag folder** → Rename folder, Delete folder.
- **Bottom navigation bar** — same destinations as compact tabs when enabled.

The **default landing screen** is set by **Settings → Interface → Set first screen**
[`prefDefaultPage`].

---

## 2. Home screen (`HomeFragment`)

A configurable dashboard of section cards.

**Toolbar buttons:**
- **Search** (`action_search`) → opens search.
- **Refresh** (`refresh_item`, overflow) → refresh all feeds (or asks, per metered-network setting).
- **Configure home** (`homesettings_items`, gear, overflow) → opens the **Home sections
  settings dialog** to show/hide and reorder sections.

**Sections** (each toggleable/orderable via `HomePreferences`): Monthly stats, Queue,
Inbox, Episode surprise, Subscriptions, Downloads. An **Echo** year-in-review section
appears seasonally and can be dismissed.

**Pull-to-refresh** swipe gesture refreshes feeds.

**Empty/welcome state** (no episodes yet):
- **Import button** (`welcomeImportButton`) → launches the **Onboarding** import screen.
- Directional arrow icon points at the drawer or bottom nav depending on layout.

**[TP] First-play nudge** — a one-time card ("press play, we skip the boring parts") shown
on the first available episode after an import, to drive activation. **Play** button
streams/plays that episode; **Dismiss** hides it.

---

## 3. Subscriptions (`SubscriptionFragment`)

Grid/list of subscribed podcasts.

**Toolbar:**
- **Search**, **Statistics** (`action_statistics`, chart icon → Statistics screen), **Refresh** (overflow).
- **Filter** (`subscriptions_filter`) → filter subscriptions by state (e.g. with new episodes).
- **Sort** (`subscriptions_sort`).
- **Feed counters** (`subscriptions_counter`) → choose what the badge counts.
- **Columns** (`subscription_num_columns`) → list, or 2/3/4/5 column grid.
- **Show titles** (`pref_show_subscription_title`, checkable) → captions under covers.
- **Show archive** (`show_archive`) → view archived feeds.

---

## 4. Queue (`QueueFragment`)

The ordered play-next list.

**Toolbar:**
- **Search**, **Refresh** (overflow).
- **Lock queue** (`queue_lock`, checkable) → prevents accidental drag-reordering.
- **Sort** (`queue_sort`).
- **Clear queue** (`clear_queue`, check icon).

**Per-item long-press / context** (`queue_context` + shared `feeditemlist_context`): Move to
top, Move to bottom, plus Mark played/unplayed, Add/Remove from queue, Delete, Favorite,
Reset position, Share, Multi-select.

---

## 5. Inbox (new episodes) (`InboxFragment`)

Newly-published, not-yet-triaged episodes.

**Toolbar:** Search, Refresh, **Sort** (`inbox_sort`), **Remove all from inbox**
(`remove_all_inbox_item`, check icon).

---

## 6. Episodes (`AllEpisodesFragment`)

All episodes across feeds.

**Toolbar:**
- **Search**, **Refresh**.
- **Filter** (`filter_items`).
- **Favorites** (`action_favorites`, star) → jump to favorite episodes.
- **Sort** (`episodes_sort`).

**Per-episode swipe actions** are configurable via **Settings → Interface → Swipe actions**
(`preferences_swipe.xml`).

**Batch action speed-dial** (FAB, `episodes_apply_action_speeddial`): Delete, Download, Mark
unplayed, Mark played, Remove/Add to queue, Remove from inbox, Move to top/bottom.

---

## 7. Downloads (`CompletedDownloadsFragment`)

**Toolbar:** Search, **Download log** (`action_download_logs`, history icon →
`DownloadLogFragment`, which has Refresh, Copy, Select-all, Cancel), **Delete played
downloads**, Refresh, **Sort**.

---

## 8. Playback history (`PlaybackHistoryFragment`)

**Toolbar:** **Sort** (`history_sort`), **Clear history** (`clear_history_item`, trash icon).

---

## 9. Add podcast / Discovery (`AddFeedFragment`)

Subscription on-ramps:
- **Combined search box** + **Search** button → searches all providers.
- **Search iTunes / fyyd / PodcastIndex** buttons → provider-specific search.
- **Add by URL** (`addViaUrlButton`) → paste an RSS URL (auto-fills from clipboard if it
  looks like a link).
- **OPML import** (`opmlImportButton`) → file picker → OPML selection screen.
- **Add local folder** (`addLocalFolderButton`) → treat a device folder as a "podcast."

**[TP] "Great first listens" rail** (`firstListensSection`) — only shown when arriving from
onboarding "Start fresh." Tiles of curated intro/ad-heavy shows; tapping one jumps
**straight to a pre-trimmed demo episode** (backend-curated feed+GUID) so a new user feels
the trim immediately, falling back to a directory search if the demo isn't ready.

---

## 10. Podcast page / feed item list (`FeedItemlistFragment`)

Episode list for one feed.

**Feed-info toolbar** (`feedinfo.xml`): **Visit website**, **Share**, **Reconnect local
folder** (local feeds only), **Edit feed URL**.

**Per-episode context** (`feeditemlist_context`): Mark for inbox/played/unplayed, Add/Remove
from queue, Delete, Favorite, Reset position, Share, Multi-select.

**Multi-select mode** (`multi_select_options`): **Select all/toggle**, then the batch
speed-dial actions above.

---

## 11. Episode detail (`ItemFragment` / options menu `feeditem_options`)

Per-episode actions: **Skip episode**, **Remove from inbox**, **Mark played/unplayed**,
**Add/Remove from queue**, **Favorite/unfavorite**, **Reset position**, **Visit website**,
**Share**, **Open podcast**.

---

## 12. The player

### 12a. Mini-player / footer (`ExternalPlayerFragment`)
Persistent bar above the nav:
- **Cover + title + feed name** → tap (`fragmentLayout`) expands the full player (or opens
  the video activity for video).
- **Play/pause** (`butPlay`).
- **Thin progress bar** (`episodeProgress`).
- **[TP] Segment overlay** (`SegmentOverlayView`) — amber trim markers layered over the
  footer progress bar, kept in sync with the full player. (Footer uses a Material progress
  indicator, not the ChapterSeekBar, hence a separate overlay view.)

### 12b. Full audio player (`AudioPlayerFragment`)
Swipeable two-page pager: **Cover page** and **Description page**.

**Transport controls:**
- **Rewind** (`butRev`) → jump back `getRewindSecs()`; **long-press** sets the rewind interval.
- **Play/pause** (`butPlay`).
- **Fast-forward** (`butFF`) → jump forward `getFastForwardSecs()`; **long-press** sets the interval.
- **Skip** (`butSkip`) → next episode (sends `MEDIA_NEXT`).
- **Playback speed** (`butPlaybackSpeed`) → opens the **Variable speed / audio controls** dialog.
- **Position/length labels** — tapping length toggles total ↔ remaining time.
- **Seek bar** (`ChapterSeekBar`) — chapter dividers; scrubbing shows a magnified
  time/chapter card; snapping to chapter starts.

**[TP] Trim seek-bar behavior:**
- **Amber segment markers** drawn over the seek bar from `TrimSegmentCache` (intro/ad/outro).
  Robust duration handling so markers survive the warm-cache load path and late-arriving
  durations; clamped to [0,1] for segments that overrun the real episode end.
- **Long-press a segment** on the seek bar → opens the **Edit segment** sheet for the
  touched segment.

**Player toolbar / overflow (`mediaplayer.xml`):**
- **Favorite / Unfavorite** (`add/remove_from_favorites_item`, star).
- **Sleep timer** (`set_sleeptimer_item` / `disable_sleeptimer_item`) → opens the
  sleep-timer dialog.
- **Open podcast** (`open_feed_item`), **Visit website**, **Switch to audio-only** (video),
  **Show chapters**.
- **Transcript** (`transcript_item`) → transcript dialog.
- **[TP] Trim segments** (`trim_segments_item`, scissors icon) → opens the **Segment list**
  sheet. Always shown for a podcast episode (works even before analysis, since the sheet also
  offers "mark a skip we missed").
- **[TP] Volume boost** (`volume_boost_item`) → per-podcast boost picker (Off / Light /
  Medium / Heavy), shown only when the device's `LoudnessEnhancer` is supported. Complements
  the volume-up-at-max key gesture (which only works in the foreground).
- **Social interact** (`open_social_interact_url`, chat icon), **Share**, **Cast** button.

### 12c. Cover page (`CoverFragment`)
- **Cover image** → tap to play/pause.
- **Chapter button** (`chapterButton`) → chapter list.
- **Prev/Next chapter** (`butPrevChapter` / `butNextChapter`).
- **Open description** (`openDescription`) → swipes to the description page.

---

## 13. [TP] Trim system — dialogs

### Segment list sheet (`SegmentListDialog`) — "Trimmed in this episode"
- **Count + subtitle** of detected segments.
- **Each segment row** (icon colored by type, label Intro/Ad/Outro, time range + duration) →
  tap opens **Edit segment**.
- **"Mark a skip we missed"** (`markMissingButton`) → seeds a new ~30s "ad" segment at the
  current playback position and opens the editor.
- Refreshes live on `TrimSegmentsEditedEvent`.

### Edit segment sheet (`EditSegmentDialog`)
- **Boundary editor** (`boundaryEditor`) — drag start/end handles over a zoomed waveform
  window that auto-pans; scrub to seek.
- **Preview** (`previewButton`) — plays just the segment, auto-stops at its end; suppresses
  auto-skip while auditioning (`trimSegmentEditPreviewActive`). Icon driven by real player status.
- **Jump to start** (`jumpToStartButton`).
- **Nudge ±½s** rows for Start and End, with live delta readouts (green for later,
  error-color for earlier).
- **Label chips** — Intro / Ad / Outro.
- **Length chip** — live segment duration.
- **Save** (`saveButton`, enabled only when changed) → writes to `TrimSegmentCache`,
  broadcasts the edit so the running episode's auto-skip picks it up, and crowd-reports the
  correction (`missing` / `adjust` / `confirm`) to the backend.
- **"Not a skip"** (`notASkipButton`) → removes the segment locally and reports a `remove`.
- **Close** (`closeButton`).

---

## 14. Variable speed / audio controls dialog (`VariableSpeedDialog`)

- **Speed seek bar** + current-speed label, preset speed chips, and **Add current speed** to presets.
- **Skip silence** checkbox [`skipSilenceCheckbox`] — engine-level silence skipping (applied
  directly in `LocalPSMP`, bypassing the speed-change debounce on initial play).
- **[TP] Per-episode skip toggles** — **Skip intros / Skip ads / Skip outros** checkboxes,
  mirroring the global trim preferences but scoped to the current playback.

---

## 15. Statistics (`StatisticsFragment`)

**Toolbar:** **Reset data** (`statistics_reset`), **Filter** (`statistics_filter`,
include-marked-played etc.), **Echo** (seasonal, hidden by default).

---

## 16. [TP] Community Impact (`CommunityImpactFragment`)

Reached from **Settings → Community Impact**. Shows the anonymous, pooled trim-impact of all
TrimPlayer listeners plus a tenure-fair "you vs community" comparison.
- **Hero numeral** — total time the community reclaimed (animated count-up) + a relatable
  phrase ("9 years").
- **Contributors count** and an **ads callout**.
- **Collective breakdown bars** — Ads / Silence / Speed / Intro / Outro.
- **Window chips** — 7d / 30d / 90d / 1y / All — re-render the comparison client-side (no refetch).
- **Comparison rows** — paired you/community bars per aspect with a verdict badge (multiple-of
  / on-par / below); plus a typical-playback-speed row.
- **Your contribution** (all-time local).
- **Share** button (`share_button`) → renders a branded PNG impact card and opens the system
  share sheet (with a trimplayer.com link).
- **Footer** "updated as-of" timestamp; **empty state** when neither community nor local data exists.

---

## 17. Settings (`MainPreferencesFragment` / `preferences.xml`)

**Search bar** over all preferences. Rows:
- **User interface** → theming (theme, black theme, tinted colors), episode info (episode
  cover, show remaining time, time-respects-speed), external elements (expand/persist
  notification, notification buttons), behavior (first screen, bottom navigation, manage
  drawer items, back-opens-drawer), episode lists (swipe actions, stream-over-download,
  downloads-button action).
- **Playback** → interruptions (pause on disconnect/focus-loss/mute, unpause on reconnect),
  playback control (FF/rewind/speed deltas), reassign hardware buttons, queue (enqueue
  location/downloaded/follow, smart mark-as-played, skip-keeps-episode). **[TP] Trim & skip**
  category — **Skip intros / Skip ads / Skip outros** master switches. **Debug** category —
  **[TP] Stub skip segments** (load from `assets/stub_segments.json`) and **View received
  segments**.
- **Downloads** (incl. **Automation** → auto-download, auto-deletion).
- **Import/Export** (database import only, OPML, PortCast — see onboarding paths; database,
  HTML and favorites exports were removed: account sync + PortCast export cover it, and OPML
  remains for moving subscriptions to another app).
- **Statistics** → Statistics screen.
- **[TP] TrimPlayer Pro** (`prefTrimPro`) → Pro screen. **Server-driven visibility**: hidden
  unless the backend's `pro_ui_visible` flag is set; summary reflects active entitlement.
- **Notifications**.
- **Project** category: **Send feedback / bug report** (`prefSendBugReport` — summary badges
  unread dev replies), **[TP] Community Impact**, **About**.

---

## 18. [TP] TrimPlayer Pro (`TrimProFragment`)

- **Active card** + subtitle (entitlement source) when Pro; **Supporter badge + thanks** when
  source is `play_supporter`.
- **Quota line** (used/limit) when not Pro.
- **Purchase buttons** — **Monthly**, **Yearly** (Supporter $50/yr is implemented end-to-end
  but hidden behind `SUPPORTER_TIER_ENABLED=false`). Buttons launch Play Billing via
  `TrimBillingManager`; prices swap to Play-localized strings when `ProductDetails` arrive;
  buttons hide on billing-unavailable.
- **Supporter Digest card** (Supporter tier only) — monthly community pulse (minutes saved,
  % change, avg/your stats, skip breakdown, algorithm accuracy, catalog growth, top podcasts)
  plus a dev-funding transparency block (income/cost/net, shipped, next-up, note).

**Related paywall dialogs (`TrimProDialogs`):** **Quota upsell** (soft paywall when free
quota exhausted; "Get Pro" / "Maybe later", once per session) and **Beta grandfather welcome**
(one-time celebratory dialog when the backend confirms grandfathered access).

---

## 19. [TP] Onboarding (`OnboardingActivity`)

First-run "Bring your podcasts with you" screen (re-openable from Home/Settings; skippable):
- **From Spotify** (`onboarding_spotify_button`) → Spotify migration (WebView).
- **Podcast Addict / AntennaPod / PortCast / OPML** buttons → file picker → shared
  `ImportFlowController` (auto-detects type; a `.db` onto an empty library *merges* rather
  than full-restores).
- **Start fresh** (`onboarding_start_fresh_button`) → drops into the discovery grid with the
  "Great first listens" rail.
- **Success screen** with a looping **"snip" animation** (intro/ad/outro bars collapsing)
  landing the auto-trim promise, and a **CTA** to finish to Home (where a background-import
  banner shows live progress). Arms the Home **first-play nudge**.

---

## Notes on accuracy

- Most screen chrome (Home, Queue, Inbox, Episodes, Downloads, Subscriptions, player
  transport, settings categories) is **inherited from AntennaPod** and rebranded; it is kept
  here for completeness.
- The genuinely **TrimPlayer-specific** surfaces are: the **trim segment overlay + edit/list
  sheets**, **per-episode + global skip toggles**, **volume boost**, **Community Impact**,
  **TrimPlayer Pro + paywall**, the **onboarding/import flow with first-listens + first-play
  nudge**, and the **debug stub-segments** tooling.
