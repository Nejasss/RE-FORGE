#include "elf_parser.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <sstream>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace reforge {

namespace {

std::string jsonEscape(const std::string& input) {
    std::ostringstream oss;
    for (unsigned char c : input) {
        switch (c) {
            case '"': oss << "\\\""; break;
            case '\\': oss << "\\\\"; break;
            case '\b': oss << "\\b"; break;
            case '\f': oss << "\\f"; break;
            case '\n': oss << "\\n"; break;
            case '\r': oss << "\\r"; break;
            case '\t': oss << "\\t"; break;
            default:
                if (c < 0x20 || c >= 0x7f) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned int>(c));
                    oss << buf;
                } else {
                    oss << static_cast<char>(c);
                }
                break;
        }
    }
    return oss.str();
}

std::string machineToName(uint16_t machine) {
    switch (machine) {
        case EM_AARCH64: return "ARM64 (AArch64)";
        case EM_ARM: return "ARM (32-bit)";
        case EM_X86_64: return "x86-64";
        case EM_386: return "x86 (IA-32)";
        case EM_RISCV: return "RISC-V";
        case EM_MIPS: return "MIPS";
        default: return "Unknown (0x" + std::to_string(machine) + ")";
    }
}

uint16_t readU16(const uint8_t* p, bool le) {
    if (le) return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
    return (static_cast<uint16_t>(p[0]) << 8) | static_cast<uint16_t>(p[1]);
}

uint32_t readU32(const uint8_t* p, bool le) {
    if (le) {
        return static_cast<uint32_t>(p[0]) |
               (static_cast<uint32_t>(p[1]) << 8) |
               (static_cast<uint32_t>(p[2]) << 16) |
               (static_cast<uint32_t>(p[3]) << 24);
    }
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) |
           static_cast<uint32_t>(p[3]);
}

uint64_t readU64(const uint8_t* p, bool le) {
    if (le) {
        return static_cast<uint64_t>(readU32(p, true)) |
               (static_cast<uint64_t>(readU32(p + 4, true)) << 32);
    }
    return (static_cast<uint64_t>(readU32(p, false)) << 32) |
           static_cast<uint64_t>(readU32(p + 4, false));
}

std::string readCStringBounded(const uint8_t* base, size_t fileSize, uint64_t offset) {
    if (offset >= fileSize) return "";
    size_t maxLen = std::min<size_t>(fileSize - static_cast<size_t>(offset), 512);
    const char* start = reinterpret_cast<const char*>(base + offset);
    size_t len = 0;
    while (len < maxLen && start[len] != '\0') {
        ++len;
    }
    return std::string(start, len);
}

void extractStringsFromRange(
    const uint8_t* base,
    size_t fileSize,
    uint64_t offset,
    uint64_t size,
    const std::string& sectionName,
    size_t maxStrings,
    std::vector<ElfStringItem>& out
) {
    if (offset >= fileSize || size == 0 || out.size() >= maxStrings) return;
    uint64_t end = std::min<uint64_t>(fileSize, offset + size);
    std::string current;
    uint64_t startOffset = offset;
    for (uint64_t pos = offset; pos < end; ++pos) {
        uint8_t b = base[pos];
        if (b >= 0x20 && b <= 0x7e) {
            if (current.empty()) startOffset = pos;
            if (current.size() < 256) current.push_back(static_cast<char>(b));
        } else {
            if (current.size() >= 4) {
                out.push_back({startOffset, sectionName, current});
                if (out.size() >= maxStrings) return;
            }
            current.clear();
        }
    }
    if (current.size() >= 4 && out.size() < maxStrings) {
        out.push_back({startOffset, sectionName, current});
    }
}

} // namespace

