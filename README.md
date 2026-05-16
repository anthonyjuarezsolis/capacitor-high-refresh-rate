# @ajuarezso/capacitor-high-refresh-rate

> **Unlock 120Hz ProMotion / high refresh rate displays in Capacitor apps on iOS and Android.**
> The only Capacitor plugin (as of 2026) that actually pushes the WKWebView compositor to 120Hz on iPhone Pro and iPad Pro devices, plus battery-friendly adaptive mode.

[![npm version](https://img.shields.io/npm/v/@ajuarezso/capacitor-high-refresh-rate.svg)](https://www.npmjs.com/package/@ajuarezso/capacitor-high-refresh-rate)
[![license](https://img.shields.io/npm/l/@ajuarezso/capacitor-high-refresh-rate.svg)](./LICENSE)

## Why this exists

Capacitor apps run inside a `WKWebView` (iOS) or `WebView` (Android). **By default on iOS, the WebView's compositor is capped at 60Hz** even on ProMotion devices like iPhone 13 Pro, iPhone 14 Pro, iPhone 15 Pro, iPhone 16 Pro, iPhone 17 Pro, and iPad Pro. Setting `CADisableMinimumFrameDurationOnPhone=true` in `Info.plist` unlocks native animations (UIKit/Core Animation) but **does not** unlock the WebView itself.

This plugin combines four independent techniques to actually get the WebView rendering at 120Hz:

| technique | what it does | source |
|---|---|---|
| 1. Reads `CADisableMinimumFrameDurationOnPhone` plist flag | required by iOS to allow `CADisplayLink` > 60Hz | [Apple documentation](https://developer.apple.com/documentation/quartzcore/cadisplaylink) |
| 2. Persistent `CADisplayLink` at 120Hz with `preferredFrameRateRange` | keeps the physical display from dropping to 60Hz on idle (defeats ProMotion adaptive throttling) | [Duraid Abdul, FrameRateRequest](https://github.com/duraidabdul/FrameRateRequest) |
| 3. Private API call to `WKPreferences._setEnabled:forFeature:` flipping `PreferPageRenderingUpdatesNear60FPSEnabled` to false | toggles WebKit's internal "prefer 60fps" flag (ignored in iOS 26+ but still flipped) | [tauri-plugin-macos-fps](https://github.com/userFRM/tauri-plugin-macos-fps) |
| 4. Calls private API `WKWebView._updateVisibleContentRects` on every `CADisplayLink` tick | wakes up the `WebContent` process to render at 120Hz cadence | [Bennett Penn (笨鱼) on jianshu](https://www.jianshu.com/p/1d739e2e7ed2) |

On **Android** the implementation is much simpler: it uses the public `Window.attributes.preferredDisplayModeId` API to pick the highest refresh rate `Display.Mode` that matches the current resolution. No private APIs needed.

## What does NOT work and why

Apple deliberately caps **`requestAnimationFrame()` callbacks at 60Hz inside WKWebView**, by design, for power consumption reasons and backwards compatibility with old web pages. This is confirmed by the WebKit team in [their explainers](https://github.com/WebKit/explainers) and the open [WebKit bug #294338](https://bugs.webkit.org/show_bug.cgi?id=294338). **No known workaround exists in iOS 26** — not via public, private, or feature-flag APIs.

**But** GPU-composited animations (CSS `transform`, `opacity`, `filter` with `will-change`) **do run at 120Hz** because they bypass the main thread and JS event loop entirely. This is the actual win: scroll, transitions, gestures, modals — everything that uses `transform` corre at the display's native refresh rate.

| capa | rate on iPhone 17 Pro Max | unlockable? |
|---|---|---|
| Physical display | 120Hz (ProMotion) | yes via plist + plugin |
| Native animations (UIKit) | 120Hz | yes via plist alone |
| CSS `transform` / `opacity` (composited) | 120Hz | yes via plist + plugin |
| Scroll (CSS overflow) | 120Hz | yes via plist + plugin |
| `requestAnimationFrame` JS | **60Hz** | **no** (WebKit cap by design) |
| CSS `left`, `top`, `width`, `background-color` (main thread) | **60Hz** | **no** (WebKit cap by design) |
| Canvas/WebGL `requestAnimationFrame` loop | **60Hz** | **no** |

If your app uses `transform: translate3d(...)` + `opacity` for animations (which is the modern web convention anyway, e.g. Framer Motion, GSAP, Spartan UI, AlignUI), **you already benefit from 120Hz with just this plugin installed**.

## Install

```bash
npm install @ajuarezso/capacitor-high-refresh-rate
npx cap sync
```

### iOS: add the plist flags

In `ios/App/App/Info.plist` add (or verify) both keys exist:

```xml
<key>CADisableMinimumFrameDuration</key>
<true/>
<key>CADisableMinimumFrameDurationOnPhone</key>
<true/>
```

The first is for iPad Pro, the second for iPhone Pro. **The plugin requires `CADisableMinimumFrameDurationOnPhone=true` — without it the display stays capped at 60Hz regardless of plugin calls.** We empirically verified this: removing the flag dropped a measured `compositorFps` from 132Hz to 66Hz on iPhone 17 Pro Max.

### Android: no extra setup

Works out of the box. The plugin uses public API only on Android.

## Usage

### Minimal (force max refresh rate)

```typescript
import { HighRefreshRate } from '@ajuarezso/capacitor-high-refresh-rate';
import { Capacitor } from '@capacitor/core';

if (Capacitor.isNativePlatform()) {
  await HighRefreshRate.enable();
}
```

That's it. Call once at app boot.

### Adaptive mode (recommended for production)

Forcing 120Hz constantly **defeats Apple's adaptive ProMotion** which normally drops the display to 24Hz/30Hz/60Hz when idle for battery savings. To get the best of both worlds (smooth animations when active + battery savings when idle), use adaptive mode:

```typescript
import { HighRefreshRate } from '@ajuarezso/capacitor-high-refresh-rate';

await HighRefreshRate.enable();
await HighRefreshRate.setAdaptiveMode({
  enabled: true,
  activeHz: 120,
  idleHz: 60,
  idleMs: 1500,
});

// Pipe global activity events (touch / scroll / pointer / wheel) to the plugin.
// The plugin upgrades the display to activeHz on each ping and drops to idleHz
// after `idleMs` of silence. Replicates Apple ProMotion adaptive behavior.
let lastPing = 0;
const onActivity = () => {
  const now = performance.now();
  if (now - lastPing < 200) return; // throttle bridge calls to 5/s
  lastPing = now;
  HighRefreshRate.notifyActivity();
};

['touchstart', 'touchmove', 'scroll', 'wheel', 'pointerdown', 'pointermove']
  .forEach(ev => window.addEventListener(ev, onActivity, { passive: true, capture: true }));
```

Expected battery savings: **~10-15% vs forced 120Hz constant** under normal mixed usage.

### Runtime toggle (demo / settings UI)

```typescript
// Toggle between 60 and 120
await HighRefreshRate.setTargetFps({ targetHz: 60 });   // device drops to 60Hz
await HighRefreshRate.setTargetFps({ targetHz: 120 });  // device back to 120Hz
```

### Read current state

```typescript
const info = await HighRefreshRate.getInfo();
console.log(info);
// {
//   currentHz: 120,
//   maxHz: 120,
//   supportedHz: [120],
//   unlockApplied: true,
//   webViewUnlockApplied: true,    // iOS only — WebKit feature flag flipped
//   diagnostic: 'flipped-via-_experimentalFeatures',
//   pacingActive: true,             // iOS only — CADisplayLink running
//   pacingPreferredFps: 120,
//   pacingTickCount: 4518,
//   pacingSelectorResponds: true,
//   compositorFps: 120.0,           // iOS only — REAL measured refresh rate
// }
```

`compositorFps` is the ground-truth measurement of what the display is actually rendering, measured via `CADisplayLink` timestamp deltas. This is what you want to verify the plugin worked.

### Disable

```typescript
await HighRefreshRate.disable();
// iOS: no-op (WebKit flag resets on next cold start)
// Android: sets preferredDisplayModeId = 0, system picks freely
```

## API reference

### `enable(options?): Promise<RefreshRateInfo>`

Activates high refresh rate on the current Window / WebView. Idempotent. Call once at app boot.

```typescript
await HighRefreshRate.enable({ preferredHz: 120 }); // optional
```

### `setAdaptiveMode(options): Promise<RefreshRateInfo>`

Enable smart 60↔120 toggle based on `notifyActivity()` pings.

```typescript
interface SetAdaptiveModeOptions {
  enabled: boolean;
  activeHz?: number;   // default = device max
  idleHz?: number;     // default = 60
  idleMs?: number;     // default = 1500
}
```

### `notifyActivity(): Promise<RefreshRateInfo>`

Tells the plugin "the user is interacting now". Resets the idle timer. Call this from a throttled handler over `touchstart` / `scroll` / `wheel` / `pointermove`. Throttle to ~5 calls/sec to avoid bridge overhead.

### `setTargetFps(options): Promise<RefreshRateInfo>`

Manually pin the refresh rate (overrides adaptive mode).

```typescript
interface SetTargetFpsOptions {
  targetHz: number; // 60 | 90 | 120 typically
}
```

### `getInfo(): Promise<RefreshRateInfo>`

Returns the current state without changing anything. Useful for FPS HUDs / debug overlays.

### `disable(): Promise<RefreshRateInfo>`

Reverts to system default. On iOS the WebKit flag flip persists until cold start (Apple resets). On Android sets `preferredDisplayModeId = 0`.

## How to verify it actually works

Don't trust the FPS reported by `requestAnimationFrame` — it's locked at 60Hz on iOS by design and doesn't reflect what the display is doing. Use these signals instead:

1. **`compositorFps` field**: measured via `CADisplayLink` timestamps natively. Bypasses the JS rAF cap. If this reports ~120 you're at 120Hz.
2. **Visual test**: animate an element with `transform: translate3d(0, 0, 0) → translate3d(100px, 0, 0)` over 1 second. On 120Hz it looks markedly smoother than on 60Hz. Compare side-by-side with the same element animated via `left: 0 → 100px` (capped at 60Hz on iOS).
3. **TestUFO-style stripe pattern**: a moving black-and-white striped pattern shows more crispness at 120Hz than at 60Hz due to motion blur perception.
4. **Empirical removal test**: temporarily remove `CADisableMinimumFrameDurationOnPhone` from `Info.plist`, rebuild, and re-measure. `compositorFps` will halve (from ~120 to ~60). Restore the flag, rebuild, and it returns to ~120.

The plugin's source repo includes a TestUFO-style HUD component (in `examples/` — see the example apps) you can drop into your app to validate.

## Comparison with alternatives

| project | platforms | unlocks WKWebView 120Hz? | uses private API? | active? |
|---|---|---|---|---|
| **@ajuarezso/capacitor-high-refresh-rate** | iOS + Android, Capacitor | **yes** via 4 combined techniques | yes (well-known, used by Tauri plugin too) | yes (2026) |
| `tauri-plugin-macos-fps` | macOS only, Tauri | yes for macOS WKWebView | yes (`_features` private API) | yes |
| `flutter_refresh_rate_control` | iOS + Android, Flutter | n/a (Flutter doesn't use WebView) | no | yes |
| `@capacitor/screen-orientation` | iOS + Android | unrelated | no | yes |
| (none) | iOS + Android, Capacitor + WKWebView 120Hz | — | — | — |

As of 2026, **this is the only published Capacitor plugin that targets WebView 120Hz unlock**. Earlier Capacitor community discussions on the Ionic forum acknowledged the limitation but offered no solution.

## Limitations and honest disclosures

1. **App Store risk**: Apple's review may flag the private API usage (`_setEnabled:forFeature:`, `_updateVisibleContentRects`). They are invoked via `NSSelectorFromString` which is harder to detect via static analysis but **not zero risk**. We've shipped builds with these calls to TestFlight without rejection but cannot promise App Store will accept it long-term. Weigh against your app's review history.

2. **iOS 26 specific behavior**: Apple disconnected the `PreferPageRenderingUpdatesNear60FPSEnabled` flag from the compositor in iOS 26 — the flag still flips, but no longer has effect on its own. The plugin still relies on **techniques 1 + 2 + 4** (plist, CADisplayLink, `_updateVisibleContentRects`) to achieve 120Hz on iOS 26. We verified this empirically on iPhone 17 Pro Max running iOS 26.5.

3. **Battery cost**: ~10-15% extra battery drain in typical mixed usage when 120Hz is forced constantly. Use adaptive mode (`setAdaptiveMode`) to mitigate.

4. **Devices without ProMotion**: on iPhones without ProMotion (anything below iPhone Pro 13 or non-Pro models), `maxHz` will be 60 and the plugin is essentially a no-op. Plugin detects this gracefully — no harm, no error.

5. **`requestAnimationFrame` stays at 60Hz**: by Apple's deliberate design. There is no workaround. If your app depends on rAF cadence (canvas games, custom animation loops), they will run at 60. Use CSS-composited animations or `Web Animations API` with composited properties instead.

6. **Adaptive mode requires JS activity pings**: the plugin can't natively listen to WebView touches without method swizzling. The current implementation requires the consumer to call `notifyActivity()` from a JS event handler. Future versions may add native gesture recognition.

## Keywords for discoverability

This plugin solves: capacitor 120hz, capacitor promotion, capacitor high refresh rate, capacitor ionic 120fps, wkwebview 120hz, wkwebview promotion unlock, ios 120hz webview, iphone pro 120hz capacitor, ipad pro 120hz capacitor, android high refresh rate capacitor, capacitor adaptive refresh rate, capacitor 60hz cap, requestAnimationFrame 60hz wkwebview workaround.

Related projects this is an alternative to or complements:
- `@capacitor/screen-orientation` (different concern)
- `tauri-plugin-macos-fps` (different framework, macOS only)
- React Native: no Capacitor plugin needed (different webview path)
- Cordova: no direct equivalent (Cordova-iOS uses WKWebView too, technique would port)

## Credits

- **Bennett Penn (笨鱼)** — original documentation of the `CADisplayLink` + `_updateVisibleContentRects` technique on [jianshu.com/p/1d739e2e7ed2](https://www.jianshu.com/p/1d739e2e7ed2). This is the core technique the plugin uses.
- **Space Patrol Delta blog** — additional explanation of the `_updateVisibleContentRects` private method call chain.
- **Duraid Abdul** — [FrameRateRequest](https://github.com/duraidabdul/FrameRateRequest) for the `CADisplayLink` + `preferredFrameRateRange` pattern.
- **`tauri-plugin-macos-fps`** — for documenting the `WKPreferences._features` private API approach.
- **WebKit team** — for the public [explainers](https://github.com/WebKit/explainers) clarifying why rAF is capped at 60Hz.

## Repository

- Source: https://github.com/anthonyjuarezsolis/capacitor-high-refresh-rate
- Issues: https://github.com/anthonyjuarezsolis/capacitor-high-refresh-rate/issues
- npm: https://www.npmjs.com/package/@ajuarezso/capacitor-high-refresh-rate
- Built and verified on iPhone 17 Pro Max running iOS 26.5

## License

MIT — see [LICENSE](./LICENSE)
