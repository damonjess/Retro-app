#include <jni.h>
#include <atomic>
#include <string>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <fstream>
#include "amiga_core.h"
#include "libretro_bridge.h"

#define LOG_TAG "RetroRTS_Amiga"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::atomic<bool> g_amiga_running{false};
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrorts_ui_AmigaBridge_startAmigaNative(
    JNIEnv* env, jclass, jstring gamePath) {

    if (g_amiga_running.load()) return JNI_TRUE;
    if (!gamePath) return JNI_FALSE;

    const char* gpath = env->GetStringUTFChars(gamePath, nullptr);

    if (!gpath) {
        return JNI_FALSE;
    }

    LOGI("Starting Amiga via bridge: game=%s", gpath);

    // Validate via amiga_core logic before starting
    auto validation = retrorts::amiga::LaunchAmigaGame(gpath);
    if (!validation.ok) {
        LOGE("Amiga validation failed: %s", validation.message.c_str());
        env->ReleaseStringUTFChars(gamePath, gpath);
        return JNI_FALSE;
    }

    int init_result = retrorts::uae_init(validation.resolvedRomPath.c_str(), validation.resolvedBiosPath.c_str());
    if (init_result != 0) {
        LOGE("UAE initialization failed via bridge with code %d", init_result);
        env->ReleaseStringUTFChars(gamePath, gpath);
        return JNI_FALSE;
    }

    g_amiga_running.store(true);
    LOGI("Amiga emulator started successfully via bridge");

    env->ReleaseStringUTFChars(gamePath, gpath);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_AmigaBridge_stopAmigaNative(JNIEnv*, jclass) {
    if (!g_amiga_running.load()) return;

    retrorts::LibretroHost::getInstance().stop();

    g_amiga_running.store(false);
    LOGI("Amiga emulator stopped via bridge");
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_AmigaBridge_updateInputNative(
    JNIEnv*, jclass, jint port, jint buttonMask) {
    retrorts::LibretroHost::getInstance().updateJoypad(port, static_cast<uint16_t>(buttonMask));
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_AmigaBridge_setSurfaceNative(
    JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* window = nullptr;
    if (surface) {
        window = ANativeWindow_fromSurface(env, surface);
    }
    retrorts::LibretroHost::getInstance().setWindow(window);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrorts_ui_AmigaBridge_isRunningNative(JNIEnv*, jclass) {
    return g_amiga_running.load() ? JNI_TRUE : JNI_FALSE;
}