ElfAnalysisReport analyzeElfFile(const std::string& path, size_t maxSymbols, size_t maxStrings) {
    ElfAnalysisReport report;
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        report.error = "Unable to open binary file";
        return report;
    }

    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < EI_NIDENT) {
        close(fd);
        report.error = "File is smaller than ELF identification header (16 bytes)";
        return report;
    }

    const size_t fileSize = static_cast<size_t>(st.st_size);
    void* mapped = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) {
        report.error = "mmap failed while mapping binary for inspection";
        return report;
    }

    const auto* data = static_cast<const uint8_t*>(mapped);
    if (std::memcmp(data, ELFMAG, SELFMAG) != 0) {
        munmap(mapped, fileSize);
        report.error = "Invalid ELF magic bytes (expected 7F 45 4C 46)";
        return report;
    }

    const uint8_t elfClass = data[EI_CLASS];
    const uint8_t elfData = data[EI_DATA];
    if ((elfClass != ELFCLASS32 && elfClass != ELFCLASS64) ||
        (elfData != ELFDATA2LSB && elfData != ELFDATA2MSB)) {
        munmap(mapped, fileSize);
        report.error = "Unsupported ELF class or endianness encoding";
        return report;
    }

    const bool is64 = (elfClass == ELFCLASS64);
    const bool le = (elfData == ELFDATA2LSB);
    const size_t minHeader = is64 ? sizeof(Elf64_Ehdr) : sizeof(Elf32_Ehdr);
    if (fileSize < minHeader) {
        munmap(mapped, fileSize);
        report.error = "Truncated ELF header";
        return report;
    }

    report.valid = true;
    report.bitWidth = is64 ? 64 : 32;
    report.littleEndian = le;
    report.objectType = readU16(data + 16, le);
    report.machine = readU16(data + 18, le);
    report.architectureName = machineToName(report.machine);

    uint64_t ePhoff = 0;
    uint64_t eShoff = 0;
    uint16_t ePhentsize = 0;
    uint16_t ePhnum = 0;
    uint16_t eShentsize = 0;
    uint16_t eShnum = 0;
    uint16_t eShstrndx = 0;

    if (is64) {
        report.entryPoint = readU64(data + 24, le);
        ePhoff = readU64(data + 32, le);
        eShoff = readU64(data + 40, le);
        report.flags = readU32(data + 48, le);
        ePhentsize = readU16(data + 54, le);
        ePhnum = readU16(data + 56, le);
        eShentsize = readU16(data + 58, le);
        eShnum = readU16(data + 60, le);
        eShstrndx = readU16(data + 62, le);
    } else {
        report.entryPoint = readU32(data + 24, le);
        ePhoff = readU32(data + 28, le);
        eShoff = readU32(data + 32, le);
        report.flags = readU32(data + 36, le);
        ePhentsize = readU16(data + 42, le);
        ePhnum = readU16(data + 44, le);
        eShentsize = readU16(data + 46, le);
        eShnum = readU16(data + 48, le);
        eShstrndx = readU16(data + 50, le);
    }
    report.phOffset = ePhoff;
    report.shOffset = eShoff;

    // Parse Program Headers (Segments)
    const size_t expectedPhSize = is64 ? 56 : 32;
    if (ePhoff > 0 && ePhnum > 0 && ePhentsize >= expectedPhSize) {
        uint16_t safePhnum = std::min<uint16_t>(ePhnum, 256);
        for (uint16_t i = 0; i < safePhnum; ++i) {
            uint64_t off = ePhoff + static_cast<uint64_t>(i) * ePhentsize;
            if (off + expectedPhSize > fileSize) break;
            const uint8_t* p = data + off;
            ElfSegmentInfo seg{};
            seg.index = i;
            if (is64) {
                seg.type = readU32(p, le);
                seg.flags = readU32(p + 4, le);
                seg.fileOffset = readU64(p + 8, le);
                seg.virtualAddress = readU64(p + 16, le);
                seg.physicalAddress = readU64(p + 24, le);
                seg.fileSize = readU64(p + 32, le);
                seg.memSize = readU64(p + 40, le);
                seg.alignment = readU64(p + 48, le);
            } else {
                seg.type = readU32(p, le);
                seg.fileOffset = readU32(p + 4, le);
                seg.virtualAddress = readU32(p + 8, le);
                seg.physicalAddress = readU32(p + 12, le);
                seg.fileSize = readU32(p + 16, le);
                seg.memSize = readU32(p + 20, le);
                seg.flags = readU32(p + 24, le);
                seg.alignment = readU32(p + 28, le);
            }
            report.segments.push_back(seg);
        }
    }

    // Parse Section Headers
    std::vector<uint32_t> sectionNameOffsets;
    const size_t expectedShSize = is64 ? 64 : 40;
    if (eShoff > 0 && eShnum > 0 && eShentsize >= expectedShSize) {
        uint16_t safeShnum = std::min<uint16_t>(eShnum, 512);
        for (uint16_t i = 0; i < safeShnum; ++i) {
            uint64_t off = eShoff + static_cast<uint64_t>(i) * eShentsize;
            if (off + expectedShSize > fileSize) break;
            const uint8_t* s = data + off;
            ElfSectionInfo sec{};
            sec.index = i;
            uint32_t shName = readU32(s, le);
            sectionNameOffsets.push_back(shName);
            sec.type = readU32(s + 4, le);
            if (is64) {
                sec.flags = readU64(s + 8, le);
                sec.virtualAddress = readU64(s + 16, le);
                sec.fileOffset = readU64(s + 24, le);
                sec.size = readU64(s + 32, le);
                sec.link = readU32(s + 40, le);
                sec.alignment = readU64(s + 48, le);
            } else {
                sec.flags = readU32(s + 8, le);
                sec.virtualAddress = readU32(s + 12, le);
                sec.fileOffset = readU32(s + 16, le);
                sec.size = readU32(s + 20, le);
                sec.link = readU32(s + 24, le);
                sec.alignment = readU32(s + 32, le);
            }
            report.sections.push_back(sec);
        }

        if (eShstrndx < report.sections.size()) {
            const auto& shstr = report.sections[eShstrndx];
            for (size_t i = 0; i < report.sections.size(); ++i) {
                uint64_t namePos = shstr.fileOffset + sectionNameOffsets[i];
                if (namePos < shstr.fileOffset + shstr.size && namePos < fileSize) {
                    report.sections[i].name = readCStringBounded(data, fileSize, namePos);
                }
            }
        }
    }

    // Parse Symbols (.dynsym / .symtab) and DT_NEEDED dependencies
    for (const auto& sec : report.sections) {
        if ((sec.type == SHT_DYNSYM || sec.type == SHT_SYMTAB) &&
            sec.link < report.sections.size() &&
            report.symbols.size() < maxSymbols) {
            const auto& strSec = report.sections[sec.link];
            const size_t symEntrySize = is64 ? 24 : 16;
            if (sec.fileOffset < fileSize && sec.size >= symEntrySize) {
                uint64_t maxEntries = std::min<uint64_t>(sec.size / symEntrySize, maxSymbols - report.symbols.size());
                for (uint64_t idx = 1; idx < maxEntries; ++idx) {
                    uint64_t symPos = sec.fileOffset + idx * symEntrySize;
                    if (symPos + symEntrySize > fileSize) break;
                    const uint8_t* sym = data + symPos;
                    uint32_t stName = readU32(sym, le);
                    ElfSymbolInfo info{};
                    info.isDynamic = (sec.type == SHT_DYNSYM);
                    if (is64) {
                        uint8_t stInfo = sym[4];
                        info.bind = ELF64_ST_BIND(stInfo);
                        info.type = ELF64_ST_TYPE(stInfo);
                        info.sectionIndex = readU16(sym + 6, le);
                        info.value = readU64(sym + 8, le);
                        info.size = readU64(sym + 16, le);
                    } else {
                        info.value = readU32(sym + 4, le);
                        info.size = readU32(sym + 8, le);
                        uint8_t stInfo = sym[12];
                        info.bind = ELF32_ST_BIND(stInfo);
                        info.type = ELF32_ST_TYPE(stInfo);
                        info.sectionIndex = readU16(sym + 14, le);
                    }
                    info.isImport = (info.sectionIndex == SHN_UNDEF);
                    if (stName > 0 && strSec.fileOffset + stName < fileSize) {
                        info.name = readCStringBounded(data, fileSize, strSec.fileOffset + stName);
                    }
                    if (!info.name.empty()) {
                        report.symbols.push_back(std::move(info));
                    }
                }
            }
        } else if (sec.type == SHT_DYNAMIC && sec.link < report.sections.size()) {
            const auto& dynStr = report.sections[sec.link];
            const size_t dynEntrySize = is64 ? 16 : 8;
            if (sec.fileOffset < fileSize && sec.size >= dynEntrySize) {
                uint64_t count = std::min<uint64_t>(sec.size / dynEntrySize, 512);
                for (uint64_t idx = 0; idx < count; ++idx) {
                    uint64_t pos = sec.fileOffset + idx * dynEntrySize;
                    if (pos + dynEntrySize > fileSize) break;
                    uint64_t tag = is64 ? readU64(data + pos, le) : readU32(data + pos, le);
                    uint64_t val = is64 ? readU64(data + pos + 8, le) : readU32(data + pos + 4, le);
                    if (tag == DT_NULL) break;
                    if (tag == DT_NEEDED && dynStr.fileOffset + val < fileSize) {
                        std::string dep = readCStringBounded(data, fileSize, dynStr.fileOffset + val);
                        if (!dep.empty()) {
                            report.dependencies.push_back(std::move(dep));
                        }
                    }
                }
            }
        }
    }

    // Extract printable strings from .rodata / .data / .cstring or whole file fallback
    bool scannedRodata = false;
    for (const auto& sec : report.sections) {
        if (sec.name == ".rodata" || sec.name == ".data" || sec.name == ".dynstr") {
            scannedRodata = true;
            extractStringsFromRange(data, fileSize, sec.fileOffset, sec.size, sec.name, maxStrings, report.strings);
        }
    }
    if (!scannedRodata) {
        extractStringsFromRange(data, fileSize, 0, std::min<size_t>(fileSize, 1024 * 1024), "file", maxStrings, report.strings);
    }

    munmap(mapped, fileSize);
    return report;
}

