package com.jack.micbridge.data

/** Immutable identity of the system microphone environment exercised by calibration. */
data class AcousticCalibrationIdentity(
    /** Changes when the selected system controller changes. */
    val settingsGeneration: Long,
    val controllerId: String,
    val micBridgeBuildId: String,
    val androidBuildId: String,
    val androidFingerprint: String,
    val androidUserId: Int,
)
