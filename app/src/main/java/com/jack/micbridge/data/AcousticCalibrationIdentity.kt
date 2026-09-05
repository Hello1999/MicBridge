package com.jack.micbridge.data

/**
 * Immutable identity of the exact environment exercised by one acoustic-calibration round.
 *
 * This deliberately includes more than a package version. A same-version reinstall or a
 * same-name replacement must not inherit evidence gathered for a different installed app.
 */
data class AcousticCalibrationIdentity(
    /** Monotonic counter changed whenever MicBridge's selected controller or target changes. */
    val settingsGeneration: Long,
    val controllerId: String,
    val targetPackage: String,
    val targetPackageVersion: String,
    val targetPackageUid: Int,
    val targetPackageLastUpdateTimeEpochMs: Long,
    val targetSigningCertificateSha256: String,
    val micBridgeBuildId: String,
    val androidBuildId: String,
    val androidFingerprint: String,
    val androidUserId: Int,
)

/**
 * Explains, in user-facing Chinese, why a stored calibration no longer authorizes the current
 * environment. Returns null only when both identities exist and are equal. The order of checks
 * matters: the most likely and most actionable cause is reported first.
 */
fun describeCalibrationMismatch(
    stored: AcousticCalibrationIdentity?,
    current: AcousticCalibrationIdentity?,
): String? {
    if (current == null) return "目标 ChatGPT 应用不可用或未授予录音权限"
    if (stored == null) return "尚未完成声学校准"
    if (stored == current) return null
    return when {
        stored.androidUserId != current.androidUserId -> "Android 用户与校准时不一致"
        stored.settingsGeneration != current.settingsGeneration ||
            stored.controllerId != current.controllerId ||
            stored.targetPackage != current.targetPackage -> "控制器或目标包设置已更改"
        stored.targetPackageUid != current.targetPackageUid ||
            stored.targetSigningCertificateSha256 != current.targetSigningCertificateSha256 ->
            "ChatGPT 已重新安装或签名变化"
        stored.targetPackageVersion != current.targetPackageVersion ||
            stored.targetPackageLastUpdateTimeEpochMs != current.targetPackageLastUpdateTimeEpochMs ->
            "ChatGPT 已更新"
        stored.androidFingerprint != current.androidFingerprint ||
            stored.androidBuildId != current.androidBuildId -> "Android 系统已更新"
        stored.micBridgeBuildId != current.micBridgeBuildId -> "MicBridge 已重装或更新"
        else -> "校准环境已变化"
    }
}
