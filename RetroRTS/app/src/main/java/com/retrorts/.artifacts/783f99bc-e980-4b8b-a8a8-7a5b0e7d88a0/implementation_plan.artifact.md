# Implementation Plan - Amiga Mouse Control Fix

This plan addresses the issue where the Amiga controller (especially for Dune II) is difficult to use because mouse movement is sluggish or non-existent, and button mappings are confusing.

## User Review Required

> [!IMPORTANT]
> I am moving the primary "Fire" button mapping for the "L" button at the top to `JOYPAD_B` (the standard fire button for Amiga/many retro consoles). In the C++ layer, I will map several joypad buttons to Mouse Left/Right Click to ensure that "Fire" also acts as a click for mouse-driven games like Dune II.

## Proposed Changes

### Native Layer

#### [MODIFY] [libretro_bridge.cpp](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/cpp/libretro_bridge.cpp)
- Enhance `inputStateCallback` for Amiga:
    - Increase Analog stick sensitivity for mouse movement (`/900` instead of `/2500`).
    - Map D-pad buttons to continuous mouse movement (±8 pixels).
    - Map `RETRO_DEVICE_ID_JOYPAD_A`, `B`, `X`, `Y` to `RETRO_DEVICE_ID_MOUSE_LEFT`.
    - Map `RETRO_DEVICE_ID_JOYPAD_L`, `R` to `RETRO_DEVICE_ID_MOUSE_RIGHT`.

---

### UI Components

#### [NEW] [AmigaVirtualController.kt](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/java/com/retrorts/ui/AmigaVirtualController.kt)
- Create a specialized controller for Amiga mouse games:
    - **Trackpad**: A large central area for finger-drag cursor movement and tap-to-click.
    - **Dedicated LMB/RMB Buttons**: Large, easy-to-hold buttons for mouse clicks.
    - **Analog Stick**: Kept for secondary movement.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/java/com/retrorts/MainActivity.kt)
- Update HUD buttons:
    - Change "L" and "R" in the top row from `IconButton` to `GamepadButton` to support holding.
    - Map "L" to `RETRO_DEVICE_ID_JOYPAD_B` (Fire).
- Integrate `AmigaVirtualController`:
    - Display the new specialized controller at the bottom when `consoleType == ConsoleType.AMIGA`.

## Verification Plan

### Manual Verification
1.  Deploy to device.
2.  Launch Dune II (Amiga).
3.  Verify:
    - The top "L" button now fires/clicks and can be held.
    - The trackpad moves the cursor smoothly.
    - The D-pad moves the cursor at a constant speed.
    - Face buttons (A/B/X/Y) perform Left Click.
