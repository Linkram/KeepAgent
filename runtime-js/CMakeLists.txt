cmake_minimum_required(VERSION 3.22)
project(keepagent_js LANGUAGES C)

set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs-ng)

if(NOT EXISTS ${QJS_DIR}/quickjs.c)
    message(FATAL_ERROR
        "quickjs-ng sources not found at ${QJS_DIR}. "
        "Clone with: git clone --depth 1 https://github.com/quickjs-ng/quickjs.git ${QJS_DIR} "
        "(see README.md)")
endif()

# Core library sources — mirrors the `qjs` target in quickjs-ng's own
# CMakeLists.txt (dtoa.c, libregexp.c, libunicode.c, quickjs.c).
# quickjs-libc.c is intentionally NOT included (it drags in os.h, socket
# code, and the wrong attack surface for a sandbox); the host provides I/O.
set(QJS_SOURCES
    ${QJS_DIR}/dtoa.c
    ${QJS_DIR}/libregexp.c
    ${QJS_DIR}/libunicode.c
    ${QJS_DIR}/quickjs.c
)

add_library(keepagent_js SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/quickjs_bridge.c
    ${QJS_SOURCES}
)

target_include_directories(keepagent_js PRIVATE ${QJS_DIR})
target_compile_definitions(keepagent_js PRIVATE _GNU_SOURCE QUICKJS_NG_BUILD)
target_compile_features(keepagent_js PRIVATE c_std_11)
# Android 15+ devices may use 16 KiB memory pages. NDK r26 does not opt in
# automatically, so align every load segment explicitly.
target_link_options(keepagent_js PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

# JNI headers: on Android the NDK sysroot provides jni.h — no host JDK
# required. (Host-side builds, if ever, fall back to find_package(JNI).)
if(ANDROID)
    target_include_directories(keepagent_js PRIVATE ${CMAKE_SYSROOT}/usr/include)
else()
    find_package(JNI REQUIRED)
    target_include_directories(keepagent_js PRIVATE ${JNI_INCLUDE_DIRS})
endif()

find_package(Threads REQUIRED)
find_library(M_LIB m)
target_link_libraries(keepagent_js Threads::Threads)
if(M_LIB)
    target_link_libraries(keepagent_js ${M_LIB})
endif()
