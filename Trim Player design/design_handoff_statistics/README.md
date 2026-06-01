# Handoff: Trim Player — Statistics Redesign

> **For your Claude Code session.** Open this folder, then paste the contents of [`CLAUDE_CODE_PROMPT.md`](./CLAUDE_CODE_PROMPT.md) into Claude Code as your initial message. The prompt instructs Claude Code to read this README, the design references, and implement the screens in your real codebase.

## Overview

A redesign of the **Statistics** section of an Android podcast app (Trim Player — open-source listener client). The redesign reframes statistics as the front page of a periodical: a magazine‑editorial system (cream paper, Instrument Serif numerals, IBM Plex labels, vermilion accent) that turns dry numbers into a readable “issue.” Five screens are covered:

| # | Screen | Role |
|---|---|---|
| 1 | **Overview** | Front page — masthead, hero stat (hours YTD), 26‑week heatmap, table of contents linking to chapters |
| 2 | **Subscriptions** | “Where the hours went” — donut chart + ranked per‑show list with cover art, bars, hours, %/wk *(this screen retains the Calm direction’s data‑viz structure, repainted in the editorial palette)* |
| 3 | **Activity** | Started / Finished / In‑progress / Abandoned counters, hour‑of‑day bars, day‑of‑week small multiples, and a **Time Saved** breakdown (speed / silence / intros) |
| 4 | **Years** | Multi‑year streamgraph + per‑year list with YoY deltas |
| 5 | **Per‑feed dialog** | Modal “dossier” for one podcast — cover, three big numbers, 12‑week sparkline, action strip |

## About the design files

The files in **`design_reference/`** are HTML/JSX **design references**, not production code. They were rendered with React + Babel inline so the layout, tokens, and interactions can be inspected in a browser. Your task is to **recreate them in the target codebase’s native environment** (Kotlin/Compose for an Android client, React/SwiftUI/etc. as appropriate) using its existing design system, navigation patterns, and data layer. Do not ship the JSX as‑is. If no UI environment is in place yet, pick the most appropriate one for the project before starting.

To preview the references, open `design_reference/Statistics Redesign.html` in a browser.

## Fidelity

**High‑fidelity.** All colors, typography, spacing, and chart treatments are final. Recreate pixel‑accurately. The only liberties: swap mock data with real data from the app’s store, wire navigation through the existing router, and substitute icon glyphs from your icon library where I used inline SVG.

---

## Design tokens

```
/* Colors */
--bg:         #f4ede0;   /* warm cream paper */
--paper:      #fbf8f1;   /* lifted card paper */
--paper-lift: #ffffff;   /* highest layer */
--ink:        #15110d;   /* primary text */
--ink-soft:   #3a322a;   /* secondary text */
--ink-mute:   #7a6e60;   /* tertiary / labels */
--rule:       #15110d;   /* hairlines (apply 10–25% alpha for soft rules) */
--faint:      rgba(21,17,13,0.10);
--very-faint: rgba(21,17,13,0.05);
--accent:     #b8442e;   /* vermilion — used for one element per view */
--accent-soft:#e8b9a4;
--accent-tint:#f3d6c4;
--gold:       #a47436;
--gold-soft:  #e1c79a;

/* Type families */
--serif: 'Instrument Serif', 'Cormorant Garamond', Georgia, serif;
--sans:  'IBM Plex Sans', system-ui, sans-serif;
--mono:  'IBM Plex Mono', ui-monospace, monospace;
```

### Typography roles

| Role | Family | Size | Weight | Tracking | Notes |
|---|---|---|---|---|---|
| Display numerals | serif | 64–88 | 400 | -1.2 | Hero stats. Unit (`hrs`, `h`, `m`) in italic, accent color, ~36% size. |
| Title H1 | serif | 52 | 400 | -1.2 | Two-line, last word italic + accent (`hours.`, `silences.`). |
| Title H2 | serif | 22–30 | 400 | -0.3 to -0.6 | Section headers, dossier titles. |
| Body | sans | 13 | 400 | normal | Lede paragraphs, `text-wrap: pretty`. |
| Lede drop cap | serif | 56–64 | 400 | — | Float left on first paragraph; accent color. |
| Mono label | mono | 9–10 | 400/500/600 | 1.4–2.4 px, UPPERCASE | All section labels, axis ticks, dateline, tab labels. |
| Italic caption | serif italic | 11–16 | 400 | — | Sub-stat phrasing (“and twenty‑four minutes.”). |

