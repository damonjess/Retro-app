#include "ps1_core.h"

#include <algorithm>
#include <cctype>
#include <dirent.h>
#include <fstream>
#include <sstream>
#include <string>
#include <android/log.h>

#define LOG_TAG "RetroRTS_PS1"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace retrorts::ps1 {
namespace {

constexpr const char* kBiosDir = "/sdcard/RetroRTS/system/ps1";
constexpr const char* kKnownBiosFiles[] = {
    "PSXONPSP660.bin", "scph101.bin", "scph7001.bin", "scph5501.bin", "scph1001.bin"
};

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

bool ensureCoreBiosName(const std::string& sourcePath, const char* coreFileName) {
    const std::string targetPath = std::string(kBiosDir) + "/" + coreFileName;
    if (sourcePath == targetPath || (fileExists(targetPath) && fileSize(targetPath) == 524288L)) {
        return true;
    }

    // The UI accepts upper-case filenames (e.g. SCPH1001.BIN), while the
    // bundled PCSX-ReARMed core searches conventional lower-case names.
    std::ifstream source(sourcePath, std::ios::binary);
    std::ofstream target(targetPath, std::ios::binary | std::ios::trunc);
    if (!source.good() || !target.good()) return false;
    target << source.rdbuf();
    return target.good() && fileSize(targetPath) == 524288L;
}

std::string findValidBios() {
    // Keep the conventional lower-case fast path.
    for (const char* filename : kKnownBiosFiles) {
        const std::string candidate = std::string(kBiosDir) + "/" + filename;
        if (fileExists(candidate) && fileSize(candidate) == 524288L) return candidate;
    }

    // Android shared storage is case-sensitive. The BIOS screen can show an
    // uppercase SCPH1001.BIN, so match known retail names case-insensitively.
    DIR* directory = opendir(kBiosDir);
    if (!directory) return "";
    std::string result;
    while (const dirent* entry = readdir(directory)) {
        const std::string actualName(entry->d_name);
        const std::string lowerName = toLower(actualName);
        for (const char* filename : kKnownBiosFiles) {
            if (lowerName == filename) {
                const std::string candidate = std::string(kBiosDir) + "/" + actualName;
                if (fileSize(candidate) == 524288L) {
                    if (ensureCoreBiosName(candidate, filename)) {
                        result = std::string(kBiosDir) + "/" + filename;
                    } else {
                        // Continue with the original path for diagnostics; the
                        // core may still discover it on filesystems that ignore case.
                        result = candidate;
                    }
                    break;
                }
            }
        }
        if (!result.empty()) break;
    }
    closedir(directory);
    return result;
}

bool IsKnownMultiTrackGame(const std::string& path) {
    std::string lower = toLower(path);
    // Add other known multi-track titles as needed
    return lower.find("gta2") != std::string::npos ||
           lower.find("gta 2") != std::string::npos ||
           lower.find("tekken") != std::string::npos ||
           lower.find("wipeout") != std::string::npos ||
           lower.find("gran turismo") != std::string::npos;
}

std::string ext(const std::string& path) {
    auto pos = path.rfind('.');
    return pos == std::string::npos ? "" : toLower(path.substr(pos));
}

static std::string generateCue(const std::string& binPath,
                                const std::string& cacheDir) {
    std::string filename = binPath.substr(binPath.rfind('/') + 1);

    // First check: does a .cue already exist next to the .bin?
    std::string sideBySideCue =
        binPath.substr(0, binPath.rfind('.')) + ".cue";
    if (fileExists(sideBySideCue)) {
        LOGI("Using existing cue: %s", sideBySideCue.c_str());
        return sideBySideCue;
    }

    // 2. REFUSE to auto-generate for known multi-track games
    if (IsKnownMultiTrackGame(binPath)) {
        LOGE("Refusing auto-cue for known multi-track game: %s", filename.c_str());
        return "";  // triggers the "need a real .cue" error
    }

    // 3. Safe to generate a single-track cue for everything else
    std::string cueName = filename.substr(0, filename.rfind('.')) + ".cue";
    std::string cuePath = cacheDir + "/" + cueName;

    std::ifstream existing(cuePath);
    if (existing.good()) {
        std::string line;
        while (std::getline(existing, line)) {
            if (line.find(filename) != std::string::npos) return cuePath;
        }
    }
    existing.close();

    std::ofstream cue(cuePath);
    if (!cue.good()) return "";

    cue << "FILE \"" << binPath << "\" BINARY\n"
        << "  TRACK 01 MODE2/2352\n"
        << "    INDEX 01 00:00:00\n";
    cue.close();
    LOGI("Generated single-track cue: %s", cuePath.c_str());
    return cuePath;
}

}  // namespace

