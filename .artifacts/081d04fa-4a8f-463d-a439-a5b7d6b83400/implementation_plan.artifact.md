# Synchronize Gamepad State to Mouse Button for Amiga

The game "Dune" on Amiga only listens to mouse input. This plan synchronizes gamepad button presses directly into the mouse-button atomic state in the native bridge.

## Proposed Changes

### Native Bridge

#### [MODIFY] [libretro_bridge.cpp](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/cpp/libretro_bridge.cpp)

- Update `updateJoypad` to sync Amiga gamepad buttons to the mouse button atomic.
- Reorder controller port initialization in `loadCore` to occur after `retro_init`.
- Simplify `inputStateCallback` to read mouse button state directly from the synced atomic.

#### [MODIFY] [amiga_uae_bridge_jni.cpp](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/cpp/amiga_uae_bridge_jni.cpp)

- (Verification: `updateMouseNative` already exists, but I will ensure it is correct if needed. Based on `read_file`, it matches the requested snippet).

## Verification Plan

### Automated Tests
- Perform a clean build using Gradle:
  ```bash
  cd RetroRTS
  ./gradlew clean
  ./gradlew assembleDebug
  ```

### Manual Verification
- Deploy the app and launch "Dune".
- Monitor logcat for sync messages:
  ```bash
  adb logcat -s LibretroBridge:D | grep -i "mouse\|Amiga"
  ```
- Verify that pressing face buttons on the gamepad results in "Amiga mouse button sync: DOWN/UP" logs and that the game responds to these clicks.
