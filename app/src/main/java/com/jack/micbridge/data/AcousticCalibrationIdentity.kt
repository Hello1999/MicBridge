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
