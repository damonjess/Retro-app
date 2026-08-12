#include "gpu_android.h"
#include <android/native_window.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <atomic>
#include <algorithm>

#define LOG_TAG "RetroRTS_GPU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr int VRAM_W = 1024;
static constexpr int VRAM_H = 512;

static ANativeWindow*    g_window  = nullptr;
static uint16_t*         g_vram    = nullptr;
static std::atomic<bool> g_running{false};

// PS1 display area — updated by GPU_writeStatus_android via PCSX core hooks
static std::atomic<int> g_dispX{0};
static std::atomic<int> g_dispY{0};
static std::atomic<int> g_dispW{640};
static std::atomic<int> g_dispH{480};

extern "C" void gpu_android_set_window(ANativeWindow* window) {
    if (g_window) ANativeWindow_release(g_window);
    g_window = window;
    if (g_window) {
        ANativeWindow_acquire(g_window);
        ANativeWindow_setBuffersGeometry(
            g_window, 0, 0, WINDOW_FORMAT_RGBX_8888);
        g_running.store(true);
        LOGI("Surface set %p", g_window);
    } else {
        g_running.store(false);
    }
}

extern "C" void gpu_android_set_vram(uint16_t* vram) {
    g_vram = vram;
    LOGI("VRAM registered %p", vram);
}

// Intercept GPU status commands to capture display area
extern "C" void GPU_writeStatus_android(uint32_t data) {
    uint32_t cmd = data >> 24;
    switch (cmd) {
        case 0x05: // Display Start
            g_dispX.store(data & 0x3FF);
            g_dispY.store((data >> 10) & 0x1FF);
            break;
        case 0x06: { // Horizontal Range
            int x1 = data & 0xFFF;
            int x2 = (data >> 12) & 0xFFF;
            if (x2 > x1) g_dispW.store((x2 - x1) / 8); // approximate
            break;
        }
        case 0x07: { // Vertical Range
            int y1 = data & 0x3FF;
            int y2 = (data >> 10) & 0x3FF;
            if (y2 > y1) g_dispH.store(y2 - y1);
            break;
        }
    }
}

// Called by PCSX-ReARMed gpulib every vblank — this is where we blit
extern "C" void GPU_vBlank_android(int val, int start) {
    if (val != 1 || !g_window || !g_vram || !g_running.load()) return;

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(g_window, &buf, nullptr) != 0) return;

    auto* dst      = static_cast<uint32_t*>(buf.bits);
    int   dstStride = buf.stride;

    int srcX = 0, srcY = 0, srcW = 640, srcH = 480;
    {
        srcX = std::clamp(g_dispX.load(), 0, VRAM_W - 1);
        srcY = std::clamp(g_dispY.load(), 0, VRAM_H - 1);
        srcW = std::clamp(g_dispW.load(), 1, VRAM_W - srcX);
        srcH = std::clamp(g_dispH.load(), 1, VRAM_H - srcY);
    }

    int drawW = std::min(buf.width,  srcW);
    int drawH = std::min(buf.height, srcH);

    for (int y = 0; y < drawH; y++) {
        int vramY = srcY + y;
        if (vramY >= VRAM_H) break;
        const uint16_t* src = g_vram + (vramY * VRAM_W) + srcX;
        uint32_t*       row = dst    + (y     * dstStride);
        for (int x = 0; x < drawW; x++) {
            if (srcX + x >= VRAM_W) break;
            uint16_t px = src[x];
            uint8_t  r  = (px & 0x1F)         << 3;
            uint8_t  g2 = ((px >>  5) & 0x1F) << 3;
            uint8_t  b  = ((px >> 10) & 0x1F) << 3;
            row[x] = (0xFFu << 24) | (b << 16) | (g2 << 8) | r;
        }
    }

    ANativeWindow_unlockAndPost(g_window);
}

// Kept for compatibility if something else expects it
extern "C" void GPU_updateLace(void) {
    GPU_vBlank_android(1, 0);
}

// Satisfy PCSX-ReARMed gpulib renderer expectations
extern "C" int renderer_init(void) { return 0; }
extern "C" void renderer_finish(void) {}
extern "C" void renderer_sync_ecmds(uint32_t* ecmds, int count) {}
extern "C" void renderer_notify_screen_change(int x, int y) {}
extern "C" void renderer_do_cmd_list(uint32_t* list, int count) {}
extern "C" void renderer_flush_queues(void) {}
extern "C" void renderer_update_caches(int x, int y, int w, int h) {}
extern "C" void renderer_set_interlace(int enable, int is_odd) {}
extern "C" void renderer_set_config(const void* config) {}
