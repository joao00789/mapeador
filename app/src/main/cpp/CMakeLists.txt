cmake_minimum_required(VERSION 3.22.1)
project(mapeador_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mapeador_engine SHARED
    jni_bridge.cpp
    engine.cpp
    evdev.cpp
    keymap.cpp
    android_api.cpp
)

target_include_directories(mapeador_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)

target_link_libraries(mapeador_engine
    ${log-lib}
)

target_compile_options(mapeador_engine PRIVATE -Wall -Wextra -O2)
