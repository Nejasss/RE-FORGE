package org.bimz.reforge.core.model

enum class CheckStatus {
    PASS,
    WARNING,
    FAILED,
    NOT_TESTED
}

data class VerificationItem(
    val checkName: String,
    val status: CheckStatus,
    val detail: String
)

data class ApkVerificationReport(
    val targetApkPath: String,
    val outputSha256: String,
    val overallStatus: CheckStatus,
    val items: List<VerificationItem>
)

enum class RebuildStatus {
    SUPPORTED,
    PARTIALLY_SUCCESSFUL,
    UNSUPPORTED,
    FAILED
}

data class RebuildResult(
    val status: RebuildStatus,
    val outputApkPath: String?,
    val changedFileCount: Int,
    val buildLog: String
)
