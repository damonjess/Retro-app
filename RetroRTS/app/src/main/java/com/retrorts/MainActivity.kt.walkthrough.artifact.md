# Mouse Support Implementation Walkthrough

This update adds mouse support to the PUAE (Amiga) core, enabling playability for mouse-driven games like **Dune** and **Dune II**.

## Changes Made

### Native Bridge (`libretro_bridge.cpp` & `libretro_bridge.h`)
- **Implemented `updateMouse`**: Added a thread-safe way to accumulate mouse deltas and store button states.
- **Enhanced `inputStateCallback`**:
    - Added handling for `RETRO_DEVICE_MOUSE`.
    - **Gamepad-to-Mouse Mapping**: For Amiga cores, face buttons (A, B, X, Y) now trigger a Left Mouse Click, and shoulder buttons (L, R) trigger a Right Mouse Click. This ensures compatibility with games like *Dune* that ignore joystick buttons but respond to mouse clicks.
- **Updated Capabilities**: The host now advertises `RETRO_DEVICE_MOUSE` support to the core during environment callbacks.
- **Updated `libretro.h`**: Added missing `RETRO_DEVICE_ID_MOUSE_*` constants.

### JNI Layer (`amiga_uae_bridge_jni.cpp` & `pcsx_jni_entry.cpp`)
- Added `updateMouseNative` JNI functions to expose mouse control to the Kotlin side for both the specific Amiga bridge and the general emulator bridge.

### Kotlin Bridges (`AmigaBridge.kt` & `NativeEmulatorBridge.kt`)
- Exposed the new `updateMouse` and `updateMouseNative` methods.

### UI Layer (`MainActivity.kt`)
- **Added Touch-to-Mouse Logic**: The `SurfaceView` now detects touch events when an Amiga game is running.
- **Relative Movement**: Touch movements are converted into relative deltas sent to the emulator.
- **Click Simulation**: Touching the screen simulates a left mouse button press, allowing for intuitive point-and-click interactions.

## Verification

To verify:
1. Launch an Amiga game (e.g., Dune II).
2. Drag your finger across the game screen.
3. The in-game cursor should follow your finger.
4. Taps should register as left clicks.

```diff
+ // Example of the new touch handling in MainActivity.kt
+ setOnTouchListener { _, event ->
+     when (event.actionMasked) {
+         MotionEvent.ACTION_DOWN -> {
+             lastX = event.x
+             lastY = event.y
+             NativeEmulatorBridge.updateMouse(1, 0, 0)
+         }
+         // ...
+     }
+ }
```
