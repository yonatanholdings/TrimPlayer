# `:garmin` — TRIM Player ↔ Garmin watch companion

The phone half of the Garmin integration (watch app lives in the separate
`trimplayer-garmin` Connect IQ repo). Full design: that repo's
`docs/android-companion-spec.md`.

The watch is a Connect IQ **Audio Content Provider** that plays already-trimmed,
already-speed-adjusted audio at 1× and exchanges listen state over BLE as
[PortCast](https://portcast.org) documents. This module:

1. maps the watch's **rendered-time** playback positions back to original episode
   time, and
2. applies received PortCast progress to the library (reusing `PortcastImporter`).

## What's implemented and verified

| Class | Role | Status |
|---|---|---|
| `GarminRenderManifest` | per-episode record of how it was rendered (kept ranges + speed) | pure Java, **unit-tested** |
| `GarminPositionMapper` | rendered-time → original-time inversion (the subtle math) | pure Java, **unit-tested** (`GarminPositionMapperTest`, 8 cases incl. round-trip) |
| `GarminRenderManifestStore` | persist manifests (guid → manifest) across render→sync | SharedPreferences |
| `GarminProgressRemapper` | rewrite watch-doc positions (rendered→original) on the SDK's Map/List | pure Java, **unit-tested** |
| `GarminPortcastBridge` | remap → serialize → `PortcastImporter` | reuses existing import path |
| `GarminAudioRenderPlan` | build the ffmpeg trim+speed graph + manifest from segments | pure Java, **unit-tested** |
| `GarminFfmpegExecutor` | abstraction over the ffmpeg call (lib-agnostic) | interface |
| `GarminProcessFfmpegExecutor` | `ProcessBuilder` impl (desktop/test/bundled-binary) | pure Java, **E2E-tested vs real ffmpeg** |
| `GarminRenderer` | orchestrate render + persist manifest | thin wrapper |

`GarminPositionMapper` maps both directions (`renderedToOriginal` for the receive
path; `originalToRendered` for round-trip verification/analytics).

Both the mapper and the render-plan tests were validated against the **real ffmpeg
render** used end-to-end (2:36 source, drop [0,20]+[60,75], 1.5× → 1:20.69 / 1,291,619
bytes). The render plan's filter graph is byte-for-byte identical to the backend
(`app/garmin.py`) and the watch repo's stub, so phone- and server-rendered files
behave the same — verified by running the planner's generated graph through ffmpeg.

**28 tests** total, including `GarminPipelineIntegrationTest` which composes the
whole chain (forward-map a position → render → simulate the watch reporting it →
remap → recover the original) and checks a real ffmpeg render's duration matches the
manifest's prediction.

## BLE transport + receive path (wired 2026-07-03)

The Connect IQ Mobile SDK is now on **Maven Central**
(`com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar`, enabled in
`garmin/build.gradle`) — no manual AAR drop needed.

- **`GarminCompanionManager`** (this module): binds the SDK (relayed through the
  Garmin Connect Mobile app), registers for app events on every known device
  (re-registering on device connect) for the watch app UUID, and hands each
  received PortCast document to a `WatchMessageHandler`. Failure-tolerant: no
  Garmin Connect / no watch is a logged no-op.
- **`TrimGarminWatchSync`** (app module): the handler. Composes the manifest
  lookup — `GarminRenderManifestStore` first (phone renders), else it **rebuilds
  the manifest for server-rendered episodes** from the episode's cached segments
  (`TrimSegmentCache`, the same canonical segments the backend rendered with) and
  the feed's synced playback rate (global-default feeds render at 1.0×, matching
  the backend's query default) — then runs `GarminPortcastBridge`. Started from
  `PodcastApp.onCreate`.
- **Send (phone → watch)** is not wired: resume points are informational-only
  today (the watch cannot seek cached audio), so there's nothing user-visible to
  send yet. `ConnectIQ.sendMessage` is the one-liner when that changes.

**Render (phone-side) is dormant but tested**: production delivery is the
backend's server-side render (`TrimPlayer-Unified` `garmin.py`), so
`GarminRenderer`/`GarminFfmpegExecutor` stay as the private-episode option. The
verified position math is shared either way.

## Tests

`GarminPositionMapperTest` is plain JUnit (no Android), runnable via the module's
`testImplementation junit` — or standalone with `javac`/`java`.
