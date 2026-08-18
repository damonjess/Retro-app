#include "libretro_bridge.h"
#include <jni.h>
#include <cstring>
#include <cstdint>
#include <signal.h>
#ifdef __ANDROID__
#include <unwind.h>
#endif
#include <dlfcn.h>
#include <android/log.h>
#include <thread>
#include <chrono>
#include <android/native_window_jni.h>
#include <algorithm>
#include <cstdarg>
#include <set>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <sys/mman.h>
#include "pcsx_rearmed/frontend/plugin_lib.h"

#ifndef RETRO_DEVICE_ID_MOUSE_X
#define RETRO_DEVICE_ID_MOUSE_X      0
#define RETRO_DEVICE_ID_MOUSE_Y      1
#define RETRO_DEVICE_ID_MOUSE_LEFT   2
#define RETRO_DEVICE_ID_MOUSE_RIGHT  3
#endif

#define LOG_TAG "LibretroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
JavaVM* g_javaVm = nullptr;
jclass g_nativeAudioFallbackClass = nullptr;
jmethodID g_nativeAudioStart = nullptr;
jmethodID g_nativeAudioWrite = nullptr;
jmethodID g_nativeAudioStop = nullptr;
std::mutex g_nativeAudioMutex;
std::vector<int16_t> g_nativeAudioPcm;

JNIEnv* getAudioJniEnv(bool* attachedByUs) {
    *attachedByUs = false;
    if (!g_javaVm) return nullptr;
    JNIEnv* env = nullptr;
    const jint getEnvResult = g_javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_OK) return env;
    if (getEnvResult != JNI_EDETACHED ||
        g_javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    *attachedByUs = true;
    return env;
}

void clearAudioJniException(JNIEnv* env, const char* operation) {
    if (env && env->ExceptionCheck()) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, "LibretroBridge", "NativeAudioFallback %s threw", operation);
    }
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    JNIEnv* env = nullptr;
    if (!vm || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass localClass = env->FindClass("com/retrorts/ui/NativeAudioFallback");
    if (!localClass) {
        // Keep the emulator usable with AAudio even if this optional fallback
        // was not compiled into an older app build.
        env->ExceptionClear();
        return JNI_VERSION_1_6;
    }
    g_nativeAudioFallbackClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (!g_nativeAudioFallbackClass) return JNI_VERSION_1_6;

    g_nativeAudioStart = env->GetStaticMethodID(g_nativeAudioFallbackClass, "start", "(I)Z");
    g_nativeAudioWrite = env->GetStaticMethodID(
        g_nativeAudioFallbackClass, "writeBuffer", "(Ljava/nio/ByteBuffer;I)I");
    g_nativeAudioStop = env->GetStaticMethodID(g_nativeAudioFallbackClass, "stop", "()V");
    if (!g_nativeAudioStart || !g_nativeAudioWrite || !g_nativeAudioStop) {
        env->ExceptionClear();
        g_nativeAudioStart = nullptr;
        g_nativeAudioWrite = nullptr;
        g_nativeAudioStop = nullptr;
    }
    return JNI_VERSION_1_6;
}

namespace retrorts {

#ifdef __ANDROID__
struct BacktraceState {
    void** current;
    void** end;
};

static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    BacktraceState* state = static_cast<BacktraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        if (state->current == state->end) return _URC_END_OF_STACK;
        *state->current++ = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

static void log_backtrace() {
    void* buffer[32];
    BacktraceState state = {buffer, buffer + 32};
    _Unwind_Backtrace(unwind_callback, &state);
    int count = state.current - buffer;

    for (int i = 0; i < count; i++) {
        Dl_info info;
        if (dladdr(buffer[i], &info) && info.dli_sname) {
            __android_log_print(ANDROID_LOG_ERROR, "LibretroBridge", "  #%02d pc %p %s (%s)",
                                i, buffer[i], info.dli_sname, info.dli_fname);
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "LibretroBridge", "  #%02d pc %p", i, buffer[i]);
        }
    }
}
#endif

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

LibretroHost::LibretroHost() {
    for (int p = 0; p < 2; p++)
        for (int i = 0; i < 2; i++)
            for (int a = 0; a < 2; a++)
                analogState_[p][i][a].store(0);
    // Install a basic native crash handler to log signals and a backtrace
    struct sigaction sa;
    sa.sa_sigaction = [](int sig, siginfo_t* info, void* ucontext) {
        LOGE("Native crash handler: signal=%d, si_addr=%p", sig, info ? info->si_addr : nullptr);
#ifdef __ANDROID__
        log_backtrace();
#endif
        // Restore default and re-raise to let system produce tombstone
        signal(sig, SIG_DFL);
        raise(sig);
    };
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGFPE,  &sa, nullptr);
    sigaction(SIGILL,  &sa, nullptr);
}

void LibretroHost::startRunLoop() {
    // If a run thread is already active, don't start another.
    if (runThread_.joinable()) return;
    runThread_ = std::thread(&LibretroHost::runLoop, this);
}

