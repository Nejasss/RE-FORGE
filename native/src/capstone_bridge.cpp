#include "capstone_bridge.h"

#include <cstdio>
#include <sstream>

#if HAVE_CAPSTONE
#include <capstone/capstone.h>
#endif

namespace reforge {

namespace {

std::string escapeJson(const std::string& s) {
    std::ostringstream oss;
    for (char c : s) {
        if (c == '"') oss << "\\\"";
        else if (c == '\\') oss << "\\\\";
        else if (c == '\n') oss << "\\n";
        else oss << c;
    }
    return oss.str();
}

} // namespace

std::string getCapstoneCapabilitiesJson() {
#if HAVE_CAPSTONE
    int major = 0, minor = 0;
    cs_version(&major, &minor);
    bool arm64 = cs_support(CS_ARCH_ARM64);
    bool arm = cs_support(CS_ARCH_ARM);
    bool x86 = cs_support(CS_ARCH_X86);
    std::ostringstream oss;
    oss << "{"
        << "\"compiled\":true,"
        << "\"version\":\"" << major << "." << minor << "\","
        << "\"arm64\":" << (arm64 ? "true" : "false") << ","
        << "\"arm\":" << (arm ? "true" : "false") << ","
        << "\"x86\":" << (x86 ? "true" : "false")
        << "}";
    return oss.str();
#else
    return "{\"compiled\":false,\"version\":\"none\",\"arm64\":false,\"arm\":false,\"x86\":false}";
#endif
}

std::string disassembleBytesJson(
    const std::string& arch,
    const std::string& mode,
    const uint8_t* code,
    size_t codeSize,
    uint64_t baseAddress,
    size_t maxInstructions
) {
#if HAVE_CAPSTONE
    cs_arch csArch = CS_ARCH_ARM64;
    cs_mode csMode = CS_MODE_ARM;

    if (arch == "arm64") {
        csArch = CS_ARCH_ARM64;
        csMode = CS_MODE_ARM;
    } else if (arch == "arm") {
        csArch = CS_ARCH_ARM;
        csMode = (mode == "thumb") ? CS_MODE_THUMB : CS_MODE_ARM;
    } else if (arch == "x86_64") {
        csArch = CS_ARCH_X86;
        csMode = CS_MODE_64;
    } else if (arch == "x86") {
        csArch = CS_ARCH_X86;
        csMode = CS_MODE_32;
    } else {
        return "{\"ok\":false,\"error\":\"Unsupported architecture requested\"}";
    }

    csh handle = 0;
    cs_err err = cs_open(csArch, csMode, &handle);
    if (err != CS_ERR_OK) {
        return std::string("{\"ok\":false,\"error\":\"") + cs_strerror(err) + "\"}";
    }

    cs_insn* insn = nullptr;
    size_t count = cs_disasm(handle, code, codeSize, baseAddress, maxInstructions, &insn);

    std::ostringstream oss;
    oss << "{\"ok\":true,\"count\":" << count << ",\"instructions\":[";
    for (size_t i = 0; i < count; ++i) {
        if (i > 0) oss << ",";
        char hexBytes[64] = {0};
        size_t bLen = insn[i].size < 16 ? insn[i].size : 16;
        for (size_t b = 0; b < bLen; ++b) {
            snprintf(hexBytes + b * 3, 4, "%02X ", insn[i].bytes[b]);
        }
        if (bLen > 0) hexBytes[bLen * 3 - 1] = '\0';

        oss << "{"
            << "\"address\":" << insn[i].address << ","
            << "\"size\":" << insn[i].size << ","
            << "\"bytes\":\"" << hexBytes << "\","
            << "\"mnemonic\":\"" << escapeJson(insn[i].mnemonic) << "\","
            << "\"opStr\":\"" << escapeJson(insn[i].op_str) << "\""
            << "}";
    }
    oss << "]}";

    if (count > 0 && insn != nullptr) {
        cs_free(insn, count);
    }
    cs_close(&handle);
    return oss.str();
#else
    (void)arch;
    (void)mode;
    (void)code;
    (void)codeSize;
    (void)baseAddress;
    (void)maxInstructions;
    return "{\"ok\":false,\"error\":\"Capstone native engine not linked in this build configuration\"}";
#endif
}

} // namespace reforge
