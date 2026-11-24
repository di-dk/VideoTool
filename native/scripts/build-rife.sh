#!/usr/bin/env bash
# Cross-compiles the RIFE CLI for arm64-v8a. Output: native/out/arm64-v8a/librife.so (an executable, named .so so Android extracts it).
set -euo pipefail
: "${ANDROID_NDK:?}" "${NCNN_DIR:?path to ncnn-*-android-vulkan/arm64-v8a}" ; API=${API:-26}
HERE=$(cd "$(dirname "$0")/.." && pwd); B=${BUILD_DIR:-$HERE/out/build-rife}
cmake -S "$HERE/rife" -B "$B" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
  -Dncnn_DIR="$NCNN_DIR/lib/cmake/ncnn" -DCMAKE_PREFIX_PATH="$NCNN_DIR"
cmake --build "$B" -j"$(nproc)"
mkdir -p "$HERE/out/arm64-v8a"; cp "$B/rife" "$HERE/out/arm64-v8a/librife.so"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$HERE/out/arm64-v8a/librife.so"
ls -l "$HERE/out/arm64-v8a/librife.so"
