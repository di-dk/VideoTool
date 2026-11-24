#!/usr/bin/env bash
# Cross-compiles x264 + vid.stab + ffmpeg CLI (GPL) for arm64-v8a.
# Output: native/out/arm64-v8a/libffmpeg.so (executable named .so so Android extracts it to nativeLibraryDir)
# Env: ANDROID_NDK, SRC_DIR (contains ffmpeg/ x264/ vidstab/ clones)
set -euo pipefail
: "${ANDROID_NDK:?}" "${SRC_DIR:?}"; API=${API:-26}
HERE=$(cd "$(dirname "$0")/.." && pwd); PREFIX=$HERE/out/deps; mkdir -p "$PREFIX" "$HERE/out/arm64-v8a"
TC=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64; export PATH=$TC/bin:$PATH
export CC=aarch64-linux-android$API-clang CXX=aarch64-linux-android$API-clang++ AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
LDPAGE="-Wl,-z,max-page-size=16384"

# --- x264
B=$HERE/out/build-x264; rm -rf "$B"; mkdir -p "$B"; cd "$B"
"$SRC_DIR/x264/configure" --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --enable-pic \
  --disable-cli --disable-opencl --extra-ldflags="$LDPAGE"
make -j"$(nproc)" install

# --- vid.stab
B=$HERE/out/build-vidstab; rm -rf "$B"
cmake -S "$SRC_DIR/vidstab" -B "$B" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DUSE_OMP=OFF -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_POSITION_INDEPENDENT_CODE=ON
cmake --build "$B" -j"$(nproc)"; cmake --install "$B"

# --- ffmpeg
B=$HERE/out/build-ffmpeg; rm -rf "$B"; mkdir -p "$B"; cd "$B"
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig
"$SRC_DIR/ffmpeg/configure" --prefix="$B/install" --enable-cross-compile --target-os=android --arch=aarch64 \
  --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --nm=llvm-nm \
  --pkg-config=pkg-config --pkg-config-flags=--static \
  --enable-gpl --enable-libx264 --enable-libvidstab --enable-pic \
  --disable-shared --enable-static --disable-doc --disable-debug --disable-ffplay --disable-ffprobe \
  --disable-network --disable-autodetect --enable-small \
  --extra-cflags="-I$PREFIX/include" --extra-ldflags="-L$PREFIX/lib -pie $LDPAGE" --extra-libs="-lm"
make -j"$(nproc)"
cp ffmpeg "$HERE/out/arm64-v8a/libffmpeg.so"; llvm-strip "$HERE/out/arm64-v8a/libffmpeg.so"
ls -l "$HERE/out/arm64-v8a/libffmpeg.so"
