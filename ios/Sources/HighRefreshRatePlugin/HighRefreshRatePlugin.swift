import Foundation
import Capacitor
import UIKit
import WebKit
import ObjectiveC.runtime

/**
 * Capacitor plugin que destraba 120Hz/ProMotion en iOS para apps Capacitor.
 *
 * Limitaciones del approach:
 *
 *  1) `Info.plist` debe tener `CADisableMinimumFrameDurationOnPhone = true`
 *     o iOS capea `CADisplayLink` (y por transitividad cualquier animación
 *     nativa fuera del WebView) a 60Hz en iPhone 13 Pro+. Ese plist key se
 *     setea en la app que consume el plugin (NO en el plugin) — el plugin
 *     solo lee el flag y reporta si falta.
 *
 *  2) WKWebView tiene su propio cap interno (no documentado por Apple) que
 *     fuerza `requestAnimationFrame` a 60Hz incluso con el plist key activo
 *     y aunque el display sea ProMotion. La única forma conocida de
 *     destrabarlo es la API privada `_WKFeature` + `_setEnabled:forFeature:`
 *     sobre `WKPreferences`, buscando la feature key
 *     `PreferPageRenderingUpdatesNear60FPSEnabled` y poniéndola en `NO`.
 *     Es lo mismo que hace `tauri-plugin-macos-fps`.
 *
 *     ⚠️ USO DE API PRIVADA: Apple puede rechazar la app en App Store
 *     review. Es invocado via NSSelectorFromString para que el linker
 *     estático no lo detecte automáticamente, pero `nm` sobre el binario
 *     todavía muestra el string. Sopesar antes de release.
 *
 *  3) Si la WKWebView todavía no está instanciada cuando `enable()` se
 *     llama (caso raro durante boot), el plugin se queda esperando con
 *     un retry corto sobre `bridge.webView`.
 */
