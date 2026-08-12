#include "emulator_core.h"
#include "dsi_core.h"
#include "ps1_core.h"
#include "amiga_core.h"
#include "xbox_core.h"
#include "libretro_bridge.h"
#include <dlfcn.h>
#include <android/log.h>
#include <algorithm>
#include <fstream>

#define LOG_TAG "RetroRTS_Core"
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

    // ── PS2 ──────────────────────────────────────────────────────────────
    else if (c == "PS2") {
        return "ERROR: PS2 core is not yet implemented. "
               "A real PS2 emulator requires PCSX2 integration (128-bit CPU, GS emulation, VU0/VU1). "
               "This is planned for a future release.";
    }

    // ── DOSBOX ───────────────────────────────────────────────────────────
    else if (c == "DOSBOX" || c == "DOS") {
        std::string gameDir = romPath;
        size_t lastSlash = romPath.rfind('/');
        if (lastSlash != std::string::npos) {
            std::string ext = romPath.substr(romPath.rfind('.'));
            if (ext == ".exe" || ext == ".bat" || ext == ".com" || ext == ".conf") {
                gameDir = romPath.substr(0, lastSlash);
            }
        }

        std::string configPath = cacheDir + "/dosbox_auto.conf";
        std::string config = R"([dosbox]
machine=svga_s3
memsize=128

[cpu]
core=dynamic
cycles=auto 30000 80% limit 40000

[render]
frameskip=0
aspect=true

[sblaster]
sbtype=sb16
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

        // Pass the ADF directly to the libretro core.
        // We explicitly tell PUAE which Kickstart to use via core options (puae_kickstart)
        int r = retrorts::uae_init(result.resolvedRomPath.c_str(), result.resolvedBiosPath.c_str());
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
