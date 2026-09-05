package com.jack.micbridge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationMismatchTest {
    private val base = AcousticCalibrationIdentity(
        settingsGeneration = 1L,
        controllerId = "audio_manager",
        targetPackage = "com.openai.chatgpt",
        targetPackageVersion = "1.0:100",
        targetPackageUid = 10432,
        targetPackageLastUpdateTimeEpochMs = 1_000L,
        targetSigningCertificateSha256 = "abc",
        micBridgeBuildId = "build-1",
        androidBuildId = "TQ3A",
        androidFingerprint = "motorola/fp",
        androidUserId = 0,
    )

    @Test
    fun `equal identities are valid`() {
        assertNull(describeCalibrationMismatch(base, base))
    }

    @Test
    fun `missing stored or current identity is explained`() {
        assertEquals("尚未完成声学校准", describeCalibrationMismatch(null, base))
        assertEquals(
            "目标 ChatGPT 应用不可用或未授予录音权限",
            describeCalibrationMismatch(base, null),
        )
        assertEquals(
            "目标 ChatGPT 应用不可用或未授予录音权限",
            describeCalibrationMismatch(null, null),
        )
    }

    @Test
    fun `target app update is the reported cause`() {
        val updated = base.copy(
            targetPackageVersion = "1.1:101",
            targetPackageLastUpdateTimeEpochMs = 2_000L,
        )
        assertEquals("ChatGPT 已更新", describeCalibrationMismatch(base, updated))
        assertEquals(
            "ChatGPT 已更新",
            describeCalibrationMismatch(base, base.copy(targetPackageLastUpdateTimeEpochMs = 3L)),
        )
    }

    @Test
    fun `reinstall system update settings change and user mismatch are distinguished`() {
        assertEquals(
            "ChatGPT 已重新安装或签名变化",
            describeCalibrationMismatch(base, base.copy(targetPackageUid = 10999)),
        )
        assertEquals(
            "Android 系统已更新",
            describeCalibrationMismatch(base, base.copy(androidFingerprint = "other")),
        )
        assertEquals(
            "MicBridge 已重装或更新",
            describeCalibrationMismatch(base, base.copy(micBridgeBuildId = "build-2")),
        )
        assertEquals(
            "控制器或目标包设置已更改",
            describeCalibrationMismatch(base, base.copy(settingsGeneration = 2L)),
        )
        assertEquals(
            "Android 用户与校准时不一致",
            describeCalibrationMismatch(base, base.copy(androidUserId = 10)),
        )
    }

    @Test
    fun `user mismatch outranks every other difference`() {
        val changed = base.copy(androidUserId = 10, targetPackageVersion = "9:9")
        assertEquals("Android 用户与校准时不一致", describeCalibrationMismatch(base, changed))
    }
}