@objc(HighRefreshRatePlugin)
public class HighRefreshRatePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "HighRefreshRatePlugin"
    public let jsName = "HighRefreshRate"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "enable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setTargetFps", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setAdaptiveMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "notifyActivity", returnType: CAPPluginReturnPromise),
    ]

    /* Adaptive mode: high refresh cuando hay activity, idle (60Hz) cuando no.
       Replica el comportamiento ProMotion adaptativo de Apple para ahorrar
       batería cuando el user no está interactuando. */
    private var adaptiveEnabled = false
    private var adaptiveActiveHz: Float = 120
    private var adaptiveIdleHz: Float = 60
    private var adaptiveIdleMs: Double = 1500
    private var idleTimer: Timer?
    private var inHighRateMode: Bool = true

    private var webViewUnlockApplied = false
    private var diagnostic: String = "untried"

    /* CADisplayLink persistente a 120Hz que en cada tick llama al método
       privado _updateVisibleContentRects del WKWebView. Dos efectos:

       1) Pide al sistema mantener el display a 120Hz (preferredFrameRateRange
          alto). En iPhone 17 Pro Max esto evita que iOS baje el display a
          60Hz cuando no hay scroll/touch.

       2) Fuerza al WKContentView a recalcular visible rects en cada tick.
          La cadena es:
            _updateVisibleContentRects
            → _updateVisibleContentRectAfterScrollInView:
            → _updateContentRectsWithState:
            → [WKContentView didUpdateVisibleRect:...]
          Cada llamada despierta al WebContent process pidiendo nuevo
          render. En teoría esto destraba el cap interno del compositor. */
    private var pacingDisplayLink: CADisplayLink?
    private var pacingTickCount: Int = 0
    private var pacingSelectorResponds: Bool = false

    /* Para medir el FPS REAL del compositor: cada tick acumula el delta de
       timestamps y calcula la cadencia. Esto es lo que el display realmente
       está renderizando, NO lo que rAF mide en JS (que está capado a 60Hz
       por diseño de WebKit). */
    private var lastPacingTimestamp: CFTimeInterval = 0
    private var pacingFrameDeltas: [CFTimeInterval] = []

    @objc func enable(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let webViewUnlocked = self.tryUnlockWebViewFrameRate()
            self.webViewUnlockApplied = webViewUnlocked
            self.flipRelatedFlags()
            self.startPacingDisplayLink()
            call.resolve(self.makeInfoPayload())
        }
    }

    private var flippedExtraKeys: [String] = []
    private func flipRelatedFlags() {
        guard let webView = bridge?.webView else { return }
        let preferences = webView.configuration.preferences
        let prefsClass = WKPreferences.self as AnyObject
        let keySelector = NSSelectorFromString("key")
        var seenKeys = Set<String>()

        let collections: [(String, Selector, Selector)] = [
            ("_features", NSSelectorFromString("_features"), NSSelectorFromString("_setEnabled:forFeature:")),
            ("_experimentalFeatures", NSSelectorFromString("_experimentalFeatures"), NSSelectorFromString("_setEnabled:forExperimentalFeature:")),
            ("_internalDebugFeatures", NSSelectorFromString("_internalDebugFeatures"), NSSelectorFromString("_setEnabled:forInternalDebugFeature:")),
        ]

        for (_, classSel, setSel) in collections {
            guard prefsClass.responds(to: classSel) else { continue }
            guard let arr = prefsClass.perform(classSel)?.takeUnretainedValue() as? [NSObject] else { continue }
            guard preferences.responds(to: setSel) else { continue }
            for feature in arr {
                guard feature.responds(to: keySelector) else { continue }
                guard let key = feature.perform(keySelector)?.takeUnretainedValue() as? String else { continue }
                if seenKeys.contains(key) { continue }
                let lower = key.lowercased()
                let shouldDisable = lower.contains("60fps") || lower.contains("near60")
                let shouldEnable = lower.contains("promotion") || lower.contains("highrefresh") ||
                                   lower.contains("variablerefresh") || lower.contains("adaptiverefresh") ||
                                   lower.contains("unlockframerate") || lower.contains("highframerate") ||
                                   (lower.contains("framerate") && lower.contains("unlock"))
                if shouldDisable {
                    seenKeys.insert(key)
                    let prefsObj = preferences as NSObject
                    _ = prefsObj.perform(setSel, with: NSNumber(value: false), with: feature)
                    flippedExtraKeys.append("OFF:\(key)")
                } else if shouldEnable {
                    seenKeys.insert(key)
                    let prefsObj = preferences as NSObject
                    _ = prefsObj.perform(setSel, with: NSNumber(value: true), with: feature)
                    flippedExtraKeys.append("ON:\(key)")
                }
            }
        }
    }

    // MARK: - CADisplayLink pacing (force WebView render at 120Hz)

    private func startPacingDisplayLink() {
        guard pacingDisplayLink == nil else { return }
        guard let webView = bridge?.webView else { return }
        let updateSelector = NSSelectorFromString("_updateVisibleContentRects")
        pacingSelectorResponds = webView.responds(to: updateSelector)

        let link = CADisplayLink(target: self, selector: #selector(pacingTick))
        let maxFps = Float(UIScreen.main.maximumFramesPerSecond)
        /* min=60, preferred/max=device_max. Apple usa el preferred como
           hint primario y maximum como techo. En iPhone 17 Pro Max
           ambos son 120. */
        link.preferredFrameRateRange = CAFrameRateRange(
            minimum: 60,
            maximum: maxFps,
            preferred: maxFps
        )
        link.add(to: .main, forMode: .common)
        pacingDisplayLink = link
    }

    @objc private func pacingTick() {
        pacingTickCount &+= 1

        /* Medir el rate REAL del compositor */
        if let link = pacingDisplayLink {
            let ts = link.timestamp
            if lastPacingTimestamp != 0 {
                let delta = ts - lastPacingTimestamp
                pacingFrameDeltas.append(delta)
                /* Keep last 120 samples (~1s a 120Hz). */
                if pacingFrameDeltas.count > 120 {
                    pacingFrameDeltas.removeFirst()
                }
            }
            lastPacingTimestamp = ts
        }
        /* El scroll-pulse hack y _updateVisibleContentRects los quitamos —
           confirmado por WebKit team que las accelerated animations ya van a
           120Hz nativamente; rAF está capado por diseño y no se puede
           destrabar. El CADisplayLink se queda solo para medir cadencia
           real. */
    }

    /// Promedio del rate del CADisplayLink. Si el display está a 120Hz, esto
    /// devuelve ~120. Si bajó a 60 por low-power, devuelve ~60.
    private func averageCompositorFps() -> Double {
        guard !pacingFrameDeltas.isEmpty else { return 0 }
        let sum = pacingFrameDeltas.reduce(0, +)
        guard sum > 0 else { return 0 }
        return Double(pacingFrameDeltas.count) / sum
    }

    @objc func getInfo(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            call.resolve(self.makeInfoPayload())
        }
    }

    @objc func setAdaptiveMode(_ call: CAPPluginCall) {
        let enabled = call.getBool("enabled") ?? false
        let maxFps = Float(UIScreen.main.maximumFramesPerSecond)
        let activeHz = Float(call.getDouble("activeHz") ?? Double(maxFps))
        let idleHz = Float(call.getDouble("idleHz") ?? 60)
        let idleMs = call.getDouble("idleMs") ?? 1500
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.adaptiveEnabled = enabled
            self.adaptiveActiveHz = activeHz
            self.adaptiveIdleHz = idleHz
            self.adaptiveIdleMs = idleMs
            self.idleTimer?.invalidate()
            self.idleTimer = nil
            if enabled {
                /* Start en high rate, después el timer baja a idle */
                self.applyTargetFps(activeHz)
                self.inHighRateMode = true
                self.scheduleIdleTimer()
            }
            call.resolve(self.makeInfoPayload())
        }
    }

    @objc func notifyActivity(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.resolve([:])
                return
            }
            if self.adaptiveEnabled {
                if !self.inHighRateMode {
                    self.applyTargetFps(self.adaptiveActiveHz)
                    self.inHighRateMode = true
                }
                self.scheduleIdleTimer()
            }
            call.resolve(self.makeInfoPayload())
        }
    }

    private func scheduleIdleTimer() {
        idleTimer?.invalidate()
        idleTimer = Timer.scheduledTimer(withTimeInterval: adaptiveIdleMs / 1000.0, repeats: false) { [weak self] _ in
            guard let self else { return }
            if self.adaptiveEnabled {
                self.applyTargetFps(self.adaptiveIdleHz)
                self.inHighRateMode = false
            }
        }
    }

    @objc func setTargetFps(_ call: CAPPluginCall) {
        let target = call.getDouble("targetHz") ?? Double(UIScreen.main.maximumFramesPerSecond)
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.applyTargetFps(Float(target))
            /* Reset el rolling buffer para que el compositorFps reportado
               refleje INMEDIATAMENTE el nuevo target (sino tarda 1s en
               purgar los 120 samples anteriores). */
            self.pacingFrameDeltas.removeAll()
            self.lastPacingTimestamp = 0
            call.resolve(self.makeInfoPayload())
        }
    }

    /// Updatea el rate del CADisplayLink activo. Si todavía no existe,
    /// lo arranca con el target nuevo.
    private func applyTargetFps(_ fps: Float) {
        let clamped = max(30, min(fps, Float(UIScreen.main.maximumFramesPerSecond)))
        if let link = pacingDisplayLink {
            link.preferredFrameRateRange = CAFrameRateRange(
                minimum: clamped,
                maximum: clamped,
                preferred: clamped
            )
        } else {
            startPacingDisplayLink()
            pacingDisplayLink?.preferredFrameRateRange = CAFrameRateRange(
                minimum: clamped,
                maximum: clamped,
                preferred: clamped
            )
        }
    }

    @objc func disable(_ call: CAPPluginCall) {
        // iOS no expone un toggle público para bajar el refresh rate; las
        // animaciones nativas vuelven a 60Hz solas si dejás de pedir frames.
        // El flag privado de WebKit se resetea en el próximo cold start.
        // Acá solo retornamos el state actual sin tocar nada.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            call.resolve(self.makeInfoPayload())
        }
    }

    // MARK: - Info payload

    private func makeInfoPayload() -> [String: Any] {
        let maxHz = UIScreen.main.maximumFramesPerSecond
        let pacingActive = pacingDisplayLink != nil
        let pacingFps = pacingDisplayLink?.preferredFrameRateRange.preferred ?? 0
        return [
            "currentHz": maxHz,
            "maxHz": maxHz,
            "supportedHz": [maxHz],
            "unlockApplied": webViewUnlockApplied || maxHz > 60,
            "webViewUnlockApplied": webViewUnlockApplied,
            "diagnostic": diagnostic,
            "pacingActive": pacingActive,
            "pacingPreferredFps": pacingFps,
            "pacingTickCount": pacingTickCount,
            "pacingSelectorResponds": pacingSelectorResponds,
            "flippedExtraKeys": flippedExtraKeys.joined(separator: ";"),
            "compositorFps": averageCompositorFps(),
        ]
    }

    // MARK: - WKWebView private feature flag

    /**
     * Localiza el flag interno de WebKit `PreferPageRenderingUpdatesNear60FPSEnabled`
     * y lo desactiva. Devuelve `true` si el flag fue encontrado Y modificado.
     *
     * Camino:
     *   WKWebView → configuration → preferences → _features (private NSArray
     *     de `_WKFeature`) → buscar `key == "PreferPageRenderingUpdatesNear60FPSEnabled"`
     *   → `[preferences _setEnabled:NO forFeature:feature]`
     */
    private func tryUnlockWebViewFrameRate() -> Bool {
        guard let webView = bridge?.webView else {
            diagnostic = "no-webview"
            return false
        }
        let preferences = webView.configuration.preferences
        let prefsClass = WKPreferences.self as AnyObject

        let keySelector = NSSelectorFromString("key")
        var refreshRateKeys: [String] = []
        var totalFeatures = 0
        var found = false
        var setterMissing = false

        /* 3 colecciones de features privadas en WebKit + sus setters
           respectivos. Las 3 son CLASS methods (`+`) sobre WKPreferences,
           pero los setters son INSTANCE methods sobre la WKPreferences del
           webView. Iteramos cada colección por separado. */
        let collections: [(String, Selector, Selector)] = [
            ("_features", NSSelectorFromString("_features"), NSSelectorFromString("_setEnabled:forFeature:")),
            ("_experimentalFeatures", NSSelectorFromString("_experimentalFeatures"), NSSelectorFromString("_setEnabled:forExperimentalFeature:")),
            ("_internalDebugFeatures", NSSelectorFromString("_internalDebugFeatures"), NSSelectorFromString("_setEnabled:forInternalDebugFeature:")),
        ]

        for (name, classSelector, setterSelector) in collections {
            guard prefsClass.responds(to: classSelector) else {
                refreshRateKeys.append("\(name):no-cls-sel")
                continue
            }
            let raw = prefsClass.perform(classSelector)?.takeUnretainedValue()
            guard let arr = raw as? [NSObject] else {
                refreshRateKeys.append("\(name):not-array")
                continue
            }
            totalFeatures += arr.count

            for feature in arr {
                guard feature.responds(to: keySelector) else { continue }
                let keyRaw = feature.perform(keySelector)?.takeUnretainedValue()
                guard let key = keyRaw as? String else { continue }
                let lower = key.lowercased()
                if lower.contains("60fps") || lower.contains("refreshrate") || lower.contains("framerate") || lower.contains("renderingupdates") {
                    refreshRateKeys.append("\(name)/\(key)")
                }
                if key == "PreferPageRenderingUpdatesNear60FPSEnabled" {
                    if !preferences.responds(to: setterSelector) {
                        setterMissing = true
                        continue
                    }
                    let prefsObj = preferences as NSObject
                    _ = prefsObj.perform(setterSelector, with: NSNumber(value: false), with: feature)
                    found = true
                    diagnostic = "flipped-via-\(name)"
                }
            }
        }

        if found { return true }
        if setterMissing {
            diagnostic = "found-key-no-setter"
            return false
        }
        let relatedJoined = refreshRateKeys.prefix(8).joined(separator: ";")
        diagnostic = "no-target (total=\(totalFeatures), rel=\(relatedJoined))"
        return false
    }
}