### Spacing & layout

- **Page gutters:** 24px left/right; vertical rhythm of 14px / 18px / 20px between blocks.
- **Hairlines:** 0.5px `--ink` at 10–16% alpha for dividers; 1px solid `--ink` for thick rules.
- **Section header pattern:** `§ 01` (mono) · UPPERCASE LABEL · faint horizontal line · optional right-aligned mono caption.
- **Chip:** 4×10px padding, 100px radius, 1px border, mono-uppercase label. Active = solid ink, cream text.

### Accent rules

- **One vermilion element per visual area.** Hero unit, italic phrase, peak bar, tab underline. Don’t spray it.
- Gold (`--gold`) appears only as a secondary series in the Time Saved breakdown.
- Backgrounds stay cream (`--bg`); cards lift to `--paper` only for the dossier modal.

---

## Screen-by-screen specs

### 1 · Overview

```
[ MASTHEAD          | TRIM PLAYER STATISTICS · VOL. 06 · ISSUE 02 ]
[ KICKER (mono)     | THE YEAR SO FAR · 2026                      ]
[ TITLE H1 (serif)  | A reader’s log.   ← "log." italic + accent  ]
[ LEDE w/ DROP CAP  | "From January through May, you spent..."    ]

§ Hero stat block ────────────────────────────────────────────
[ "LISTENED, YEAR-TO-DATE" mono label ]      [ +34% YoY mono   ]
[ 187 hrs   (serif 88px, italic accent unit) ] [ sparkline 84×38 ]
[ "and twenty-four minutes." italic serif ]

= = = = = = = = thick rule = = = = = = = = =

§ Three-column hangs (Trimmed | Finished | Streak)
   each cell: serif numeral 36, mono UPPERCASE label below

§ 01  WHEN YOU LISTENED ─────────────── LAST 26 WEEKS
[ 7×26 calendar heatmap, 5-step accent ramp                  ]
[ legend "LESS [□][□][▤][▦] MORE"   "Tuesdays loudest." italic ]

§ 02  IN THIS ISSUE ─────────────────── PAGE 02 — 18
   01  Subscriptions      Where the hours went, ranked.   38h  p.02
   02  Activity           Started, finished, abandoned.   70%  p.08
   03  Six years          A reading list, by year.       729h  p.12
   04  Time saved         Speed, silence, intros.         31h  p.18
```

**Behavior:** Each TOC row taps into the corresponding screen. The `+34% YoY` chip is purely informational — no tap. Sparkline is decorative.

### 2 · Subscriptions

```
MASTHEAD ("Where the hours went.")
[ DATELINE: Jan 1 – May 10 · 187 hours across 38 shows ]
[ TAB BAR: Shows | Activity | Years | Saved   (active=Shows, vermilion underline) ]

[ DONUT 200×200    ][ Top 3 ranked column                ]
  center: "187 h"    01 ● Field Notes Weekly  38.4h 21%
  9 shows · 24m      02 ● The Decoder Hour    27.1h 15%
                     03 ● Margin Notes        22.6h 12%

[ FILTER CHIPS: All shows (active) | This year | Comedy | News | + filter ]

§ 01  ALL SUBSCRIPTIONS · 9 ─────────── SORTED BY HOURS

  01 [□] Field Notes Weekly         38.4h
         ▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰░░  21%   44m / wk
  02 [□] The Decoder Hour           27.1h
         ▰▰▰▰▰▰▰▰▰▰▰▰░░░░░░  15%   31m / wk
  ... etc, 9 rows total

※ "Other" gathers 23 shows that account for less than 4% of listening each.
```

