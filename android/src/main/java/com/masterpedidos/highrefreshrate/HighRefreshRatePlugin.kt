package com.masterpedidos.highrefreshrate

import android.os.Build
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
            val window = bridge.activity.window
            val params = window.attributes
            params.preferredDisplayModeId = 0
            window.attributes = params
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

        return currentInfo()
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
        return result
    }
}
