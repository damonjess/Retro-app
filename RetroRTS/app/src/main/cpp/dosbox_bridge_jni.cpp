#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <string>
#include <atomic>
#include "libretro_bridge.h"

#define LOG_TAG "RetroRTS_DOSBox"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::atomic<bool> g_dosbox_running{false};
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrorts_ui_DosboxBridge_startDosboxNative(
    JNIEnv* env, jclass, jstring gameDir, jstring configPath) {

    if (g_dosbox_running.load()) return JNI_TRUE;

    const char* gdir = env->GetStringUTFChars(gameDir, nullptr);
    const char* cpath = env->GetStringUTFChars(configPath, nullptr);
    if (!gdir || !cpath) {
        if (gdir) env->ReleaseStringUTFChars(gameDir, gdir);
        if (cpath) env->ReleaseStringUTFChars(configPath, cpath);
        return JNI_FALSE;
    }

    LOGI("Starting DOSBox via bridge: config=%s", cpath);

    int r = retrorts::dosbox_init(cpath, gdir);

    env->ReleaseStringUTFChars(gameDir, gdir);
    env->ReleaseStringUTFChars(configPath, cpath);

    if (r != 0) {
        LOGE("DOSBox init via bridge failed: %d", r);
        return JNI_FALSE;
    }

    g_dosbox_running.store(true);
    LOGI("DOSBox started successfully via bridge");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_stopDosboxNative(JNIEnv*, jclass) {
    if (!g_dosbox_running.load()) return;

    retrorts::LibretroHost::getInstance().stop();

    g_dosbox_running.store(false);
    LOGI("DOSBox stopped via bridge");
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_setCpuCyclesNative(JNIEnv*, jclass, jint cycles) {
    retrorts::LibretroHost::getInstance().setCycles(cycles);
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_setFrameCapNative(JNIEnv*, jclass, jint fps) {
    // Frame cap is handled by the core's timing or LibretroHost stats if implemented.
    // For now, we store cycles which affects performance.
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_setVolumeNative(JNIEnv*, jclass, jfloat volume) {
    retrorts::LibretroHost::getInstance().setVolume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_notifyThermalLevelNative(JNIEnv*, jclass, jint level) {
    // To be implemented via LibretroHost
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_retrorts_ui_DosboxBridge_getPerfStatsNative(JNIEnv* env, jclass) {
    auto& host = retrorts::LibretroHost::getInstance();
    float stats[2] = { host.getFps(), host.getCpuUsage() };
    jfloatArray arr = env->NewFloatArray(2);
    env->SetFloatArrayRegion(arr, 0, 2, stats);
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrorts_ui_DosboxBridge_saveStateNative(
    JNIEnv* env, jclass, jstring gameId, jint slot, jstring path) {
    return JNI_FALSE; // To be implemented via LibretroHost
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrorts_ui_DosboxBridge_loadStateNative(
    JNIEnv* env, jclass, jstring gameId, jint slot, jstring path) {
    return JNI_FALSE; // To be implemented via LibretroHost
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_updateInputNative(
    JNIEnv*, jclass, jint port, jint buttonMask) {
    if (buttonMask != 0) {
        LOGI("JNI DosboxBridge: port=%d, mask=0x%04X", port, buttonMask);
    }
    retrorts::LibretroHost::getInstance().updateJoypad(port, static_cast<uint16_t>(buttonMask));
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_updateAnalogNative(
    JNIEnv*, jclass, jint port, jint index, jint id, jint value) {
    retrorts::LibretroHost::getInstance().updateAnalog(port, index, id, static_cast<int16_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_updateMouseNative(
    JNIEnv*, jclass, jint buttonMask, jint dx, jint dy) {
    retrorts::LibretroHost::getInstance().updateMouse(
        buttonMask, static_cast<int>(dx), static_cast<int>(dy));
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_DosboxBridge_setSurfaceNative(
    JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* window = surface
        ? ANativeWindow_fromSurface(env, surface)
        : nullptr;
    retrorts::LibretroHost::getInstance().setWindow(window);
    if (window) ANativeWindow_release(window);
}
