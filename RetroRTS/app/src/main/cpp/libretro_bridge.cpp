#include "libretro_bridge.h"
#include <dlfcn.h>
#include <android/log.h>
#include <thread>
#include <chrono>
#include <android/native_window_jni.h>
#include <algorithm>
#include <cstdarg>
#include <set>
#include <cstdlib>
#include <sys/mman.h>
#include "pcsx_rearmed/frontend/plugin_lib.h"

#define LOG_TAG "LibretroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace retrorts {

static void dummy_vblank(int, int) {}

static void bridge_get_layer_pos(int *x, int *y, int *w, int *h) {}
static int bridge_vout_open(void) { return 0; }
static void bridge_vout_set_mode(int w, int h, int raw_w, int raw_h, int bpp) {}
static void bridge_vout_flip(const void *vram, int vram_offset, int bgr24,
    int x, int y, int w, int h, int dims_changed) {}
static void bridge_vout_close(void) {}
static void bridge_vout_set_raw_vram(void *vram) {}
static void bridge_cspace_blit(void *dst, const void *src, int bytes) {}
static void *bridge_mmap(unsigned int size) {
    void *ptr = malloc(size);
    if (!ptr) return MAP_FAILED;
    // The core expects a pointer with some alignment; malloc gives 8‑byte aligned which is fine.
    return ptr;
}
static void bridge_munmap(void *ptr, unsigned int size) {
    free(ptr);
}
static void bridge_pl_set_gpu_caps(int caps) {}
static void bridge_gpu_state_change(int what, int cycles) {}

static void libretroLog(enum retro_log_level level, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int androidLevel = ANDROID_LOG_INFO;
    switch(level) {
        case RETRO_LOG_DEBUG: androidLevel = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO:  androidLevel = ANDROID_LOG_INFO;  break;
        case RETRO_LOG_WARN:  androidLevel = ANDROID_LOG_WARN;  break;
        case RETRO_LOG_ERROR: androidLevel = ANDROID_LOG_ERROR; break;
    }
    __android_log_vprint(androidLevel, "LibretroCore", fmt, args);
    va_end(args);
}

LibretroHost& LibretroHost::getInstance() {
    static LibretroHost instance;
    return instance;
}

LibretroHost::LibretroHost() = default;

bool LibretroHost::initAudio(double sampleRate) {
    deinitAudio();
    lastSampleRate_ = sampleRate;
    LOGI("Initializing AAudio at %.2f Hz", sampleRate);

    AAudioStreamBuilder* builder;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return false;

    AAudioStreamBuilder_setSampleRate(builder, (int32_t)sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);

    result = AAudioStreamBuilder_openStream(builder, &audioStream_);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGE("Failed to open AAudio stream: %s", AAudio_convertResultToText(result));
        return false;
    }

    // Increased buffer size to the full capacity to handle jitter better.
    int32_t capacity = AAudioStream_getBufferCapacityInFrames(audioStream_);
    AAudioStream_setBufferSizeInFrames(audioStream_, capacity);
    LOGI("AAudio stream opened: capacity=%d, bufferSize=%d", capacity, AAudioStream_getBufferSizeInFrames(audioStream_));

    result = AAudioStream_requestStart(audioStream_);
    if (result != AAUDIO_OK) {
        LOGE("Failed to start AAudio stream: %s", AAudio_convertResultToText(result));
        AAudioStream_close(audioStream_);
        audioStream_ = nullptr;
        return false;
    }

    return true;
}

void LibretroHost::deinitAudio() {
    if (audioStream_) {
        AAudioStream_requestStop(audioStream_);
        AAudioStream_close(audioStream_);
        audioStream_ = nullptr;
    }
}

LibretroHost::~LibretroHost() {
    stop();
}

