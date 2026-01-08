# CMakeLists.txt for native whisper.cpp integration
cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Optimization flags for release
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")

# ARM NEON optimizations for mobile
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp+simd")
    add_compile_definitions(GGML_USE_CPU_AARCH64)
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -mfloat-abi=softfp")
endif()

# Whisper.cpp source directory
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# Check if whisper.cpp exists with new structure (src/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/src/whisper.cpp)
    message(WARNING "whisper.cpp not found. Building stub library.")
    
    # Build stub library
    add_library(whisper_jni SHARED
        ${CMAKE_SOURCE_DIR}/whisper_jni_stub.cpp
    )
    
    find_library(log-lib log)
    target_link_libraries(whisper_jni ${log-lib})
    
else()
    message(STATUS "Building with whisper.cpp from ${WHISPER_DIR}")
    
    # Include directories for new structure
    include_directories(
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/src
        ${WHISPER_DIR}/ggml/include
        ${WHISPER_DIR}/ggml/src
        ${CMAKE_SOURCE_DIR}
    )

    # GGML sources (new structure in ggml/src/)
    set(GGML_SOURCES
        ${WHISPER_DIR}/ggml/src/ggml.c
        ${WHISPER_DIR}/ggml/src/ggml-alloc.c
        ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
        ${WHISPER_DIR}/ggml/src/ggml-quants.c
        ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
        ${WHISPER_DIR}/ggml/src/ggml-backend-reg.cpp
        ${WHISPER_DIR}/ggml/src/gguf.cpp
    )

    # GGML CPU backend
    file(GLOB GGML_CPU_SOURCES
        ${WHISPER_DIR}/ggml/src/ggml-cpu/*.c
        ${WHISPER_DIR}/ggml/src/ggml-cpu/*.cpp
    )

    # Whisper sources (new structure in src/)
    set(WHISPER_SOURCES
        ${WHISPER_DIR}/src/whisper.cpp
    )

    # JNI bridge
    set(JNI_SOURCES
        ${CMAKE_SOURCE_DIR}/whisper_jni.cpp
    )

    # Build shared library
    add_library(whisper_jni SHARED
        ${GGML_SOURCES}
        ${GGML_CPU_SOURCES}
        ${WHISPER_SOURCES}
        ${JNI_SOURCES}
    )

    # Link libraries
    find_library(log-lib log)
    find_library(android-lib android)

    target_link_libraries(whisper_jni
        ${log-lib}
        ${android-lib}
    )

    # Compile definitions
    target_compile_definitions(whisper_jni PRIVATE
        GGML_USE_CPU
        _GNU_SOURCE
    )

endif()