bool LibretroHost::initJavaAudioFallback(int sampleRate) {
    usingJavaAudioFallback_ = false;
    if (sampleRate <= 0 || !g_nativeAudioFallbackClass || !g_nativeAudioStart) return false;

    std::lock_guard<std::mutex> lock(g_nativeAudioMutex);
    bool attachedByUs = false;
    JNIEnv* env = getAudioJniEnv(&attachedByUs);
    if (!env) return false;
    const jboolean started = env->CallStaticBooleanMethod(
        g_nativeAudioFallbackClass, g_nativeAudioStart, static_cast<jint>(sampleRate));
    clearAudioJniException(env, "start");
    if (attachedByUs) g_javaVm->DetachCurrentThread();
    usingJavaAudioFallback_ = (started == JNI_TRUE);
    if (usingJavaAudioFallback_) LOGI("Using Java AudioTrack fallback at %d Hz", sampleRate);
    return usingJavaAudioFallback_;
}

void LibretroHost::deinitJavaAudioFallback() {
    if (!usingJavaAudioFallback_) return;
    std::lock_guard<std::mutex> lock(g_nativeAudioMutex);
    bool attachedByUs = false;
    JNIEnv* env = getAudioJniEnv(&attachedByUs);
    if (env && g_nativeAudioFallbackClass && g_nativeAudioStop) {
        env->CallStaticVoidMethod(g_nativeAudioFallbackClass, g_nativeAudioStop);
        clearAudioJniException(env, "stop");
    }
    if (attachedByUs) g_javaVm->DetachCurrentThread();
    usingJavaAudioFallback_ = false;
}

size_t LibretroHost::writeJavaAudioFallback(const int16_t* data, size_t frames) {
    if (!usingJavaAudioFallback_ || !data || frames == 0 || !g_nativeAudioWrite) return 0;
    const size_t sampleCount = frames * 2;
    const size_t byteCount = sampleCount * sizeof(int16_t);
    if (byteCount > static_cast<size_t>(INT32_MAX)) return 0;

    std::lock_guard<std::mutex> lock(g_nativeAudioMutex);
    bool attachedByUs = false;
    JNIEnv* env = getAudioJniEnv(&attachedByUs);
    if (!env) return 0;

    // Reuse native storage and expose it as a direct ByteBuffer. This avoids
    // allocating a Java short[] and copying it on every emulated audio frame.
    if (g_nativeAudioPcm.size() < sampleCount) g_nativeAudioPcm.resize(sampleCount);
    std::memcpy(g_nativeAudioPcm.data(), data, byteCount);
    jobject pcmBuffer = env->NewDirectByteBuffer(g_nativeAudioPcm.data(),
                                                   static_cast<jlong>(byteCount));
    if (!pcmBuffer) {
        clearAudioJniException(env, "allocate direct PCM buffer");
        if (attachedByUs) g_javaVm->DetachCurrentThread();
        return 0;
    }
    const jint written = env->CallStaticIntMethod(g_nativeAudioFallbackClass, g_nativeAudioWrite,
                                                   pcmBuffer, static_cast<jint>(byteCount));
    clearAudioJniException(env, "writeBuffer");
    env->DeleteLocalRef(pcmBuffer);
    if (attachedByUs) g_javaVm->DetachCurrentThread();
    return written > 0 ? std::min(frames, static_cast<size_t>(written)) : 0;
}

