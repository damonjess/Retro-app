package com.retrorts.ui

import java.io.File

object NativeEmulatorBridge {
    private val nativeLoaded = runCatching {
        System.loadLibrary("pcsx_rearmed")
    }.isSuccess

    data class LaunchResult(val started: Boolean, val message: String)

    fun launchGame(console: String, romPath: String, context: android.content.Context? = null): LaunchResult {
        if (!nativeLoaded) return LaunchResult(false, "Native library unavailable")
        val ctx = context ?: throw IllegalArgumentException("Context required for native launch")
        return runCatching {
            val cacheDir = ctx.cacheDir.absolutePath
            val saveDir = File(ctx.getExternalFilesDir(null), "Saves/$console").also { it.mkdirs() }.absolutePath
            val msg = launchGameNative(console, romPath, cacheDir, saveDir)
            // C++ returns "OK:..." for success or "ERR:..." for failure
            LaunchResult(
                started = msg.startsWith("OK") || msg.contains("ready"),
                message = msg,
            )
        }.getOrElse { LaunchResult(false, "Crash in native: ${it.message}") }
    }

    fun stopGame() {
        if (nativeLoaded) runCatching { stopGameNative() }
    }

    fun updateInput(port: Int, buttonMask: Int) {
        if (nativeLoaded) runCatching { updateInputNative(port, buttonMask) }
    }

    fun updateAnalog(port: Int, index: Int, id: Int, value: Int) {
        if (nativeLoaded) runCatching { updateAnalogNative(port, index, id, value) }
    }

    fun updateMouse(buttonMask: Int, dx: Int, dy: Int) {
        if (nativeLoaded) runCatching { updateMouseNative(buttonMask, dx, dy) }
    }

    fun setSurface(surface: android.view.Surface?) {
        if (nativeLoaded) runCatching { setSurfaceNative(surface) }
    }

    fun setCoreDir(dir: String) {
        if (nativeLoaded) runCatching { setCoreDirNative(dir) }
    }

    fun setSystemDir(dir: String) {
        if (nativeLoaded) runCatching { setSystemDirNative(dir) }
    }

    fun setSaveDir(dir: String) {
        if (nativeLoaded) runCatching { setSaveDirNative(dir) }
    }

    fun sendKeyString(text: String) {
        if (nativeLoaded) runCatching { sendKeyStringNative(text) }
    }

    fun sendKeyCode(keycode: Int) {
        if (nativeLoaded) runCatching { sendKeyCodeNative(keycode) }
    }

    fun getNumDisks(): Int =
        if (nativeLoaded) runCatching { getNumDisksNative() }.getOrDefault(0) else 0

    fun getCurrentDiskIndex(): Int =
        if (nativeLoaded) runCatching { getCurrentDiskIndexNative() }.getOrDefault(-1) else -1

    fun setDiskIndex(index: Int): Boolean =
        if (nativeLoaded) runCatching { setDiskIndexNative(index) }.getOrDefault(false) else false

    @JvmStatic private external fun launchGameNative(console: String, romPath: String, cacheDir: String, saveDir: String): String
    @JvmStatic private external fun stopGameNative()
    @JvmStatic private external fun updateInputNative(port: Int, buttonMask: Int)
    @JvmStatic private external fun updateAnalogNative(port: Int, index: Int, id: Int, value: Int)
    @JvmStatic private external fun updateMouseNative(buttonMask: Int, dx: Int, dy: Int)
    @JvmStatic private external fun setSurfaceNative(surface: android.view.Surface?)
    @JvmStatic private external fun setCoreDirNative(coreDir: String)
    @JvmStatic private external fun setSystemDirNative(systemDir: String)
    @JvmStatic private external fun setSaveDirNative(saveDir: String)
    @JvmStatic private external fun sendKeyStringNative(text: String)
    @JvmStatic private external fun sendKeyCodeNative(keycode: Int)
    @JvmStatic private external fun getNumDisksNative(): Int
    @JvmStatic private external fun getCurrentDiskIndexNative(): Int
    @JvmStatic private external fun setDiskIndexNative(index: Int): Boolean
}