**Donut spec:** stroke 28px, 1.2px gap between segments, butt caps, double hairline ring (inner + outer at `--faint`). Center has mono kicker, serif 42 stat, mono caption — implemented as `<text>` elements in the SVG.

**Per-show row:** 18px-wide ordinal · 40px cover (initials, serif 50%, ~20% inset shadow) · 17px serif title · 3px progress bar (`--faint` track, show color fill) · right-aligned 20px serif hours + minutes/wk caption.

### 3 · Activity

```
MASTHEAD ("Habits & silences." — "silences." italic + accent)
[ TAB BAR active=Activity ]

[ 2×2 grid of "hangs" (top-rule + serif numeral + mono label + italic hint) ]
   Started 412     | Finished 287
   In progress 125 | Abandoned 38

§ 01 WHEN YOU LISTEN ─── LOCAL TIME, WEEKDAY AVG
   Lede: "You start your day on commute and peak at 8 a.m.; ..."
   24 vertical bars, peak (8h) painted vermilion + "PEAK · 38m" tick
   axis: 00 06 12 18 23

§ 02 DAY OF THE WEEK
   7 small-multiple bars (S M T W T F S), Tuesday peak vermilion
   each shows minute count below in serif

= = = = = = = thick rule = = = = = = =

[ "TIME SAVED" label ──────── 17% OF AUDIO ]
[ 31 hrs 12 min  (serif 56)  ]
   Faster playback   Avg 1.6× speed     22h 24m  72% bar (vermilion)
   Skip silence     7,432 gaps           6h 06m  19% bar (gold)
   Skip intros     142 episodes          2h 42m   9% bar (ink)
```

### 4 · Years

```
MASTHEAD ("Six years." — "years." italic + accent)
[ TAB BAR active=Years ]

[ ALL TIME, SINCE 2021 mono label ]
[ 729 hrs (serif 84) ]
[ "That’s 30 days, 9 hours of audio." italic   "+34% on 2024" sans accent ]

[ STREAMGRAPH ─ smooth Catmull-Rom-ish, 6 points ]
   - tinted gradient fill (accent 34% → 2%)
   - 1.6px accent stroke
   - hollow circles at each year (peak = larger)
   - serif numeral above each point (74, 121, 158, 142, 187, 47)
   - mono "'21 '22 '23 '24 '25 '26" below
   - "PEAK" mono tick over highest year, "YTD" over current

§ 01 BY YEAR ─────────── YEAR OVER YEAR
   2026  ▰▰░░░░░░░░  47h   ▼ 75%   23 hrs/mo · avg
   2025  ▰▰▰▰▰▰▰▰▰▰  187h  ▲ 32%   16 hrs/mo · avg
   2024  ▰▰▰▰▰▰▰░░░  142h  ▼ 10%   12 hrs/mo · avg
   ...
```

Delta arrows: green `#3a7a3a` for positive, vermilion `--accent` for negative. First year shows `— FIRST YEAR`.

### 5 · Per-feed dialog

```
[ Backdrop: blurred Subscriptions page + 42% ink overlay ]

  ┌──────────────────────────────────────────┐
  │  ● DOSSIER · NO. 02         FILED MAY 10 ✕│
  ├──────────────────────────────────────────┤
  │ [▦72]  SUBSCRIBED SINCE MAY 2024          │
  │        The Decoder Hour                    │
  │        by Eli Park & Sam Reyes (italic)    │
  ├──────────────────────────────────────────┤
  │   27.1 h listened │ 4.8 h saved │ 86 of 142│
  │   #02 of 38       │ 15% trim    │ 60% caught│
  ├──────────────────────────────────────────┤
  │  LAST 12 WEEKS · HRS              ↗ trending │
  │  [sparkline area + line + dots]              │
  │  AVG · 52 MIN   RSS · TWICE WEEKLY  NEXT · TUE │
  ├──────────────────────────────────────────┤
  │   Open feed  →   │  ▸ Listen now (inverted) │
  └──────────────────────────────────────────┘
```

