#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include "libretro_bridge.h"

#define LOG_TAG "LibretroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::atomic<bool> g_emu_running{false};
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_NativeEmulatorBridge_stopGameNative(JNIEnv*, jclass) {
    LOGI("NativeEmulatorBridge stopGameNative requested");
    g_emu_running.store(false);
    retrorts::LibretroHost::getInstance().stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_NativeEmulatorBridge_updateInputNative(JNIEnv*, jclass, jint padIndex, jint buttonMask) {
    if (buttonMask != 0) {
        LOGE("JNI NativeEmulatorBridge: port=%d, mask=0x%04X", padIndex, buttonMask);
    }
    retrorts::LibretroHost::getInstance().updateJoypad(padIndex, static_cast<uint16_t>(buttonMask));
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_NativeEmulatorBridge_updateMouseNative(JNIEnv*, jclass, jint buttonMask, jint dx, jint dy) {
    retrorts::LibretroHost::getInstance().updateMouse(buttonMask, static_cast<int16_t>(dx), static_cast<int16_t>(dy));
}
