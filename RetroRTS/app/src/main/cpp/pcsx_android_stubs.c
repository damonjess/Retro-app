#include <stdio.h>
#include <stdarg.h>
#include <android/log.h>

#define LOG_TAG "PCSX"

// Most Sys* functions are provided by main.c and libretro.c
// SysRunGui is missing from main.c so we provide a stub here.

void SysRunGui() {}
