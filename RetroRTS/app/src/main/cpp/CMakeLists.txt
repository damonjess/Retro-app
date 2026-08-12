cmake_minimum_required(VERSION 3.22.1)
project(pcsx_rearmed LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(pcsx_rearmed_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/pcsx_rearmed)

# Detect if PCSX-ReARMed source is present
set(PCSX_CORE_PRESENT TRUE)
message(STATUS "PCSX-ReARMed source forced — building full PS1 core")

if(PCSX_CORE_PRESENT)
    # Enable ASM if building for ARM64
    if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        enable_language(ASM)
    endif()

    set(PCSX_SOURCES
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/psxbios.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/psxcounters.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/psxdma.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/psxhw.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/psxinterpreter.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/psxmem.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/r3000a.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/cdrom.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/cdriso.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/cheat.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/decode_xa.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/mdec.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/misc.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/sio.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/ppf.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/psxcommon.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/psxevents.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/plugins.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/database.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/gpu.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/gte.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/gte_nf.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/spu.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/cdrom-async.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/gte_divider.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/pad.c

        # Frontend / Libretro
        ${pcsx_rearmed_SOURCE_DIR}/frontend/plugin.c
        ${pcsx_rearmed_SOURCE_DIR}/frontend/cspace.c
        ${pcsx_rearmed_SOURCE_DIR}/frontend/libretro.c

        # SPU Plugin
        ${pcsx_rearmed_SOURCE_DIR}/plugins/dfsound/dma.c
        ${pcsx_rearmed_SOURCE_DIR}/plugins/dfsound/freeze.c
        ${pcsx_rearmed_SOURCE_DIR}/plugins/dfsound/registers.c
        ${pcsx_rearmed_SOURCE_DIR}/plugins/dfsound/spu.c
        ${pcsx_rearmed_SOURCE_DIR}/plugins/dfsound/out.c
        ${pcsx_rearmed_SOURCE_DIR}/plugins/dfsound/nullsnd.c

        # GPU Plugin
        ${pcsx_rearmed_SOURCE_DIR}/plugins/gpulib/gpu.c
        ${pcsx_rearmed_SOURCE_DIR}/plugins/gpulib/prim.c
        ${pcsx_rearmed_SOURCE_DIR}/plugins/gpulib/vout_pl.c

        # Dynarec
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/new_dynarec/new_dynarec.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/new_dynarec/pcsxmem.c
        ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/new_dynarec/emu_if.c

        # Libretro Common Features
        ${pcsx_rearmed_SOURCE_DIR}/deps/libretro-common/features/features_cpu.c
        ${pcsx_rearmed_SOURCE_DIR}/deps/libretro-common/string/stdstring.c
        ${pcsx_rearmed_SOURCE_DIR}/deps/libretro-common/compat/compat_strl.c
        ${pcsx_rearmed_SOURCE_DIR}/deps/libretro-common/encodings/encoding_utf.c
        ${pcsx_rearmed_SOURCE_DIR}/frontend/pcsxr-threads.c
        ${pcsx_rearmed_SOURCE_DIR}/frontend/main.c
        ${CMAKE_CURRENT_SOURCE_DIR}/pcsx_android_stubs.c
    )

    if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        list(APPEND PCSX_SOURCES
            ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/gte_arm64.S
            ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/gte_nf_arm64.S
            ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore/new_dynarec/linkage_arm64.S
        )
    endif()

    add_library(pcsx_core STATIC ${PCSX_SOURCES})
else()
    add_library(pcsx_core STATIC pcsx_android_stubs.c)
endif()

target_include_directories(pcsx_core PUBLIC
    ${pcsx_rearmed_SOURCE_DIR}
    ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore
    ${pcsx_rearmed_SOURCE_DIR}/include
    ${pcsx_rearmed_SOURCE_DIR}/frontend
    ${pcsx_rearmed_SOURCE_DIR}/plugins/gpulib
    ${pcsx_rearmed_SOURCE_DIR}/plugins/dfsound
    ${pcsx_rearmed_SOURCE_DIR}/deps/libretro-common/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(pcsx_core PRIVATE
    __ANDROID__=1
    NEON_BUILD=1
    HAVE_ZLIB=1
    BUILT_IN_GPU=1
    HAVE_LIBRETRO=1
    REARMED=1
    NO_FRONTEND=1
    NDRC_THREAD=1
    PLATFORM=libretro
)

if(NOT CMAKE_ANDROID_ARCH_ABI MATCHES "arm|aarch64")
    target_compile_definitions(pcsx_core PRIVATE DRC_DISABLE=1)
endif()

find_package(ZLIB REQUIRED)
target_link_libraries(pcsx_core PRIVATE ZLIB::ZLIB)

# Main JNI shared library
add_library(pcsx_rearmed SHARED
    native-lib.cpp
    emulator_core.cpp
    libretro_bridge.cpp
    dsi_core.cpp
    ps1_core.cpp
    amiga_core.cpp
    xbox_core.cpp
    amiga_uae_bridge_jni.cpp
    dosbox_bridge_jni.cpp
    dosbox_audio.cpp
    surface_jni.cpp
    pcsx_jni_entry.cpp
    gpu_android.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(aaudio-lib aaudio)

target_link_libraries(pcsx_rearmed
    -Wl,--whole-archive pcsx_core -Wl,--no-whole-archive
    ${log-lib}
    ${android-lib}
    ${aaudio-lib}
)

# Enforce 16 KB page alignment for Android 15+ compatibility
target_link_options(pcsx_rearmed PRIVATE "-Wl,-z,max-page-size=16384")

if(NOT PCSX_CORE_PRESENT)
    set_target_properties(pcsx_rearmed PROPERTIES LINK_FLAGS "-Wl,--allow-shlib-undefined")
endif()

target_include_directories(pcsx_rearmed PRIVATE
    ${pcsx_rearmed_SOURCE_DIR}
    ${pcsx_rearmed_SOURCE_DIR}/libpcsxcore
    ${pcsx_rearmed_SOURCE_DIR}/include
)

if(PCSX_CORE_PRESENT)
    target_compile_definitions(pcsx_rearmed PRIVATE PCSX_CORE_PRESENT=1)
else()
    target_compile_definitions(pcsx_rearmed PRIVATE PCSX_CORE_PRESENT=0)
endif()
