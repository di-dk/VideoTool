// Stub: VideoTool only exchanges JPG/PNG frames with rife-ncnn-vulkan, so the
// libwebp dependency of upstream is replaced by no-op functions.
#ifndef WEBP_IMAGE_H
#define WEBP_IMAGE_H
static inline unsigned char* webp_load(const unsigned char*, int, int*, int*, int*) { return 0; }
static inline int webp_save(const char*, int, int, int, const unsigned char*) { return 0; }
#endif
