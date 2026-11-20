#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace reforge {

std::string getCapstoneCapabilitiesJson();
std::string disassembleBytesJson(
    const std::string& arch,
    const std::string& mode,
    const uint8_t* code,
    size_t codeSize,
    uint64_t baseAddress,
    size_t maxInstructions
);

} // namespace reforge