int LibretroHost::loadCore(const std::string& corePath) {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    if (coreLib_) {
        dlclose(coreLib_);
        coreLib_ = nullptr;
    }

    LOGI("Loading core: %s", corePath.c_str());
    coreLib_ = dlopen(corePath.c_str(), RTLD_NOW);
    if (!coreLib_) {
        LOGI("Core %s not found by name, attempting self-load", corePath.c_str());
        coreLib_ = dlopen(NULL, RTLD_NOW);
    }

    if (!coreLib_) {
        LOGE("Failed to load core %s: %s", corePath.c_str(), dlerror());
        return -1;
    }

    retro_init_fn = (void (*)())dlsym(coreLib_, "retro_init");
    retro_deinit_fn = (void (*)())dlsym(coreLib_, "retro_deinit");
    retro_run_fn = (void (*)())dlsym(coreLib_, "retro_run");
    retro_get_system_av_info_fn = (void (*)(struct retro_system_av_info*))dlsym(coreLib_, "retro_get_system_av_info");
    retro_load_game_fn = (bool (*)(const struct retro_game_info*))dlsym(coreLib_, "retro_load_game");
    retro_unload_game_fn = (void (*)())dlsym(coreLib_, "retro_unload_game");
    retro_set_environment_fn = (void (*)(retro_environment_t))dlsym(coreLib_, "retro_set_environment");
    retro_set_video_refresh_fn = (void (*)(retro_video_refresh_t))dlsym(coreLib_, "retro_set_video_refresh");
    retro_set_audio_sample_fn = (void (*)(retro_audio_sample_t))dlsym(coreLib_, "retro_set_audio_sample");
    retro_set_audio_sample_batch_fn = (void (*)(retro_audio_sample_batch_t))dlsym(coreLib_, "retro_set_audio_sample_batch");
    retro_set_input_poll_fn = (void (*)(retro_input_poll_t))dlsym(coreLib_, "retro_set_input_poll");
    retro_set_input_state_fn = (void (*)(retro_input_state_t))dlsym(coreLib_, "retro_set_input_state");
    retro_set_controller_port_device_fn = (void (*)(unsigned, unsigned))dlsym(coreLib_, "retro_set_controller_port_device");

    if (!retro_init_fn) LOGE("Missing symbol: retro_init");
    if (!retro_run_fn) LOGE("Missing symbol: retro_run");
    if (!retro_load_game_fn) LOGE("Missing symbol: retro_load_game");
    if (!retro_set_environment_fn) LOGE("Missing symbol: retro_set_environment");
    if (!retro_get_system_av_info_fn) LOGE("Missing symbol: retro_get_system_av_info");
    if (!retro_set_video_refresh_fn) LOGE("Missing symbol: retro_set_video_refresh");
    if (!retro_set_audio_sample_fn) LOGE("Missing symbol: retro_set_audio_sample");
    if (!retro_set_audio_sample_batch_fn) LOGE("Missing symbol: retro_set_audio_sample_batch");
    if (!retro_set_input_poll_fn) LOGE("Missing symbol: retro_set_input_poll");
    if (!retro_set_input_state_fn) LOGE("Missing symbol: retro_set_input_state");

    if (!retro_init_fn || !retro_run_fn || !retro_load_game_fn || !retro_set_environment_fn ||
        !retro_set_video_refresh_fn || !retro_set_audio_sample_fn || !retro_set_audio_sample_batch_fn ||
        !retro_set_input_poll_fn || !retro_set_input_state_fn || !retro_get_system_av_info_fn) {
        LOGE("Core is missing essential symbols");
        dlclose(coreLib_);
        coreLib_ = nullptr;
        return -2;
    }

    retro_set_environment_fn(envCallback);
    retro_set_video_refresh_fn(videoCallback);
    retro_set_audio_sample_fn(audioCallback);
    retro_set_audio_sample_batch_fn(audioBatchCallback);
    retro_set_input_poll_fn(inputPollCallback);
    retro_set_input_state_fn(inputStateCallback);

    retro_init_fn();

    LOGI("Core initialized");

    if (retro_set_controller_port_device_fn) {
        LOGI("Setting controller port devices to RETRO_DEVICE_JOYPAD");
        retro_set_controller_port_device_fn(0, RETRO_DEVICE_JOYPAD);
        retro_set_controller_port_device_fn(1, RETRO_DEVICE_JOYPAD);
    }

    keyboard_cb_ = nullptr;
    return 0;
}

