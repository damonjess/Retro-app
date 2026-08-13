#pragma once

#include <string>
#include <atomic>
#include <mutex>
#include <vector>
#include <android/native_window.h>
#include <aaudio/AAudio.h>
#include "libretro.h"

namespace retrorts {

enum class CoreType { NONE, PS1, AMIGA, DOSBOX, NINTENDO_DSI, XBOX };

class LibretroHost {
public:
    static LibretroHost& getInstance();

    int loadCore(const std::string& corePath);
    int loadGame(const std::string& romPath);
    void runLoop();
    void stop();

    void sendKeyString(const std::string& text);
    void updateJoypad(int port, uint16_t state);
    void updateMouse(int buttonMask, int16_t dx, int16_t dy);

    void setWindow(ANativeWindow* window);

    void setSystemDir(const std::string& dir) { systemDir_ = dir; }
    void setSaveDir(const std::string& dir) { saveDir_ = dir; }
    void setCoreType(CoreType type) { coreType_ = type; }
    void setAmigaKickstart(const std::string& filename) { amigaKickstart_ = filename; }

    static bool envCallback(unsigned cmd, void* data);
    static void videoCallback(const void* data, unsigned width, unsigned height, size_t pitch);
    static void audioCallback(int16_t left, int16_t right);
    static size_t audioBatchCallback(const int16_t* data, size_t frames);
    static void inputPollCallback();
    static int16_t inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id);

private:
    LibretroHost();
    ~LibretroHost();

    void* coreLib_ = nullptr;
    ANativeWindow* window_ = nullptr;
    std::atomic<bool> running_{false};
    std::atomic<uint16_t> padState_[2]{0, 0};
    std::atomic<bool> keyState_[512]{false}; // RETROK_LAST is usually around 320
    std::recursive_mutex coreMutex_;

    std::string systemDir_;
    std::string saveDir_;
    std::string amigaKickstart_;
    double lastSampleRate_ = 44100.0;
    std::atomic<uint16_t> mouseButtons_{0};
    std::atomic<int64_t> mouseHoldUntil_{0};
    std::atomic<int16_t> mouseX_{0};
    std::atomic<int16_t> mouseY_{0};
    CoreType coreType_ = CoreType::NONE;
    retro_keyboard_event_t keyboard_cb_ = nullptr;

    struct KeyboardEvent {
        bool down;
        unsigned keycode;
        uint32_t character;
    };
    std::vector<KeyboardEvent> keyEventQueue_;
    std::mutex queueMutex_;

    AAudioStream* audioStream_ = nullptr;
    bool initAudio(double sampleRate);
    void deinitAudio();

    void (*retro_init_fn)() = nullptr;
    void (*retro_deinit_fn)() = nullptr;
    void (*retro_run_fn)() = nullptr;
    void (*retro_get_system_av_info_fn)(struct retro_system_av_info*) = nullptr;
    bool (*retro_load_game_fn)(const struct retro_game_info*) = nullptr;
    void (*retro_unload_game_fn)() = nullptr;
    void (*retro_set_environment_fn)(retro_environment_t) = nullptr;
    void (*retro_set_video_refresh_fn)(retro_video_refresh_t) = nullptr;
    void (*retro_set_audio_sample_fn)(retro_audio_sample_t) = nullptr;
    void (*retro_set_audio_sample_batch_fn)(retro_audio_sample_batch_t) = nullptr;
    void (*retro_set_input_poll_fn)(retro_input_poll_t) = nullptr;
    void (*retro_set_input_state_fn)(retro_input_state_t) = nullptr;
    void (*retro_set_controller_port_device_fn)(unsigned port, unsigned device) = nullptr;
};

extern "C" {
    int PCSX_Run(const char* bios, const char* disc, const char* saveDir);
    int uae_init(const char* rom_path, const char* bios_path);
    int dosbox_init(const char* config_path, const char* saveDir);
    int dsi_init(const char* rom_path);
    int xbox_init(const char* rom_path, const char* bios_path);
}

} // namespace retrorts
