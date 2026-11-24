# VideoTool

An Android toolbox for editing video on the phone. Tools are small plug-ins, so new features are easy to add.

![banner](art/banner-1200x630.png)

## Tools

| Tool | What it does |
|---|---|
| **Smooth & Stabilize** | Raises the frame rate (2×, 60 or 120 fps) using **RIFE** AI interpolation on the GPU (Vulkan) or FFmpeg `minterpolate` on the CPU, with optional two-pass **vid.stab** stabilization (Off / Light / Strong). |

Results are saved to `Movies/VideoTool` and show up in the gallery. Long jobs run in a foreground service (progress notification, cancel button), so they keep going with the screen off.

## Adding a tool

1. Implement `VideoTool` (`app/src/main/java/dev/videotool/app/tools/VideoTool.kt`): an id, title, a list of options and a `run(job)` function. `JobContext` gives you the input file, an `FfmpegRunner`, a `RifeRunner`, a work dir, the output file and a progress callback.
2. Add it to `ToolRegistry.all`.

The home screen, option form, background service, notification and save-to-gallery are generic.

## Building

Requirements: JDK 17, Android SDK (platform 35), NDK r27, CMake 3.22.

The FFmpeg and RIFE executables are cross-compiled and shipped inside the APK as `lib*.so` files (they are executables, extracted at install time to `nativeLibraryDir`).

```bash
export ANDROID_NDK=$HOME/Android/sdk/ndk/27.2.12479018
# 1. FFmpeg (+ x264 + vid.stab). SRC_DIR holds git clones of FFmpeg/FFmpeg, videolan/x264, georgmartius/vid.stab as ffmpeg/, x264/, vidstab/
SRC_DIR=$HOME/src native/scripts/build-ffmpeg.sh
# 2. RIFE. NCNN_DIR is the arm64-v8a folder of Tencent/ncnn's ncnn-*-android-vulkan release
NCNN_DIR=$HOME/src/ncnn-android-vulkan/arm64-v8a native/scripts/build-rife.sh
# 3. copy native/out/arm64-v8a/{libffmpeg,librife,libomp}.so into app/src/main/jniLibs/arm64-v8a/
echo "sdk.dir=$HOME/Android/sdk" > local.properties
./gradlew assembleDebug
```

Target: arm64-v8a, Android 10+ (minSdk 29). RIFE needs a Vulkan-capable GPU; without one the app falls back to the FFmpeg engine.

## Limits

- RIFE works on frames up to 1080p (taller videos are scaled down for that engine).
- Output is 8-bit H.264 / AAC in MP4. HDR and 10-bit sources are converted to 8-bit SDR without tone mapping.
- Interpolation is applied in short segments, so a motion blip can occur at segment joins.

## License

GPL-3.0-or-later, because the bundled FFmpeg build enables GPL components (x264, vid.stab). RIFE-ncnn-vulkan (MIT, `native/rife/LICENSE-rife-ncnn-vulkan`) and its RIFE v4.6 model are by nihui and the RIFE authors. Artwork was generated locally with ComfyUI (see `art/PROMPTS.md`).
