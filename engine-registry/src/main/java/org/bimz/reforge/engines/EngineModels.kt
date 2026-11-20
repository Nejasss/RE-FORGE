package org.bimz.reforge.engines

enum class EngineStatus {
    INTEGRATED,
    AVAILABLE,
    PARTIALLY_INTEGRATED,
    UNSUPPORTED,
    DISABLED,
    FAILED,
    NOT_TESTED
}

enum class EngineIntegrationType {
    JNI_CPP_NATIVE,
    JVM_ANDROID_LIBRARY,
    BUNDLED_ARM64_EXECUTABLE,
    ANDROID_FRAMEWORK_API
}

data class EngineDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val sourceRepository: String,
    val license: String,
    val integrationType: EngineIntegrationType,
    val status: EngineStatus,
    val androidAbi: String,
    val initialized: Boolean,
    val enabled: Boolean,
    val estimatedBinarySizeBytes: Long,
    val features: List<String>,
    val runtimeDependencies: List<String>,
    val compatibilityReport: String,
    val lastTestResult: String
)
