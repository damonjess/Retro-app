#include "emulator_core.h"
#include "dsi_core.h"
#include "ps1_core.h"
#include "ps2_core.h"
#include "amiga_core.h"
#include "xbox_core.h"
#include "libretro_bridge.h"
#include <dlfcn.h>
#include <android/log.h>
#include <algorithm>
#include <fstream>

#define LOG_TAG "LibretroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declaration — real implementation comes from pcsx_core (or stub in pcsx_jni_entry.cpp)
extern "C" int PCSX_Run(const char* bios, const char* disc, const char* saveDir);

namespace retrorts {

namespace {
    bool writeTextFile(const std::string& path, const std::string& content) {
        std::ofstream f(path);
        if (!f) return false;
        f << content;
        return f.good();
    }
}

std::string LaunchGame(const std::string& console,
                       const std::string& romPath,
                       const std::string& cacheDir,
                       const std::string& saveDir) {
    const std::string c = [&]{
        std::string s = console;
        std::transform(s.begin(), s.end(), s.begin(), ::toupper);
        return s;
    }();

    LOGE("LaunchGame: console=%s, romPath=%s", c.c_str(), romPath.c_str());

    // ── PS1 ──────────────────────────────────────────────────────────────
    if (c == "PS1") {
        auto result = retrorts::ps1::LaunchPs1Game(romPath, cacheDir);
        if (!result.ok) return "ERROR: " + result.message;
        int r = PCSX_Run(result.resolvedBiosPath.c_str(), result.resolvedCuePath.c_str(), saveDir.c_str());
        if (r == -10) {
            return "ERROR: PS1 core is not bundled in this build. "
                   "Add the PCSX-ReARMed source tree under app/src/main/cpp/pcsx_rearmed and rebuild.";
        }
        if (r != 0) return "ERROR: PS1 error code " + std::to_string(r);
        return "OK: " + result.message;
    }

    // ── PS2 / Play! ───────────────────────────────────────────────────────
    else if (c == "PS2") {
        auto result = retrorts::ps2::LaunchPs2Game(romPath);
        if (!result.ok) return "ERROR: " + result.message;

        const int status = play_init(result.resolvedGamePath.c_str(), saveDir.c_str());
        if (status == -10) {
            return "ERROR: PlayStation 2 support needs the Play! libretro core. "
                   "Build the official Play! Android libretro target and copy the resulting "
                   "libplay_libretro.so into app/src/main/jniLibs/arm64-v8a/.";
        }
        if (status != 0) return "ERROR: PlayStation 2 core failed with code " + std::to_string(status);
        return "OK: " + result.message;
    }

    // ── DOSBOX ───────────────────────────────────────────────────────────
    else if (c == "DOSBOX" || c == "DOS") {
        std::string lowerRomPath = romPath;
        std::transform(lowerRomPath.begin(), lowerRomPath.end(), lowerRomPath.begin(), [](unsigned char ch) {
            return static_cast<char>(std::tolower(ch));
        });
        if (lowerRomPath.ends_with(".zip") || lowerRomPath.ends_with(".7z") ||
            lowerRomPath.ends_with(".rar")) {
            return "ERROR: DOSBox cannot run a compressed archive. Extract this game first, then "
                   "launch its folder or its .exe/.bat file. For Dune III, extract "
                   "Dune-III_DOS_RU.zip to /storage/emulated/0/RetroRTS/Games/DOSBox/Dune-III/ "
                   "and press Scan again.";
        }

        std::string gameDir = romPath;
        const size_t lastSlash = romPath.rfind('/');
        const size_t lastDot = romPath.rfind('.');
        const std::string ext = lastDot == std::string::npos ? "" : lowerRomPath.substr(lastDot);
        if (lastSlash != std::string::npos &&
            (ext == ".exe" || ext == ".bat" || ext == ".com" || ext == ".conf")) {
            gameDir = romPath.substr(0, lastSlash);
        }

        std::string configPath = cacheDir + "/dosbox_auto.conf";
        std::string config = R"([dosbox]
machine=svga_s3
memsize=16

[cpu]
core=dynamic
# Dune II does not need an aggressive 386DX/33 profile. A modest fixed
# budget leaves Android enough time to drain audio without slowing gameplay.
cycles=fixed 6000

[mixer]
rate=48000
blocksize=512
prebuffer=35

[render]
frameskip=0
aspect=true

[sblaster]
sbtype=sbpro2
sbbase=220
irq=7
dma=1
hdma=5
oplmode=auto
oplemu=default

[speaker]
pcspeaker=true

[joystick]
joysticktype=auto

[autoexec]
@echo off
mount c ")" + gameDir + R"("
c:
set BLASTER=A220 I7 D1 H5 T6
if exist dune2000.exe dune2000.exe
if exist ra95.exe ra95.exe
if exist c&c.exe c&c.exe
if exist play.bat call play.bat
if exist game.exe game.exe
if exist dune dune
if exist dune.exe dune.exe
if exist dune.bat dune.bat
)";

        if (!writeTextFile(configPath, config)) {
            return "ERROR: Failed to write DOSBox config to " + configPath;
        }

        int r = retrorts::dosbox_init(configPath.c_str(), saveDir.c_str());
        if (r != 0) {
            return "ERROR: DOSBox initialization failed via bridge with code " + std::to_string(r);
        }

        LOGI("DOSBox started via bridge: config=%s", configPath.c_str());
        return "OK: DOSBox launching " + romPath + " with config " + configPath;
    }

