#pragma once

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace ComposeMediaPlayer {
namespace NativeLogging {

inline bool IsEnabled() {
    static const bool enabled = [] {
        const char* value = std::getenv("COMPOSE_MEDIA_PLAYER_NATIVE_LOGGING");
        return value != nullptr && value[0] != '\0' &&
               (_stricmp(value, "1") == 0 ||
                _stricmp(value, "true") == 0 ||
                _stricmp(value, "yes") == 0 ||
                _stricmp(value, "on") == 0);
    }();
    return enabled;
}

inline void Logf(const char* format, ...) {
    if (!IsEnabled()) return;

    va_list args;
    va_start(args, format);
    std::vfprintf(stderr, format, args);
    va_end(args);
}

} // namespace NativeLogging
} // namespace ComposeMediaPlayer