Dossier card has a 1px ink border + an 8px×8px solid black drop shadow (offset, no blur) — gives it a printed-and-pinned feel. Bottom action strip: left button transparent, right button inverted (ink fill, cream text).

---

## Interaction & navigation

- **Tabs (Subscriptions / Activity / Years / Saved):** swap content; vermilion underline animates 180ms ease.
- **Subscription row tap:** opens the per-feed dialog with that show’s data.
- **Filter chips:** toggle filters; active = ink fill, cream label. Multi‑select within a category, single‑select across categories.
- **TOC rows on Overview:** navigate to the corresponding tab.
- **Year list rows:** tap to filter every other tab to that year.
- **Heatmap cell tap (optional):** show that day’s minutes in a tooltip.
- **Modal dismiss:** tap backdrop or the ✕ in the upper-right strip.

All transitions are 180–240ms, ease-out. Avoid bounce/spring. The aesthetic is print-quiet, not playful.

---

## State requirements

The screens consume the same statistics object the existing app already produces. From the reference (`shared.jsx`):

```ts
type Stats = {
  totalHours: number;
  totalMinutes: number;
  savedHours: number;
  savedMinutes: number;
  savedBreakdown: { speed: number; silence: number; intros: number }; // hours
  episodesPlayed: number;
  episodesCompleted: number;
  episodesInProgress: number;
  streakDays: number;
  bestDay: string;
  topHourLocal: number;
  shows: Array<{
    id: string; title: string; host: string;
    hrs: number; pct: number;          // pct rounded 0–100, sums ≈100
    color: string;                     // category/brand swatch
  }>;
  yearly: Array<{ year: number; hrs: number }>;
  byHour: number[];                    // length 24, minutes per hour-of-day
  byDay: number[];                     // length 7, Sun..Sat, minutes per weekday
  weekly: number[];                    // last 12 weeks, hours
  heatmap: number[][];                 // 26 weeks × 7 days, intensity 0..4
};

type FeedDetail = {
  title: string; host: string; color: string;
  subscribed: string;                  // human "May 2024"
  episodesTotal: number; episodesPlayed: number;
  hrsListened: number; hrsSaved: number;
  avgEpisodeMin: number;
  weekly: number[];                    // last 12 weeks, hours
};
```

Filters are local UI state: `{ year: number | 'all'; category: string | null; markedPlayed: boolean }`.

The modal has no global state — pass `feedDetail` as a prop and a `onDismiss` callback.

---

## Assets

- **Fonts:** Instrument Serif, IBM Plex Sans, IBM Plex Mono (all Google Fonts). The reference HTML loads them from Google Fonts. Use whatever font-loading mechanism your app already has; in Compose use `FontFamily.Default` overrides or bundled `.otf` files.
- **Icons:** the reference uses inline SVG glyphs (`back`, `share`, `play`, `clock`, `close`). Substitute your icon library (Material Symbols Outlined / Lucide).
- **Cover art:** `<ECover>` is a colored square with serif initials. Replace with the user’s real cover art when available; fall back to the initials placeholder using the show’s dominant color.

---

## Files in this bundle

```
design_handoff_statistics/
├── README.md                       ← you are here
├── CLAUDE_CODE_PROMPT.md           ← paste this into Claude Code
└── design_reference/
    ├── Statistics Redesign.html    ← open in a browser to preview
    ├── shared.jsx                  ← mock data (Stats + FeedDetail shapes)
    ├── final.jsx                   ← all five screen components
    ├── app.jsx                     ← canvas wiring
    ├── design-canvas.jsx           ← preview-only chrome (ignore in port)
    ├── tweaks-panel.jsx            ← preview-only chrome (ignore in port)
    └── android-frame.jsx           ← preview-only chrome (ignore in port)
```

The three “preview-only chrome” files exist to render the mocks side‑by‑side in a browser. **Do not port them.** Only `final.jsx` (screen components) and `shared.jsx` (data shapes) are relevant to implementation.