bool LibretroHost::initAudio(double sampleRate) {
    deinitAudio();
    lastSampleRate_ = sampleRate;

    // DOSBox-Pure is the affected path on this device. Prefer AudioTrack for
    // it: it uses Android's managed mixer and avoids silent AAudio streams.
    if (coreType_ == CoreType::DOSBOX &&
        initJavaAudioFallback(static_cast<int>(sampleRate))) {
        return true;
    }

    LOGI("Initializing AAudio at %.2f Hz", sampleRate);
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || !builder) {
        LOGE("AAudio_createStreamBuilder failed: %s", AAudio_convertResultToText(result));
        return initJavaAudioFallback(static_cast<int>(sampleRate));
    }

    auto configureBuilder = [](AAudioStreamBuilder* b, aaudio_performance_mode_t mode) {
        AAudioStreamBuilder_setChannelCount(b, 2);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setPerformanceMode(b, mode);
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    };

    // Some devices reject LOW_LATENCY even though shared PCM playback works.
    // Retry with the compatible performance profile instead of leaving DOSBox silent.
    configureBuilder(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    result = AAudioStreamBuilder_openStream(builder, &audioStream_);
    if (result != AAUDIO_OK) {
        LOGE("Low-latency AAudio open failed: %s; retrying compatible mode",
             AAudio_convertResultToText(result));
        AAudioStreamBuilder_delete(builder);
        builder = nullptr;
        result = AAudio_createStreamBuilder(&builder);
        if (result == AAUDIO_OK && builder) {
            configureBuilder(builder, AAUDIO_PERFORMANCE_MODE_NONE);
            result = AAudioStreamBuilder_openStream(builder, &audioStream_);
        }
    }
    if (builder) AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || !audioStream_) {
        LOGE("Failed to open compatible AAudio stream: %s",
             AAudio_convertResultToText(result));
        audioStream_ = nullptr;
        return initJavaAudioFallback(static_cast<int>(sampleRate));
    }

    // Keep latency low; don't max out the buffer. Query the actual
    // stream sample rate chosen by the system and adapt our bookkeeping.
    lastSampleRate_ = AAudioStream_getSampleRate(audioStream_);
    int32_t capacity = AAudioStream_getBufferCapacityInFrames(audioStream_);
    int32_t burst = AAudioStream_getFramesPerBurst(audioStream_);
    int32_t requestedSize = burst * 4;
    AAudioStream_setBufferSizeInFrames(audioStream_, requestedSize);
    LOGI("AAudio stream opened: capacity=%d, burst=%d, bufferSize=%d",
         capacity, burst, AAudioStream_getBufferSizeInFrames(audioStream_));

    result = AAudioStream_requestStart(audioStream_);
    if (result != AAUDIO_OK) {
        LOGE("Failed to start AAudio stream: %s", AAudio_convertResultToText(result));
        AAudioStream_close(audioStream_);
        audioStream_ = nullptr;
        return initJavaAudioFallback(static_cast<int>(sampleRate));
    }

    return true;
}