Ps1LaunchResult LaunchPs1Game(const std::string& discPath, const std::string& cacheDir) {
    if (discPath.empty())
        return {false, "PS1 launch failed: empty disc path", ""};

    const std::string e = ext(discPath);

    // ── Validate extension ───────────────────────────────────────────────
    if (e != ".bin" && e != ".cue" && e != ".img" && e != ".iso")
        return {false, "PS1 launch failed: expected .bin, .cue, .img, or .iso — got: " + discPath, ""};

    // ── Check disc file exists ───────────────────────────────────────────
    if (!fileExists(discPath))
        return {false, "PS1 launch failed: disc file not found: " + discPath, ""};

    // ── Check BIOS ───────────────────────────────────────────────────────
    // PCSX-ReARMed supports several standard retail BIOS names. Restrict the
    // test to 512 KiB images so corrupt files cannot be mistaken for firmware.
    const std::string biosPath = findValidBios();
    const bool hasBios = !biosPath.empty();
    if (!hasBios) {
        LOGE("No valid retail PS1 BIOS found; GTA2 will use HLE and may be unstable");
    }

    // ── Resolve to .cue (generate one if only .bin provided) ────────────
    std::string cuePath;
    if (e == ".cue") {
        cuePath = discPath;
        // Make sure the .bin it references exists (basic check)
        std::ifstream cueFile(discPath);
        if (!cueFile.good())
            return {false, "PS1 launch failed: cannot read .cue file", ""};

        int referencedFiles = 0;
        int declaredTracks = 0;
        std::string line;
        while (std::getline(cueFile, line)) {
            const std::string lowerLine = toLower(line);
            if (lowerLine.find("track ") != std::string::npos) ++declaredTracks;
            if (lowerLine.find("file") != std::string::npos) {
                // Extract filename/path from: FILE "name.bin" BINARY.
                const auto q1 = line.find('"');
                const auto q2 = line.rfind('"');
                if (q1 == std::string::npos || q2 <= q1)
                    return {false, "PS1 launch failed: malformed FILE entry in .cue", ""};

                const std::string binRef = line.substr(q1 + 1, q2 - q1 - 1);
                const std::string dir = discPath.substr(0, discPath.rfind('/') + 1);
                const std::string resolvedRef =
                    (binRef.find('/') == std::string::npos) ? dir + binRef : binRef;
                if (!fileExists(resolvedRef))
                    return {false, "PS1 launch failed: .cue references missing file: " + binRef, ""};
                ++referencedFiles;
            }
        }
        if (referencedFiles == 0 || declaredTracks == 0)
            return {false, "PS1 launch failed: .cue contains no valid FILE/TRACK entries", ""};
        if (IsKnownMultiTrackGame(discPath) && declaredTracks < 2) {
            LOGE("GTA2 cue has only one track; game audio/boot may be incomplete: %s", discPath.c_str());
        }
    } else if (e == ".bin" || e == ".img") {
        cuePath = generateCue(discPath, cacheDir);
        if (cuePath.empty())
            return {false,
                "GTA2 and other multi-track PS1 games need a real "
                ".cue file alongside the .bin.\n\n"
                "On your PC, right-click the game in ImgBurn or "
                "use CDRDAO to extract a proper .cue, then copy "
                "BOTH files to /sdcard/RetroRTS/Games/PS1/",
                "", ""};
    } else {
        // .iso — pass directly, PCSX-ReARMed accepts ISO
        cuePath = discPath;
    }

    LOGI("PS1 ready: disc=%s bios=%s", cuePath.c_str(), hasBios ? biosPath.c_str() : "HLE");
    return {true, "PS1 core ready — " + cuePath, cuePath, hasBios ? biosPath : "HLE"};
}

}  // namespace retrorts::ps1
