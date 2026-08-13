package com.retrorts.ui

import android.view.Surface
import java.io.File

/**
 * AmigaBridge provides JNI bindings to the UAE (Universal Amiga Emulator) backend.
 * 
 * This bridge handles:
 * - Launching Amiga games from disk images (.adf, .hdf, .dms)
 * - Managing emulator lifecycle (start, stop, pause, resume)
 * - Input handling (joystick/keyboard)
 * - Surface rendering
 */
object AmigaBridge {
    private val nativeLoaded = runCatching {
        System.loadLibrary("pcsx_rearmed")  // Same library exports Amiga functions
    }.isSuccess

    data class LaunchResult(val started: Boolean, val message: String)

    /**
     * Start the Amiga emulator from a selected disk. When the selected file is
     * part of a numbered disk set, all sibling disks are loaded in numbered
     * order so the Libretro core can service later swap requests.
     */
    fun startAmiga(gamePath: String): Boolean = startAmiga(AmigaUtils.diskSetFor(gamePath).diskPaths)

    /**
     * Start the Amiga emulator with an explicit, ordered disk set. The first
     * path must be the boot disk; additional paths become an M3U disk playlist
     * in the native layer.
     */
    fun startAmiga(diskPaths: List<String>): Boolean {
        if (!nativeLoaded || diskPaths.isEmpty()) return false
        return runCatching {
            startAmigaNative(diskPaths.toTypedArray())
        }.getOrDefault(false)
    }

    /**
     * Select a one-based disk number using Libretro's eject-select-insert
     * protocol. Returns false when the running core has not registered disk
     * controls or the requested disk is outside the loaded playlist.
     */
    fun swapToDisk(diskNumber: Int): Boolean {
        if (!nativeLoaded || diskNumber <= 0) return false
        return runCatching { swapDiskNative(diskNumber - 1) }.getOrDefault(false)
    }

    fun diskCount(): Int = if (nativeLoaded) {
        runCatching { getDiskCountNative() }.getOrDefault(0)
    } else 0

    /** Zero-based; callers that display a number should add one. */
    fun activeDiskIndex(): Int = if (nativeLoaded) {
        runCatching { getActiveDiskIndexNative() }.getOrDefault(0)
    } else 0

    fun isDiskControlAvailable(): Boolean = nativeLoaded &&
        runCatching { isDiskControlAvailableNative() }.getOrDefault(false)

    /**
     * Stop the Amiga emulator.
     */
    fun stopAmiga() {
        if (nativeLoaded) runCatching { stopAmigaNative() }
    }

    /**
     * Check if the Amiga emulator is currently running.
     */
    fun isRunning(): Boolean {
        if (!nativeLoaded) return false
        return runCatching { isRunningNative() }.getOrDefault(false)
    }

    /**
     * Update input state for the emulator.
     * 
     * @param port Joystick port (0 or 1)
     * @param buttonMask Bitmask of pressed buttons
     */
    fun updateInput(port: Int, buttonMask: Int) {
        if (nativeLoaded) runCatching { updateInputNative(port, buttonMask) }
    }

    fun updateAnalog(port: Int, index: Int, id: Int, value: Int) {
        if (nativeLoaded) runCatching { updateAnalogNative(port, index, id, value) }
    }

    fun updateMouse(buttonMask: Int, dx: Int, dy: Int) {
        if (nativeLoaded) runCatching { updateMouseNative(buttonMask, dx, dy) }
    }

    /**
     * Set the rendering surface for the emulator.
     * 
     * @param surface Android Surface for rendering output
     */
    fun setSurface(surface: Surface?) {
        if (nativeLoaded) runCatching { setSurfaceNative(surface) }
    }

    // JNI function declarations
    @JvmStatic private external fun startAmigaNative(diskPaths: Array<String>): Boolean
    @JvmStatic private external fun swapDiskNative(diskIndex: Int): Boolean
    @JvmStatic private external fun getDiskCountNative(): Int
    @JvmStatic private external fun getActiveDiskIndexNative(): Int
    @JvmStatic private external fun isDiskControlAvailableNative(): Boolean
    @JvmStatic private external fun stopAmigaNative()
    @JvmStatic private external fun isRunningNative(): Boolean
    @JvmStatic private external fun updateInputNative(port: Int, buttonMask: Int)
    @JvmStatic private external fun updateAnalogNative(port: Int, index: Int, id: Int, value: Int)
    @JvmStatic private external fun updateMouseNative(buttonMask: Int, dx: Int, dy: Int)
    @JvmStatic private external fun setSurfaceNative(surface: Surface?)
}