void LibretroHost::deinitAudio() {
    deinitJavaAudioFallback();
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
    // Core callbacks are only valid for the currently loaded core.
    diskControl_ = {};
    diskControlAvailable_ = false;
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

    // Set ports AFTER retro_init — some cores ignore it before init
    if (retro_set_controller_port_device_fn) {
        if (coreType_ == CoreType::AMIGA) {
            LOGI("Amiga: port 0 = MOUSE, port 1 = JOYPAD");
            retro_set_controller_port_device_fn(0, RETRO_DEVICE_MOUSE);
            retro_set_controller_port_device_fn(1, RETRO_DEVICE_JOYPAD);
        } else if (coreType_ == CoreType::DOSBOX) {
            LOGI("DOSBox: port 0 = MOUSE, port 1 = JOYPAD");
            retro_set_controller_port_device_fn(0, RETRO_DEVICE_MOUSE);
            retro_set_controller_port_device_fn(1, RETRO_DEVICE_JOYPAD);
        } else {
            LOGI("Setting controller port devices to RETRO_DEVICE_JOYPAD");
            retro_set_controller_port_device_fn(0, RETRO_DEVICE_JOYPAD);
            retro_set_controller_port_device_fn(1, RETRO_DEVICE_JOYPAD);
        }
    }

    LOGI("Core initialized");

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

void LibretroHost::sendKeyCode(unsigned keycode) {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    std::lock_guard<std::mutex> qlock(queueMutex_);
    keyEventQueue_.push_back({true, keycode, 0});
    keyEventQueue_.push_back({false, keycode, 0});
}

void LibretroHost::updateJoypad(int port, uint16_t state) {
    if (port >= 0 && port < 2) {
        padState_[port].store(state);
    }
}

void LibretroHost::updateAnalog(int port, int index, int id, int16_t value) {
    if (port >= 0 && port < 2 && index >= 0 && index < 2 && id >= 0 && id < 2) {
        analogState_[port][index][id].store(value);
    }
}

void LibretroHost::updateMouse(int buttonMask, int dx, int dy) {
    mouseButtons_.store(static_cast<uint16_t>(buttonMask));
    mouseX_.fetch_add(static_cast<int32_t>(dx));
    mouseY_.fetch_add(static_cast<int32_t>(dy));
}

bool LibretroHost::swapDisk(unsigned diskIndex) {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    if (!running_.load() || !diskControlAvailable_ ||
        !diskControl_.set_eject_state || !diskControl_.set_image_index ||
        !diskControl_.get_num_images) {
        LOGE("Disk swap unavailable: core did not register a complete disk-control interface");
        return false;
    }

    const unsigned imageCount = diskControl_.get_num_images();
    if (diskIndex >= imageCount) {
        LOGE("Disk swap rejected: requested index %u, but only %u images are available", diskIndex, imageCount);
        return false;
    }

    // Libretro requires selecting an image while the virtual drive is ejected.
    if (!diskControl_.set_eject_state(true)) {
        LOGE("Disk swap failed: core refused to eject the current image");
        return false;
    }

    const bool selected = diskControl_.set_image_index(diskIndex);
    const bool inserted = diskControl_.set_eject_state(false);
    if (!selected || !inserted) {
        LOGE("Disk swap failed: selected=%d inserted=%d for image %u", selected ? 1 : 0,
             inserted ? 1 : 0, diskIndex);
        return false;
    }

    LOGI("Disk swap complete: selected image %u of %u", diskIndex + 1, imageCount);
    return true;
}

unsigned LibretroHost::diskCount() {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    return (diskControlAvailable_ && diskControl_.get_num_images)
        ? diskControl_.get_num_images()
        : 0;
}

unsigned LibretroHost::activeDiskIndex() {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    return (diskControlAvailable_ && diskControl_.get_image_index)
        ? diskControl_.get_image_index()
        : 0;
}

bool LibretroHost::supportsDiskControl() {
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    return diskControlAvailable_;
}

int LibretroHost::loadGame(const std::string& romPath) {
    LOGI("LibretroHost::loadGame: %s", romPath.c_str());
    std::lock_guard<std::recursive_mutex> lock(coreMutex_);
    if (!coreLib_ || !retro_load_game_fn) return -1;

    struct retro_game_info game = {romPath.c_str(), nullptr, 0, nullptr};
    currentGamePath_ = romPath;
    if (!retro_load_game_fn(&game)) {
        LOGE("Failed to load game: %s", romPath.c_str());
        return -2;
    }

    struct retro_system_av_info av_info;
    retro_get_system_av_info_fn(&av_info);
    if (!initAudio(av_info.timing.sample_rate)) {
        LOGE("DOSBox audio initialization failed; continuing without audio");
    }

    lastStatsTime_ = std::chrono::steady_clock::now();
    frameCount_.store(0);
    totalRunTimeUs_.store(0);
    currentFps_.store(0.0f);
    currentCpu_.store(0.0f);

    running_.store(true);
    LOGI("Game loaded: %s", romPath.c_str());
    return 0;
}

void LibretroHost::runLoop() {
    LOGI("runLoop: started");
    while (running_.load()) {
        auto start = std::chrono::steady_clock::now();
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

        auto end = std::chrono::steady_clock::now();
        totalRunTimeUs_.fetch_add(
            std::chrono::duration_cast<std::chrono::microseconds>(end - start).count());

        // DOSBox-Pure is audio-clocked. An additional 60 Hz sleep makes it
        // miss its native timing (and starves audio on busy Android devices).
        // AudioTrack's blocking PCM write provides the real-time backpressure.
        // Keep the legacy video pacing only for the other libretro cores.
        if (coreType_ != CoreType::DOSBOX) {
            const auto frameBudget = std::chrono::microseconds(16667); // ~60 FPS
            if (end - start < frameBudget) {
                std::this_thread::sleep_for(frameBudget - (end - start));
            }
        }

        // Update stats every second
        auto now = std::chrono::steady_clock::now();
        auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastStatsTime_).count();
        if (elapsedMs >= 1000) {
            currentFps_.store((float)frameCount_.exchange(0) * 1000.0f / elapsedMs);
            // cpu % = (time spent in run / total time) * 100
            currentCpu_.store((float)totalRunTimeUs_.exchange(0) / (elapsedMs * 10.0f));
            lastStatsTime_ = now;
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
    // Signal runLoop to exit and wait for its thread to finish before
    // unloading cores or touching core state. This prevents races where
    // the loop calls into a core while it's being dlclosed.
    running_.store(false);
    if (runThread_.joinable()) {
        runThread_.join();
    }

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
    diskControl_ = {};
    diskControlAvailable_ = false;
    LOGI("Host stopped");
}

bool LibretroHost::envCallback(unsigned cmd, void* data) {
    auto& host = getInstance();
    if (!data && (cmd == RETRO_ENVIRONMENT_GET_VARIABLE || cmd == RETRO_ENVIRONMENT_SET_PIXEL_FORMAT ||
                  cmd == RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY || cmd == RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY)) {
        return false;
    }

    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool*)data = true;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto format = *static_cast<const enum retro_pixel_format*>(data);
            host.pixelFormat_ = format;
            LOGI("envCallback: Core set pixel format to %d", (int)format);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE: {
            const auto* callback = static_cast<const retro_disk_control_callback*>(data);
            if (!callback || !callback->set_eject_state || !callback->set_image_index ||
                !callback->get_num_images || !callback->get_image_index) {
                host.diskControl_ = {};
                host.diskControlAvailable_ = false;
                LOGE("envCallback: Core supplied an incomplete disk-control interface");
                return false;
            }
            host.diskControl_ = *callback;
            host.diskControlAvailable_ = true;
            LOGI("envCallback: Core registered disk-control interface");
            return true;
        }
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK: {
            LOGI("envCallback: Core requested RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK");
            const struct retro_keyboard_callback *cb = (const struct retro_keyboard_callback*)data;
            host.keyboard_cb_ = cb->callback;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES: {
            LOGI("envCallback: Core requested RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES");
            uint64_t *mask = (uint64_t*)data;
            *mask = (1ULL << RETRO_DEVICE_JOYPAD) |
                    (1ULL << RETRO_DEVICE_KEYBOARD) |
                    (1ULL << RETRO_DEVICE_MOUSE);
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
            LOGI("envCallback: Core requested variable: %s", key.c_str());

            if (host.coreType_ == CoreType::PS1) {
                const std::string game = host.currentGamePath_;
                const bool isGta2 = game.find("gta2") != std::string::npos ||
                                    game.find("GTA2") != std::string::npos ||
                                    game.find("gta 2") != std::string::npos ||
                                    game.find("GTA 2") != std::string::npos;
                if (key == "pcsx_rearmed_bios") {
                    var->value = "auto"; // Uses a real BIOS when one is present in system/ps1.
                    return true;
                }
                if (key == "pcsx_rearmed_frameskip") {
                    var->value = "0";
                    return true;
                }
                if (key == "pcsx_rearmed_gpu_thread_rendering") {
                    var->value = "disabled";
                    return true;
                }
                if (key == "pcsx_rearmed_async_cd") {
                    var->value = "sync";
                    return true;
                }
                if (key == "pcsx_rearmed_drc" && isGta2) {
                    // GTA2 is stable with the interpreter path; this avoids a
                    // device-specific dynarec crash at the cost of performance.
                    var->value = "disabled";
                    return true;
                }
            } else if (host.coreType_ == CoreType::AMIGA) {
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
                        LOGI("envCallback: Providing kickstart: %s", var->value);
                        return true;
                    }
                }
                if (key == "puae_cpu_speed") {
                    var->value = "real";
                    return true;
                }
                if (key == "puae_cpu_compatible") {
                    var->value = "true";
                    return true;
                }
                if (key == "puae_immediate_blitter") {
                    var->value = "false";
                    return true;
                }
                if (key == "puae_chipmem_size") {
                    var->value = "2"; // 1M
                    return true;
                }
                if (key == "puae_bogomem_size") {
                    var->value = "2"; // 0.5M
                    return true;
                }
                if (key == "puae_sound_stereo_separation") {
                    var->value = "100%";
                    return true;
                }
                if (key == "puae_sound_filter") {
                    var->value = "emulated";
                    return true;
                }
                if (key == "puae_mouse_mode") {
                    var->value = "joypad";
                    return true;
                }
                if (key == "puaemouse") {
                    var->value = "joypad";
                    return true;
                }
                if (key == "mouse") {
                    var->value = "joypad";
                    return true;
                }
                if (key == "puae_mouse_speed") {
                    var->value = "100";
                    return true;
                }
            } else if (host.coreType_ == CoreType::DOSBOX) {
                // Dune II is controlled with a mouse. These options force
                // DOSBox-Pure to consume the frontend's RETRO_DEVICE_MOUSE
                // deltas instead of leaving Android touch handling disabled.
                if (key == "dosbox_pure_mouse_input") {
                    var->value = "true"; // Auto: virtual/direct mouse input.
                    return true;
                }
                if (key == "dosbox_pure_mouse_speed_factor" ||
                    key == "dosbox_pure_mouse_speed_factor_x") {
                    var->value = "2.0";
                    return true;
                }
                if (key == "dosbox_pure_auto_mapping") {
                    var->value = "false"; // Do not override the direct mouse scheme.
                    return true;
                }

                // Match the core sample rate to the Android output path and
                // expose the Sound Blaster configuration Dune II expects.
                if (key == "dosbox_pure_audiorate") {
                    var->value = "48000";
                    return true;
                }
                if (key == "dosbox_pure_sblaster_conf") {
                    var->value = "A220 I7 D1 H5";
                    return true;
                }
                if (key == "dosbox_pure_sblaster_type") {
                    var->value = "sbpro2";
                    return true;
                }
                if (key == "dosbox_pure_sblaster_adlib_mode") {
                    var->value = "opl2";
                    return true;
                }
                if (key == "dosbox_pure_volume_sb" ||
                    key == "dosbox_pure_volume_adlib" ||
                    key == "dosbox_pure_volume_speaker") {
                    var->value = "1.0";
                    return true;
                }
                if (key == "dosbox_pure_cycles") {
                    var->value = "6000"; // Smooth Dune II timing without excess Android CPU load.
                    return true;
                }
                if (key == "dosbox_pure_memory_size") {
                    var->value = "4";
                    return true;
                }
                if (key == "dosbox_pure_svga") {
                    var->value = "svga_s3";
                    return true;
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
    // Cores may signal a duplicated frame with nullptr and may briefly report
    // a zero-sized frame while changing video mode. Never divide by zero or
    // dereference a non-frame pointer during those transitions.
    // RETRO_HW_FRAME_BUFFER_VALID is represented by the non-dereferenceable
    // pointer value -1. PCSX-ReARMed can emit it while changing GTA2 display
    // modes; this host renders CPU frames only, so ignore it safely.
    constexpr uintptr_t kHardwareFrameBufferValid = static_cast<uintptr_t>(-1);
    if (data == reinterpret_cast<const void*>(kHardwareFrameBufferValid) ||
        !data || width == 0 || height == 0 || width > 2048 || height > 1024) return;
    auto& host = getInstance();
    const size_t bytesPerPixel =
        (host.pixelFormat_ == RETRO_PIXEL_FORMAT_XRGB8888) ? sizeof(uint32_t) : sizeof(uint16_t);
    if (pitch < static_cast<size_t>(width) * bytesPerPixel ||
        pitch > static_cast<size_t>(width) * bytesPerPixel * 8) {
        LOGE("videoCallback: rejecting invalid frame pitch=%zu for %ux%u", pitch, width, height);
        return;
    }
    host.frameCount_.fetch_add(1);

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

    int dstW = buffer.width;
    int dstH = buffer.height;
    int srcW = static_cast<int>(width);
    int srcH = static_cast<int>(height);

    // 1. Calculate aspect-ratio-preserving scale
    float scaleX = static_cast<float>(dstW) / static_cast<float>(srcW);
    float scaleY = static_cast<float>(dstH) / static_cast<float>(srcH);
    float scale = std::min(scaleX, scaleY);

    int drawW = static_cast<int>(static_cast<float>(srcW) * scale);
    int drawH = static_cast<int>(static_cast<float>(srcH) * scale);
    int offsetX = (dstW - drawW) / 2;
    int offsetY = (dstH - drawH) / 2;

    // 2. Clear black bars (only if necessary)
    if (offsetY > 0) {
        // Top bar
        for (int y = 0; y < offsetY; y++) {
            uint32_t* row = dst + y * buffer.stride;
            std::fill(row, row + dstW, 0xFF000000);
        }
        // Bottom bar
        for (int y = offsetY + drawH; y < dstH; y++) {
            uint32_t* row = dst + y * buffer.stride;
            std::fill(row, row + dstW, 0xFF000000);
        }
    }
    if (offsetX > 0) {
        // Left and right bars for the middle section
        for (int y = offsetY; y < offsetY + drawH; y++) {
            uint32_t* row = dst + y * buffer.stride;
            std::fill(row, row + offsetX, 0xFF000000);
            std::fill(row + offsetX + drawW, row + dstW, 0xFF000000);
        }
    }

    // 3. Blit based on pixel format
    if (host.pixelFormat_ == RETRO_PIXEL_FORMAT_XRGB8888) {
        auto* src = static_cast<const uint32_t*>(data);
        for (int y = 0; y < drawH; y++) {
            int srcY = static_cast<int>(static_cast<float>(y) / scale);
            if (srcY >= srcH) srcY = srcH - 1;
            const uint32_t* src_row = src + srcY * (pitch / 4);
            uint32_t* dst_row = dst + (y + offsetY) * buffer.stride + offsetX;

            for (int x = 0; x < drawW; x++) {
                int srcX = static_cast<int>(static_cast<float>(x) / scale);
                if (srcX >= srcW) srcX = srcW - 1;
                // Add alpha channel if missing
                dst_row[x] = src_row[srcX] | 0xFF000000;
            }
        }
    } else {
        // Default: RGB565
        auto* src = static_cast<const uint16_t*>(data);
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
    if (!data || frames == 0) return 0;
    if (host.usingJavaAudioFallback_) {
        return host.writeJavaAudioFallback(data, frames);
    }
    if (!host.audioStream_) {
        if (host.initJavaAudioFallback(static_cast<int>(host.lastSampleRate_))) {
            return host.writeJavaAudioFallback(data, frames);
        }
        return 0;
    }

    aaudio_stream_state_t state = AAudioStream_getState(host.audioStream_);
    if (state == AAUDIO_STREAM_STATE_DISCONNECTED) {
        LOGE("AAudio stream disconnected, attempting restart...");
        if (host.initAudio(host.lastSampleRate_) && host.usingJavaAudioFallback_) {
            return host.writeJavaAudioFallback(data, frames);
        }
        return 0;
    }
    if (state == AAUDIO_STREAM_STATE_STOPPED || state == AAUDIO_STREAM_STATE_PAUSED) {
        const aaudio_result_t startResult = AAudioStream_requestStart(host.audioStream_);
        if (startResult != AAUDIO_OK) {
            LOGE("AAudio restart failed: %s", AAudio_convertResultToText(startResult));
            return 0;
        }
    }

    const int16_t* output = data;
    const float volume = std::clamp(host.volume_.load(), 0.0f, 1.0f);
    static std::vector<int16_t> volumeBuffer;
    if (volume < 0.999f) {
        const size_t samples = frames * 2;
        if (volumeBuffer.size() < samples) volumeBuffer.resize(samples);
        for (size_t i = 0; i < samples; ++i) {
            const int scaled = static_cast<int>(static_cast<float>(data[i]) * volume);
            volumeBuffer[i] = static_cast<int16_t>(std::clamp(scaled, -32768, 32767));
        }
        output = volumeBuffer.data();
    }

    // AAudio is allowed to accept fewer frames than requested. Keep writing
    // the remaining PCM data, otherwise a partial write becomes an audible
    // gap that can look like missing DOSBox sound on slower devices.
    size_t written = 0;
    while (written < frames) {
        const aaudio_result_t result = AAudioStream_write(
            host.audioStream_, output + written * 2,
            static_cast<int32_t>(frames - written), 20000000); // 20 ms
        if (result > 0) {
            written += static_cast<size_t>(result);
            continue;
        }
        if (result == 0 || result == AAUDIO_ERROR_TIMEOUT) {
            break;
        }
        LOGE("AAudio write error: %s", AAudio_convertResultToText(result));
        if (host.initAudio(host.lastSampleRate_) && host.usingJavaAudioFallback_) {
            return written + host.writeJavaAudioFallback(data + written * 2, frames - written);
        }
        break;
    }
    return written;
}

void LibretroHost::inputPollCallback() {
}

int16_t LibretroHost::inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) {
    auto& host = getInstance();
    if (port >= 2) return 0;

    if (device == RETRO_DEVICE_JOYPAD) {
        return (host.padState_[port].load() & (1U << id)) ? 1 : 0;
    }

    if (device == RETRO_DEVICE_ANALOG) {
        if (port < 2 && index < 2 && id < 2) {
            return host.analogState_[port][index][id].load();
        }
        return 0;
    }

    if (device == RETRO_DEVICE_MOUSE) {
        if ((host.coreType_ == CoreType::AMIGA || host.coreType_ == CoreType::DOSBOX) && port == 0) {
            // Amiga/DOSBox port 0 = MOUSE, port 1 = JOYPAD.
            // The on-screen virtual controller typically feeds port 1, so we
            // aggregate both ports so D-pad / face buttons / L / R all drive
            // the mouse when an Amiga/DOSBox game is running.
            uint16_t pad0 = host.padState_[0].load();
            uint16_t pad1 = host.padState_[1].load();
            uint16_t pad  = pad0 | pad1;

            switch (id) {
                case RETRO_DEVICE_ID_MOUSE_X: {
                    int32_t stickX0 = host.analogState_[0][0][0].load();
                    int32_t stickX1 = host.analogState_[1][0][0].load();
                    int32_t stickX  = (stickX0 != 0) ? stickX0 : stickX1;
                    int32_t stickDelta = (stickX != 0) ? (stickX / 900) : 0;
                    int32_t dpadDelta = 0;
                    if (pad & (1U << 7)) dpadDelta += 8; // Right
                    if (pad & (1U << 6)) dpadDelta -= 8; // Left
                    int32_t acc = host.mouseX_.exchange(0);
                    int32_t sum = stickDelta + dpadDelta + acc;
                    if (sum > INT16_MAX) sum = INT16_MAX;
                    if (sum < INT16_MIN) sum = INT16_MIN;
                    return static_cast<int16_t>(sum);
                }
                case RETRO_DEVICE_ID_MOUSE_Y: {
                    int32_t stickY0 = host.analogState_[0][0][1].load();
                    int32_t stickY1 = host.analogState_[1][0][1].load();
                    int32_t stickY  = (stickY0 != 0) ? stickY0 : stickY1;
                    int32_t stickDelta = (stickY != 0) ? (stickY / 900) : 0;
                    int32_t dpadDelta = 0;
                    if (pad & (1U << 5)) dpadDelta += 8; // Down
                    if (pad & (1U << 4)) dpadDelta -= 8; // Up
                    int32_t acc = host.mouseY_.exchange(0);
                    int32_t sum = stickDelta + dpadDelta + acc;
                    if (sum > INT16_MAX) sum = INT16_MAX;
                    if (sum < INT16_MIN) sum = INT16_MIN;
                    return static_cast<int16_t>(sum);
                }
                case RETRO_DEVICE_ID_MOUSE_LEFT: {
                    bool mouseLeft = (host.mouseButtons_.load() & 1);
                    // Map Joypad B, A, X, Y to mouse left for convenience in mouse games
                    bool padLeft = (pad & (1U << 0)) || (pad & (1U << 8)) || (pad & (1U << 1)) || (pad & (1U << 9));
                    return (mouseLeft || padLeft) ? 1 : 0;
                }
                case RETRO_DEVICE_ID_MOUSE_RIGHT: {
                    bool mouseRight = (host.mouseButtons_.load() & 2);
                    // Map Joypad L, R to mouse right
                    bool padRight = (pad & (1U << 10)) || (pad & (1U << 11));
                    return (mouseRight || padRight) ? 1 : 0;
                }
                case RETRO_DEVICE_ID_MOUSE_WHEELUP:
                    return (host.mouseButtons_.load() & (1U << 4)) ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_WHEELDOWN:
                    return (host.mouseButtons_.load() & (1U << 5)) ? 1 : 0;
            }
        }

        switch (id) {
            case RETRO_DEVICE_ID_MOUSE_X:
                return host.mouseX_.exchange(0);
            case RETRO_DEVICE_ID_MOUSE_Y:
                return host.mouseY_.exchange(0);
            case RETRO_DEVICE_ID_MOUSE_LEFT:
                return (host.mouseButtons_.load() & 1) ? 1 : 0;
            case RETRO_DEVICE_ID_MOUSE_RIGHT:
                return (host.mouseButtons_.load() & 2) ? 1 : 0;
            case RETRO_DEVICE_ID_MOUSE_WHEELUP:
                return (host.mouseButtons_.load() & (1U << 4)) ? 1 : 0;
            case RETRO_DEVICE_ID_MOUSE_WHEELDOWN:
                return (host.mouseButtons_.load() & (1U << 5)) ? 1 : 0;
        }
        return 0;
    }

    if (device == RETRO_DEVICE_KEYBOARD) {
        if (id < 512) {
            return host.keyState_[id].load() ? 1 : 0;
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

    host.startRunLoop();
    return 0;
}

extern "C" int play_init(const char* gamePath, const char* saveDir) {
    if (!gamePath || gamePath[0] == '\0') {
        LOGE("Bridge: play_init called without a game path");
        return -3;
    }

    LOGI("Bridge: play_init called for %s", gamePath);
    auto& host = LibretroHost::getInstance();
    host.stop();
    host.setCoreType(CoreType::PS2);
    host.setSystemDir("/storage/emulated/0/RetroRTS/system/ps2");
    host.setSaveDir(saveDir ? saveDir : "/storage/emulated/0/RetroRTS/Saves/PS2");

    // Produced by Play!'s official build_retro/android_build.sh script.
    if (host.loadCore("libplay_libretro.so") != 0) {
        if (host.loadCore("libplay_libretro_android.so") != 0) return -10;
    }
    if (host.loadGame(gamePath) != 0) return -2;

    host.startRunLoop();
    return 0;
}

extern "C" int uae_init(const char* rom_path, const char* bios_path) {
    const char* const singleDisk[] = {rom_path};
    return uae_init_multi(singleDisk, 1, bios_path);
}

extern "C" int uae_init_multi(const char* const* disk_paths, size_t disk_count, const char* bios_path) {
    if (!disk_paths || disk_count == 0 || !disk_paths[0]) {
        LOGE("Bridge: uae_init_multi called without a launch disk");
        return -3;
    }

    for (size_t index = 0; index < disk_count; ++index) {
        if (!disk_paths[index] || disk_paths[index][0] == '\0') {
            LOGE("Bridge: uae_init_multi received an empty disk path at index %zu", index);
            return -3;
        }
    }

    LOGI("Bridge: uae_init_multi called with %zu disk(s); disk 1=%s", disk_count, disk_paths[0]);
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
    std::string contentPath = disk_paths[0];
    if (disk_count > 1) {
        const std::filesystem::path playlistDir =
            std::filesystem::path("/storage/emulated/0/RetroRTS/Saves/Amiga") / "playlists";
        const std::filesystem::path playlistPath = playlistDir / "active-disks.m3u";
        std::error_code error;
        std::filesystem::create_directories(playlistDir, error);
        if (error) {
            LOGE("Bridge: failed to create Amiga playlist directory: %s", error.message().c_str());
            return -4;
        }

        std::ofstream playlist(playlistPath, std::ios::out | std::ios::trunc);
        if (!playlist) {
            LOGE("Bridge: failed to create multi-disk playlist: %s", playlistPath.c_str());
            return -4;
        }
        for (size_t index = 0; index < disk_count; ++index) {
            playlist << disk_paths[index] << '\n';
        }
        playlist.close();
        if (!playlist) {
            LOGE("Bridge: failed while writing multi-disk playlist: %s", playlistPath.c_str());
            return -4;
        }

        contentPath = playlistPath.string();
        LOGI("Bridge: loading Amiga disk playlist %s (%zu images)", contentPath.c_str(), disk_count);
    }

    if (host.loadGame(contentPath) != 0) return -2;

    host.startRunLoop();
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

    host.startRunLoop();
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

    host.startRunLoop();
    return 0;
}

extern "C" int xbox_init(const char* rom_path, const char* bios_path) {
    LOGI("Bridge: xbox_init called for %s (bios=%s)", rom_path, bios_path ? bios_path : "none");
    auto& host = LibretroHost::getInstance();
    host.stop();
    host.setCoreType(CoreType::XBOX);
    host.setSystemDir("/storage/emulated/0/RetroRTS/system/xbox");
    host.setSaveDir("/storage/emulated/0/RetroRTS/Saves/Xbox");

    // Attempt to load xemu or similar if a libretro port exists.
    // Note: A standard Xbox libretro core is currently highly experimental.
    if (host.loadCore("libxemu_libretro.so") != 0) {
        return -10; // Core not found
    }

    if (host.loadGame(rom_path) != 0) return -2;

    host.startRunLoop();
    return 0;
}

} // namespace retrorts
