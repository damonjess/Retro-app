# PlayStation 2 / Play! Core Setup

RetroRTS now contains a **PS2 launch path** built around the official [Play! emulator](https://github.com/jpd002/Play-) libretro core. Play! includes high-level PS2 BIOS emulation, so RetroRTS does not need or distribute a proprietary PS2 BIOS file.

## Required core binary

The core binary is not included in this project. Build it from the official Play! source repository, which requires its submodules and the Android NDK. The Play! project documents its Android and libretro builds in its official README and `build_retro/android_build.sh` script.

```bash
git clone --recurse-submodules https://github.com/jpd002/Play-.git
cd Play-/build_retro
export ANDROID_NDK=/path/to/android-ndk
export ANDROID_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
export NINJA_EXE=ninja
./android_build.sh
```

For this arm64-only RetroRTS project, take the generated file:

```text
Play-/build_retro/play_libretro_arm64-v8a_android.so
```

Copy it into the RetroRTS project at the following path and rename it exactly as shown:

```text
RetroRTS/app/src/main/jniLibs/arm64-v8a/libplay_libretro.so
```

The Android Gradle plugin will package this native library with the app. At runtime, RetroRTS will load it as `libplay_libretro.so` and launch PS2 disc images through the same video, audio, save, and controller host already used by the other libretro cores.

## Game locations and formats

Store legally dumped PS2 images under:

```text
/sdcard/RetroRTS/Games/PS2/
```

The integrated launcher accepts `.iso`, `.chd`, `.cso`, and `.bin` image paths. When the library scans an image under a `PS2` directory, it identifies it as a PlayStation 2 game.

## Licensing

The Play! licence text is included at `app/src/main/assets/licenses/PLAY_LICENSE.txt`. If you distribute the Play! core with RetroRTS, retain the required attribution and licence text in the distributed materials.

## Practical expectations

PS2 emulation is substantially more demanding than the existing PS1, DSi, Amiga, and DOSBox paths. Test on a physical arm64 device, begin with a known-compatible game from the Play! compatibility tracker, and expect game-specific performance differences.

## Setup-tab UI integration

This feature build includes `app/src/main/java/com/retrorts/ui/Ps2SetupPanel.kt`. Add the following import to the Compose file that renders the existing **System Setup** tab:

```kotlin
import com.retrorts.ui.Ps2SetupPanel
```

Then place this composable below the existing storage/system-information card:

```kotlin
Ps2SetupPanel(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
)
```

The project dump used for this feature build did not contain the active `MainActivity.kt` source, so this final one-line placement is intentionally left for the existing navigation file in your Android Studio project.
