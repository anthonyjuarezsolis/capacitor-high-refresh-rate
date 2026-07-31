package com.masterpedidos.highrefreshrate

import android.os.Build
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlin.math.roundToInt

/**
 * Plugin Android que destraba el max refresh rate del display (típicamente
 * 90Hz o 120Hz) pidiéndole al WindowManager que use el `Display.Mode` con
 * el `refreshRate` más alto disponible.
 *
 * Approach (Android 6+, API 23+):
 *   1) Enumerar `display.supportedModes` (API 23+ — `Display.getMode()` /
 *      `getSupportedModes()`).
 *   2) Filtrar por `physicalWidth`/`physicalHeight` iguales a la resolución
 *      actual (no queremos cambiar de 1080×2400 a 720×1600 solo para subir
 *      Hz — eso degrada UX visiblemente).
 *   3) Tomar el modo con mayor `refreshRate` que matchee resolución, o el
 *      que coincida con `preferredHz` si se especificó.
 *   4) Setear `window.attributes.preferredDisplayModeId = mode.modeId` y
 *      reasignar attributes.
 *
 * El WebView nativo de Android respeta el refresh rate del Window padre,
 * así que esto sí destraba 120Hz para toda la app (incluido el contenido
 * del WebView) — distinto a iOS donde WKWebView tiene su propio cap.
 *
 * Android 15+ (API 35) — Adaptive Refresh Rate (ARR):
 *   En devices con `Display.hasArrSupport() == true` (Samsung One UI sobre
 *   Android 15/16, Pixel QPR1+, etc.) el `preferredDisplayModeId` ya NO basta.
 *   El compositor del WebView (Chromium) vota una *categoría* de frame rate —
 *   `REQUESTED_FRAME_RATE_CATEGORY_HIGH` — para su rAF/animaciones. En varios
 *   Samsung esa categoría HIGH mapea a 90Hz (`frameRateCategoryRate {high=90}`),
 *   NO al pico de 120Hz, aunque el display y el surface override estén a 120.
 *   Resultado: `requestAnimationFrame` queda capado a 90fps.
 *
 *   Fix: setear un voto de frame rate de VALOR EXACTO sobre la View del WebView
 *   con `view.requestedFrameRate = 120f`. En el agregador de votos ARR, un voto
 *   de valor exacto 120 combinado con la categoría HIGH del compositor resuelve
 *   a 120Hz ("a 120 Hz exact vote = 120 Hz final frame rate" — docs ARR). El
 *   voto de categoría por sí solo no llega al pico; el de valor exacto sí.
 *
 *   Gotchas (de la doc oficial ARR):
 *     - NO propaga a hijos: hay que setearlo en la View del WebView, no en el
 *       decorView/ViewGroup padre.
 *     - "Valid as long as the View is invalidated" — el rAF loop invalida cada
 *       frame, así que persiste mientras hay animación; se re-aplica en cada
 *       applyMode()/notifyActivity() por seguridad.
 *     - Gated por `display.hasArrSupport()` — en devices sin ARR es no-op.
 */
@CapacitorPlugin(name = "HighRefreshRate")
class HighRefreshRatePlugin : Plugin() {

    private var adaptiveEnabled = false
    private var adaptiveActiveHz = 120.0
    private var adaptiveIdleHz = 60.0
    private var adaptiveIdleMs = 1500L
    private var inHighRateMode = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var idleRunnable: Runnable? = null

    /* Contador de frames REALES del compositor (paridad con el CADisplayLink de
       iOS, que expone `pacingTickCount`). Choreographer.FrameCallback se dispara
       1× por VSYNC entregado a la ventana, así que cuenta los frames que el
       compositor REALMENTE produce — no el modo del display ni rAF (main-thread,
       capado). Esto es lo que el FPS test usa para medir la cadencia real y
       detectar el cap de 90Hz del ARR de Samsung.
       Gateado a high-rate (start en applyMode>60, stop al idle) para NO mantener
       el VSYNC activo cuando la app está idle (sino se rompería el ahorro de
       batería del adaptive 60Hz). */
    private var pacingTickCount: Long = 0L
    private var pacingCounterRunning = false
    private val pacingFrameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            pacingTickCount++
            if (pacingCounterRunning) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun startPacingCounter() {
        if (pacingCounterRunning) return
        pacingCounterRunning = true
        Choreographer.getInstance().postFrameCallback(pacingFrameCb)
    }

