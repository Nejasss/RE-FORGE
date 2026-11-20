#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace reforge {

std::vector<uint8_t> readFileSlice(const std::string& path, uint64_t offset, uint32_t length);
int64_t searchBytePattern(const std::string& path, uint64_t startOffset, const std::vector<uint8_t>& pattern);
bool patchFileBytes(const std::string& path, uint64_t offset, const std::vector<uint8_t>& replacement);

} // namespace reforge