void LibretroHost::sendKeyString(const std::string& text) {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    LOGI("sendKeyString: Sending '%s' (cb=%p)", text.c_str(), keyboard_cb_);

    for (char c : text) {
        unsigned keycode = RETROK_UNKNOWN;
        uint32_t character = static_cast<uint32_t>(c);

        if (c >= 'a' && c <= 'z') keycode = RETROK_a + (c - 'a');
        else if (c >= 'A' && c <= 'Z') keycode = RETROK_a + (c - 'A'); // libretro keys are lowercase
        else if (c >= '0' && c <= '9') keycode = RETROK_0 + (c - '0');
        else if (c == ' ') keycode = RETROK_SPACE;
        else if (c == '\n' || c == '\r') keycode = RETROK_RETURN;
        else if (c == '.') keycode = RETROK_PERIOD;
        else if (c == '/') keycode = RETROK_SLASH;
        else if (c == '+') keycode = RETROK_PLUS;
        else if (c == '-') keycode = RETROK_MINUS;
        else if (c == '_') keycode = RETROK_UNDERSCORE;
        else if (c == ':') keycode = RETROK_COLON;
        else if (c == ';') keycode = RETROK_SEMICOLON;

        if (keycode != RETROK_UNKNOWN) {
            std::lock_guard<std::mutex> qlock(queueMutex_);
            keyEventQueue_.push_back({true, keycode, character});
            keyEventQueue_.push_back({false, keycode, character});
        }
    }
}

void LibretroHost::updateJoypad(int port, uint16_t state) {
    if (port >= 0 && port < 2) {
        padState_[port].store(state);
    }
}

int LibretroHost::loadGame(const std::string& romPath) {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    if (!coreLib_ || !retro_load_game_fn) return -1;

    struct retro_game_info game = {romPath.c_str(), nullptr, 0, nullptr};
    if (!retro_load_game_fn(&game)) {
        LOGE("Failed to load game: %s", romPath.c_str());
        return -2;
    }

    struct retro_system_av_info av_info;
    retro_get_system_av_info_fn(&av_info);
    initAudio(av_info.timing.sample_rate);

    running_.store(true);
    LOGI("Game loaded: %s", romPath.c_str());
    return 0;
}

void LibretroHost::runLoop() {
    LOGI("runLoop: started");
    int frames = 0;
    while (running_.load()) {
        {
            std::lock_guard<std::recursive_mutex> lock(coreMutex_);

            // Process keyboard queue: 1 event per frame to ensure "down" time
            {
                std::lock_guard<std::mutex> qlock(queueMutex_);
                if (!keyEventQueue_.empty()) {
                    auto& ev = keyEventQueue_.front();
                    if (ev.keycode < 512) {
                        keyState_[ev.keycode].store(ev.down);
                    }
                    if (keyboard_cb_) {
                        keyboard_cb_(ev.down, ev.keycode, ev.character, 0);
                    }
                    keyEventQueue_.erase(keyEventQueue_.begin());
                }
            }

            if (retro_run_fn) retro_run_fn();
        }
        // Removed sleep_for(1). The audio write in audioBatchCallback
        // will block when the buffer is full, providing natural sync.

        if (++frames == 1 || frames % 3000 == 0) {
            LOGI("runLoop: still running (%d frames)", frames);
        }
    }
    LOGI("runLoop: exited");
}

void LibretroHost::setWindow(ANativeWindow* window) {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    if (window_) ANativeWindow_release(window_);
    window_ = window;
    if (window_) {
        ANativeWindow_acquire(window_);
        ANativeWindow_setBuffersGeometry(window_, 0, 0, WINDOW_FORMAT_RGBX_8888);
        LOGI("Window acquired: %p", window_);
    } else {
        LOGI("Window cleared");
    }
}

void LibretroHost::stop() {
    running_.store(false);
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    deinitAudio();
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    if (coreLib_) {
        if (retro_unload_game_fn) retro_unload_game_fn();
        if (retro_deinit_fn) retro_deinit_fn();
        dlclose(coreLib_);
        coreLib_ = nullptr;
    }
    LOGI("Host stopped");
}

bool LibretroHost::envCallback(unsigned cmd, void* data) {
    auto& host = getInstance();
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool*)data = true;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK: {
            LOGI("envCallback: Core requested RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK");
            const struct retro_keyboard_callback *cb = (const struct retro_keyboard_callback*)data;
            host.keyboard_cb_ = cb->callback;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES: {
            LOGI("envCallback: Core requested RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES");
            uint64_t *mask = (uint64_t*)data;
            *mask = (1ULL << RETRO_DEVICE_JOYPAD) | (1ULL << RETRO_DEVICE_KEYBOARD);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto* cb = reinterpret_cast<struct retro_log_callback*>(data);
            cb->log = libretroLog;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **)data = host.systemDir_.empty() ? nullptr : host.systemDir_.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **)data = host.saveDir_.empty() ? nullptr : host.saveDir_.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = static_cast<struct retro_variable*>(data);
            if (!var || !var->key) return false;

            std::string key = var->key;
            if (host.coreType_ == CoreType::AMIGA) {
                if (key == "puae_model") {
                    var->value = "A500";
                    return true;
                }
                if (key == "puae_video_standard") {
                    var->value = "PAL";
                    return true;
                }
                if (key == "puae_kickstart") {
                    if (!host.amigaKickstart_.empty()) {
                        var->value = host.amigaKickstart_.c_str();
                        return true;
                    }
                }
            }
            // For other variables, return false so the core uses defaults
            return false;
        }
        default:
            static std::set<unsigned> logged_cmds;
            if (logged_cmds.find(cmd) == logged_cmds.end()) {
                LOGI("envCallback: Core requested unknown cmd %u", cmd);
                logged_cmds.insert(cmd);
            }
            return false;
    }
}