std::string serializeElfReportJson(const ElfAnalysisReport& r) {
    std::ostringstream out;
    out << "{";
    out << "\"valid\":" << (r.valid ? "true" : "false") << ",";
    out << "\"error\":\"" << jsonEscape(r.error) << "\",";
    out << "\"bitWidth\":" << static_cast<int>(r.bitWidth) << ",";
    out << "\"littleEndian\":" << (r.littleEndian ? "true" : "false") << ",";
    out << "\"objectType\":" << r.objectType << ",";
    out << "\"machine\":" << r.machine << ",";
    out << "\"architectureName\":\"" << jsonEscape(r.architectureName) << "\",";
    out << "\"entryPoint\":" << r.entryPoint << ",";

    out << "\"segments\":[";
    for (size_t i = 0; i < r.segments.size(); ++i) {
        const auto& s = r.segments[i];
        if (i > 0) out << ",";
        out << "{\"index\":" << s.index
            << ",\"type\":" << s.type
            << ",\"flags\":" << s.flags
            << ",\"fileOffset\":" << s.fileOffset
            << ",\"virtualAddress\":" << s.virtualAddress
            << ",\"fileSize\":" << s.fileSize
            << ",\"memSize\":" << s.memSize
            << ",\"alignment\":" << s.alignment << "}";
    }
    out << "],";

    out << "\"sections\":[";
    for (size_t i = 0; i < r.sections.size(); ++i) {
        const auto& s = r.sections[i];
        if (i > 0) out << ",";
        out << "{\"index\":" << s.index
            << ",\"name\":\"" << jsonEscape(s.name) << "\""
            << ",\"type\":" << s.type
            << ",\"flags\":" << s.flags
            << ",\"virtualAddress\":" << s.virtualAddress
            << ",\"fileOffset\":" << s.fileOffset
            << ",\"size\":" << s.size
            << ",\"alignment\":" << s.alignment << "}";
    }
    out << "],";

    out << "\"symbols\":[";
    for (size_t i = 0; i < r.symbols.size(); ++i) {
        const auto& sym = r.symbols[i];
        if (i > 0) out << ",";
        out << "{\"name\":\"" << jsonEscape(sym.name) << "\""
            << ",\"value\":" << sym.value
            << ",\"size\":" << sym.size
            << ",\"bind\":" << static_cast<int>(sym.bind)
            << ",\"type\":" << static_cast<int>(sym.type)
            << ",\"isDynamic\":" << (sym.isDynamic ? "true" : "false")
            << ",\"isImport\":" << (sym.isImport ? "true" : "false") << "}";
    }
    out << "],";

    out << "\"dependencies\":[";
    for (size_t i = 0; i < r.dependencies.size(); ++i) {
        if (i > 0) out << ",";
        out << "\"" << jsonEscape(r.dependencies[i]) << "\"";
    }
    out << "],";

    out << "\"strings\":[";
    for (size_t i = 0; i < r.strings.size(); ++i) {
        const auto& item = r.strings[i];
        if (i > 0) out << ",";
        out << "{\"offset\":" << item.offset
            << ",\"section\":\"" << jsonEscape(item.section) << "\""
            << ",\"value\":\"" << jsonEscape(item.value) << "\"}";
    }
    out << "]}";
    return out.str();
}

} // namespace reforge
