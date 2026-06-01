# Claude Code Prompt — Trim Player Statistics Redesign

> **Paste everything below the line into Claude Code as your first message after opening the project.**

---

I want you to implement a redesign of the **Statistics** section of this app. The full spec is in `design_handoff_statistics/README.md` and the visual references are in `design_handoff_statistics/design_reference/` — please read those first before writing any code.

## What you’re implementing

Five screens, all rendered with the same editorial type/color system:

1. **Overview** — masthead, hero stat (hours YTD), 26‑week heatmap, table of contents.
2. **Subscriptions** — donut chart + ranked per‑show list (cover, bars, hours, %/wk).
3. **Activity** — Started/Finished/In‑progress/Abandoned, hour‑of‑day bars, day‑of‑week small multiples, Time Saved breakdown.
4. **Years** — multi‑year streamgraph + per‑year list with YoY deltas.
5. **Per‑feed dialog** — modal “dossier” for a single show.

The references are HTML/JSX prototypes — **design specs, not code to copy**. Recreate them in this codebase’s existing UI environment using its established patterns. If the project has no UI environment yet, pick the most appropriate one for the platform and tell me your choice before scaffolding.

## Workflow

1. **Survey the codebase first.**
   - Read the project root, `package.json` / `build.gradle` / equivalent, and the existing screens to understand: the framework (Compose, React, SwiftUI, Flutter, etc.), navigation library, theming system, state management, data layer (where stats actually come from), test setup, and folder conventions.
   - Find the existing Statistics implementation (likely `Statistics*`, `Stats*`, or similar) and read it end‑to‑end. The redesign **replaces** these screens but reuses the same data sources.
   - Summarize what you found and your implementation plan in a short message before writing code. Wait for my OK.

2. **Set up the design tokens.**
   - Add the color palette and type roles from `README.md → Design tokens` to the project’s theme system (don’t hardcode hex values in components).
   - Add Instrument Serif / IBM Plex Sans / IBM Plex Mono using the project’s font‑loading mechanism. If the project bundles fonts, add the OTF/TTF files; if it uses a CDN, add the link.

3. **Build the data adapter.**
   - Find where statistics are computed today and write a small adapter that returns the `Stats` and `FeedDetail` shapes documented in `README.md → State requirements`. Don’t change how raw data is collected — just reshape it for the new screens. If a field doesn’t exist yet, list what’s missing and propose where in the data layer to compute it.

4. **Build shared atoms first**, in this order, each as its own component/file:
   - `EditorialMasthead` (volume/issue strip + kicker + serif title with italic accent word + optional drop‑cap lede + dateline).
   - `EditorialTabs` (mono uppercase, vermilion underline on active).
   - `SectionLabel` (§ N · UPPERCASE LABEL · faint hairline · right caption).
   - `SerifNumber` (serif numeral + italic accent unit, configurable size).
   - `EditorialCover` (colored square + serif initials).
   - `Hairline` (0.5px / 1px ink, with alpha variants).
   - `EditorialChip` (filter chip — outlined / filled).
   Match the styling exactly — sizes, tracking, colors. The downstream screens compose these and nothing else for chrome.

5. **Build chart primitives** as standalone components:
   - `Donut` (segmented arc with butt caps, 1.2px gaps, double hairline ring, center label slot).
   - `Heatmap` (7×N grid, 5‑step accent ramp).
   - `HourBars` (24 bars, peak in vermilion with marker tick).
   - `DayMultiples` (7 small bars + serif minute labels).
   - `Streamgraph` (smooth area + stroke + hollow circles + per‑point labels + peak/YTD ticks).
   - `Sparkline` (area + line + endpoint dots).
   Implement them with whatever the platform offers (SwiftUI Canvas / Compose Canvas / SVG / Recharts) — but the **visual output must match the reference SVGs**. Use real values from the data layer; never hardcode chart data in the component.

6. **Compose the screens.** One file per screen. Layout, copy, and chart placement should mirror `README.md → Screen-by-screen specs`. Wire navigation through the existing router. Wire the tab bar so all four tabs share state (selected year, selected filters).

7. **Per‑feed dialog.** Use the platform’s native modal/dialog primitive — but match the dossier styling (1px ink border + 8px×8px solid offset shadow, no blur). Tapping a row in Subscriptions opens it with that show’s `FeedDetail`.

8. **Verify against the reference.** Open `design_handoff_statistics/design_reference/Statistics Redesign.html` in a browser and compare side‑by‑side. Pay attention to:
   - Numeral sizes and unit treatment (italic, vermilion, ~36% of numeral size).
   - Hairline weights and alphas.
   - Spacing rhythm (24px gutters, the 14/18/20px vertical scale).
   - The "one vermilion element per visual area" rule — don’t spray accent color.
   - Chart annotations (PEAK ticks, % deltas, sparkline endpoint dots).

9. **Tests.** Add a snapshot/UI test per screen using mock `Stats` and `FeedDetail`. Tests should at minimum verify: hero numerals render with correct units; tab switching works; modal opens/closes; chart components don’t crash on empty data.

## Constraints

- Use the project’s **existing** design system additions where they overlap (Material Theme tokens, navigation, etc.). The editorial palette and type are net‑new tokens that sit alongside.
- **No new dependencies** unless absolutely required (e.g., a charting library if the project already has one). Prefer drawing with the platform’s canvas / SVG.
- Follow the project’s code style, lint rules, and module boundaries.
- Don’t port `design-canvas.jsx`, `tweaks-panel.jsx`, or `android-frame.jsx` — those are preview chrome only.

## Deliverable

A PR (or set of commits) that:

- Adds the editorial theme tokens and fonts.
- Replaces the existing Statistics screens with the five new ones.
- Includes the data adapter, shared atoms, chart primitives, and tests.
- Includes a short `STATS_REDESIGN.md` in the project root summarizing what changed and any open follow‑ups (e.g., missing data fields).

Start by surveying the codebase and posting your implementation plan. Don’t write production code until I confirm.
