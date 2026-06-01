# TrimPlayer — Claude Code project notes

TrimPlayer is an Android podcast player forked from AntennaPod, with built-in audio trimming. Ships to Google Play as `com.trimplayer` (debug suffix `.debug`). Pre-launch — no real users yet.

## Module layout

Multi-module Gradle project. `settings.gradle` is the source of truth.

- `app/` — application module. Package namespace is `de.danoeh.antennapod` (kept from upstream); applicationId is `com.trimplayer`. TrimPlayer-specific glue lives at `app/src/main/java/de/danoeh/antennapod/`: `TrimAnalytics`, `TrimEventsUploadWorker`, `TrimPrefetchSubscriber`, `TrimQueueSubscriber`, `PodcastApp`, `billing/TrimBillingManager`.
- `event/`, `model/`, `system/` — core domain.
- `net/` — `common`, `discovery`, `download/{service-interface,service}`, `ssl`, `sync/{gpoddernet,service-interface,service}`.
- `parser/` — `feed`, `media`, `transcript`.
- `playback/` — `base`, `cast`, `service`. **Playback engine lives in `playback/service/.../internal/LocalPSMP.java`** — central to skip-silence and player logic.
- `storage/` — `database`, `database-maintenance-service`, `importexport`, `preferences`.
- `ui/` — `app-start-intent`, `chapters`, `common`, `discovery`, `echo`, `episodes`, `glide`, `i18n`, `notifications`, `preferences`, `statistics`, `widget`, `transcript`.

Most "AntennaPod" packages should be left untouched unless the change is TrimPlayer-specific. Prefer adding new code under `de.danoeh.antennapod.<feature>` or the relevant `Trim*` classes.

## Build / tooling

- Gradle wrapper. AGP `8.11.0`, compileSdk 35, minSdk 23, targetSdk 35, Java 17 source/target, JVM toolchain 21 (`org.gradle.java.home` pinned in `gradle.properties` to Android Studio's JBR).
- Flavors: `free` and `play` (market dimension). `play` is what ships; `free` is vestigial from upstream. Build types: `debug` (suffix `.debug`, app name "TrimPlayer Debug") and `release` (minified, shrunk, signed via `trimplayer-upload.p12` at repo root).
- Version: single source of truth is `versionName` in `app/build.gradle`. `versionCode` is computed: `major*1_000_000 + minor*10_000 + patch*100 + (betaN or 95)`. e.g. `1.2.3-beta4 → 1020304`, `1.2.3 → 1020395`.
- Resource locales whitelisted in `common.gradle` (`resourceConfigurations`). Only English strings should be edited — other locales come from upstream AntennaPod or the TrimPlayer rebrand sweep (see memory).
- Firebase: Analytics + Crashlytics enabled (`google-services.json` checked in). EventBus uses an annotation-processor index (`ApEventBusIndex`).
- Billing: Google Play Billing 8.0.0 included on both flavors; managed by `TrimBillingManager`.
- Lint: strict (`warningsAsErrors true`, `abortOnError true`), with a curated disable list in `app/build.gradle`. SpotBugs runs on every build and fails on findings (see `parseSpotBugsXml` in `common.gradle`).
- Style check: `./gradlew checkstyle spotbugsPlayDebug spotbugsDebug :app:lintPlayDebug` (per CONTRIBUTING.md).
- Tests: `./gradlew :core:testPlayDebugUnitTest` for unit tests; integration tests via `sh .github/workflows/runTests.sh` against a connected device/AVD.

## Companion repos & artifacts

- `C:/git/TrimPlayer-Unified/` — release artifacts, screenshots, pricing plan (`PRICING_PLAN.md`), Play Console checklist, website, backend resources. Build scripts (`build-release.bat`, `build-release-aab.ps1`, `bump-version-name.ps1`) live here.
- Backend (TrimBrain) is deployed off-repo; this Android app talks to it via `TrimEventsUploadWorker` → `/events` and `/usage/report`. See memory: telemetry pipeline, queue prioritization, pricing plan.

## Conventions

- Base branch: `develop`. `master` is only updated at release.
- Only change English strings (`values/strings.xml`); other locales are inherited.
- Don't upgrade dependencies opportunistically (per CONTRIBUTING.md).
- Pre-launch: no need to invent backwards-compat scenarios in code or comments — there are no real users to migrate.
- Bug-fix discipline: reproduce, trace upstream to the originating logic, fix that, clean up corrupted data the bug produced, verify against the actual user-facing path. Don't ship "would handle this if it ran" — verify the guards in callers.

## Deployment (do NOT auto-run)

The user builds and deploys APKs / AABs manually. Do not run `./gradlew assembleRelease`, `./gradlew bundlePlayRelease`, `adb install`, or invoke `build-release*.{bat,ps1,sh}` on the user's behalf unless they explicitly ask. After making code changes, summarize the diff and stop.

## Known gotchas

- **Gradle transform-cache corruption** ("Could not read workspace metadata from metadata.bin"): nuke the whole `~/.gradle/caches/<ver>/transforms/` directory, not just the named entry. See memory.
- **Debug build icon override**: a debug-only mipmap can shadow the release icon. See `project_tromplayer_android` memory.
- **Skip-silence on initial play**: applied directly at `LocalPSMP` to bypass the speed-change debounce — see memory `project_trimplayer_skip_silence_apply`.
- **Home screen sections**: adding one requires touching ~4 places (see memory `project_trimplayer_home_sections`).
- **External share handlers** (Spotify/YouTube → RSS): conservative VIEW intent-filters to avoid clobbering other apps. See memory.
- **Free vs play flavor**: billing/Firebase code currently runs on both flavors as a plain `implementation`. If F-Droid distribution is ever pursued, split into `src/free/java/` with a no-op `TrimBillingManager` (noted in `app/build.gradle` comment).

## Pointers

- Memory index: `C:/Users/Jonathan/.claude/projects/C--git/memory/MEMORY.md` — start here. Mobile-specific entries are prefixed `project_trimplayer_*` and `feedback_*`.
- `WORKLOG.md` at `C:/git/WORKLOG.md` — append after each task.
- Per-user global instructions: `C:/Users/Jonathan/.claude/CLAUDE.md`.
