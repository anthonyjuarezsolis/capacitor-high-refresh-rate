/**
 * Result of querying current refresh rate state.
 *
 * - `currentHz`: best-effort measurement of the rate the display is actually
 *   running at (iOS: `UIScreen.maximumFramesPerSecond`; Android:
 *   `Display.getMode().refreshRate`).
 * - `maxHz`: highest rate the hardware supports (informational; matches
 *   `currentHz` on iOS where the screen can't be downgraded by the app).
 * - `supportedHz`: list of all modes available (Android only; iOS returns the
 *   single value `[currentHz]` for parity).
 * - `unlockApplied`: whether the unlock workaround was successfully applied
 *   this run. On iOS this means the WKWebView private feature flag was
 *   flipped; on Android it means a high-refresh-rate display mode was
 *   selected via `Window.setPreferredDisplayModeId`.
 * - `webViewUnlockApplied` (iOS only): whether the private WebKit feature
 *   flag `PreferPageRenderingUpdatesNear60FPSEnabled` was located and
 *   disabled. If `false`, animations inside the WebView will stay at 60Hz
 *   even when `currentHz` reports 120 — Apple's CADisplayLink will run at
 *   120 but the compositor still throttles WebView page updates.
 */
export interface RefreshRateInfo {
  currentHz: number;
  maxHz: number;
  supportedHz: number[];
  unlockApplied: boolean;
  webViewUnlockApplied?: boolean;
  /**
   * iOS-only diagnostic string. Values:
   *  - `untried` — enable() not called yet
   *  - `no-webview` — bridge.webView was nil
   *  - `no-_features-selector` — Apple removed the private selector entirely
   *  - `_features-not-array` — selector returned unexpected type
   *  - `found-key-no-setEnabled` — found the key but couldn't set it
   *  - `flag-set-but-ignored-in-iOS26` — key found and flipped, but WebView
   *    won't honor it (iOS 26+ deliberate behavior, confirmed by Apple)
   *  - `key-not-found (N features, related:...)` — key not in the features
   *    list anymore; `N` is the total features count and `related` lists
   *    the first few matching 60fps/refreshrate/framerate/rendering
   */
  diagnostic?: string;
  /** iOS-only: whether the pacing CADisplayLink is running. */
  pacingActive?: boolean;
  /** iOS-only: preferredFrameRate of the pacing CADisplayLink (typically maxHz). */
  pacingPreferredFps?: number;
  /** iOS-only: cumulative tick count since enable() — useful to confirm the
   *  link is actually firing at the expected rate (delta should match maxHz). */
  pacingTickCount?: number;
  /** iOS-only: whether the WKWebView responded to `_updateVisibleContentRects`. */
  pacingSelectorResponds?: boolean;
  /** iOS-only: extra WebKit feature keys we flipped (ON:/OFF: prefixed). */
  flippedExtraKeys?: string;
  /**
   * iOS-only: actual compositor FPS measured via CADisplayLink timestamp
   * deltas. **This is the REAL refresh rate**, unlike `requestAnimationFrame`
   * in the WebView which is capped at 60Hz by WebKit design. If this reports
   * 120, accelerated CSS animations (transform/opacity) ARE running at 120Hz
   * even though rAF callbacks fire only 60 times per second.
   */
  compositorFps?: number;
}

export interface EnableHighRefreshRateOptions {
  /**
   * Target refresh rate. If omitted, picks the highest supported mode.
   * Use `60` to force the device back to the low-power rate (Android only;
   * iOS ignores this and resets to system default).
   */
  preferredHz?: number;
}

export interface SetTargetFpsOptions {
  /**
   * Target refresh rate in Hz. Typical values: 60, 90, 120. The plugin
   * picks the closest supported mode (Android) or sets the CADisplayLink
   * preferredFrameRateRange (iOS). Use this to demo 60↔120 toggles at
   * runtime without reinstalling the app.
   */
  targetHz: number;
}

export interface SetAdaptiveModeOptions {
  /** Enable smart 60↔120 toggle based on user activity. */
  enabled: boolean;
  /** Hz to use when activity is detected (default = device max). */
  activeHz?: number;
  /** Hz to drop to when idle (default 60). */
  idleHz?: number;
  /** ms without notifyActivity() before dropping to idle Hz (default 1500). */
  idleMs?: number;
}

export interface HighRefreshRatePlugin {
  /**
   * Activates high refresh rate on the current Window / WebView.
   *
   * iOS:
   *  1) Reads `Info.plist` for `CADisableMinimumFrameDurationOnPhone`. If
   *     missing/false the system caps `CADisplayLink` at 60Hz regardless of
   *     anything else done here — the plugin will warn but still try the
   *     WebKit feature flag flip.
   *  2) Walks `bridge.webView.configuration.preferences._features` (private
   *     `_WKFeature` API) and disables `PreferPageRenderingUpdatesNear60FPSEnabled`
   *     so the WKWebView compositor stops throttling page updates.
   *
   * Android:
   *  1) Enumerates `Display.getSupportedModes()` and picks the one with the
   *     highest `refreshRate` (or matches `preferredHz` if provided).
   *  2) Sets `window.attributes.preferredDisplayModeId = mode.modeId` so the
   *     OS hands the app a Surface running at that rate.
   */
  enable(options?: EnableHighRefreshRateOptions): Promise<RefreshRateInfo>;

  /** Returns the current refresh rate state without changing anything. */
  getInfo(): Promise<RefreshRateInfo>;

  /**
   * Reverts to the system default refresh rate. On iOS this is a no-op for
   * the WebKit flag (Apple resets it on next launch); on Android it sets
   * `preferredDisplayModeId = 0` so the system picks again.
   */
  disable(): Promise<RefreshRateInfo>;

  /**
   * Toggle the target refresh rate at runtime (60↔120). Useful for demos
   * and A/B testing. On iOS updates the CADisplayLink `preferredFrameRateRange`
   * to `(min: target, max: target, preferred: target)`. On Android picks
   * the supported mode closest to `targetHz`.
   */
  setTargetFps(options: SetTargetFpsOptions): Promise<RefreshRateInfo>;

  /**
   * Habilita modo adaptive: cuando se reciben pings `notifyActivity()` el
   * plugin mantiene el display al `activeHz` (default = max device). Si
   * pasan `idleMs` sin pings, baja al `idleHz` (default 60) para ahorrar
   * batería. Replica el comportamiento de ProMotion adaptativo de Apple.
   */
  setAdaptiveMode(options: SetAdaptiveModeOptions): Promise<RefreshRateInfo>;

  /**
   * Notifica al plugin que el user está interactuando (touch/scroll). Si
   * adaptive está enabled, esto upgradea el display al `activeHz` (si no
   * lo estaba) y resetea el idle timer.
   */
  notifyActivity(): Promise<RefreshRateInfo>;
}