void LibretroHost::videoCallback(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;
    auto& host = getInstance();
    std::lock_guard<std::recursive_mutex> lock(host.coreMutex_);
    if (!host.window_) {
        static bool logged = false;
        if (!logged) {
            LOGI("videoCallback: no window — waiting for Surface");
            logged = true;
        }
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(host.window_, &buffer, nullptr) != 0) return;

    auto* dst = static_cast<uint32_t*>(buffer.bits);
    auto* src = static_cast<const uint16_t*>(data);

    int dstW = buffer.width;
    int dstH = buffer.height;
    int srcW = static_cast<int>(width);
    int srcH = static_cast<int>(height);

    // 1. Clear entire surface to black
    for (int y = 0; y < dstH; y++) {
        uint32_t* row = dst + y * buffer.stride;
        for (int x = 0; x < dstW; x++) {
            row[x] = 0xFF000000;
        }
    }

    // 2. Calculate aspect-ratio-preserving scale
    float scaleX = static_cast<float>(dstW) / static_cast<float>(srcW);
    float scaleY = static_cast<float>(dstH) / static_cast<float>(srcH);
    float scale = std::min(scaleX, scaleY);   // use std::max if you want stretch-to-fill

    int drawW = static_cast<int>(static_cast<float>(srcW) * scale);
    int drawH = static_cast<int>(static_cast<float>(srcH) * scale);
    int offsetX = (dstW - drawW) / 2;
    int offsetY = (dstH - drawH) / 2;

    // 3. Nearest-neighbor blit
    for (int y = 0; y < drawH; y++) {
        int srcY = static_cast<int>(static_cast<float>(y) / scale);
        if (srcY >= srcH) srcY = srcH - 1;
        const uint16_t* src_row = src + srcY * (pitch / 2);
        uint32_t* dst_row = dst + (y + offsetY) * buffer.stride + offsetX;

        for (int x = 0; x < drawW; x++) {
            int srcX = static_cast<int>(static_cast<float>(x) / scale);
            if (srcX >= srcW) srcX = srcW - 1;
            uint16_t px = src_row[srcX];

            // RGB565 -> RGBX8888
            uint8_t r = (px >> 11) << 3;
            uint8_t g = ((px >> 5) & 0x3F) << 2;
            uint8_t b = (px & 0x1F) << 3;
            dst_row[x] = (0xFFu << 24) | (b << 16) | (g << 8) | r;
        }
    }

    ANativeWindow_unlockAndPost(host.window_);

    static bool loggedFirst = false;
    if (!loggedFirst) {
        LOGI("videoCallback: first frame blitted (%dx%d -> %dx%d, scale=%.2f)",
             srcW, srcH, dstW, dstH, scale);
        loggedFirst = true;
    }
}

void LibretroHost::audioCallback(int16_t left, int16_t right) {
    int16_t samples[2] = {left, right};
    audioBatchCallback(samples, 1);
}

size_t LibretroHost::audioBatchCallback(const int16_t* data, size_t frames) {
    auto& host = getInstance();
    if (host.audioStream_) {
        aaudio_stream_state_t state = AAudioStream_getState(host.audioStream_);
        if (state == AAUDIO_STREAM_STATE_DISCONNECTED) {
             LOGE("AAudio stream disconnected, attempting restart...");
             host.initAudio(host.lastSampleRate_);
             return 0;
        }

        // Use a timeout to allow blocking if the buffer is full.
        aaudio_result_t result = AAudioStream_write(host.audioStream_, data, (int32_t)frames, 100000000);
        if (result >= 0) return (size_t)result;

        if (result == AAUDIO_ERROR_DISCONNECTED || result == AAUDIO_ERROR_INVALID_STATE) {
            LOGE("AAudio write failed: %s. Attempting to restart...", AAudio_convertResultToText(result));
            host.initAudio(host.lastSampleRate_);
        }
    }
    return frames;
}

