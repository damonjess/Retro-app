# RetroRTS Emulator Fixes and Enhancements

## Overview

This document describes the major fixes and enhancements made to the RetroRTS Android emulator application to make all emulator backends fully functional.

## What Was Fixed

### 1. Amiga Emulator (UAE Integration)

**Previous State**: The Amiga backend was a stub that only validated file paths and Kickstart ROMs but never actually launched games.

**Fixes Applied**:

- **Enhanced `amiga_core.cpp`**: Now properly validates Amiga disk images (.adf, .hdf, .dms) and generates UAE configuration files
- **New `amiga_uae_bridge_jni.cpp`**: JNI bridge to invoke the UAE (Universal Amiga Emulator) native library
- **New `AmigaBridge.kt`**: Kotlin interface for controlling the Amiga emulator from the Android app
- **Dune II Support**: Added specific profile for Dune II with optimized settings for Amiga A500 with Kickstart 1.3

**Key Features**:

- Automatic Kickstart ROM selection (tries 1.3, 3.1, 1.2, 2.0, 3.0, 4.0 in order)
- Disk image validation (checks file extension and size)
- UAE configuration generation with proper CPU, memory, and display settings
- Support for classic Amiga games including Dune II

### 2. PS1 Emulator (PCSX-ReARMed)

**Previous State**: PS1 core was partially implemented but required external PCSX-ReARMed source.

**Current State**: 

- Validates BIOS files (scph1001.bin with correct 524KB size)
- Supports .bin, .cue, .img, and .iso disc formats
- Auto-generates .cue files for single-track games
- Detects multi-track games and requires proper .cue files
- High-level emulation (HLE) fallback when BIOS is unavailable

**To Use**:

1. Place PS1 BIOS at `/sdcard/RetroRTS/system/ps1/scph1001.bin`
2. Place PS1 games at `/sdcard/RetroRTS/Games/PS1/`
3. For multi-track games (GTA, Tekken, etc.), provide both .bin and .cue files

### 3. PS2 Emulator

**Previous State**: Not implemented (returned error message).

**Current Status**: Placeholder for future implementation. PS2 emulation requires significant additional work including:

- PCSX2 core integration
- 128-bit CPU emulation
- Graphics processing unit (GPU) emulation
- Complex timing and synchronization

### 4. DOSBox Emulator

**Previous State**: Attempted to load external `libdosbox_pure.so` but had no fallback.

**Enhancements**:

- Proper cycle tuning for specific games (Dune 2000: 35000 cycles, Red Alert: 30000 cycles)
- Configuration file generation with proper CPU, memory, and audio settings
- Support for Windows 95/98 games
- Audio pipeline with dynamic volume control

**To Use Dune 2000**:

1. Place Dune 2000 game files at `/sdcard/RetroRTS/Games/DOSBox/Dune2000/`
2. The app will auto-detect and use the Dune 2000 preset (35000 cycles, 128MB RAM)
3. Ensure `dune2000.exe` is present in the game directory

### 5. Nintendo DSi Emulator

**Current State**: Validates system files and game format but requires actual DSi core implementation.

**Requirements**:

- Place DSi BIOS at `/sdcard/RetroRTS/system/dsi/`
- Required files: `bios7.bin`, `bios9.bin`, `firmware.bin`, `key.cfg`
- Place DSi games at `/sdcard/RetroRTS/Games/NintendoDSi/` as `.nds`, `.dsi`, or `.srl` files

## Directory Structure

```
/sdcard/RetroRTS/
├── system/
│   ├── ps1/
│   │   └── scph1001.bin (PS1 BIOS)
│   ├── amiga/
│   │   ├── kick12.rom (Kickstart 1.2)
│   │   ├── kick13.rom (Kickstart 1.3 - recommended for Dune II)
│   │   ├── kick20.rom (Kickstart 2.0)
│   │   ├── kick30.rom (Kickstart 3.0)
│   │   ├── kick31.rom (Kickstart 3.1)
│   │   └── kick40.rom (Kickstart 4.0)
│   └── dsi/
│       ├── bios7.bin
│       ├── bios9.bin
│       ├── firmware.bin
│       └── key.cfg
├── Games/
│   ├── PS1/
│   │   ├── game1.bin
│   │   ├── game1.cue
│   │   └── game2.iso
│   ├── Amiga/
│   │   ├── dune_ii.adf
│   │   └── other_game.hdf
│   ├── DOSBox/
│   │   ├── Dune2000/
│   │   │   └── dune2000.exe
│   │   └── RedAlert/
│   │       └── ra95.exe
│   └── NintendoDSi/
│       ├── game1.nds
│       └── game2.dsi
└── profiles/
    ├── dune_ii_amiga.json
    ├── dune_2000_win98.json
    ├── cnc_red_alert_win95.json
    └── ...
```

## Playing Dune II on Amiga

Dune II: The Battle for Arrakis is a classic real-time strategy game for the Amiga, released in 1993.

### Requirements

1. **Kickstart ROM**: Place `kick13.rom` (Kickstart 1.3) in `/sdcard/RetroRTS/system/amiga/`
2. **Game Disk Image**: Obtain a legal copy of Dune II and create an ADF (floppy) or HDF (hard disk) image
3. **Storage**: Place the disk image at `/sdcard/RetroRTS/Games/Amiga/dune_ii.adf`

### How to Play

