cmake_minimum_required(VERSION 3.22.1)
project(reforge_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

option(REFORGE_USE_SYSTEM_CAPSTONE "Use prebuilt Capstone headers/libs if provided" OFF)
set(CAPSTONE_SRC_DIR "" CACHE PATH "Path to Capstone 5.x C source directory")

add_library(reforge_native SHARED
    src/elf_parser.cpp
    src/hex_mmap.cpp
    src/hash_engine.cpp
    src/capstone_bridge.cpp
    src/jni_bridge.cpp
)

target_include_directories(reforge_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

if(CAPSTONE_SRC_DIR AND EXISTS "${CAPSTONE_SRC_DIR}/include/capstone/capstone.h")
    message(STATUS "Building RE FORGE with Capstone source at ${CAPSTONE_SRC_DIR}")
    set(CAPSTONE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(CAPSTONE_BUILD_CSTOOL OFF CACHE BOOL "" FORCE)
    set(CAPSTONE_BUILD_SHARED OFF CACHE BOOL "" FORCE)
    set(CAPSTONE_BUILD_STATIC ON CACHE BOOL "" FORCE)
    set(CAPSTONE_ARM64_SUPPORT ON CACHE BOOL "" FORCE)
    set(CAPSTONE_ARM_SUPPORT ON CACHE BOOL "" FORCE)
    set(CAPSTONE_X86_SUPPORT ON CACHE BOOL "" FORCE)
    add_subdirectory(${CAPSTONE_SRC_DIR} ${CMAKE_CURRENT_BINARY_DIR}/capstone_build EXCLUDE_FROM_ALL)
    target_compile_definitions(reforge_native PRIVATE HAVE_CAPSTONE=1)
    target_include_directories(reforge_native PRIVATE "${CAPSTONE_SRC_DIR}/include")
    target_link_libraries(reforge_native PRIVATE capstone)
else()
    message(STATUS "Capstone source directory not set; compiling with dynamic runtime probe fallback")
    target_compile_definitions(reforge_native PRIVATE HAVE_CAPSTONE=0)
endif()

find_library(log-lib log)
target_link_libraries(reforge_native PRIVATE ${log-lib})
target_link_options(reforge_native PRIVATE "-Wl,-z,max-page-size=16384")