void LibretroHost::inputPollCallback() {
}

int16_t LibretroHost::inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) {
    auto& host = getInstance();
    if (port < 2) {
        if (device == RETRO_DEVICE_JOYPAD) {
            int16_t state = (host.padState_[port].load() & (1 << id)) ? 1 : 0;
            return state;
        }
        if (device == RETRO_DEVICE_KEYBOARD) {
            // Amiga Fix: Map Gamepad START to Space bar to allow skipping intros
            if (host.coreType_ == CoreType::AMIGA && id == RETROK_SPACE) {
                if (host.padState_[port].load() & (1 << RETRO_DEVICE_ID_JOYPAD_START)) {
                    return 1;
                }
            }
            if (id < 512) {
                return host.keyState_[id].load() ? 1 : 0;
            }
        }
    }
    return 0;
}

extern "C" int PCSX_Run(const char* bios, const char* disc, const char* saveDir) {
    LOGI("Bridge: PCSX_Run called for %s", disc);
    auto& host = LibretroHost::getInstance();
    host.stop();
    host.setCoreType(CoreType::PS1);
    host.setSystemDir("/storage/emulated/0/RetroRTS/system/ps1");
    if (saveDir) host.setSaveDir(saveDir);

    if (host.loadCore("libpcsx_rearmed.so") != 0) return -10;

    if (host.loadGame(disc) != 0) return -2;

    std::thread([&host]() { host.runLoop(); }).detach();
    return 0;
}

extern "C" int uae_init(const char* rom_path, const char* bios_path) {
    LOGI("Bridge: uae_init called for %s (bios=%s)", rom_path, bios_path ? bios_path : "none");
    auto& host = LibretroHost::getInstance();
    host.stop();
    host.setCoreType(CoreType::AMIGA);
    host.setSystemDir("/storage/emulated/0/RetroRTS/system/amiga");
    host.setSaveDir("/storage/emulated/0/RetroRTS/Saves/Amiga");

    if (bios_path) {
        std::string bp = bios_path;
        auto pos = bp.rfind('/');
        if (pos != std::string::npos) {
            host.setAmigaKickstart(bp.substr(pos + 1));
        } else {
            host.setAmigaKickstart(bp);
        }
    } else {
        host.setAmigaKickstart("");
    }

    if (host.loadCore("libpuae_libretro.so") != 0) {
        if (host.loadCore("libpuae.so") != 0) return -1;
    }
    // Pass the ADF directly — the core handles kickstart detection
    if (host.loadGame(rom_path) != 0) return -2;

    std::thread([&host]() { host.runLoop(); }).detach();
    return 0;
}

extern "C" int dosbox_init(const char* config_path, const char* saveDir) {
    LOGI("Bridge: dosbox_init called for %s", config_path);
    auto& host = LibretroHost::getInstance();
    host.stop();
    host.setCoreType(CoreType::DOSBOX);
    host.setSystemDir("/storage/emulated/0/RetroRTS/system/dosbox");
    if (saveDir) host.setSaveDir(saveDir);

    if (host.loadCore("libdosbox_pure_libretro.so") != 0) {
        if (host.loadCore("libdosbox_pure.so") != 0) return -1;
    }
    if (host.loadGame(config_path) != 0) return -2;

    std::thread([&host]() { host.runLoop(); }).detach();
    return 0;
}

extern "C" int dsi_init(const char* rom_path) {
    LOGI("Bridge: dsi_init called for %s", rom_path);
    auto& host = LibretroHost::getInstance();
    host.stop();
    host.setCoreType(CoreType::NINTENDO_DSI);
    host.setSystemDir("/storage/emulated/0/RetroRTS/system/dsi");
    host.setSaveDir("/storage/emulated/0/RetroRTS/Saves/DSi");

    if (host.loadCore("libmelonds_libretro.so") != 0) {
        if (host.loadCore("libmelonds.so") != 0) return -1;
    }
    if (host.loadGame(rom_path) != 0) return -2;

    std::thread([&host]() { host.runLoop(); }).detach();
    return 0;
}

} // namespace retrorts