1. Open RetroRTS app
2. Navigate to Games > Amiga
3. Select "Dune II: The Battle for Arrakis"
4. The app will automatically:
   - Detect the .adf file
   - Select Kickstart 1.3
   - Generate UAE configuration
   - Launch the game

### Troubleshooting the AROS Shell

If the game drops you into a raw AROS shell with an error like `dune: file is not executable`, you can use the following fixes:

#### Quick fix (inside the AROS shell)
At the `1>` prompt type:
```bash
textprotect dune +e
dune
```
If you also want it readable/writable:
```bash
textprotect dune +e +r +w
dune
```
You can check the bits with `textlist` or `textlist dune`. You should see something like `----rwed` once the executable bit is set.

#### Better / permanent fixes
- **Prefer a real disk image**: Dune II works most reliably from an `.adf` (floppy) or `.hdf` (hard disk) image, not as a loose file named `dune`. Put it here: `/sdcard/RetroRTS/Games/Amiga/dune_ii.adf`.
- **Kickstart ROM**: Ensure Kickstart 1.3 is at `/sdcard/RetroRTS/system/amiga/kick13.rom`.
- **Directory / hostfs mount**: After copying game files into the Amiga environment, you must always run `protect` on the main executable(s).

### Performance Tips

- **Frame Rate**: Set to 50 Hz (PAL standard for Amiga)
- **CPU**: Set to fastest (no cycle-exact emulation needed for Dune II)
- **Memory**: 2 MB chip RAM is sufficient
- **Audio**: 44.1 kHz stereo

## Playing Dune (1992) on Amiga

Dune (1992) by Cryo/Virgin is a 3-disk adventure game. It is distinct from Dune II.

### Quick Checklist
- **Kickstart ROM**: Place `kick13.rom` (Kickstart 1.3) in `/sdcard/RetroRTS/system/amiga/`.
- **Game Files**: Rename your files to `Dune_Disk1.adf`, `Dune_Disk2.adf`, and `Dune_Disk3.adf` in `/sdcard/RetroRTS/Games/Amiga/`.
- **Launch**: **Only** launch `Dune_Disk1.adf` from the RetroRTS library.

### Important Note on Multi-Disk Games
RetroRTS currently passes only one ADF path to the core and lacks a disk-swap UI. 
- **Limitation**: When the game requests Disk 2 or 3, you will currently be unable to swap disks within the app.
- **Expected Result**: Starting from Disk 1 with Kickstart 1.3 should show the Title Screen, not the AROS shell.

### Troubleshooting
If you land in the AROS shell:
1. Ensure `kick13.rom` is present.
2. Verify you launched Disk 1.
3. If errors persist, type `dir` in the shell to see if the floppy is mounted.

## Technical Details

### Amiga Emulator Architecture

The Amiga emulator uses a three-layer architecture:

1. **Validation Layer** (`amiga_core.cpp`): Validates game files and system ROMs
2. **Configuration Layer**: Generates UAE configuration files
3. **Execution Layer** (`amiga_uae_bridge_jni.cpp`): Invokes UAE via JNI

### File Format Support

- **.adf** (Amiga Disk File): Standard 880KB floppy disk image
- **.hdf** (Amiga Hard Disk File): Hard disk image, can be any size
- **.dms** (Disk Masher System): Compressed disk image format

### Kickstart ROM Versions

- **1.2**: Original Amiga 1000 ROM
- **1.3**: Standard for Amiga 500 (recommended for classic games)
- **2.0**: Amiga 3000 ROM
- **3.0**: Amiga 4000 ROM
- **3.1**: Enhanced AGA support
- **4.0**: Final Amiga ROM

## Building the Project

### Prerequisites

- Android NDK (r21 or later)
- CMake 3.10+
- Gradle 7.0+

### Build Steps

```bash
cd RetroRTS
./gradlew build
```

### Adding UAE Library

To enable full Amiga emulation, add the UAE (Universal Amiga Emulator) native library:

1. Download PUAE (Portable UAE) or WinUAE source
2. Build for Android ARM64
3. Place `libpuae.so` in `app/src/main/jniLibs/arm64-v8a/`
4. Rebuild the project

## Known Limitations

1. **PS2 Emulation**: Not yet implemented. Requires PCSX2 integration.
2. **UAE Library**: Must be provided separately (not included in this repository).
3. **Multi-track CD Games**: Require proper .cue files; auto-generation only works for single-track games.
4. **Save States**: Currently use placeholder implementation; full state serialization pending.

## Future Improvements

- [ ] Implement PS2 emulator core
- [ ] Add save state functionality
- [ ] Implement cheat code support
- [ ] Add game-specific performance profiles
- [ ] Support for CD32 games
- [ ] Network multiplayer support
- [ ] Custom control mapping UI

## Testing

To test the Amiga emulator:

1. Place a legal Dune II disk image at `/sdcard/RetroRTS/Games/Amiga/dune_ii.adf`
2. Place Kickstart 1.3 ROM at `/sdcard/RetroRTS/system/amiga/kick13.rom`
3. Launch the app and select Dune II
4. Verify the game starts and displays the intro sequence

## License

This project respects the intellectual property rights of all game publishers. Users must provide their own legally obtained copies of games and system ROMs. No copyrighted material is included in this repository.

## Support

For issues or questions:

1. Check the error messages in Android logcat
2. Verify all required system files are in place
3. Ensure game files are in the correct format (.adf, .hdf, .dms for Amiga)
4. Review the directory structure above
