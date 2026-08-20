# Local AI Setup

Run GGUF models on-device via llama.cpp — fully offline, no API key required for inference.

## User Setup

1. Open **Settings**
2. Set **AI Provider** to **Local AI (Offline)**
3. In **Local AI Models**, tap a model to download (1–5 GB)
4. Wait for download; the model loads automatically

## Available Models

Models defined in `LocalModelManager.AVAILABLE_MODELS`:

| Model | ID | Size | Notes |
|-------|-----|------|-------|
| **Qwen3 4B** ⭐ | `qwen3-4b` | ~2.5 GB | Recommended, thinking mode |
| Qwen3 1.7B | `qwen3-1.7b` | ~1.8 GB | Fastest Qwen3 |
| **Gemma 4 E2B** ⭐ | `gemma-4-e2b` | ~3.1 GB | Google, Gemma prompt format |
| Gemma 4 E4B | `gemma-4-e4b` | ~5.0 GB | Larger, tablets |
| **DeepSeek R1 1.5B** ⭐ | `deepseek-r1-distill-qwen-1.5b` | ~1.1 GB | Reasoning |
| **SmolLM3 3B** ⭐ | `smollm3-3b` | ~1.9 GB | Multilingual |
| **Phi-4 Mini 3.8B** ⭐ | `phi-4-mini-instruct` | ~2.5 GB | Reasoning |
| Gemma 3 4B | `gemma-3-4b-it` | ~2.5 GB | Multilingual |

⭐ = recommended in the app UI

**Limitations in local mode:** no web search, weather, vision, or tool calling.

## Developer: Native Libraries

Prebuilt libs and matching headers live in `app/src/main/cpp/llama/prebuilt/arm64-v8a/` (gitignored). A release build fails before packaging if they are absent, so it cannot silently ship the stub backend.

### Quick build (recommended)

```bash
export NDK=/path/to/android/ndk
./scripts/build-llama-android.sh "$NDK"
./gradlew :app:assembleDebug
```

Auto-download NDK:

```bash
./scripts/build-llama-android.sh --download-ndk
```

OpenMP (not recommended — requires `libomp.so` on device):

```bash
./scripts/build-llama-android.sh --enable-openmp /path/to/ndk
```

### Manual CMake build

```bash
cd app/src/main/cpp/llama/llama.cpp
export NDK=/path/to/android/sdk/ndk/27.0.12077973
export CMAKE=/path/to/android/sdk/cmake/3.22.1/bin/cmake

mkdir build-android && cd build-android
$CMAKE .. \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_CPU_AARCH64=ON \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_CURL=OFF \
    -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
$CMAKE --build . --config Release -j4 -- llama

cp bin/libllama.so ../prebuilt/arm64-v8a/
cp bin/libggml*.so ../prebuilt/arm64-v8a/
```

## 16KB Page Size

Linker flag `-Wl,-z,max-page-size=16384` ensures compatibility with Android 15+ and Pixel 9+.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "No local model loaded" | Download a model in Settings; check free storage |
| Release build fails on `checkLlamaPrebuilt` | Run `scripts/build-llama-android.sh <path-to-android-ndk>` |
| Slow inference | Use Qwen3 1.7B or DeepSeek R1 1.5B; close other apps |
| Download fails | Check network and 3+ GB free space |

## Technical Details

- JNI bridge: `LlamaCppBridge.kt` → `llama_jni.cpp`
- Quantization: Q4_K_M (default for most models)
- Prompt formats: ChatML (default), Gemma (`promptFormat = "gemma"`)
- Generation defaults: temperature 0.4, max 1024 tokens, context 2048 (configurable in code)
