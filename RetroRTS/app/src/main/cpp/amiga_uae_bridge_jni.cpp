#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <fstream>
#include "amiga_core.h"
#include "libretro_bridge.h"

#define LOG_TAG "LibretroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::atomic<bool> g_amiga_running{false};
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrorts_ui_AmigaBridge_startAmigaNative(
    JNIEnv* env, jclass, jobjectArray diskPaths) {

    if (g_amiga_running.load()) return JNI_TRUE;
    if (!diskPaths) return JNI_FALSE;

    const jsize diskCount = env->GetArrayLength(diskPaths);
    if (diskCount <= 0) return JNI_FALSE;

    std::vector<std::string> resolvedPaths;
    resolvedPaths.reserve(static_cast<size_t>(diskCount));
    std::string biosPath;

    for (jsize index = 0; index < diskCount; ++index) {
        auto diskPath = static_cast<jstring>(env->GetObjectArrayElement(diskPaths, index));
        if (!diskPath) {
            LOGE("Amiga launch failed: missing disk at index %d", index);
            return JNI_FALSE;
        }

        const char* utfPath = env->GetStringUTFChars(diskPath, nullptr);
        if (!utfPath) {
            env->DeleteLocalRef(diskPath);
            return JNI_FALSE;
        }
        const std::string path(utfPath);
        env->ReleaseStringUTFChars(diskPath, utfPath);
        env->DeleteLocalRef(diskPath);

        const auto validation = retrorts::amiga::LaunchAmigaGame(path);
        if (!validation.ok) {
            LOGE("Amiga validation failed for disk %d: %s", index + 1, validation.message.c_str());
            return JNI_FALSE;
        }
        if (index == 0) {
            biosPath = validation.resolvedBiosPath;
        }
        resolvedPaths.push_back(validation.resolvedRomPath);
    }

    std::vector<const char*> diskPathPointers;
    diskPathPointers.reserve(resolvedPaths.size());
    for (const auto& path : resolvedPaths) {
        diskPathPointers.push_back(path.c_str());
    }

    LOGI("Starting Amiga via bridge with %d disk(s); Disk 1=%s", diskCount,
         diskPathPointers.front());
    const int initResult = retrorts::uae_init_multi(
        diskPathPointers.data(), diskPathPointers.size(), biosPath.c_str());
    if (initResult != 0) {
        LOGE("UAE initialization failed via bridge with code %d", initResult);
        return JNI_FALSE;
    }

    g_amiga_running.store(true);
    LOGI("Amiga emulator started successfully via bridge");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrorts_ui_AmigaBridge_swapDiskNative(JNIEnv*, jclass, jint diskIndex) {
    if (!g_amiga_running.load() || diskIndex < 0) return JNI_FALSE;
    return retrorts::LibretroHost::getInstance().swapDisk(static_cast<unsigned>(diskIndex))
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_retrorts_ui_AmigaBridge_getDiskCountNative(JNIEnv*, jclass) {
    return static_cast<jint>(retrorts::LibretroHost::getInstance().diskCount());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_retrorts_ui_AmigaBridge_getActiveDiskIndexNative(JNIEnv*, jclass) {
    return static_cast<jint>(retrorts::LibretroHost::getInstance().activeDiskIndex());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_retrorts_ui_AmigaBridge_isDiskControlAvailableNative(JNIEnv*, jclass) {
    return retrorts::LibretroHost::getInstance().supportsDiskControl() ? JNI_TRUE : JNI_FALSE;
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
    if (buttonMask != 0) {
        LOGE("JNI AmigaBridge: port=%d, mask=0x%04X", port, buttonMask);
    }
    retrorts::LibretroHost::getInstance().updateJoypad(port, static_cast<uint16_t>(buttonMask));
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_AmigaBridge_updateAnalogNative(
    JNIEnv*, jclass, jint port, jint index, jint id, jint value) {
    retrorts::LibretroHost::getInstance().updateAnalog(port, index, id, static_cast<int16_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_AmigaBridge_updateMouseNative(
    JNIEnv*, jclass, jint buttonMask, jint dx, jint dy) {
    retrorts::LibretroHost::getInstance().updateMouse(
        buttonMask, static_cast<int>(dx), static_cast<int>(dy));
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