    private fun stopPacingCounter() {
        if (!pacingCounterRunning) return
        pacingCounterRunning = false
        Choreographer.getInstance().removeFrameCallback(pacingFrameCb)
    }

    @PluginMethod
    fun enable(call: PluginCall) {
        val preferredHz = call.getDouble("preferredHz")
        bridge.activity.runOnUiThread {
            val info = applyMode(preferredHz)
            call.resolve(info)
        }
    }

    @PluginMethod
    fun getInfo(call: PluginCall) {
        bridge.activity.runOnUiThread {
            call.resolve(currentInfo())
        }
    }

    @PluginMethod
    fun setAdaptiveMode(call: PluginCall) {
        adaptiveEnabled = call.getBoolean("enabled") ?: false
        adaptiveActiveHz = call.getDouble("activeHz") ?: 120.0
        adaptiveIdleHz = call.getDouble("idleHz") ?: 60.0
        adaptiveIdleMs = (call.getDouble("idleMs") ?: 1500.0).toLong()

        bridge.activity.runOnUiThread {
            idleRunnable?.let { handler.removeCallbacks(it) }
            idleRunnable = null
            if (adaptiveEnabled) {
                applyMode(adaptiveActiveHz)
                inHighRateMode = true
                scheduleIdle()
            }
            call.resolve(currentInfo())
        }
    }

    @PluginMethod
    fun notifyActivity(call: PluginCall) {
        bridge.activity.runOnUiThread {
            if (adaptiveEnabled) {
                if (!inHighRateMode) {
                    applyMode(adaptiveActiveHz)
                    inHighRateMode = true
                }
                scheduleIdle()
            }
            call.resolve(currentInfo())
        }
    }

    private fun scheduleIdle() {
        idleRunnable?.let { handler.removeCallbacks(it) }
        idleRunnable = Runnable {
            if (adaptiveEnabled) {
                applyMode(adaptiveIdleHz)
                inHighRateMode = false
            }
        }
        handler.postDelayed(idleRunnable!!, adaptiveIdleMs)
    }

    @PluginMethod
    fun setTargetFps(call: PluginCall) {
        val target = call.getDouble("targetHz") ?: 120.0
        bridge.activity.runOnUiThread {
            val info = applyMode(target)
            call.resolve(info)
        }
    }

    @PluginMethod
    fun disable(call: PluginCall) {
        bridge.activity.runOnUiThread {
            stopPacingCounter()
            val window = bridge.activity.window
            val params = window.attributes
            params.preferredDisplayModeId = 0
            window.attributes = params
            // Devolver el voto de frame rate del WebView al default del sistema.
            if (Build.VERSION.SDK_INT >= 35) {
                bridge.webView?.let {
                    it.requestedFrameRate = android.view.View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
                    it.invalidate()
                }
            }
            call.resolve(currentInfo())
        }
    }

    // MARK: - Implementation

    private fun applyMode(preferredHz: Double?): JSObject {
        val activity = bridge.activity
        val window = activity.window
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        }

        if (display == null) {
            return currentInfo()
        }

