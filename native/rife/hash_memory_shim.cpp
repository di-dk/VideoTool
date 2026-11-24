// The prebuilt ncnn was compiled against a newer libc++ that provides
// std::__hash_memory; NDK r27's libc++ does not. Any consistent hash is valid
// here (only used for in-process unordered containers), so provide FNV-1a.
#include <cstddef>
#include <cstdint>
namespace std { inline namespace __ndk1 {
size_t __hash_memory(const void* p, size_t n) noexcept
{
    const unsigned char* b = static_cast<const unsigned char*>(p);
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; i++) { h ^= b[i]; h *= 1099511628211ULL; }
    return static_cast<size_t>(h);
}
}}
