# CMakeLists.txt for native llama.cpp integration
# Optimized for ARM64 (Pixel devices on GrapheneOS)
cmake_minimum_required(VERSION 3.22.1)
project(llama_jni)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# 16KB page size compatibility (required for Android 15+ / Pixel 9+)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# llama.cpp prebuilt library path
set(LLAMA_PREBUILT_DIR ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI})

# Keep the imported ABI set all-or-nothing. Importing only libllama.so gives a
# confusing linker or runtime error when one of its ggml dependencies is absent.
set(LLAMA_REQUIRED_FILES
    libggml-base.so
    libggml-cpu.so
    libggml.so
    libllama.so
    include/llama.h
)
set(LLAMA_PREBUILT_COMPLETE TRUE)
foreach(REQUIRED_FILE IN LISTS LLAMA_REQUIRED_FILES)
    if(NOT EXISTS ${LLAMA_PREBUILT_DIR}/${REQUIRED_FILE})
        message(STATUS "Missing llama.cpp prebuilt file: ${LLAMA_PREBUILT_DIR}/${REQUIRED_FILE}")
        set(LLAMA_PREBUILT_COMPLETE FALSE)
    endif()
endforeach()

# Check for the complete prebuilt native backend
if(LLAMA_PREBUILT_COMPLETE)
    message(STATUS "Using prebuilt llama.cpp from ${LLAMA_PREBUILT_DIR}")
    
    # Import prebuilt libraries
    add_library(ggml-base SHARED IMPORTED)
    set_target_properties(ggml-base PROPERTIES 
        IMPORTED_LOCATION ${LLAMA_PREBUILT_DIR}/libggml-base.so
    )
    
    add_library(ggml-cpu SHARED IMPORTED)
    set_target_properties(ggml-cpu PROPERTIES 
        IMPORTED_LOCATION ${LLAMA_PREBUILT_DIR}/libggml-cpu.so
    )
    
    add_library(ggml SHARED IMPORTED)
    set_target_properties(ggml PROPERTIES 
        IMPORTED_LOCATION ${LLAMA_PREBUILT_DIR}/libggml.so
    )
    
    add_library(llama SHARED IMPORTED)
    set_target_properties(llama PROPERTIES 
        IMPORTED_LOCATION ${LLAMA_PREBUILT_DIR}/libllama.so
    )
    
    # Build JNI wrapper
    add_library(llama_jni SHARED
        ${CMAKE_SOURCE_DIR}/llama_jni.cpp
    )
    
    target_include_directories(llama_jni PRIVATE
        ${LLAMA_PREBUILT_DIR}/include
    )
    
    find_library(log-lib log)
    find_library(android-lib android)
    
    target_link_libraries(llama_jni
        llama
        ggml
        ggml-cpu
        ggml-base
        ${log-lib}
        ${android-lib}
    )
    
    target_compile_definitions(llama_jni PRIVATE
        LLAMA_CPP_AVAILABLE
    )
    
else()
    # No complete native backend - use the stub for development builds.
    message(WARNING "Complete llama.cpp prebuilt backend not found. Building stub library.")
    
    # Build stub library
    add_library(llama_jni SHARED
        ${CMAKE_SOURCE_DIR}/llama_jni_stub.cpp
    )
    
    find_library(log-lib log)
    target_link_libraries(llama_jni ${log-lib})
endif()

# Output library naming
set_target_properties(llama_jni PROPERTIES
    OUTPUT_NAME "llama_jni"
)
