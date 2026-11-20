#include <jni.h>
#include <string>
#include <vector>

#include "capstone_bridge.h"
#include "elf_parser.h"
#include "hash_engine.h"
#include "hex_mmap.h"

namespace {

std::string jstringToUtf8(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* raw = env->GetStringUTFChars(jstr, nullptr);
    if (raw == nullptr) return "";
    std::string out(raw);
    env->ReleaseStringUTFChars(jstr, raw);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_org_bimz_reforge_binary_NativeBinaryBridge_nativeAnalyzeElf(
    JNIEnv* env,
    jobject /* thiz */,
    jstring filePath,
    jint maxSymbols,
    jint maxStrings
) {
    std::string path = jstringToUtf8(env, filePath);
    auto report = reforge::analyzeElfFile(
        path,
        maxSymbols > 0 ? static_cast<size_t>(maxSymbols) : 4096,
        maxStrings > 0 ? static_cast<size_t>(maxStrings) : 2048
    );
    std::string json = reforge::serializeElfReportJson(report);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_bimz_reforge_binary_NativeBinaryBridge_nativeComputeHashes(
    JNIEnv* env,
    jobject /* thiz */,
    jstring filePath
) {
    std::string path = jstringToUtf8(env, filePath);
    std::string json = reforge::computeFileHashesJson(path);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jbyteArray JNICALL
Java_org_bimz_reforge_binary_NativeBinaryBridge_nativeReadSlice(
    JNIEnv* env,
    jobject /* thiz */,
    jstring filePath,
    jlong offset,
    jint length
) {
    if (offset < 0 || length <= 0) {
        return env->NewByteArray(0);
    }
    std::string path = jstringToUtf8(env, filePath);
    auto slice = reforge::readFileSlice(path, static_cast<uint64_t>(offset), static_cast<uint32_t>(length));
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(slice.size()));
    if (arr != nullptr && !slice.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(slice.size()), reinterpret_cast<const jbyte*>(slice.data()));
    }
    return arr;
}

JNIEXPORT jlong JNICALL
Java_org_bimz_reforge_binary_NativeBinaryBridge_nativeSearchBytes(
    JNIEnv* env,
    jobject /* thiz */,
    jstring filePath,
    jlong startOffset,
    jbyteArray pattern
) {
    if (startOffset < 0 || pattern == nullptr) return -1;
    jsize len = env->GetArrayLength(pattern);
    if (len <= 0) return -1;
    std::vector<uint8_t> vec(static_cast<size_t>(len));
    env->GetByteArrayRegion(pattern, 0, len, reinterpret_cast<jbyte*>(vec.data()));
    std::string path = jstringToUtf8(env, filePath);
    return static_cast<jlong>(reforge::searchBytePattern(path, static_cast<uint64_t>(startOffset), vec));
}

JNIEXPORT jboolean JNICALL
Java_org_bimz_reforge_binary_NativeBinaryBridge_nativePatchBytes(
    JNIEnv* env,
    jobject /* thiz */,
    jstring filePath,
    jlong offset,
    jbyteArray replacement
) {
    if (offset < 0 || replacement == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(replacement);
    if (len <= 0) return JNI_FALSE;
    std::vector<uint8_t> vec(static_cast<size_t>(len));
    env->GetByteArrayRegion(replacement, 0, len, reinterpret_cast<jbyte*>(vec.data()));
    std::string path = jstringToUtf8(env, filePath);
    bool ok = reforge::patchFileBytes(path, static_cast<uint64_t>(offset), vec);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_bimz_reforge_binary_NativeBinaryBridge_nativeCapstoneCapabilities(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::string json = reforge::getCapstoneCapabilitiesJson();
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_bimz_reforge_binary_NativeBinaryBridge_nativeDisassemble(
    JNIEnv* env,
    jobject /* thiz */,
    jstring arch,
    jstring mode,
    jbyteArray code,
    jlong baseAddress,
    jint maxInstructions
) {
    if (code == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"Empty code buffer\"}");
    }
    jsize len = env->GetArrayLength(code);
    if (len <= 0) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"Zero-length code buffer\"}");
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(len));
    env->GetByteArrayRegion(code, 0, len, reinterpret_cast<jbyte*>(bytes.data()));
    std::string archStr = jstringToUtf8(env, arch);
    std::string modeStr = jstringToUtf8(env, mode);
    std::string json = reforge::disassembleBytesJson(
        archStr,
        modeStr,
        bytes.data(),
        bytes.size(),
        static_cast<uint64_t>(baseAddress),
        maxInstructions > 0 ? static_cast<size_t>(maxInstructions) : 128
    );
    return env->NewStringUTF(json.c_str());
}

} // extern "C"
