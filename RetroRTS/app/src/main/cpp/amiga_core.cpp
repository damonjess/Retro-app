#include "amiga_core.h"
#include <fstream>
#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>

#define LOG_TAG "RetroRTS_Amiga"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace retrorts::amiga {

constexpr const char* kBiosDir = "/storage/emulated/0/RetroRTS/system/amiga";

bool fileExists(const std::string& p) {
    return std::ifstream(p, std::ios::binary).good();
}

long fileSize(const std::string& p) {
    std::ifstream f(p, std::ios::ate | std::ios::binary);
    return f.good() ? static_cast<long>(f.tellg()) : -1L;
}

std::string toLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
        [](unsigned char c){ return static_cast<char>(std::tolower(c)); });
    return s;
}

std::string ext(const std::string& path) {
    auto pos = path.rfind('.');
    return pos == std::string::npos ? "" : toLower(path.substr(pos));
}

// Detect which Kickstart ROM version to use based on game requirements
std::string selectKickstartRom(const std::string& gamePath) {
    // Dune II works best with Kickstart 1.3 or 3.1
    // Try in order of preference
    const char* kickstarts[] = {
        "kick13.rom",      // Kickstart 1.3 (most compatible for classic games)
        "kick34005.A500",  // Official PUAE name for 1.3
        "kick31.rom",      // Kickstart 3.1 (AGA support)
        "kick40068.A1200", // Official PUAE name for 3.1
        "kick12.rom",      // Kickstart 1.2
        "kick33180.A500",  // Official PUAE name for 1.2
        "kick20.rom",      // Kickstart 2.0
        "kick37175.A500",  // Official PUAE name for 2.04
        "kick30.rom",      // Kickstart 3.0
        "kick40.rom",      // Kickstart 4.0
        nullptr
    };

    for (int i = 0; kickstarts[i]; i++) {
        std::string biosPath = std::string(kBiosDir) + "/" + kickstarts[i];
        if (fileExists(biosPath)) {
            LOGI("Selected Kickstart: %s", kickstarts[i]);
            return biosPath;
        }
    }

    return "";  // No Kickstart found
}

// Validate that the game file is a valid Amiga disk image
bool isValidAmigaDiskImage(const std::string& path) {
    std::string e = ext(path);
    if (e != ".adf" && e != ".hdf" && e != ".dms") {
        return false;
    }

    // Basic size validation
    long size = fileSize(path);
    if (size < 0) return false;

    // ADF files are typically 880KB (floppy) or larger
    // HDF files can be much larger (hard disk images)
    // DMS files are compressed, so size varies
    if (e == ".adf" && size < 100000) return false;  // Too small for ADF
    if (e == ".hdf" && size < 100000) return false;  // Too small for HDF

    return true;
}

AmigaLaunchResult LaunchAmigaGame(const std::string& romPath) {
    if (romPath.empty())
        return {false, "Amiga launch failed: empty ROM path", "", ""};

    // Validate the game file
    if (!fileExists(romPath)) {
        return {false, "Amiga launch failed: game file not found: " + romPath, "", ""};
    }

    if (!isValidAmigaDiskImage(romPath)) {
        std::string fileName = romPath.substr(romPath.rfind('/') + 1);
        std::string lowerName = toLower(fileName);

        std::string errorMsg = "Amiga launch failed: invalid disk image format. Expected .adf, .hdf, or .dms file.\n";

        if (lowerName == "dune") {
            errorMsg += "\n[DUNE (1992) / DUNE II DETECTED]\n"
                        "It looks like you are trying to launch a loose 'dune' file.\n"
                        "Recommended: Use a proper .adf (floppy) or .hdf (hard disk) image.\n"
                        "Quick Fix: If dropped into the AROS shell, type exactly:\n"
                        "  textprotect dune +e\n"
                        "  dune\n"
                        "(Or use the 'Fix Dune' button in the app overlay)";
        } else if (lowerName.find("dune") != std::string::npos &&
                   (lowerName.find("disk 2") != std::string::npos || lowerName.find("disk 3") != std::string::npos ||
                    lowerName.find("disk2") != std::string::npos || lowerName.find("disk3") != std::string::npos)) {
            errorMsg += "\n[DUNE (1992) DETECTED]\n"
                        "You are attempting to launch Disk 2 or 3.\n"
                        "Please launch Disk 1 (e.g., 'Dune_Disk1.adf') to start the game.";
        } else {
            errorMsg += "For Dune II, use the .adf (floppy) or .hdf (hard disk) version.";
        }

        return {false, errorMsg, "", ""};
    }

    // Find a suitable Kickstart ROM
    std::string biosPath = selectKickstartRom(romPath);
    if (biosPath.empty()) {
        return {false, 
            "Amiga launch failed: No Kickstart ROM found in " + std::string(kBiosDir) + "/\n"
            "Required: kick13.rom, kick31.rom, or other Kickstart versions.\n"
            "Place legally dumped Kickstart ROMs in the system directory.",
            "", ""};
    }

    LOGI("Amiga ready: rom=%s bios=%s", romPath.c_str(), biosPath.c_str());

    return {true, 
            "Amiga core ready for " + romPath + " using " + biosPath,
            romPath, 
            biosPath};
}

}  // namespace retrorts::amiga
