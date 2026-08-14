# DOSBox Analog Stick and Audio Stability Plan

This plan adds an analog stick to the DOSBox virtual gamepad for cursor control and addresses recurring audio stuttering.

## Proposed Changes

### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/StudioProjects/Retro-app/RetroRTS/app/src/main/java/com/retrorts/MainActivity.kt)

#### Virtual Gamepad
- Update `VirtualGamepad` to display an `AnalogStick` when the console is `DOSBOX`.
- In `DosboxPlayScreen`, update `onAnalogMove` to translate analog stick input into mouse movement (`updateMouse`) when playing a DOS game. This provides an alternative way to move the cursor if touch is unreliable.

#### Audio Stability
- In `DosboxPlayScreen`'s `LaunchedEffect(Unit)`, explicitly call `DosboxBridge.setFrameCap(60)` to lock the emulator to 60Hz. This helps prevent audio buffer desync that causes "coming and going" sound.
- Also call `DosboxBridge.setVolume(settings.volume)` to ensure the user's volume preference is applied to the native engine.

## Verification Plan

### Manual Verification
- **Analog Stick**: Launch a DOS game and verify that the left analog stick moves the mouse cursor smoothly.
- **Audio**: Listen to the game audio for at least 60 seconds to ensure it remains stable and continuous without stuttering.
- **Volume**: Change the volume in settings and verify that it affects the game audio in `DosboxPlayScreen`.
