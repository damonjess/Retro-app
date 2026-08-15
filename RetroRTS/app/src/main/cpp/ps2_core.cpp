#include "ps2_core.h"

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <string>
#include <system_error>

namespace retrorts::ps2 {
namespace {

std::string toLower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool isSupportedImage(const std::string& path) {
    const std::string lower = toLower(path);
    return lower.ends_with(".iso") || lower.ends_with(".chd") ||
           lower.ends_with(".cso") || lower.ends_with(".bin");
}

} // namespace

Ps2LaunchResult LaunchPs2Game(const std::string& gamePath) {
    if (gamePath.empty()) {
        return {false, "PlayStation 2 launch failed: empty game path", ""};
    }
    if (!isSupportedImage(gamePath)) {
        return {false,
                "PlayStation 2 launch failed: expected a .iso, .chd, .cso, or .bin disc image",
                ""};
    }

    const std::filesystem::path filePath(gamePath);
    std::error_code error;
    if (!std::filesystem::is_regular_file(filePath, error) || error) {
        return {false, "PlayStation 2 launch failed: game file not found: " + gamePath, ""};
    }
    const auto size = std::filesystem::file_size(filePath, error);
    if (error || size == 0) {
        return {false, "PlayStation 2 launch failed: game image is empty or unreadable", ""};
    }

    // Play! uses its own high-level BIOS implementation. The app therefore
    // does not request or distribute proprietary PS2 BIOS firmware.
    return {true, "PlayStation 2 game ready for the Play! core", gamePath};
}

} // namespace retrorts::ps2
