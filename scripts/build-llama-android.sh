#!/usr/bin/env bash
set -euo pipefail

# Build llama.cpp for Android (arm64-v8a) and copy resulting .so files into
# app/src/main/cpp/llama/prebuilt/arm64-v8a/
#
# Usage:
#   ./scripts/build-llama-android.sh [--ndk /path/to/ndk]
#
# Requirements:
#  - Android NDK 27+
#  - CMake 3.22+
#  - git, python (for build scripts)

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
LLAMA_DIR="$ROOT_DIR/app/src/main/cpp/llama"
PREBUILT_DIR="$LLAMA_DIR/prebuilt/arm64-v8a"
BUILD_DIR="$LLAMA_DIR/build-android"
# Support --download-ndk flag and --enable-openmp to force building with OpenMP
DOWNLOAD_NDK=false
ENABLE_OPENMP=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --download-ndk)
      DOWNLOAD_NDK=true; shift ;;
    --enable-openmp)
      ENABLE_OPENMP=true; shift ;;
    --)
      shift; break ;;
    *)
      break ;;
  esac
done

NDK_PATH="${1:-${NDK:-${ANDROID_NDK_HOME:-}}}"

if [ -z "$NDK_PATH" ]; then
  if [ "$DOWNLOAD_NDK" = true ]; then
    echo "No NDK provided, will download Android NDK r27b into build directory (this may take a few minutes)."
    NDK_ZIP="$BUILD_DIR/android-ndk-r27b-linux.zip"
    NDK_EXTRACT_DIR="$BUILD_DIR/android-ndk-r27b"
    if [ ! -d "$NDK_EXTRACT_DIR" ]; then
      echo "Downloading NDK..."
      curl -L -o "$NDK_ZIP" "https://dl.google.com/android/repository/android-ndk-r27b-linux.zip"
      unzip -q "$NDK_ZIP" -d "$BUILD_DIR"
      # The archive extracts to android-ndk-r27b/
    fi
    NDK_PATH="$NDK_EXTRACT_DIR"
  else
    echo "Android NDK not found. Please set NDK or ANDROID_NDK_HOME env var, or pass path as the first arg."
    echo "To auto-download NDK r27b, run: ./scripts/build-llama-android.sh --download-ndk"
    echo "To build with OpenMP (not recommended by default), pass --enable-openmp"
    echo "See docs/LOCAL_AI_SETUP.md for instructions."
    exit 1
  fi
fi

echo "Using NDK: $NDK_PATH"

# Print OpenMP mode
if [ "$ENABLE_OPENMP" = true ]; then
  echo "Building with OpenMP enabled (libomp dependency will be required at runtime)."
else
  echo "Building with OpenMP disabled (recommended, produces libs without libomp dependency)."
fi

# Ensure prebuilt dir exists
mkdir -p "$PREBUILT_DIR"
mkdir -p "$BUILD_DIR"

# Clone llama.cpp into a temporary directory inside the build dir
LLAMA_SRC_DIR="$BUILD_DIR/llama.cpp-src"
if [ -d "$LLAMA_SRC_DIR" ]; then
  echo "Updating existing llama.cpp clone..."
  git -C "$LLAMA_SRC_DIR" fetch --depth=1 origin main || git -C "$LLAMA_SRC_DIR" fetch --depth=1 origin master || true
  git -C "$LLAMA_SRC_DIR" pull --ff-only || true
else
  echo "Cloning llama.cpp..."
  git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$LLAMA_SRC_DIR"
fi

# Configure and build for android arm64
BUILD_SUBDIR="$BUILD_DIR/arm64"
rm -rf "$BUILD_SUBDIR"
mkdir -p "$BUILD_SUBDIR"
pushd "$BUILD_SUBDIR" >/dev/null

CMAKE_TOOLCHAIN="$NDK_PATH/build/cmake/android.toolchain.cmake"
if [ ! -f "$CMAKE_TOOLCHAIN" ]; then
  echo "Cannot find Android toolchain at $CMAKE_TOOLCHAIN"
  exit 2
fi

# Configure OpenMP flag explicitly
if [ "$ENABLE_OPENMP" = true ]; then
  OPENMP_FLAG="-DGGML_OPENMP=ON"
  CXX_FLAGS=""
else
  OPENMP_FLAG="-DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DOpenMP_FOUND=0"
  CXX_FLAGS=""
fi

echo "OpenMP flag: $OPENMP_FLAG"

cmake "$LLAMA_SRC_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$CMAKE_TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DBUILD_SHARED_LIBS=ON \
  -DGGML_CPU_SIZE=64 \
  -DGGML_CUDA=OFF \
  $OPENMP_FLAG \
  $CXX_FLAGS

# Note: The above sets -DGGML_OPENMP=ON when --enable-openmp is passed; otherwise defaults to OFF.

cmake --build . --config Release -j$(nproc) -- llama ggml ggml-cpu ggml-base || cmake --build . --config Release -j$(nproc)

# Copy resulting .so files to prebuilt dir
# Locate built libs
LIB_FILES=("libggml-base.so" "libggml-cpu.so" "libggml.so" "libllama.so")
FOUND=()
for f in "${LIB_FILES[@]}"; do
  src=$(find . -name "$f" -print -quit || true)
  if [ -n "$src" ]; then
    echo "Found $f at $src"
    cp "$src" "$PREBUILT_DIR/"
    FOUND+=("$f")
  fi
done

if [ ${#FOUND[@]} -eq 0 ]; then
    echo "No libraries found - build may have failed. Inspect build logs in $BUILD_SUBDIR"
    exit 3
fi

if [ ${#FOUND[@]} -ne ${#LIB_FILES[@]} ]; then
  echo "Incomplete llama.cpp build. Expected: ${LIB_FILES[*]}; found: ${FOUND[*]}"
  exit 4
fi

# The JNI wrapper is compiled separately by Gradle and needs the public headers
# that correspond exactly to these shared libraries. Previously only .so files
# were copied, which made a clean native-enabled Gradle build fail or fall back
# to the stub implementation.
INCLUDE_DIR="$PREBUILT_DIR/include"
mkdir -p "$INCLUDE_DIR"
cp "$LLAMA_SRC_DIR"/include/*.h "$INCLUDE_DIR/"
cp "$LLAMA_SRC_DIR"/ggml/include/*.h "$INCLUDE_DIR/"

popd >/dev/null

echo "Copied libraries: ${FOUND[*]} to $PREBUILT_DIR"
echo "Copied llama.cpp and ggml public headers to $INCLUDE_DIR"

echo "Done. Now rebuild the app: ./gradlew :app:assembleDebug"
