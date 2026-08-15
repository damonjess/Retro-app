#pragma once

#include <string>

namespace retrorts::ps2 {

struct Ps2LaunchResult {
    bool ok;
    std::string message;
    std::string resolvedGamePath;
};

Ps2LaunchResult LaunchPs2Game(const std::string& gamePath);

} // namespace retrorts::ps2
