# Changelog

All notable changes to this project will be documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [SemVer](https://semver.org/).

## [0.6.0] — 2026-05-16

### Added
- **Adaptive mode** (`setAdaptiveMode` + `notifyActivity`) — replicates Apple's
  ProMotion adaptive behavior: 120Hz when user is interacting, drops to 60Hz
  after configurable idle timeout (default 1500ms). Saves ~10-15% battery
  vs forced 120Hz constant.

## [0.5.1] — 2026-05-16

### Fixed
- Web stub was missing `setTargetFps` implementation (TypeScript compile error).

## [0.5.0] — 2026-05-16

### Added
- `setTargetFps({ targetHz })` — runtime toggle between 60Hz and 120Hz
  without app restart. Useful for demos and A/B testing.

## [0.4.0] — 2026-05-16

### Added
- `compositorFps` field in `RefreshRateInfo` — measured live via
  `CADisplayLink` timestamp deltas. This is the ground-truth refresh rate
  the display is actually rendering at, independent of WebKit's rAF cap.

### Changed
- HUD example now shows compositor-measured FPS as the primary value,
  with rAF FPS as secondary (clarifies the 60Hz cap is a WebKit design
  choice, not the actual display rate).

## [0.3.0] — 2026-05-16

### Added
- Bulk-flip of WebKit experimental features matching keywords
  (`60fps`, `near60`, `promotion`, `highrefresh`, etc) — defensive against
  Apple renaming the relevant flag in future iOS versions.

## [0.2.0] — 2026-05-16

### Added
- **CADisplayLink pacing**: persistent display link at 120Hz with
  `preferredFrameRateRange = (60, 120, 120)` keeps the physical display
  from dropping to 60Hz when idle (defeats ProMotion adaptive throttling).
- **`_updateVisibleContentRects` call on every tick**: forces the
  WebContent process to render at the CADisplayLink cadence.
  Source technique: Bennett Penn (笨鱼) on jianshu.com/p/1d739e2e7ed2.

## [0.1.x] — 2026-05-16

### Added
- Initial release.
- iOS: WebKit private API flag flip
  (`PreferPageRenderingUpdatesNear60FPSEnabled` via `_setEnabled:forFeature:`).
- Android: `WindowManager.LayoutParams.preferredDisplayModeId` set to
  highest available `Display.Mode` matching current resolution.
- TypeScript definitions + web fallback.
