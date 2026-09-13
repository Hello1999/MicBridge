package com.jack.micbridge.service

import com.jack.micbridge.data.AcousticCalibrationIdentity
import com.jack.micbridge.data.CalibrationStage
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.OperationResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSessionTest {
    @Test
    fun `display observer failure cannot interrupt the verified calibration sequence`() {
        val session = CalibrationSession { error("unavailable display observer") }
        completeRound(session, identity())
        assertTrue(session.identityForCommit(identity(), finalBoundarySafe = true) == identity())
    }

    @Test
    fun `UI observes only verified stages and failed rounds return to idle`() {
        val observed = mutableListOf<CalibrationStage>()
        val session = CalibrationSession(observed::add)
        session.beginOpenAttempt(identity())
        session.observeOpen(identity(), openResult().copy(controlReadback = false))
        assertTrue(observed.all { it == CalibrationStage.IDLE })
        completeRound(session, identity())
        assertTrue(observed.takeLast(3) == listOf(CalibrationStage.OPEN, CalibrationStage.ISOLATED, CalibrationStage.BLOCKED))
        session.reset()
        assertTrue(observed.last() == CalibrationStage.IDLE)
    }

    @Test
    fun `commit requires guarded open root isolation then verified blocked in same session`() {
        val session = CalibrationSession()
        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)

        assertTrue(session.beginOpenAttempt(identity()))
        session.observeOpen(identity(), openResult())
        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)

        session.observeRootFailsafeIsolation(identity(), verified = true)
        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)

        session.observeIsolationConfirmationAndBlock(identity(), verified = true)
        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = false) != null)

        // A failed commit check invalidates the round; complete a fresh one for success.
        completeRound(session, identity())
        assertTrue(session.identityForCommit(identity(), finalBoundarySafe = true) == identity())

        // A successful identity handoff is consumed too; a failed settings write cannot retry it.
        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)

        session.reset()
        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)
    }

    @Test
    fun `unguarded or unreadable open never qualifies`() {
        val session = CalibrationSession()
        session.beginOpenAttempt(identity())
        session.observeOpen(identity(), openResult().copy(leaseRootWatchdogArmed = false))
        session.observeRootFailsafeIsolation(identity(), verified = true)
        session.observeIsolationConfirmationAndBlock(identity(), verified = true)
        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)

        session.beginOpenAttempt(identity())
        session.observeOpen(identity(), openResult().copy(controlReadback = false))
        session.observeRootFailsafeIsolation(identity(), verified = true)
        session.observeIsolationConfirmationAndBlock(identity(), verified = true)
        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)
    }

    @Test
    fun `failed or out of order root isolation never qualifies`() {
        val session = CalibrationSession()
        session.observeRootFailsafeIsolation(identity(), verified = true)
        session.beginOpenAttempt(identity())
        session.observeOpen(identity(), openResult())
        session.observeRootFailsafeIsolation(identity(), verified = false)
        session.observeIsolationConfirmationAndBlock(identity(), verified = true)

        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)
    }

    @Test
    fun `failed second round invalidates a previously complete round`() {
        val session = CalibrationSession()
        completeRound(session, identity())
        assertTrue(session.identityForCommit(identity(), finalBoundarySafe = true) != null)

        session.beginOpenAttempt(identity())
        session.observeOpen(identity(), openResult().copy(controlReadback = false))
        session.observeRootFailsafeIsolation(identity(), verified = false)
        session.observeIsolationConfirmationAndBlock(identity(), verified = true)

        assertFalse(session.identityForCommit(identity(), finalBoundarySafe = true) != null)
    }

    @Test
    fun `target update between any steps invalidates the whole round`() {
        val original = identity(targetVersion = "1.0:100")
        val updated = identity(targetVersion = "1.1:101")
        val session = CalibrationSession()

        session.beginOpenAttempt(original)
        session.observeOpen(original, openResult())
        session.observeRootFailsafeIsolation(updated, verified = true)
        session.observeIsolationConfirmationAndBlock(updated, verified = true)

        assertFalse(session.identityForCommit(updated, finalBoundarySafe = true) != null)
    }

    @Test
    fun `submission never returns a newly observed package version as tested`() {
        val tested = identity(targetVersion = "1.0:100")
        val replacement = identity(targetVersion = "1.1:101")
        val session = CalibrationSession()
        completeRound(session, tested)

        assertFalse(session.identityForCommit(replacement, finalBoundarySafe = true) != null)

        // The mismatch consumed the proof; changing the app back cannot revive it.
        assertFalse(session.identityForCommit(tested, finalBoundarySafe = true) != null)
    }

    @Test
    fun `same-version reinstall and controller or system changes invalidate the round`() {
        val original = identity()
        val changes = listOf(
            original.copy(settingsGeneration = original.settingsGeneration + 1L),
            original.copy(targetPackageUid = original.targetPackageUid + 1),
            original.copy(targetPackageLastUpdateTimeEpochMs = 2_000L),
            original.copy(targetSigningCertificateSha256 = "bb"),
            original.copy(controllerId = "audio_manager"),
            original.copy(micBridgeBuildId = "new-build"),
            original.copy(androidBuildId = "new-android-build"),
            original.copy(androidFingerprint = "new-fingerprint"),
            original.copy(androidUserId = 10),
        )

        changes.forEach { changed ->
            val session = CalibrationSession()
            session.beginOpenAttempt(original)
            session.observeOpen(changed, openResult())
            assertFalse(session.matchesCurrentIdentity(original))
        }
    }

    @Test
    fun `missing identity fails closed`() {
        val session = CalibrationSession()
        assertFalse(session.beginOpenAttempt(null))
        session.observeOpen(null, openResult())
        session.observeRootFailsafeIsolation(null, verified = true)
        session.observeIsolationConfirmationAndBlock(null, verified = true)
        assertFalse(session.identityForCommit(null, finalBoundarySafe = true) != null)
    }

    private fun completeRound(
        session: CalibrationSession,
        identity: AcousticCalibrationIdentity,
    ) {
        session.beginOpenAttempt(identity)
        session.observeOpen(identity, openResult())
        session.observeRootFailsafeIsolation(identity, verified = true)
        session.observeIsolationConfirmationAndBlock(identity, verified = true)
    }

    private fun identity(targetVersion: String = "1.0:100") = AcousticCalibrationIdentity(
        settingsGeneration = 1L,
        controllerId = "fake",
        targetPackage = "com.openai.chatgpt",
        targetPackageVersion = targetVersion,
        targetPackageUid = 10123,
        targetPackageLastUpdateTimeEpochMs = 1_000L,
        targetSigningCertificateSha256 = "aa",
        micBridgeBuildId = "micbridge-build",
        androidBuildId = "android-build",
        androidFingerprint = "vendor/device/build",
        androidUserId = 0,
    )

    private fun openResult() = OperationResult(
        ok = false,
        commandSucceeded = true,
        micAccess = MicAccessState.OPEN,
        previous = MicAccessState.BLOCKED,
        controlReadback = true,
        acousticCalibrationValid = false,
        controllerId = "fake",
        requestId = "calibration-test",
        autoBlockAtEpochMs = 20_000L,
        latencyMs = 1L,
        leaseExactAlarmArmed = true,
        leaseRootWatchdogArmed = true,
        errorCode = "ACOUSTIC_CALIBRATION_REQUIRED",
    )
}
