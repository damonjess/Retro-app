#include <jni.h>
#include <string>
#include <android/native_window_jni.h>
#include "emulator_core.h"
#include "libretro_bridge.h"
#include <android/log.h>

#define LOG_TAG "LibretroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_retrorts_ui_NativeEmulatorBridge_launchGameNative(JNIEnv* env, jclass, jstring console, jstring romPath, jstring cacheDir, jstring saveDir) {
    if (console == nullptr || romPath == nullptr || cacheDir == nullptr || saveDir == nullptr) {
        return env->NewStringUTF("");
    }

    const char* cConsole  = env->GetStringUTFChars(console,  nullptr);
    const char* cRomPath  = env->GetStringUTFChars(romPath,  nullptr);
    const char* cCacheDir = env->GetStringUTFChars(cacheDir, nullptr);
    const char* cSaveDir  = env->GetStringUTFChars(saveDir,  nullptr);

    if (!cConsole || !cRomPath || !cCacheDir || !cSaveDir) {
        if (cConsole)  env->ReleaseStringUTFChars(console,  cConsole);
        if (cRomPath)  env->ReleaseStringUTFChars(romPath,  cRomPath);
        if (cCacheDir) env->ReleaseStringUTFChars(cacheDir, cCacheDir);
        if (cSaveDir)  env->ReleaseStringUTFChars(saveDir,  cSaveDir);
        return env->NewStringUTF("");
    }

    std::string result = retrorts::LaunchGame(cConsole, cRomPath, cCacheDir, cSaveDir);

    env->ReleaseStringUTFChars(console,  cConsole);
    env->ReleaseStringUTFChars(romPath,  cRomPath);
    env->ReleaseStringUTFChars(cacheDir, cCacheDir);
    env->ReleaseStringUTFChars(saveDir,  cSaveDir);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_NativeEmulatorBridge_setCoreDirNative(JNIEnv* env, jclass, jstring coreDir) {
    // No-op: LibretroHost no longer uses coreDir. Cores are loaded by name or absolute path.
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_NativeEmulatorBridge_setSystemDirNative(JNIEnv* env, jclass, jstring systemDir) {
    if (systemDir == nullptr) return;
    const char* cDir = env->GetStringUTFChars(systemDir, nullptr);
    if (cDir) {
        retrorts::LibretroHost::getInstance().setSystemDir(cDir);
        env->ReleaseStringUTFChars(systemDir, cDir);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_NativeEmulatorBridge_setSaveDirNative(JNIEnv* env, jclass, jstring saveDir) {
    if (saveDir == nullptr) return;
    const char* cDir = env->GetStringUTFChars(saveDir, nullptr);
    if (cDir) {
        retrorts::LibretroHost::getInstance().setSaveDir(cDir);
        env->ReleaseStringUTFChars(saveDir, cDir);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_retrorts_ui_NativeEmulatorBridge_sendKeyStringNative(JNIEnv* env, jclass, jstring text) {
    if (text == nullptr) return;
    const char* cText = env->GetStringUTFChars(text, nullptr);
    if (cText) {
        retrorts::LibretroHost::getInstance().sendKeyString(cText);
        env->ReleaseStringUTFChars(text, cText);
    }
}
