package com.retrorts.ui
import android.util.Log
class DosboxBridge {
companion object {
private const val TAG = "DosboxBridge"
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("pcsx_rearmed")
        true
    }.getOrElse {
        Log.e(TAG, "Failed to load pcsx_rearmed: ${it.message}")
        false
    }

    fun startDosbox(gameDir: String, configPath: String): Boolean {
        if (!isAvailable) return false
        return runCatching {
            startDosboxNative(gameDir, configPath)
        }.getOrElse {
            Log.e(TAG, "startDosbox error: ${it.message}")
            false
        }
    }

    fun stopDosbox() {
        if (!isAvailable) return
        runCatching { stopDosboxNative() }
    }

    fun setCpuCycles(cycles: Int) {
        if (isAvailable) runCatching { setCpuCyclesNative(cycles) }
    }

    fun setFrameCap(fps: Int) {
        if (isAvailable) runCatching { setFrameCapNative(fps) }
    }

    fun setVolume(volume: Float) {
        if (isAvailable) runCatching { setVolumeNative(volume) }
    }

    fun notifyThermalLevel(level: Int) {
        if (isAvailable) runCatching { notifyThermalLevelNative(level) }
    }

    fun getPerfStats(): FloatArray {
        if (!isAvailable) return floatArrayOf(0f, 0f)
        return runCatching { getPerfStatsNative() }.getOrDefault(floatArrayOf(0f, 0f))
    }

    fun saveState(gameId: String, slot: Int, path: String): Boolean {
        if (!isAvailable) return false
        return runCatching { saveStateNative(gameId, slot, path) }.getOrDefault(false)
    }

    fun loadState(gameId: String, slot: Int, path: String): Boolean {
        if (!isAvailable) return false
        return runCatching { loadStateNative(gameId, slot, path) }.getOrDefault(false)
    }

    fun updateInput(port: Int, buttonMask: Int) {
        if (isAvailable) runCatching { updateInputNative(port, buttonMask) }
    }

    fun updateAnalog(port: Int, index: Int, id: Int, value: Int) {
        if (isAvailable) runCatching { updateAnalogNative(port, index, id, value) }
    }

    fun updateMouse(buttonMask: Int, dx: Int, dy: Int) {
        if (isAvailable) runCatching { updateMouseNative(buttonMask, dx, dy) }
    }

    // ── native declarations ─────────────────────────────────────────
    @JvmStatic private external fun startDosboxNative(gameDir: String, configPath: String): Boolean
    @JvmStatic private external fun stopDosboxNative()
    @JvmStatic private external fun setCpuCyclesNative(cycles: Int)
    @JvmStatic private external fun setFrameCapNative(fps: Int)
    @JvmStatic private external fun setVolumeNative(volume: Float)
    @JvmStatic private external fun notifyThermalLevelNative(level: Int)
    @JvmStatic private external fun getPerfStatsNative(): FloatArray
    @JvmStatic private external fun saveStateNative(gameId: String, slot: Int, path: String): Boolean
    @JvmStatic private external fun loadStateNative(gameId: String, slot: Int, path: String): Boolean
    @JvmStatic private external fun updateInputNative(port: Int, buttonMask: Int)
    @JvmStatic private external fun updateAnalogNative(port: Int, index: Int, id: Int, value: Int)
    @JvmStatic private external fun updateMouseNative(buttonMask: Int, dx: Int, dy: Int)
}
}