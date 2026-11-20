#include "hash_engine.h"

#include <array>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace reforge {

namespace {

class Sha256State {
public:
    Sha256State() {
        h_ = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
              0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};
    }

    void update(const uint8_t* data, size_t len) {
        for (size_t i = 0; i < len; ++i) {
            buffer_[bufLen_++] = data[i];
            totalBits_ += 8;
            if (bufLen_ == 64) {
                compress(buffer_.data());
                bufLen_ = 0;
            }
        }
    }

    std::string finalHex() {
        buffer_[bufLen_++] = 0x80;
        if (bufLen_ > 56) {
            while (bufLen_ < 64) buffer_[bufLen_++] = 0x00;
            compress(buffer_.data());
            bufLen_ = 0;
        }
        while (bufLen_ < 56) buffer_[bufLen_++] = 0x00;
        for (int i = 7; i >= 0; --i) {
            buffer_[bufLen_++] = static_cast<uint8_t>((totalBits_ >> (i * 8)) & 0xffu);
        }
        compress(buffer_.data());

        char out[65];
        for (size_t i = 0; i < 8; ++i) {
            snprintf(out + i * 8, 9, "%08x", h_[i]);
        }
        out[64] = '\0';
        return std::string(out);
    }

private:
    static uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

    void compress(const uint8_t block[64]) {
        static constexpr uint32_t k[64] = {
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
        };

        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(block[i * 4]) << 24) |
                   (static_cast<uint32_t>(block[i * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(block[i * 4 + 2]) << 8) |
                   static_cast<uint32_t>(block[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }

        uint32_t a = h_[0], b = h_[1], c = h_[2], d = h_[3];
        uint32_t e = h_[4], f = h_[5], g = h_[6], h = h_[7];

        for (int i = 0; i < 64; ++i) {
            uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t temp1 = h + S1 + ch + k[i] + w[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t temp2 = S0 + maj;

            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }

        h_[0] += a; h_[1] += b; h_[2] += c; h_[3] += d;
        h_[4] += e; h_[5] += f; h_[6] += g; h_[7] += h;
    }

    std::array<uint32_t, 8> h_{};
    std::array<uint8_t, 64> buffer_{};
    size_t bufLen_ = 0;
    uint64_t totalBits_ = 0;
};

uint32_t updateCrc32(uint32_t crc, const uint8_t* data, size_t len) {
    crc = ~crc;
    for (size_t i = 0; i < len; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
        }
    }
    return ~crc;
}

std::string detectMagicSignature(const uint8_t* header, size_t len) {
    if (len >= 4 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F') {
        return "ELF Executable / Shared Library (7F 45 4C 46)";
    }
    if (len >= 4 && header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n') {
        return "Dalvik Executable DEX (64 65 78 0A)";
    }
    if (len >= 4 && header[0] == 'P' && header[1] == 'K' && header[2] == 0x03 && header[3] == 0x04) {
        return "ZIP / APK / JAR Archive (50 4B 03 04)";
    }
    if (len >= 4 && header[0] == 0x03 && header[1] == 0x00 && header[2] == 0x08 && header[3] == 0x00) {
        return "Android Binary XML AXML (03 00 08 00)";
    }
    if (len >= 4 && header[0] == 0x02 && header[1] == 0x00 && header[2] == 0x0c && header[3] == 0x00) {
        return "Android Resource Table ARSC (02 00 0C 00)";
    }
    if (len >= 4 && header[0] == 0xca && header[1] == 0xfe && header[2] == 0xba && header[3] == 0xbe) {
        return "Java Class / Mach-O Universal Binary (CA FE BA BE)";
    }
    if (len >= 2 && header[0] == 'M' && header[1] == 'Z') {
        return "Windows PE / DOS Executable (4D 5A)";
    }
    if (len >= 4 && header[0] == 0x00 && header[1] == 'a' && header[2] == 's' && header[3] == 'm') {
        return "WebAssembly Binary Module (00 61 73 6D)";
    }
    return "Raw / Unclassified Binary";
}

} // namespace

std::string computeFileHashesJson(const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return "{\"ok\":false,\"error\":\"Cannot open file for native hashing\"}";
    }

    Sha256State sha256;
    uint32_t crc = 0;
    uint64_t totalBytes = 0;
    std::array<uint8_t, 64> headerBytes{};
    size_t headerCaptured = 0;

    std::vector<uint8_t> chunk(128 * 1024);
    while (true) {
        ssize_t n = read(fd, chunk.data(), chunk.size());
        if (n < 0) {
            close(fd);
            return "{\"ok\":false,\"error\":\"Read error during native hashing\"}";
        }
        if (n == 0) break;
        if (headerCaptured < headerBytes.size()) {
            size_t copyLen = std::min<size_t>(headerBytes.size() - headerCaptured, static_cast<size_t>(n));
            std::memcpy(headerBytes.data() + headerCaptured, chunk.data(), copyLen);
            headerCaptured += copyLen;
        }
        sha256.update(chunk.data(), static_cast<size_t>(n));
        crc = updateCrc32(crc, chunk.data(), static_cast<size_t>(n));
        totalBytes += static_cast<uint64_t>(n);
    }
    close(fd);

    char crcHex[16];
    snprintf(crcHex, sizeof(crcHex), "%08x", crc);

    char magicHex[64] = {0};
    size_t previewBytes = std::min<size_t>(headerCaptured, 16);
    for (size_t i = 0; i < previewBytes; ++i) {
        snprintf(magicHex + i * 3, 4, "%02X ", headerBytes[i]);
    }
    if (previewBytes > 0) magicHex[previewBytes * 3 - 1] = '\0';

    std::ostringstream oss;
    oss << "{"
        << "\"ok\":true,"
        << "\"size\":" << totalBytes << ","
        << "\"sha256\":\"" << sha256.finalHex() << "\","
        << "\"crc32\":\"" << crcHex << "\","
        << "\"magicHex\":\"" << magicHex << "\","
        << "\"signature\":\"" << detectMagicSignature(headerBytes.data(), headerCaptured) << "\""
        << "}";
    return oss.str();
}

} // namespace reforge
