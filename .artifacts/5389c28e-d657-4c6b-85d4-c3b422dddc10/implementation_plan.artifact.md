# Multi-Disk Support and Disk Swap UI

This plan implements a disk-swap interface for multi-disk Amiga games like *Dune (1992)*. It involves adding Libretro disk control support to the native bridge, extending the JNI layer, and updating the UI to show disk swap buttons.

## User Review Required

> [!IMPORTANT]
> This change introduces a temporary `.m3u` file generation for multi-disk games to ensure the Libretro core (PUAE) recognizes all disks. This file will be created in the app's cache directory.

## Proposed Changes

### Native Bridge (C++)

#### [MODIFY] [libretro.h](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/cpp/libretro.h)
- Add definitions for `retro_disk_control_callback` and related function pointers.
- Add `RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE` definition.

#### [MODIFY] [libretro_bridge.h](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/cpp/libretro_bridge.h)
- Add `disk_control_` and `disk_control_ext_` members to `LibretroHost`.
- Add `swapDisk(int index)` method.

#### [MODIFY] [libretro_bridge.cpp](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/cpp/libretro_bridge.cpp)
- Handle `RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE` and `RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE` in `envCallback`.
- Implement `LibretroHost::swapDisk(int index)` using the stored callbacks.

#### [MODIFY] [native-lib.cpp](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/cpp/native-lib.cpp)
- Add `Java_com_retrorts_ui_NativeEmulatorBridge_swapDiskNative` JNI function.

---

### Emulator Core Management (C++)

#### [MODIFY] [emulator_core.cpp](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/cpp/emulator_core.cpp)
- Update `LaunchGame` to handle multiple ROM paths.
- If multiple paths are provided for Amiga, create a temporary `.m3u` file and load it.

---

### Android UI (Kotlin)

#### [MODIFY] [NativeEmulatorBridge.kt](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/java/com/retrorts/ui/NativeEmulatorBridge.kt)
- Update `launchGame` to accept `romPaths: List<String>`.
- Add `swapDisk(index: Int)` method.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/java/com/retrorts/MainActivity.kt)
- Update the Amiga overlay logic.
- Replace "Swap UI unavailable" with clickable disk buttons (Disk 1, Disk 2, Disk 3).
- Call `NativeEmulatorBridge.swapDisk(index)` when a button is clicked.

## Verification Plan

### Manual Verification
1. Launch "Dune (1992)" which will now be detected as multi-disk.
2. Verify that Disk 1, Disk 2, and Disk 3 buttons appear in the overlay.
3. Type `dune` in the AmigaDOS shell to start the game.
4. When prompted to swap disks, click the corresponding disk button.
5. Verify the game proceeds correctly.
