package com.jack.micbridge.safety

import com.jack.micbridge.data.SettingsRepository
import com.jack.micbridge.data.SafetyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootFailSafeScriptsTest {
    @Test
    fun `direct boot gate requires exact controller user and appops package`() {
        val appOps = SafetyTarget(
            SettingsRepository.CONTROLLER_APP_OPS,
            "com.openai.chatgpt",
            10,
        )

        assertTrue(directBootTargetMatches(appOps, appOps.controllerId, appOps.targetPackage, 10))
        assertFalse(directBootTargetMatches(appOps, appOps.controllerId, "other.app", 10))
        assertFalse(directBootTargetMatches(appOps, appOps.controllerId, appOps.targetPackage, 0))
        assertFalse(
            directBootTargetMatches(
                appOps,
                SettingsRepository.CONTROLLER_AUDIO_MANAGER,
                appOps.targetPackage,
                10,
            ),
        )
    }

    @Test
    fun `direct boot gate normalizes global controller package`() {
        val sensor = SafetyTarget(
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
            "android-global-microphone",
            0,
        )

        assertTrue(
            directBootTargetMatches(
                sensor,
                sensor.controllerId,
                "com.openai.chatgpt",
                0,
            ),
        )
    }

    @Test
    fun `watcher uses absolute boottime deadline and retries fresh block until verified`() {
        val script = watcher(SettingsRepository.CONTROLLER_SENSOR_PRIVACY)

        assertTrue(script.contains("DEADLINE_MS='123456789'"))
        assertTrue(script.contains("BLOCK_AT_MS='123446789'"))
        assertTrue(script.contains("/proc/uptime"))
        assertTrue(script.contains("NOW_MS\" -ge \"\$BLOCK_AT_MS"))
        assertTrue(script.contains("CLOCK_FAILED=1"))
        assertTrue(script.contains("sleep 0.05"))
        assertTrue(script.contains("sleep 0.15"))

        val finalFreshReset = script.lastIndexOf("BLOCK_VERIFIED=0")
        val finalBlock = script.lastIndexOf("timeout -k 0.1 0.5 cmd sensor_privacy enable")
        val finalVerification = script.indexOf("if [ \"\$BLOCK_VERIFIED\" = 1 ]", finalBlock)
        val terminalCommit = script.indexOf("write_lease \"expired-blocked-\$REQUEST_ID\"", finalVerification)
        val retrySleep = script.indexOf("sleep 0.15", terminalCommit)
        assertTrue(finalFreshReset >= 0)
        assertTrue(finalBlock > finalFreshReset)
        assertTrue(finalVerification > finalBlock)
        assertTrue(terminalCommit > finalVerification)
        assertTrue(retrySleep > terminalCommit)

        assertFalse(script.contains("ENFORCE_UNTIL_MS"))
        assertFalse(script.contains("FINAL_VERIFIED"))
        assertFalse(script.contains("sleep 30"))
    }

    @Test
    fun `watcher uses crash releasing flock correct traps and bounded control calls`() {
        val script = watcher(SettingsRepository.CONTROLLER_SENSOR_PRIVACY)

        assertTrue(script.contains("exec 0>>\"\$LOCK_FILE\""))
        assertTrue(script.contains("flock -n -x 0"))
        assertTrue(script.contains("flock -u 0"))
        assertFalse(script.contains("mkdir \"\$LOCK"))
        assertTrue(script.contains("trap cleanup EXIT"))
        assertTrue(script.contains("trap 'exit 1' HUP INT TERM"))
        assertFalse(script.contains("trap cleanup EXIT HUP INT TERM"))
        assertTrue(script.contains("timeout -k 0.1 0.5 cmd sensor_privacy enable"))
        assertTrue(script.contains("timeout -k 0.1 0.5 dumpsys sensor_privacy"))
    }

    @Test
    fun `watcher treats flock contention as retryable before and after the deadline`() {
        val script = watcher(SettingsRepository.CONTROLLER_SENSOR_PRIVACY)

        assertTrue(script.contains("write_status \"arm-lock-failed-\$REQUEST_ID\""))
        val contentionOffsets = Regex("lock-contended-\\\$REQUEST_ID").findAll(script).map { it.range.first }.toList()
        assertEquals(2, contentionOffsets.size)
        contentionOffsets.forEach { offset ->
            val retry = script.indexOf("continue", startIndex = offset)
            val fatalExit = script.indexOf("exit 1", startIndex = offset)
            assertTrue("lock contention must reach a retry", retry in (offset + 1)..(offset + 400))
            assertTrue("lock contention must retry before any fatal exit", fatalExit == -1 || retry < fatalExit)
        }
    }

    @Test
    fun `legacy appops root failsafes close only the global sensor gate`() {
        val scripts = listOf(
            watcher(SettingsRepository.CONTROLLER_APP_OPS),
            RootFailSafeScripts.bootGuard(
                SettingsRepository.CONTROLLER_APP_OPS,
                "com.openai.chatgpt",
                10,
                "g-external-block",
            )!!,
            RootFailSafeScripts.revokeLease(
                SettingsRepository.CONTROLLER_APP_OPS,
                "com.openai.chatgpt",
                10,
                "request-external-block",
                "cancel-request-external-block-1-2",
            )!!,
        )

        scripts.forEach { script ->
            assertTrue(script.contains("timeout -k 0.1 0.5 cmd sensor_privacy enable"))
            assertTrue(script.contains("timeout -k 0.1 0.5 dumpsys sensor_privacy"))
            assertFalse("legacy fail-safe must not overwrite AppOps provenance", script.contains("cmd appops"))
        }

        val prerequisites = RootFailSafeScripts.prerequisiteCheck(SettingsRepository.CONTROLLER_APP_OPS)
        assertNotNull(prerequisites)
        assertTrue(prerequisites!!.contains("command -v dumpsys"))
        assertTrue(prerequisites.contains("command -v pm"))
        assertTrue(prerequisites.contains("command -v am"))
        assertFalse(prerequisites.contains("appops"))
    }

    @Test
    fun `sensor watcher rejects malformed numeric dump fields`() {
        val script = watcher(SettingsRepository.CONTROLLER_SENSOR_PRIVACY)

        assertTrue(script.contains("if(s!~/^[0-9]+$/)return -1"))
        assertFalse(script.contains("gsub(/[[:space:]]/,\"\",s)"))
    }

    @Test
    fun `every root failsafe blocks and reads back every installed plus foreground user`() {
        val scripts = listOf(
            watcher(SettingsRepository.CONTROLLER_SENSOR_PRIVACY),
            watcher(SettingsRepository.CONTROLLER_APP_OPS),
            RootFailSafeScripts.bootGuard(
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                "android-global-microphone",
                10,
                "g-multi-user",
            )!!,
            RootFailSafeScripts.revokeLease(
                SettingsRepository.CONTROLLER_APP_OPS,
                "com.openai.chatgpt",
                10,
                "request-multi-user",
                "cancel-request-multi-user-1-2",
            )!!,
        )

        scripts.forEach { script ->
            assertTrue(script.contains("pm list users"))
            assertTrue(script.contains("am get-current-user"))
            assertTrue(script.contains("for MB_USER in \$USER_IDS"))
            assertTrue(script.contains("sensor_privacy enable \"\$MB_USER\" microphone"))
            assertTrue(script.contains("awk -v wanted=\"\$MB_USER\""))
            assertTrue(script.contains("TARGET_FOUND"))
        }
    }

    @Test
    fun `watcher proves its native block path before publishing armed`() {
        val script = watcher(SettingsRepository.CONTROLLER_SENSOR_PRIVACY)
        val lock = script.indexOf("if ! acquire_lock; then")
        val recordedPid = script.indexOf("RECORDED_PID=", lock)
        val pidCheck = script.indexOf("RECORDED_PID\" != \"\$SELF_PID", recordedPid)
        val markerCheck = script.indexOf("CURRENT\" != \"\$REQUEST_ID", pidCheck)
        val preflightBlock = script.indexOf("sensor_privacy enable", markerCheck)
        val preflightVerify = script.indexOf("preflight-block-unverified", preflightBlock)
        val armed = script.indexOf("write_status \"armed-\$REQUEST_ID-\$SELF_PID\"")

        assertTrue(lock >= 0)
        assertTrue(recordedPid > lock)
        assertTrue(pidCheck > recordedPid)
        assertTrue(markerCheck >= 0)
        assertTrue(preflightBlock > markerCheck)
        assertTrue(preflightVerify > preflightBlock)
        assertTrue(armed > preflightVerify)
    }

    @Test
    fun `lease cancellation blocks and verifies under flock before replacing marker`() {
        val mutation = "timeout -k 0.1 0.5 cmd sensor_privacy enable"
        val script = RootFailSafeScripts.revokeLease(
            controllerId = SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
            targetPackage = "android-global-microphone",
            userId = 0,
            expectedRequestId = "request-cancel-0001",
            replacementMarker = "cancel-request-cancel-0001-123-456",
        )

        assertNotNull(script)
        script!!
        val lock = script.indexOf("flock -x 0")
        val generationCheck = script.indexOf("case \"\$CURRENT_REQUEST\"")
        val block = script.indexOf(mutation)
        val verified = script.indexOf("[ \"\$BLOCK_VERIFIED\" = 1 ]")
        val replace = script.indexOf("mv -f \"\$REVOKE_TMP\" \"\$LEASE_FILE\"")
        assertTrue(lock >= 0)
        assertTrue(generationCheck > lock)
        assertTrue(block > generationCheck)
        assertTrue(verified > block)
        assertTrue(replace > verified)
        assertTrue(script.contains("expired-*-\"\$EXPECTED_REQUEST\""))
        assertTrue(script.contains("cancel-\"\$EXPECTED_REQUEST\"-*"))
        assertTrue(script.contains("trap cleanup_revoke EXIT"))
    }

    @Test
    fun `boot guard freezes target and fences every retry by generation under flock`() {
        val script = RootFailSafeScripts.bootGuard(
            controllerId = SettingsRepository.CONTROLLER_APP_OPS,
            targetPackage = "com.openai.chatgpt",
            userId = 10,
            generation = "g-fixed-generation",
        )

        assertNotNull(script)
        script!!
        assertTrue(script.contains("GENERATION='g-fixed-generation'"))
        assertTrue(script.contains("PKG='com.openai.chatgpt'"))
        assertTrue(script.contains("USER_ID='10'"))
        assertTrue(script.contains("flock -n -x 0"))
        assertTrue(script.contains("CURRENT_GENERATION"))
        assertTrue(script.contains("CURRENT_GENERATION\" != \"\$GENERATION"))
        assertTrue(
            script.indexOf("CURRENT_GENERATION\" != \"\$GENERATION") <
                script.indexOf("cmd sensor_privacy enable"),
        )
        assertTrue(script.contains("timeout -k 0.1 0.5 cmd sensor_privacy enable"))
        assertTrue(script.contains("timeout -k 0.1 0.5 dumpsys sensor_privacy"))
        assertFalse(script.contains("cmd appops"))
        assertTrue(script.contains("BOOT_DEADLINE_MS=\$((BOOT_NOW_MS + 180000))"))
        assertTrue(script.contains("BOOT_CLOCK_FAILED=1"))
        assertTrue(script.contains("BOOT_CLOCK_FAILURE_TRY\" -ge 180"))

        val selfStart = script.indexOf("SELF_START=\$(awk")
        val pidRecord = script.indexOf("printf '%s|%s\\n' \"\$\$\" \"\$SELF_START\"")
        val pidCleanup = script.indexOf("PID_RECORD%%|*")
        assertTrue(selfStart >= 0)
        assertTrue(pidRecord > selfStart)
        assertTrue(pidCleanup >= 0)

        val contention = script.indexOf("write_status \"lock-contended-\$GENERATION\"")
        val retry = script.indexOf("continue", startIndex = contention)
        val fatalExit = script.indexOf("exit 1", startIndex = contention)
        assertTrue(contention >= 0)
        assertTrue(retry in (contention + 1)..(contention + 200))
        assertTrue(fatalExit == -1 || retry < fatalExit)
    }

    @Test
    fun `service d launcher selects immutable version through current pointer`() {
        val launcher = RootFailSafeScripts.bootLauncher()

        assertTrue(launcher.contains("boot-current"))
        assertTrue(launcher.contains("boot-\$GENERATION.sh"))
        assertTrue(launcher.contains("supervisor.lock"))
        assertTrue(launcher.contains("flock -n -x 9"))
        assertTrue(launcher.contains("sh \"\$VERSION_SCRIPT\""))
        assertTrue(launcher.contains("while [ -f \"\$GENERATION_FILE\" ]"))
    }

    @Test
    fun `boot generation continuously supervises lease watcher and user context`() {
        val script = RootFailSafeScripts.bootGuard(
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
            "android-global-microphone",
            10,
            "g-supervisor",
        )!!

        assertTrue(script.contains("while :; do"))
        assertTrue(script.contains("DISCOVERED_USER_CONTEXT"))
        assertTrue(script.contains("DISCOVERY_INTERVAL=5"))
        assertTrue(script.contains("DISCOVERY_INTERVAL=1"))
        assertTrue(
            script.contains(
                "META_REQUEST= META_BLOCK_AT= META_DEADLINE= META_APP_PID= META_APP_START= " +
                    "META_GENERATION= META_ARM_BY= META_EXTRA=",
            ),
        )
        assertTrue(
            script.contains(
                "IFS='|' read -r META_REQUEST META_BLOCK_AT META_DEADLINE META_APP_PID " +
                    "META_APP_START META_GENERATION META_ARM_BY META_EXTRA",
            ),
        )
        assertTrue(script.contains("[ -z \"\$META_EXTRA\" ] || CLOSE_REASON=meta-extra"))
        assertTrue(script.contains("META_BLOCK_AT\" -ge \"\$META_DEADLINE"))
        assertTrue(script.contains("NOW_MS\" -ge \"\$META_BLOCK_AT"))
        assertTrue(script.contains("APP_START\" = \"\$META_APP_START"))
        assertTrue(script.contains("case \"\$APP_STATE\" in R|S"))
        assertTrue(script.contains("watch-\$CURRENT_LEASE.sh"))
        assertTrue(script.contains("kill -0 \"\$WATCH_PID\""))
        assertTrue(script.contains("/proc/\$WATCH_PID/cmdline"))
        assertTrue(script.contains("armed-\$CURRENT_LEASE-\$WATCH_PID"))
        assertTrue(script.contains("CLOSE_REASON=watch-arm-timeout"))
        assertFalse(script.contains("nohup sh \"\$WATCH_SCRIPT\""))
        assertTrue(script.contains("expired-*|*[!A-Za-z0-9._-]*)"))

        val closeReason = script.indexOf("if [ -n \"\$CLOSE_REASON\" ]; then")
        val freshReset = script.indexOf("BLOCK_VERIFIED=0", closeReason)
        val freshBlock = script.indexOf("cmd sensor_privacy enable", freshReset)
        val freshVerify = script.indexOf("if [ \"\$BLOCK_VERIFIED\" = 1 ]", freshBlock)
        val terminalMarker = script.indexOf(
            "expired-blocked-supervisor-\$CLOSE_REASON-\$CURRENT_LEASE",
            freshVerify,
        )
        assertTrue(closeReason >= 0)
        assertTrue(freshReset > closeReason)
        assertTrue(freshBlock > freshReset)
        assertTrue(freshVerify > freshBlock)
        assertTrue(terminalMarker > freshVerify)
    }

    @Test
    fun `generated scripts pass an available posix shell syntax check`() {
        val shell = sequenceOf(
            File("/bin/sh"),
            File("C:/Program Files/Git/bin/sh.exe"),
            File("C:/Program Files/Git/usr/bin/sh.exe"),
        ).firstOrNull(File::isFile) ?: return
        val scripts = listOf(
            RootFailSafeScripts.bootLauncher(),
            RootFailSafeScripts.bootGuard(
                SettingsRepository.CONTROLLER_APP_OPS,
                "com.openai.chatgpt",
                0,
                "g-syntax-check",
            )!!,
            RootFailSafeScripts.bootGuard(
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                "android-global-microphone",
                0,
                "g-syntax-check",
            )!!,
            RootFailSafeScripts.revokeLease(
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                "android-global-microphone",
                0,
                "request-revoke-syntax",
                "cancel-request-revoke-syntax-1-2",
            )!!,
            watcher(SettingsRepository.CONTROLLER_APP_OPS),
            watcher(SettingsRepository.CONTROLLER_SENSOR_PRIVACY),
        )

        scripts.forEach { script ->
            val process = ProcessBuilder(shell.absolutePath, "-n").start()
            process.outputStream.bufferedWriter().use { it.write(script) }
            assertTrue("shell syntax check timed out", process.waitFor(5, TimeUnit.SECONDS))
            val error = process.errorStream.bufferedReader().use { it.readText() }
            assertEquals(error, 0, process.exitValue())
        }
    }

    private fun watcher(controllerId: String): String {
        val script = RootFailSafeScripts.watcher(
            controllerId = controllerId,
            targetPackage = if (controllerId == SettingsRepository.CONTROLLER_SENSOR_PRIVACY) {
                "android-global-microphone"
            } else {
                "com.openai.chatgpt"
            },
            userId = 10,
            requestId = "request-12345678",
            deadlineElapsedRealtimeMs = 123_456_789L,
            blockAtElapsedRealtimeMs = 123_446_789L,
            watcherPath = "/data/adb/micbridge/watch-request-12345678.sh",
            watcherStatusPath = "/data/adb/micbridge/watch-request-12345678.status",
            wakeTag = "micbridge-request-12345678",
        )
        assertNotNull(script)
        return script!!
    }
}