        val currentMode = display.mode
        val modes = display.supportedModes.toList()
        val sameResolution = modes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }

        val chosen = if (preferredHz != null) {
            sameResolution.minByOrNull { kotlin.math.abs(it.refreshRate - preferredHz) }
        } else {
            sameResolution.maxByOrNull { it.refreshRate }
        }

        if (chosen != null) {
            val params: WindowManager.LayoutParams = window.attributes
            params.preferredDisplayModeId = chosen.modeId
            window.attributes = params
        }

        // Android 15+ ARR: sacar al WebView de la categoría HIGH (que en este
        // Samsung mapea a 90Hz) metiendo un voto de frame rate de valor EXACTO.
        val targetHz = chosen?.refreshRate?.toDouble() ?: preferredHz
        applyWebViewFrameRate(display, targetHz)

        /* Contador de compositor: solo corre en high-rate. En idle (≤60) se
           para para no mantener el VSYNC activo y romper el ahorro de batería. */
        val effectiveHz = chosen?.refreshRate?.toDouble() ?: preferredHz ?: 0.0
        if (effectiveHz > 60.5) startPacingCounter() else stopPacingCounter()

        return currentInfo()
    }

    /**
     * Setea un voto de frame rate de valor exacto sobre la View del WebView para
     * Android 15+ con ARR. Sin esto, el compositor Chromium del WebView solo vota
     * la categoría HIGH, que en algunos Samsung tope a 90Hz en vez de 120.
     *
     * Gated por API 36 + `hasArrSupport()`. En devices sin ARR es no-op silencioso.
     *
     * OJO: `Display.hasArrSupport()` existe recién en API 36 (Android 16). El gate
     * decía 35 y en Android 15 tiraba NoSuchMethodError FATAL en el arranque —
     * la app moría en el splash en todo device que no fuera Android 16 (bug real:
     * ZTE Blade V70 Max de un repartidor, 2026-07-31).
     */
    private fun applyWebViewFrameRate(display: Display?, hz: Double?) {
        if (Build.VERSION.SDK_INT < 36) return
        if (display == null || !display.hasArrSupport()) return

        val webView = bridge.webView ?: return
        val rate = hz?.toFloat()

        if (rate != null && rate > 60.5f) {
            // Voto de valor EXACTO (ej. 120f). Gana sobre la categoría HIGH del
            // compositor en el agregador de votos ARR.
            webView.requestedFrameRate = rate
            // Forzar invalidación: la preferencia "is valid as long as the View
            // is invalidated" — un solo invalidate engancha el voto al próximo frame.
            webView.invalidate()
        } else {
            // <=60Hz o sin target → devolver el control al sistema.
            webView.requestedFrameRate = android.view.View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            webView.invalidate()
        }
    }

    private fun currentInfo(): JSObject {
        val activity = bridge.activity
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.window.windowManager.defaultDisplay
        }

        val currentHz: Double = display?.refreshRate?.toDouble() ?: 60.0
        val modes = display?.supportedModes?.toList() ?: emptyList()
        val maxHz: Double = modes.maxByOrNull { it.refreshRate }?.refreshRate?.toDouble() ?: currentHz

        val supportedArray = JSArray()
        modes
            .map { it.refreshRate.toDouble() }
            .distinct()
            .sortedDescending()
            .forEach { supportedArray.put(it.roundToInt()) }

        val result = JSObject()
        result.put("currentHz", currentHz.roundToInt())
        result.put("maxHz", maxHz.roundToInt())
        result.put("supportedHz", supportedArray)
        result.put("unlockApplied", currentHz > 60.5)

        // Diagnóstico ARR (Android 16+): si el voto de frame rate del WebView se
        // aplicó. requestedFrameRate negativo = categoría; positivo = valor exacto.
        // hasArrSupport() es API 36 — con gate en 35 crasheaba en Android 15.
        if (Build.VERSION.SDK_INT >= 36) {
            val arr = display?.hasArrSupport() ?: false
            result.put("arrSupported", arr)
            bridge.webView?.let {
                result.put("webViewRequestedFrameRate", it.requestedFrameRate.toDouble())
                result.put("webViewUnlockApplied", arr && it.requestedFrameRate > 60.5f)
            }
        }

        /* Paridad con iOS: contador monotónico de frames del compositor (vía
           Choreographer). El FPS test lee el delta start→end para la cadencia
           real. `pacingActive` indica si el contador corre (high-rate). */
        result.put("pacingTickCount", pacingTickCount)
        result.put("pacingActive", pacingCounterRunning)
        return result
    }
}
