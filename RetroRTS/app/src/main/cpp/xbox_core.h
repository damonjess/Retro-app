#pragma once
#include <string>

namespace retrorts::xbox {

struct XboxLaunchResult {
    bool        ok;
    std::string message;
    std::string resolvedRomPath;
    std::string resolvedBiosPath;
};

XboxLaunchResult LaunchXboxGame(const std::string& romPath);

}  // namespace retrorts::xbox