    // ── AMIGA ────────────────────────────────────────────────────────────
    else if (c == "AMIGA") {
        auto result = retrorts::amiga::LaunchAmigaGame(romPath);
        if (!result.ok) return "ERROR: " + result.message;

        std::string finalRom = result.resolvedRomPath;

        // Multi-disk Dune detection (robust search)
        std::string lowerPath = romPath;
        std::transform(lowerPath.begin(), lowerPath.end(), lowerPath.begin(), ::tolower);

        if (lowerPath.find("dune") != std::string::npos &&
           (lowerPath.find("disk 1") != std::string::npos || lowerPath.find("disk1") != std::string::npos)) {

            std::string dir = romPath.substr(0, romPath.rfind('/') + 1);
            std::string m3uPath = cacheDir + "/dune.m3u";
            std::ofstream f(m3uPath);
            if (f) {
                // Find potential siblings
                auto findDisk = [&](int num) -> std::string {
                    std::string n1 = "Dune_Disk" + std::to_string(num) + ".adf";
                    std::string n2 = "Dune Disk " + std::to_string(num) + ".adf";
                    if (std::ifstream(dir + n1).good()) return dir + n1;
                    if (std::ifstream(dir + n2).good()) return dir + n2;
                    // Fallback to what we have if it's disk 1
                    if (num == 1) return romPath;
                    return "";
                };

                std::string d1 = findDisk(1);
                std::string d2 = findDisk(2);
                std::string d3 = findDisk(3);

                if (!d1.empty()) f << d1 << "\n";
                if (!d2.empty()) f << d2 << "\n";
                if (!d3.empty()) f << d3 << "\n";

                f.close();
                finalRom = m3uPath;
                LOGI("Amiga: Multi-disk Dune detected, created M3U at %s", m3uPath.c_str());
                LOGI("M3U content: 1:%s, 2:%s, 3:%s", d1.c_str(), d2.c_str(), d3.c_str());
            }
        }

        // Pass the ADF or M3U directly to the libretro core.
        // We explicitly tell PUAE which Kickstart to use via core options (puae_kickstart)
        int r = retrorts::uae_init(finalRom.c_str(), result.resolvedBiosPath.c_str());
        if (r != 0) {
            return "ERROR: UAE initialization failed via bridge with code " + std::to_string(r);
        }

        LOGI("Amiga UAE started via bridge: rom=%s", result.resolvedRomPath.c_str());
        return "OK: " + result.message;
    }

    // ── NINTENDO DSi ─────────────────────────────────────────────────────
    else if (c == "NINTENDO_DSI" || c == "DSI") {
        auto result = retrorts::dsi::LaunchDsiGame(romPath);
        if (!result.ok) return "ERROR: " + result.message;

        int r = retrorts::dsi_init(romPath.c_str());
        if (r != 0) {
            return "ERROR: Nintendo DSi core initialization failed with code " + std::to_string(r);
        }

        return "OK: " + result.message;
    }

    // ── XBOX ─────────────────────────────────────────────────────────────
    else if (c == "XBOX") {
        auto result = retrorts::xbox::LaunchXboxGame(romPath);
        if (!result.ok) return "ERROR: " + result.message;

        int r = retrorts::xbox_init(result.resolvedRomPath.c_str(), result.resolvedBiosPath.c_str());
        if (r == -10) {
            return "ERROR: Xbox libretro core (xemu) is not bundled or failed to load. "
                   "Xbox emulation on Android requires a high-performance 64-bit device.";
        }
        if (r != 0) return "ERROR: Xbox initialization failed with code " + std::to_string(r);

        return "OK: " + result.message;
    }

    // ── AUTO-DETECT fallback ─────────────────────────────────────────────
    else {
        const std::string lower = [&]{
            std::string s = romPath;
            std::transform(s.begin(), s.end(), s.begin(), ::tolower);
            return s;
        }();

        if (lower.ends_with(".bin") || lower.ends_with(".cue") ||
            lower.ends_with(".img") || lower.ends_with(".iso") ||
            lower.ends_with(".xbe")) {

            if (lower.ends_with(".xbe") || lower.find("xbox") != std::string::npos) {
                auto result = retrorts::xbox::LaunchXboxGame(romPath);
                if (!result.ok) return "ERROR: " + result.message;
                int r = retrorts::xbox_init(result.resolvedRomPath.c_str(), result.resolvedBiosPath.c_str());
                if (r == -10) return "ERROR: Xbox core not bundled.";
                return r == 0 ? "OK: Xbox auto-detected" : "ERROR: Xbox error " + std::to_string(r);
            }

            bool isPs2 = false;
            if (lower.ends_with(".iso")) {
                FILE* f = fopen(romPath.c_str(), "rb");
                if (f) {
                    fseek(f, 0, SEEK_END);
                    long size = ftell(f);
                    fclose(f);
                    if (size > 700 * 1024 * 1024) isPs2 = true;
                }
            }

            if (isPs2) {
                return "ERROR: PS2 core is not yet implemented. ISO detected as PS2 (size > 700MB).";
            }

            auto result = retrorts::ps1::LaunchPs1Game(romPath, cacheDir);
            if (!result.ok) return "ERROR: " + result.message;
            int r = PCSX_Run(result.resolvedBiosPath.c_str(), result.resolvedCuePath.c_str(), saveDir.c_str());
            if (r == -10) {
                return "ERROR: PS1 core is not bundled in this build. "
                       "Add the PCSX-ReARMed source tree under app/src/main/cpp/pcsx_rearmed and rebuild.";
            }
            return r == 0 ? "OK: PS1 auto-detected"
                          : "ERROR: PS1 error " + std::to_string(r);
        }

        return "ERROR: Unknown console type: " + console;
    }
}

}  // namespace retrorts
