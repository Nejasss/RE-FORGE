#include "hex_mmap.h"

#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace reforge {

std::vector<uint8_t> readFileSlice(const std::string& path, uint64_t offset, uint32_t length) {
    std::vector<uint8_t> buffer;
    if (length == 0 || length > 1024 * 1024) return buffer;

    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return buffer;

    struct stat st {};
    if (fstat(fd, &st) != 0 || offset >= static_cast<uint64_t>(st.st_size)) {
        close(fd);
        return buffer;
    }

    uint64_t available = static_cast<uint64_t>(st.st_size) - offset;
    size_t toRead = static_cast<size_t>(std::min<uint64_t>(available, length));
    buffer.resize(toRead);

    ssize_t bytesRead = pread64(fd, buffer.data(), toRead, static_cast<off64_t>(offset));
    close(fd);

    if (bytesRead <= 0) {
        buffer.clear();
    } else {
        buffer.resize(static_cast<size_t>(bytesRead));
    }
    return buffer;
}

int64_t searchBytePattern(const std::string& path, uint64_t startOffset, const std::vector<uint8_t>& pattern) {
    if (pattern.empty() || pattern.size() > 4096) return -1;

    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;

    struct stat st {};
    if (fstat(fd, &st) != 0 || startOffset >= static_cast<uint64_t>(st.st_size)) {
        close(fd);
        return -1;
    }

    const uint64_t fileSize = static_cast<uint64_t>(st.st_size);
    const size_t chunkSize = 256 * 1024;
    std::vector<uint8_t> window(chunkSize + pattern.size());

    uint64_t currentOffset = startOffset;
    while (currentOffset < fileSize) {
        size_t toRead = static_cast<size_t>(std::min<uint64_t>(fileSize - currentOffset, window.size()));
        ssize_t n = pread64(fd, window.data(), toRead, static_cast<off64_t>(currentOffset));
        if (n < static_cast<ssize_t>(pattern.size())) break;

        auto it = std::search(
            window.begin(),
            window.begin() + n,
            pattern.begin(),
            pattern.end()
        );
        if (it != window.begin() + n) {
            close(fd);
            return static_cast<int64_t>(currentOffset + static_cast<uint64_t>(std::distance(window.begin(), it)));
        }

        if (currentOffset + static_cast<uint64_t>(n) >= fileSize) break;
        currentOffset += static_cast<uint64_t>(n) - (pattern.size() - 1);
    }

    close(fd);
    return -1;
}

bool patchFileBytes(const std::string& path, uint64_t offset, const std::vector<uint8_t>& replacement) {
    if (replacement.empty() || replacement.size() > 65536) return false;

    int fd = open(path.c_str(), O_RDWR | O_CLOEXEC);
    if (fd < 0) return false;

    struct stat st {};
    if (fstat(fd, &st) != 0 || offset + replacement.size() > static_cast<uint64_t>(st.st_size)) {
        close(fd);
        return false;
    }

    ssize_t written = pwrite64(fd, replacement.data(), replacement.size(), static_cast<off64_t>(offset));
    fsync(fd);
    close(fd);
    return written == static_cast<ssize_t>(replacement.size());
}

} // namespace reforge
