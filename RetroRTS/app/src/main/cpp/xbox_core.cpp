#include "xbox_core.h"
#include "libretro_bridge.h"
#include <algorithm>
#include <fstream>
#include <android/log.h>

#define LOG_TAG "RetroRTS_Xbox"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace retrorts::xbox {

namespace {
    bool fileExists(const std::string& p) {
        return std::ifstream(p, std::ios::binary).good();
    }
}

XboxLaunchResult LaunchXboxGame(const std::string& romPath) {
    if (romPath.empty()) return {false, "Xbox launch failed: empty ROM path", ""};

    // Check BIOS
    const std::string biosDir = "/sdcard/RetroRTS/system/xbox";
    const std::string mcpxPath = biosDir + "/mcpx_1.0.bin";
    const std::string flashPath = biosDir + "/bios.bin";

    bool hasMcpx = fileExists(mcpxPath);
    bool hasFlash = fileExists(flashPath);

    if (!hasMcpx || !hasFlash) {
        std::string missing = "";
        if (!hasMcpx) missing += "mcpx_1.0.bin ";
        if (!hasFlash) missing += "bios.bin ";

        return {false, "Xbox BIOS missing in " + biosDir + ": " + missing +
                       "\nPlease copy your BIOS files to /sdcard/RetroRTS/system/xbox/ on your device.",
                romPath, ""};
    }

    LOGI("Xbox ready: rom=%s bios=%s", romPath.c_str(), mcpxPath.c_str());
    return {true, "Xbox core ready", romPath, mcpxPath};
}

} // namespace retrorts::xbox
