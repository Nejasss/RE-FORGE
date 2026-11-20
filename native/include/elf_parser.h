#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace reforge {

struct ElfSectionInfo {
    uint32_t index;
    std::string name;
    uint32_t type;
    uint64_t flags;
    uint64_t virtualAddress;
    uint64_t fileOffset;
    uint64_t size;
    uint32_t link;
    uint64_t alignment;
};

struct ElfSegmentInfo {
    uint32_t index;
    uint32_t type;
    uint32_t flags;
    uint64_t fileOffset;
    uint64_t virtualAddress;
    uint64_t physicalAddress;
    uint64_t fileSize;
    uint64_t memSize;
    uint64_t alignment;
};

struct ElfSymbolInfo {
    std::string name;
    uint64_t value;
    uint64_t size;
    uint8_t bind;
    uint8_t type;
    uint16_t sectionIndex;
    bool isDynamic;
    bool isImport;
};

struct ElfStringItem {
    uint64_t offset;
    std::string section;
    std::string value;
};

struct ElfAnalysisReport {
    bool valid = false;
    std::string error;
    uint8_t bitWidth = 0;
    bool littleEndian = true;
    uint16_t objectType = 0;
    uint16_t machine = 0;
    std::string architectureName;
    uint64_t entryPoint = 0;
    uint64_t phOffset = 0;
    uint64_t shOffset = 0;
    uint32_t flags = 0;
    std::vector<ElfSegmentInfo> segments;
    std::vector<ElfSectionInfo> sections;
    std::vector<ElfSymbolInfo> symbols;
    std::vector<std::string> dependencies;
    std::vector<ElfStringItem> strings;
};

ElfAnalysisReport analyzeElfFile(const std::string& path, size_t maxSymbols = 4096, size_t maxStrings = 2048);
std::string serializeElfReportJson(const ElfAnalysisReport& report);

} // namespace reforge
