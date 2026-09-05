package com.jack.micbridge.safety

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.jack.micbridge.MainActivity
import com.jack.micbridge.data.DirectBootSettings
import com.jack.micbridge.data.SettingsRepository
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.mic.RootShell
import com.jack.micbridge.mic.SensorPrivacyRootProtocol
import com.jack.micbridge.receiver.EmergencyBlockReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

class AutoBlockSafety(
    private val context: Context,
    private val settings: SettingsRepository,
    private val rootShell: RootShell,
    private val mutationGate: LeaseMutationGate = ProcessLeaseMutationGate.instance,
) : LeaseSafety {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val leaseStore = SafetyLeaseStore(context)
    @Volatile
    private var preparedRootBootGuard: RootBootGuardTarget? = null
    @Volatile
    private var rootWorkspaceClaimed = false

    override suspend fun arm(
        requestId: String,
        durationSeconds: Int,
        target: SafetyTarget,
    ): LeaseArmResult = mutationGate.withLock {
        armWhileLocked(requestId, durationSeconds, target, persistent = false)
    }

    override suspend fun armPersistent(
        requestId: String,
        target: SafetyTarget,
    ): LeaseArmResult = mutationGate.withLock {
        armWhileLocked(requestId, durationSeconds = null, target, persistent = true)
    }

    private suspend fun armWhileLocked(
        requestId: String,
        durationSeconds: Int?,
        target: SafetyTarget,
        persistent: Boolean,
    ): LeaseArmResult {
        if (!rootWorkspaceClaimed && !ownsRootWorkspace()) {
            return LeaseArmResult(
                false,
                0L,
                0L,
                false,
                false,
                "当前 Android 用户不是 Root 安全工作区所有者；已拒绝开放",
            )
        }
        val directBootMatches = runCatching {
            val directBoot = DirectBootSettings(context)
            directBootTargetMatches(
                frozen = target,
                mirroredControllerId = directBoot.controllerId,
                mirroredTargetPackage = directBoot.targetPackage,
                mirroredUserId = directBoot.userId,
            )
        }.getOrDefault(false)
        if (!directBootMatches) {
            return LeaseArmResult(
                false,
                0L,
                0L,
                false,
                false,
                "Direct Boot 安全目标与当前冻结目标不一致；已拒绝开放",
            )
        }
        val safeId = runCatching { RootShell.requireOpaqueId(requestId) }.getOrNull()
            ?: return LeaseArmResult(false, 0L, 0L, false, false, "request_id 不适合安全租约")
        if (!persistent && (durationSeconds == null || durationSeconds !in 1..SettingsRepository.MAX_OPEN_SECONDS)) {
            return LeaseArmResult(false, 0L, 0L, false, false, "安全租约时长超出允许范围")
        }
        val timedDurationSeconds = durationSeconds ?: 0
        val hardDeadline = if (persistent) {
            0L
        } else {
            System.currentTimeMillis() + timedDurationSeconds * 1_000L
        }
        val hardDeadlineElapsed = if (persistent) {
            0L
        } else {
            android.os.SystemClock.elapsedRealtime() + timedDurationSeconds * 1_000L
        }
        val controllerId = target.controllerId
        val targetPackage = target.targetPackage
        val userId = target.userId
        val rootRequired = controllerId in ROOT_WATCHDOG_CONTROLLERS
        val retryBudgetMs = if (persistent) {
            0L
        } else if (rootRequired) {
            minOf(ROOT_BLOCK_RETRY_BUDGET_MS, timedDurationSeconds * 1_000L / 2L)
        } else {
            0L
        }
        // This is the authorization deadline exposed to the coordinator and API. Root
        // controllers reserve the remaining interval solely for fail-closed enforcement.
        val openValidUntil = hardDeadline - retryBudgetMs
        val openValidUntilElapsed = hardDeadlineElapsed - retryBudgetMs
        val provisional = ActiveSafetyLease(
            safeId,
            target,
            openValidUntil,
            openValidUntilElapsed,
            exactAlarmArmed = false,
            rootWatchdogArmed = false,
            persistent = persistent,
        )
        // Commit the immutable block target before arming anything or allowing OPEN. If the
        // process dies at any later instruction, startup can still block this exact target.
        if (!leaseStore.save(provisional)) {
            return LeaseArmResult(
                false,
                openValidUntil,
                openValidUntilElapsed,
                false,
                false,
                "无法持久化安全租约目标",
            )
        }
        val exact = if (persistent) {
            alarmManager.cancel(alarmIntent(requestCode = WALL_ALARM_REQUEST_CODE))
            alarmManager.cancel(alarmIntent(requestCode = ELAPSED_ALARM_REQUEST_CODE))
            false
        } else {
            armExactAlarm(
                safeId,
                openValidUntil,
                openValidUntilElapsed,
                controllerId,
                targetPackage,
                userId,
            )
        }
        val root = if ((persistent || exact) && rootRequired) {
            armRootWatchdog(
                safeId,
                hardDeadlineElapsed,
                openValidUntilElapsed,
                target,
                persistent,
            )
        } else {
            false
        }
        // A shell `sleep` is useful process-death defence but is not a wake-up primitive:
        // deep sleep can delay it. The OS exact ELAPSED_REALTIME_WAKEUP alarm is therefore
        // mandatory; the root watcher is only a second, independent layer.
        val guardsArmed = if (persistent) {
            rootRequired && root
        } else {
            exact && (!rootRequired || root)
        }
        val durable = leaseStore.save(
            provisional.copy(
                exactAlarmArmed = exact,
                rootWatchdogArmed = root,
                persistent = persistent,
            ),
        )
        val armed = guardsArmed && durable
        return LeaseArmResult(
            armed = armed,
            deadlineEpochMs = openValidUntil,
            deadlineElapsedRealtimeMs = openValidUntilElapsed,
            exactAlarmArmed = exact,
            rootWatchdogArmed = root,
            persistent = persistent,
            error = when {
                armed -> null
                !durable -> "安全租约布防结果无法持久化；已拒绝开放"
                persistent && !root -> "持续开放 Root 监督器未布防；已拒绝开放"
                !exact -> "双系统精确安全闹钟未完整布防；已拒绝开放"
                rootRequired && !root -> "独立 Root 租约看门狗未布防；已拒绝开放"
                else -> "安全租约未完整布防；已拒绝开放"
            },
        )
    }

    override suspend fun cancel() = mutationGate.withLock {
        val active = leaseStore.load()
        if (active != null && active.target.controllerId in ROOT_WATCHDOG_CONTROLLERS) {
            val marker =
                "cancel-${active.requestId}-${System.currentTimeMillis()}-${SecureRandom().nextInt().toUInt()}"
            // The revoke script proves workspace ownership under the same flock it uses to block
            // and replace the marker, so a separate ownership round trip would only add latency
            // before an OPEN can be closed. A foreign owner fails this check inside the script
            // and therefore still surfaces through the same fail-closed `check` below.
            check(revokeRootLeaseAfterVerifiedBlock(active, marker)) {
                "Root lease could not be atomically blocked and revoked"
            }
        } else {
            check(ownsRootWorkspace()) {
                "Current Android user does not own the Root safety workspace"
            }
        }
        // This method is called only after a fresh BLOCKED readback for the same persisted
        // target. A failed clear leaves a stale lease that merely causes another safe block.
        check(leaseStore.clear()) {
            "Durable safety lease could not be cleared"
        }
        // Cancel the alarms only after both durable revocation steps have committed. If either
        // fails, throwing keeps the coordinator's in-memory lease/error and leaves the alarms
        // available to make another fail-closed attempt.
        alarmManager.cancel(alarmIntent(requestCode = WALL_ALARM_REQUEST_CODE))
        alarmManager.cancel(alarmIntent(requestCode = ELAPSED_ALARM_REQUEST_CODE))
        Unit
    }

    override suspend fun loadActiveLease(): ActiveSafetyLease? = leaseStore.load()

    /** Fresh, PID-bound proof that both independent Root processes still protect this lease. */
    override suspend fun verifyActiveGuard(requestId: String): Boolean {
        val safeId = runCatching { RootShell.requireOpaqueId(requestId) }.getOrNull() ?: return false
        val appPid = android.os.Process.myPid().toString()
        val result = rootShell.execute(
            """
                set -e
                command -v flock >/dev/null 2>&1
                command -v awk >/dev/null 2>&1
                command -v grep >/dev/null 2>&1
                command -v tr >/dev/null 2>&1
                exec 0>>$ROOT_LEASE_LOCK_FILE
                flock -x 0
                test "${'$'}(cat $ROOT_OWNER_FILE 2>/dev/null)" = ${RootShell.quote(android.os.Process.myUid().toString())}
                CURRENT_REQUEST=${'$'}(cat $LEASE_FILE 2>/dev/null)
                test "${'$'}CURRENT_REQUEST" = ${RootShell.quote(safeId)}
                META_REQUEST= META_BLOCK_AT= META_DEADLINE= META_APP_PID= META_APP_START= META_GENERATION= META_ARM_BY= META_EXTRA=
                IFS='|' read -r META_REQUEST META_BLOCK_AT META_DEADLINE META_APP_PID META_APP_START META_GENERATION META_ARM_BY META_EXTRA < $LEASE_META_FILE
                test "${'$'}META_REQUEST" = "${'$'}CURRENT_REQUEST"
                test -z "${'$'}META_EXTRA"
                test "${'$'}META_APP_PID" = ${RootShell.quote(appPid)}
                case "${'$'}META_BLOCK_AT" in ''|*[!0-9]*) exit 1;; esac
                case "${'$'}META_DEADLINE" in ''|*[!0-9]*) exit 1;; esac
                case "${'$'}META_APP_START" in ''|*[!0-9]*) exit 1;; esac
                case "${'$'}META_ARM_BY" in ''|*[!0-9]*) exit 1;; esac
                case "${'$'}META_GENERATION" in ''|*[!A-Za-z0-9._-]*) exit 1;; esac
                if [ "${'$'}META_BLOCK_AT" = 0 ]; then
                  test "${'$'}META_DEADLINE" = 0
                else
                  test "${'$'}META_BLOCK_AT" -lt "${'$'}META_DEADLINE"
                fi
                test "${'$'}(cat $ROOT_BOOT_GENERATION_FILE 2>/dev/null)" = "${'$'}META_GENERATION"
                NOW_MS=${'$'}(awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null)
                case "${'$'}NOW_MS" in ''|*[!0-9]*) exit 1;; esac
                if [ "${'$'}META_BLOCK_AT" != 0 ]; then
                  test "${'$'}NOW_MS" -lt "${'$'}META_BLOCK_AT"
                fi
                APP_PROC=${'$'}(awk '{print ${'$'}3 "|" ${'$'}22}' "/proc/${'$'}META_APP_PID/stat" 2>/dev/null)
                APP_STATE=${'$'}{APP_PROC%%\|*}
                APP_START=${'$'}{APP_PROC#*\|}
                test "${'$'}APP_START" = "${'$'}META_APP_START"
                case "${'$'}APP_STATE" in R|S) ;; *) exit 1;; esac
                WATCH_SCRIPT=$ROOT_DIR/watch-${'$'}CURRENT_REQUEST.sh
                WATCH_PID=${'$'}(cat $ROOT_DIR/watch-${'$'}CURRENT_REQUEST.pid 2>/dev/null)
                case "${'$'}WATCH_PID" in ''|*[!0-9]*) exit 1;; esac
                kill -0 "${'$'}WATCH_PID" 2>/dev/null
                WATCH_STATE=${'$'}(awk '{print ${'$'}3}' "/proc/${'$'}WATCH_PID/stat" 2>/dev/null)
                case "${'$'}WATCH_STATE" in R|S) ;; *) exit 1;; esac
                tr '\000' ' ' < "/proc/${'$'}WATCH_PID/cmdline" | grep -Fq "${'$'}WATCH_SCRIPT"
                test "${'$'}(cat $ROOT_DIR/watch-${'$'}CURRENT_REQUEST.status 2>/dev/null)" = "armed-${'$'}CURRENT_REQUEST-${'$'}WATCH_PID"
                BOOT_RECORD=${'$'}(cat $ROOT_DIR/boot-${'$'}META_GENERATION.pid 2>/dev/null)
                BOOT_PID=${'$'}{BOOT_RECORD%%\|*}
                BOOT_START=${'$'}{BOOT_RECORD#*\|}
                case "${'$'}BOOT_PID" in ''|*[!0-9]*) exit 1;; esac
                case "${'$'}BOOT_START" in ''|*[!0-9]*) exit 1;; esac
                kill -0 "${'$'}BOOT_PID" 2>/dev/null
                BOOT_PROC=${'$'}(awk '{print ${'$'}3 "|" ${'$'}22}' "/proc/${'$'}BOOT_PID/stat" 2>/dev/null)
                case "${'$'}{BOOT_PROC%%\|*}" in R|S) ;; *) exit 1;; esac
                test "${'$'}{BOOT_PROC#*\|}" = "${'$'}BOOT_START"
                tr '\000' ' ' < "/proc/${'$'}BOOT_PID/cmdline" | grep -Fq "$ROOT_DIR/boot-${'$'}META_GENERATION.sh"
                flock -u 0
            """.trimIndent(),
            timeoutMs = ROOT_ARM_TIMEOUT_MS,
        )
        return result.succeeded
    }

    /**
     * Process-destruction boundary that does not wait for the in-process coordinator mutex.
     * For an active Root lease, the same root flock used by every authorized OPEN is acquired,
     * the effective global gate is freshly BLOCKED, and the lease marker is replaced before
     * returning. A late OPEN shell therefore either finishes before this block or observes the
     * replacement marker and cannot reverse it.
     */
    suspend fun emergencyFenceAndBlock(): Boolean {
        val active = leaseStore.load()
        if (active != null && active.target.controllerId in ROOT_WATCHDOG_CONTROLLERS) {
            val marker =
                "cancel-${active.requestId}-destroy-${System.currentTimeMillis()}-" +
                    SecureRandom().nextInt().toUInt()
            // Ownership is asserted inside the revoke script, under the same flock as the block
            // and marker replacement, so this destruction path needs one root round trip.
            return revokeRootLeaseAfterVerifiedBlock(active, marker)
        }
        if (!ownsRootWorkspace()) return false
        return DirectBootFailsafeBlocker.block(context.applicationContext)
    }

    override suspend fun <T> withMutationLock(block: suspend () -> T): T =
        mutationGate.withLock(block)

    fun canScheduleExactAlarm(): Boolean = alarmManager.canScheduleExactAlarms()

    /**
     * `/data/adb/micbridge` is device-global while Android app instances are per user. Claim it
     * for exactly one Linux UID so a work profile cannot replace another user's live lease or
     * boot generation. The owner is retained across process death and app updates; the explicit
     * safe guard-removal flow releases it.
     */
    suspend fun claimRootWorkspaceOwner(): Boolean {
        val owner = android.os.Process.myUid().toString()
        val result = rootShell.execute(
            """
                set -e
                command -v flock >/dev/null 2>&1
                mkdir -p $ROOT_DIR
                chmod 700 $ROOT_DIR
                exec 0>>$ROOT_LEASE_LOCK_FILE
                flock -x 0
                OWNER_FILE=$ROOT_OWNER_FILE
                OWNER_TMP=${RootShell.quote("$ROOT_OWNER_FILE.tmp-$owner")}
                cleanup_owner() { rm -f "${'$'}OWNER_TMP"; flock -u 0 || true; }
                trap cleanup_owner EXIT
                trap 'exit 1' HUP INT TERM
                if [ -f "${'$'}OWNER_FILE" ]; then
                  test "${'$'}(cat "${'$'}OWNER_FILE")" = ${RootShell.quote(owner)}
                else
                  printf %s ${RootShell.quote(owner)} > "${'$'}OWNER_TMP"
                  mv -f "${'$'}OWNER_TMP" "${'$'}OWNER_FILE"
                fi
                test "${'$'}(cat "${'$'}OWNER_FILE")" = ${RootShell.quote(owner)}
                flock -u 0
                trap - EXIT HUP INT TERM
            """.trimIndent(),
            timeoutMs = ROOT_DEPLOY_TIMEOUT_MS,
        )
        rootWorkspaceClaimed = result.succeeded
        return result.succeeded
    }

    /**
     * Verify ownership without creating or replacing the owner marker. Destructive lease
     * cleanup must use this check: another Android profile may share the device-global Root
     * directory but must never revoke the active owner's watchdog or lease.
     */
    private suspend fun ownsRootWorkspace(): Boolean {
        val owner = android.os.Process.myUid().toString()
        val result = rootShell.execute(
            """
                set -e
                command -v flock >/dev/null 2>&1
                test -d $ROOT_DIR
                test -f $ROOT_OWNER_FILE
                exec 0>>$ROOT_LEASE_LOCK_FILE
                flock -x 0
                test "${'$'}(cat $ROOT_OWNER_FILE 2>/dev/null)" = ${RootShell.quote(owner)}
                flock -u 0
            """.trimIndent(),
            timeoutMs = ROOT_DEPLOY_TIMEOUT_MS,
        )
        return result.succeeded
    }

    suspend fun installOrRefreshRootBootGuard(): Boolean {
        if (!claimRootWorkspaceOwner()) return false
        val selectedControllerId = settings.controllerId
        if (selectedControllerId !in ROOT_WATCHDOG_CONTROLLERS) return false
        val controllerId = if (selectedControllerId == SettingsRepository.CONTROLLER_AUDIO_MANAGER) {
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY
        } else {
            selectedControllerId
        }
        val targetPackage = when (controllerId) {
            SettingsRepository.CONTROLLER_APP_OPS -> runCatching {
                RootShell.requirePackageName(settings.targetPackage)
            }.getOrNull() ?: return false
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY -> GLOBAL_MIC_TARGET
            else -> return false
        }
        val userId = currentUserId() ?: return false
        return deployRootBootGuard(controllerId, targetPackage, userId)
    }

    private suspend fun deployRootBootGuard(
        controllerId: String,
        targetPackage: String,
        userId: Int,
    ): Boolean {
        val preparedTarget = RootBootGuardTarget(controllerId, targetPackage, userId)
        // Once a deployment attempt starts, the prior in-memory proof is no longer usable: the
        // command may advance the generation before failing. A later OPEN must redeploy.
        preparedRootBootGuard = null
        val generation = newRootGeneration()
        val versionPath = "$ROOT_DIR/boot-$generation.sh"
        val bootScript = RootFailSafeScripts.bootGuard(
            controllerId = controllerId,
            targetPackage = targetPackage,
            userId = userId,
            generation = generation,
        ) ?: return false
        val launcher = RootFailSafeScripts.bootLauncher()
        val encoded = Base64.encodeToString(
            bootScript.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val launcherEncoded = Base64.encodeToString(
            launcher.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val prerequisite = RootFailSafeScripts.prerequisiteCheck(controllerId) ?: return false
        // The versioned script is immutable. The current-generation pointer is moved last,
        // while holding the same flock used by both boot retries and lease watchers. Thus an
        // already-running old service.d generation can never perform a late BLOCK after this
        // deployment has returned and the coordinator proceeds to OPEN.
        val command = """
            set -e
            test -d /data/adb/service.d
            { command -v magisk >/dev/null 2>&1 || command -v ksud >/dev/null 2>&1 || test -d /data/adb/ksu || test -d /data/adb/ap; }
            command -v base64 >/dev/null 2>&1
            command -v flock >/dev/null 2>&1
            command -v timeout >/dev/null 2>&1
            command -v nohup >/dev/null 2>&1
            command -v tr >/dev/null 2>&1
            $prerequisite
            mkdir -p $ROOT_DIR
            chmod 700 $ROOT_DIR
            exec 0>>$ROOT_LEASE_LOCK_FILE
            flock -x 0
            test "${'$'}(cat $ROOT_OWNER_FILE 2>/dev/null)" = ${RootShell.quote(android.os.Process.myUid().toString())}
            DEPLOY_LOCKED=1
            VERSION_TMP=${RootShell.quote("$versionPath.tmp")}
            LAUNCHER_TMP=${RootShell.quote("$BOOT_SCRIPT.tmp")}
            CURRENT_TMP=${RootShell.quote("$ROOT_BOOT_GENERATION_FILE.tmp")}
            CONTROLLER_TMP=${RootShell.quote("$ROOT_DIR/controller.tmp")}
            PACKAGE_TMP=${RootShell.quote("$ROOT_DIR/package.tmp")}
            USER_TMP=${RootShell.quote("$ROOT_DIR/user.tmp")}
            cleanup_deploy() {
              rm -f "${'$'}VERSION_TMP" "${'$'}LAUNCHER_TMP" "${'$'}CURRENT_TMP" "${'$'}CONTROLLER_TMP" "${'$'}PACKAGE_TMP" "${'$'}USER_TMP"
              if [ "${'$'}DEPLOY_LOCKED" = 1 ]; then
                flock -u 0 || true
                DEPLOY_LOCKED=0
              fi
            }
            trap cleanup_deploy EXIT
            trap 'exit 1' HUP INT TERM
            printf %s ${RootShell.quote(encoded)} | base64 -d > "${'$'}VERSION_TMP"
            chmod 700 "${'$'}VERSION_TMP"
            mv -f "${'$'}VERSION_TMP" ${RootShell.quote(versionPath)}
            printf %s ${RootShell.quote(launcherEncoded)} | base64 -d > "${'$'}LAUNCHER_TMP"
            chmod 700 "${'$'}LAUNCHER_TMP"
            mv -f "${'$'}LAUNCHER_TMP" $BOOT_SCRIPT
            printf %s ${RootShell.quote(controllerId)} > "${'$'}CONTROLLER_TMP"
            mv -f "${'$'}CONTROLLER_TMP" $ROOT_DIR/controller
            printf %s ${RootShell.quote(targetPackage)} > "${'$'}PACKAGE_TMP"
            mv -f "${'$'}PACKAGE_TMP" $ROOT_DIR/package
            printf %s ${RootShell.quote(userId.toString())} > "${'$'}USER_TMP"
            mv -f "${'$'}USER_TMP" $ROOT_DIR/user
            rm -f $ROOT_BOOT_STATUS_FILE
            printf %s ${RootShell.quote(generation)} > "${'$'}CURRENT_TMP"
            mv -f "${'$'}CURRENT_TMP" $ROOT_BOOT_GENERATION_FILE
            test -x $BOOT_SCRIPT
            test -x ${RootShell.quote(versionPath)}
            test "${'$'}(cat $ROOT_BOOT_GENERATION_FILE)" = ${RootShell.quote(generation)}
            for OLD_SCRIPT in $ROOT_DIR/boot-*.sh; do
              if [ -e "${'$'}OLD_SCRIPT" ]; then
                if [ "${'$'}OLD_SCRIPT" != ${RootShell.quote(versionPath)} ]; then
                  rm -f "${'$'}OLD_SCRIPT"
                fi
              fi
            done
            flock -u 0
            DEPLOY_LOCKED=0
            trap - EXIT HUP INT TERM
            nohup sh $BOOT_SCRIPT >/dev/null 2>&1 &
            i=0
            while [ ${'$'}i -lt $ROOT_DEPLOY_STATUS_POLLS ]; do
              STATUS=${'$'}(cat $ROOT_BOOT_STATUS_FILE 2>/dev/null || true)
              case "${'$'}STATUS" in
                blocked-${generation}|user-context-blocked-${generation}|lease-watcher-restarted-${generation})
                  SUPERVISOR_RECORD=${'$'}(cat ${RootShell.quote("$ROOT_DIR/boot-$generation.pid")} 2>/dev/null)
                  SUPERVISOR_PID=${'$'}{SUPERVISOR_RECORD%%\|*}
                  SUPERVISOR_START=${'$'}{SUPERVISOR_RECORD#*\|}
                  case "${'$'}SUPERVISOR_PID" in ''|*[!0-9]*) exit 1;; esac
                  case "${'$'}SUPERVISOR_START" in ''|*[!0-9]*) exit 1;; esac
                  kill -0 "${'$'}SUPERVISOR_PID" 2>/dev/null
                  SUPERVISOR_PROC=${'$'}(awk '{print ${'$'}3 "|" ${'$'}22}' "/proc/${'$'}SUPERVISOR_PID/stat" 2>/dev/null)
                  case "${'$'}{SUPERVISOR_PROC%%\|*}" in R|S) ;; *) exit 1;; esac
                  test "${'$'}{SUPERVISOR_PROC#*\|}" = "${'$'}SUPERVISOR_START"
                  tr '\000' ' ' < "/proc/${'$'}SUPERVISOR_PID/cmdline" | grep -Fq ${RootShell.quote(versionPath)}
                  exit 0
                  ;;
                prerequisite-*|pid-file-*|block-unverified-*) exit 1 ;;
              esac
              i=${'$'}((i + 1))
              sleep 0.05
            done
            exit 1
        """.trimIndent()
        val result = rootShell.execute(command, timeoutMs = ROOT_DEPLOY_TIMEOUT_MS)
        if (result.succeeded) {
            settings.rootBootGuardInstalled = true
            preparedRootBootGuard = preparedTarget
        }
        return result.succeeded
    }

    suspend fun removeRootBootGuard(): Boolean {
        preparedRootBootGuard = null
        rootWorkspaceClaimed = false
        val tombstone = "removed-${newRootGeneration()}"
        val result = rootShell.execute(
            """
                set -e
                command -v flock >/dev/null 2>&1
                mkdir -p $ROOT_DIR
                exec 0>>$ROOT_LEASE_LOCK_FILE
                flock -x 0
                test "${'$'}(cat $ROOT_OWNER_FILE 2>/dev/null)" = ${RootShell.quote(android.os.Process.myUid().toString())}
                REMOVE_LOCKED=1
                CURRENT_TMP=${RootShell.quote("$ROOT_BOOT_GENERATION_FILE.remove.tmp")}
                cleanup_remove() {
                  rm -f "${'$'}CURRENT_TMP"
                  if [ "${'$'}REMOVE_LOCKED" = 1 ]; then
                    flock -u 0 || true
                    REMOVE_LOCKED=0
                  fi
                }
                trap cleanup_remove EXIT
                trap 'exit 1' HUP INT TERM
                printf %s ${RootShell.quote(tombstone)} > "${'$'}CURRENT_TMP"
                mv -f "${'$'}CURRENT_TMP" $ROOT_BOOT_GENERATION_FILE
                rm -f $BOOT_SCRIPT $ROOT_DIR/controller $ROOT_DIR/package $ROOT_DIR/user $LEASE_FILE $LEASE_META_FILE $ROOT_DIR/boot-status
                for OLD_SCRIPT in $ROOT_DIR/boot-*.sh; do
                  if [ -e "${'$'}OLD_SCRIPT" ]; then
                    rm -f "${'$'}OLD_SCRIPT"
                  fi
                done
                for WATCH_FILE in $ROOT_DIR/watch-*.sh $ROOT_DIR/watch-*.status $ROOT_DIR/watch-*.pid; do
                  if [ -e "${'$'}WATCH_FILE" ]; then
                    rm -f "${'$'}WATCH_FILE"
                  fi
                done
                rm -f $ROOT_DIR/boot-*.pid
                rm -f $ROOT_BOOT_GENERATION_FILE
                rm -f $ROOT_OWNER_FILE
                flock -u 0
                REMOVE_LOCKED=0
                trap - EXIT HUP INT TERM
            """.trimIndent(),
            timeoutMs = ROOT_DEPLOY_TIMEOUT_MS,
        )
        if (result.succeeded) settings.rootBootGuardInstalled = false
        return result.succeeded
    }

    private fun armExactAlarm(
        requestId: String,
        deadlineEpochMs: Long,
        deadlineElapsedRealtimeMs: Long,
        controllerId: String,
        targetPackage: String,
        userId: Int,
    ): Boolean {
        if (!canScheduleExactAlarm()) return false
        return runCatching {
            // Alarm-clock alarms are the public API whose delivery time Android documents as
            // never adjusted; the system exits low-power idle before delivery. A safety lease
            // is user-visible and time-critical, so this stronger primitive is intentional.
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    deadlineEpochMs,
                    safetyDetailsIntent(),
                ),
                alarmIntent(
                    requestId,
                    controllerId,
                    targetPackage,
                    userId,
                    WALL_ALARM_REQUEST_CODE,
                ),
            )
            // A second alarm uses the monotonic elapsed clock so a wall-clock/time-zone
            // change cannot extend the lease. AlarmClock covers the idle-throttling side.
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadlineElapsedRealtimeMs,
                alarmIntent(
                    requestId,
                    controllerId,
                    targetPackage,
                    userId,
                    ELAPSED_ALARM_REQUEST_CODE,
                ),
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun armRootWatchdog(
        requestId: String,
        hardDeadlineElapsedRealtimeMs: Long,
        openValidUntilElapsedRealtimeMs: Long,
        target: SafetyTarget,
        persistent: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val controllerId = if (
            target.controllerId == SettingsRepository.CONTROLLER_AUDIO_MANAGER
        ) {
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY
        } else {
            target.controllerId
        }
        val targetPackage = when (controllerId) {
            SettingsRepository.CONTROLLER_APP_OPS -> runCatching {
                RootShell.requirePackageName(target.targetPackage)
            }.getOrNull() ?: return@withContext false
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY -> {
                if (target.targetPackage != GLOBAL_MIC_TARGET) return@withContext false
                GLOBAL_MIC_TARGET
            }
            else -> return@withContext false
        }
        if (target.userId < 0) return@withContext false

        // Reboot while OPEN must also fail closed. A Root lease is therefore not considered
        // armed unless the persistent service.d blocker for this exact controller target was
        // prepared by this service process. Install/startup prepares it once; repeating the full
        // generation deployment on every button press adds seconds without adding an independent
        // guard. RootOpenAuthorizationGuard still freshly proves the exact supervisor PID,
        // generation and process start time at the last instruction boundary before OPEN.
        val requiredGuard = RootBootGuardTarget(controllerId, targetPackage, target.userId)
        if (
            preparedRootBootGuard != requiredGuard &&
            !deployRootBootGuard(controllerId, targetPackage, target.userId)
        ) {
            return@withContext false
        }

        val watcherPath = "$ROOT_DIR/watch-$requestId.sh"
        val watcherStatusPath = "$ROOT_DIR/watch-$requestId.status"
        val wakeTag = "micbridge-$requestId"
        val watcher = RootFailSafeScripts.watcher(
            controllerId = controllerId,
            targetPackage = targetPackage,
            userId = target.userId,
            requestId = requestId,
            deadlineElapsedRealtimeMs = hardDeadlineElapsedRealtimeMs,
            blockAtElapsedRealtimeMs = openValidUntilElapsedRealtimeMs,
            watcherPath = watcherPath,
            watcherStatusPath = watcherStatusPath,
            wakeTag = wakeTag,
            persistent = persistent,
        ) ?: return@withContext false
        val encoded = Base64.encodeToString(
            watcher.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val command = RootFailSafeScripts.armWatcher(
            controllerId = controllerId,
            requestId = requestId,
            ownerUid = android.os.Process.myUid().toString(),
            appPid = android.os.Process.myPid().toString(),
            watcherPath = watcherPath,
            watcherStatusPath = watcherStatusPath,
            encodedWatcher = encoded,
            openValidUntilElapsedRealtimeMs = openValidUntilElapsedRealtimeMs,
            hardDeadlineElapsedRealtimeMs = hardDeadlineElapsedRealtimeMs,
            persistent = persistent,
        ) ?: return@withContext false
        rootShell.execute(command, timeoutMs = ROOT_ARM_TIMEOUT_MS).succeeded
    }

    private suspend fun revokeRootLeaseAfterVerifiedBlock(
        active: ActiveSafetyLease,
        marker: String,
    ): Boolean {
        val controllerId = if (
            active.target.controllerId == SettingsRepository.CONTROLLER_AUDIO_MANAGER
        ) {
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY
        } else {
            active.target.controllerId
        }
        val targetPackage = when (controllerId) {
            SettingsRepository.CONTROLLER_APP_OPS -> runCatching {
                RootShell.requirePackageName(active.target.targetPackage)
            }.getOrNull() ?: return false
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY -> GLOBAL_MIC_TARGET
            else -> return false
        }
        val command = RootFailSafeScripts.revokeLease(
            controllerId = controllerId,
            targetPackage = targetPackage,
            userId = active.target.userId,
            expectedRequestId = active.requestId,
            replacementMarker = marker,
            ownerUid = android.os.Process.myUid().toString(),
        ) ?: return false
        return rootShell.execute(command, timeoutMs = ROOT_ARM_TIMEOUT_MS).succeeded
    }

    private fun newRootGeneration(): String {
        val random = ByteArray(16).also(SecureRandom()::nextBytes)
        return buildString(2 + random.size * 2) {
            append("g-")
            random.forEach { byte ->
                append(HEX[(byte.toInt() ushr 4) and 0x0f])
                append(HEX[byte.toInt() and 0x0f])
            }
        }
    }

    private suspend fun currentUserId(): Int? {
        val result = rootShell.execute("am get-current-user")
        return result.stdout.trim().toIntOrNull()?.takeIf { result.succeeded && it >= 0 }
    }

    private fun alarmIntent(
        requestId: String? = null,
        controllerId: String? = null,
        targetPackage: String? = null,
        userId: Int? = null,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, EmergencyBlockReceiver::class.java)
                .setAction(ACTION_AUTO_BLOCK)
                .apply {
                    if (requestId != null) putExtra(EXTRA_REQUEST_ID, requestId)
                    if (userId != null) putExtra(EXTRA_USER_ID, userId)
                    if (controllerId != null) putExtra(EXTRA_CONTROLLER_ID, controllerId)
                    if (targetPackage != null) putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun safetyDetailsIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        SHOW_REQUEST_CODE,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_AUTO_BLOCK = "com.jack.micbridge.AUTO_BLOCK"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CONTROLLER_ID = "controller_id"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_USER_ID = "user_id"
        private const val WALL_ALARM_REQUEST_CODE = 8787
        private const val ELAPSED_ALARM_REQUEST_CODE = 8789
        private const val SHOW_REQUEST_CODE = 8788
        private const val ROOT_DIR = "/data/adb/micbridge"
        private const val LEASE_FILE = "$ROOT_DIR/lease"
        private const val LEASE_META_FILE = "$ROOT_DIR/lease-meta"
        private const val ROOT_LEASE_LOCK_FILE = "$ROOT_DIR/lease.lock"
        private const val ROOT_OWNER_FILE = "$ROOT_DIR/owner-uid"
        private const val ROOT_BOOT_GENERATION_FILE = "$ROOT_DIR/boot-current"
        private const val ROOT_BOOT_STATUS_FILE = "$ROOT_DIR/boot-status"
        private const val BOOT_SCRIPT = "/data/adb/service.d/micbridge-failsafe.sh"
        private const val GLOBAL_MIC_TARGET = "android-global-microphone"
        private const val ROOT_DEPLOY_TIMEOUT_MS = 8_000L
        private const val ROOT_ARM_TIMEOUT_MS = 6_000L
        private const val ROOT_DEPLOY_STATUS_POLLS = 100
        private const val ROOT_BLOCK_RETRY_BUDGET_MS = 10_000L
        private const val HEX = "0123456789abcdef"
        private val ROOT_WATCHDOG_CONTROLLERS = setOf(
            SettingsRepository.CONTROLLER_AUDIO_MANAGER,
            SettingsRepository.CONTROLLER_APP_OPS,
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
        )
    }

    private data class RootBootGuardTarget(
        val controllerId: String,
        val targetPackage: String,
        val userId: Int,
    )
}

internal fun directBootTargetMatches(
    frozen: SafetyTarget,
    mirroredControllerId: String,
    mirroredTargetPackage: String,
    mirroredUserId: Int,
): Boolean {
    if (frozen.controllerId != mirroredControllerId || frozen.userId != mirroredUserId) {
        return false
    }
    val frozenPackage = if (frozen.controllerId == SettingsRepository.CONTROLLER_APP_OPS) {
        frozen.targetPackage
    } else {
        "android-global-microphone"
    }
    val mirroredPackage = if (mirroredControllerId == SettingsRepository.CONTROLLER_APP_OPS) {
        mirroredTargetPackage
    } else {
        "android-global-microphone"
    }
    return frozenPackage == mirroredPackage
}

/**
 * Pure script generation kept separate from Android services so the fail-safe protocol can be
 * regression-tested on the host JVM. All interpolated values have already passed RootShell's
 * opaque-id/package validation (or are numeric) at the call sites.
 */
internal object RootFailSafeScripts {
    private const val ROOT_DIR = "/data/adb/micbridge"
    private const val LEASE_FILE = "$ROOT_DIR/lease"
    private const val LEASE_META_FILE = "$ROOT_DIR/lease-meta"
    private const val LOCK_FILE = "$ROOT_DIR/lease.lock"
    private const val OWNER_FILE = "$ROOT_DIR/owner-uid"
    private const val GENERATION_FILE = "$ROOT_DIR/boot-current"
    private const val BOOT_STATUS_FILE = "$ROOT_DIR/boot-status"
    // Arm-handshake budget: 150 polls x (10 ms sleep + a `cat` fork) stays at or below the
    // previous 40 x 50 ms (~2 s) and therefore below ARM_GRACE_MS, while a watcher that
    // publishes `armed` quickly is observed in ~10 ms instead of ~50 ms.
    const val ARM_STATUS_POLLS = 150
    private const val ARM_STATUS_POLL_SECONDS = "0.01"
    private const val ARM_GRACE_MS = 3_000L
    private const val ROOT_BLOCK_RETRY_DELAY_SECONDS = "0.15"
    private const val ROOT_BOOT_RETRY_WINDOW_MS = 180_000L
    private const val ROOT_BOOT_CLOCK_FAILURE_ATTEMPTS = 180
    private const val ROOT_WATCHDOG_TAIL_MS = 90_000L
    private const val ROOT_FLOCK_ATTEMPTS = 40
    private const val ROOT_FLOCK_RETRY_DELAY_SECONDS = "0.05"
    private const val ROOT_SUPERVISOR_ACTIVE_POLL_SECONDS = "0.20"
    private const val ROOT_SUPERVISOR_IDLE_POLL_SECONDS = "1"
    private const val ROOT_SUPERVISOR_ACTIVE_DISCOVERY_TICKS = 5
    // User/profile switches are a microphone authority boundary. Keep the idle discovery gap
    // at or below one second; the inexpensive proc/file poll remains 1 Hz as well.
    private const val ROOT_SUPERVISOR_IDLE_DISCOVERY_TICKS = 1
    private const val ROOT_SUPERVISOR_RESTART_SECONDS = "0.20"
    private const val COMMAND_TIMEOUT = SensorPrivacyRootProtocol.COMMAND_TIMEOUT

    fun prerequisiteCheck(controllerId: String): String? = when (controllerId) {
        SettingsRepository.CONTROLLER_APP_OPS -> """
            command -v cmd >/dev/null 2>&1
            command -v dumpsys >/dev/null 2>&1
            command -v pm >/dev/null 2>&1
            command -v am >/dev/null 2>&1
            command -v grep >/dev/null 2>&1
            command -v awk >/dev/null 2>&1
        """.trimIndent()
        SettingsRepository.CONTROLLER_SENSOR_PRIVACY ->
            SensorPrivacyRootProtocol.prerequisites().replace(" && ", "\n")
        else -> null
    }

    /**
     * Revokes a live Root lease only after its effective watchdog gate is freshly BLOCKED
     * under the same flock used by authorized OPEN. This is the cross-process equivalent of
     * the coordinator's mutation gate: an orphaned OPEN shell either finishes before this
     * block, or sees the replacement marker and is rejected afterwards.
     */
    fun revokeLease(
        controllerId: String,
        targetPackage: String,
        userId: Int,
        expectedRequestId: String,
        replacementMarker: String,
        ownerUid: String,
    ): String? {
        if (userId < 0) return null
        val blockAttempt = blockAttempt(controllerId) ?: return null
        val runtimePrerequisites = runtimePrerequisiteCondition(controllerId) ?: return null
        return """
            set -e
            EXPECTED_REQUEST=${RootShell.quote(expectedRequestId)}
            REPLACEMENT_MARKER=${RootShell.quote(replacementMarker)}
            LEASE_FILE=$LEASE_FILE
            LOCK_FILE=$LOCK_FILE
            PKG=${RootShell.quote(targetPackage)}
            USER_ID=${RootShell.quote(userId.toString())}
            REVOKE_LOCKED=0
            REVOKE_TMP=${RootShell.quote("$LEASE_FILE.revoke.tmp")}
            cleanup_revoke() {
              rm -f "${'$'}REVOKE_TMP"
              if [ "${'$'}REVOKE_LOCKED" = 1 ]; then
                flock -u 0 || true
                REVOKE_LOCKED=0
              fi
            }
            trap cleanup_revoke EXIT
            trap 'exit 1' HUP INT TERM
            mkdir -p $ROOT_DIR
            command -v flock >/dev/null 2>&1
            command -v timeout >/dev/null 2>&1
            command -v grep >/dev/null 2>&1
            $runtimePrerequisites
            exec 0>>"${'$'}LOCK_FILE"
            flock -x 0
            REVOKE_LOCKED=1
            # Workspace ownership is proven inside the same flock that blocks and replaces the
            # marker, so a foreign Android profile can never revoke the owner's live lease. This
            # replaces the caller's separate ownership round trip without weakening it.
            test "${'$'}(cat $OWNER_FILE 2>/dev/null)" = ${RootShell.quote(ownerUid)} || exit 78
            CURRENT_REQUEST=
            if [ -f "${'$'}LEASE_FILE" ]; then
              CURRENT_REQUEST=${'$'}(cat "${'$'}LEASE_FILE")
            fi
            case "${'$'}CURRENT_REQUEST" in
              ''|"${'$'}EXPECTED_REQUEST"|expired-*-"${'$'}EXPECTED_REQUEST"|cancel-"${'$'}EXPECTED_REQUEST"-*) ;;
              *) exit 73 ;;
            esac
            LAST=unverified
            BLOCK_VERIFIED=0
            $blockAttempt
            [ "${'$'}BLOCK_VERIFIED" = 1 ] || exit 76
            printf %s "${'$'}REPLACEMENT_MARKER" > "${'$'}REVOKE_TMP"
            mv -f "${'$'}REVOKE_TMP" "${'$'}LEASE_FILE"
            REVOKE_TMP=
            flock -u 0
            REVOKE_LOCKED=0
            trap - EXIT HUP INT TERM
        """.trimIndent()
    }

    /**
     * Installs the immutable per-lease watcher, publishes the lease under the shared flock and
     * completes the arm handshake. Kept next to the other generated scripts so the ordering
     * guarantees (watcher script, child PID and meta committed before the active request marker)
     * and the arm-status polling contract stay testable on the host JVM.
     */
    fun armWatcher(
        controllerId: String,
        requestId: String,
        ownerUid: String,
        appPid: String,
        watcherPath: String,
        watcherStatusPath: String,
        encodedWatcher: String,
        openValidUntilElapsedRealtimeMs: Long,
        hardDeadlineElapsedRealtimeMs: Long,
        persistent: Boolean,
    ): String? {
        val prerequisite = prerequisiteCheck(controllerId) ?: return null
        val safeRequestId = RootShell.requireOpaqueId(requestId)
        val openValidUntil = RootShell.quote(openValidUntilElapsedRealtimeMs.toString())
        // A persistent lease has no timed deadline, so once the watcher publishes `armed` the
        // only property left to prove is that the same child process is still alive. The extra
        // settle-and-recheck below exists solely to re-test a timed deadline.
        val armedConfirmation = (
            if (persistent) {
                """
                    kill -0 "${'$'}WATCH_PID" 2>/dev/null
                    exit 0
                """
            } else {
                """
                    if ! kill -0 "${'$'}WATCH_PID" 2>/dev/null; then
                      exit 1
                    fi
                    NOW_MS=${'$'}(awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null)
                    case "${'$'}NOW_MS" in ''|*[!0-9]*) exit 1;; esac
                    if [ 0 != 1 ] && [ "${'$'}NOW_MS" -ge $openValidUntil ]; then
                      exit 1
                    fi
                    sleep 0.05
                    NOW_MS=${'$'}(awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null)
                    case "${'$'}NOW_MS" in ''|*[!0-9]*) exit 1;; esac
                    if [ 0 != 1 ] && [ "${'$'}NOW_MS" -ge $openValidUntil ]; then
                      exit 1
                    fi
                    kill -0 "${'$'}WATCH_PID" 2>/dev/null
                    exit 0
                """
            }
            ).trimIndent().replace("\n", "\n                ")
        return """
            set -e
            mkdir -p $ROOT_DIR
            command -v base64 >/dev/null 2>&1
            command -v flock >/dev/null 2>&1
            command -v timeout >/dev/null 2>&1
            command -v nohup >/dev/null 2>&1
            command -v awk >/dev/null 2>&1
            command -v grep >/dev/null 2>&1
            $prerequisite
            exec 0>>$LOCK_FILE
            flock -x 0
            test "${'$'}(cat $OWNER_FILE 2>/dev/null)" = ${RootShell.quote(ownerUid)}
            SETUP_LOCKED=1
            LEASE_TMP=${RootShell.quote("$LEASE_FILE.$safeRequestId.tmp")}
            META_TMP=${RootShell.quote("$LEASE_META_FILE.$safeRequestId.tmp")}
            WATCH_TMP=${RootShell.quote("$watcherPath.tmp")}
            WATCH_PID_FILE=${RootShell.quote("$ROOT_DIR/watch-$safeRequestId.pid")}
            WATCH_PID_TMP=${RootShell.quote("$ROOT_DIR/watch-$safeRequestId.pid.tmp")}
            cleanup_setup() {
              rm -f "${'$'}LEASE_TMP" "${'$'}META_TMP" "${'$'}WATCH_TMP" "${'$'}WATCH_PID_TMP"
              if [ "${'$'}SETUP_LOCKED" = 1 ]; then
                flock -u 0 || true
                SETUP_LOCKED=0
              fi
            }
            trap cleanup_setup EXIT
            trap 'exit 1' HUP INT TERM
            APP_PID=${RootShell.quote(appPid)}
            APP_PROC=${'$'}(awk '{print ${'$'}3 "|" ${'$'}22}' "/proc/${'$'}APP_PID/stat" 2>/dev/null)
            APP_STATE=${'$'}{APP_PROC%%\|*}
            APP_START=${'$'}{APP_PROC#*\|}
            case "${'$'}APP_STATE" in R|S) ;; *) exit 1;; esac
            case "${'$'}APP_START" in ''|*[!0-9]*) exit 1;; esac
            GENERATION=${'$'}(cat $GENERATION_FILE 2>/dev/null)
            case "${'$'}GENERATION" in g-[0-9a-f][0-9a-f]*) ;; *) exit 1;; esac
            SETUP_NOW_MS=${'$'}(awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null)
            case "${'$'}SETUP_NOW_MS" in ''|*[!0-9]*) exit 1;; esac
            ARM_BY_MS=${'$'}((SETUP_NOW_MS + $ARM_GRACE_MS))
            printf %s ${RootShell.quote(encodedWatcher)} | base64 -d > "${'$'}WATCH_TMP"
            chmod 700 "${'$'}WATCH_TMP"
            mv -f "${'$'}WATCH_TMP" ${RootShell.quote(watcherPath)}
            rm -f ${RootShell.quote(watcherStatusPath)} "${'$'}WATCH_PID_FILE"
            nohup sh ${RootShell.quote(watcherPath)} >/dev/null 2>&1 &
            WATCH_PID=${'$'}!
            if ! kill -0 "${'$'}WATCH_PID" 2>/dev/null; then
              exit 1
            fi
            printf %s "${'$'}WATCH_PID" > "${'$'}WATCH_PID_TMP"
            mv -f "${'$'}WATCH_PID_TMP" "${'$'}WATCH_PID_FILE"
            printf '%s|%s|%s|%s|%s|%s|%s\n' \
              ${RootShell.quote(safeRequestId)} \
              $openValidUntil \
              ${RootShell.quote(hardDeadlineElapsedRealtimeMs.toString())} \
              "${'$'}APP_PID" "${'$'}APP_START" "${'$'}GENERATION" "${'$'}ARM_BY_MS" > "${'$'}META_TMP"
            mv -f "${'$'}META_TMP" $LEASE_META_FILE
            # Publish the active request last. The supervisor can therefore never observe an
            # active lease without its immutable script, exact child PID, app identity and
            # monotonic deadlines already committed under the same flock.
            printf %s ${RootShell.quote(safeRequestId)} > "${'$'}LEASE_TMP"
            mv -f "${'$'}LEASE_TMP" $LEASE_FILE
            flock -u 0
            SETUP_LOCKED=0
            trap - EXIT HUP INT TERM
            i=0
            while [ ${'$'}i -lt $ARM_STATUS_POLLS ]; do
              STATUS=${'$'}(cat ${RootShell.quote(watcherStatusPath)} 2>/dev/null || true)
              if [ "${'$'}STATUS" = "armed-$safeRequestId-${'$'}WATCH_PID" ]; then
                $armedConfirmation
              fi
              case "${'$'}STATUS" in
                deadline-*)
                  exit 1
                  ;;
                wake-lock-*|marker-*|prerequisite-*|preflight-*|lock-*)
                  kill "${'$'}WATCH_PID" 2>/dev/null || true
                  exit 1
                  ;;
              esac
              if ! kill -0 "${'$'}WATCH_PID" 2>/dev/null; then
                exit 1
              fi
              i=${'$'}((i + 1))
              sleep $ARM_STATUS_POLL_SECONDS
            done
            kill "${'$'}WATCH_PID" 2>/dev/null || true
            exit 1
        """.trimIndent()
    }

    fun bootLauncher(): String = """
        #!/system/bin/sh
        MB_DIR=$ROOT_DIR
        GENERATION_FILE=$GENERATION_FILE
        SUPERVISOR_LOCK="${'$'}MB_DIR/supervisor.lock"
        command -v flock >/dev/null 2>&1 || exit 1
        # Some Android shells mark descriptors above stderr close-on-exec, so `flock 9`
        # loses its descriptor when the external flock binary starts. Descriptor 0 is already
        # used by the lease protocol and is preserved across exec on the supported Root shells.
        exec 0>>"${'$'}SUPERVISOR_LOCK" || exit 1
        # Magisk/KernelSU may invoke the launcher again after an in-app refresh. Exactly one
        # root-owned parent supervises the current immutable generation at a time.
        flock -n -x 0 || exit 0
        while [ -f "${'$'}GENERATION_FILE" ]; do
          GENERATION=${'$'}(cat "${'$'}GENERATION_FILE" 2>/dev/null) || break
          case "${'$'}GENERATION" in
            ''|*[!A-Za-z0-9._-]*) break ;;
          esac
          VERSION_SCRIPT="${'$'}MB_DIR/boot-${'$'}GENERATION.sh"
          [ -x "${'$'}VERSION_SCRIPT" ] || { sleep $ROOT_SUPERVISOR_RESTART_SECONDS; continue; }
          sh "${'$'}VERSION_SCRIPT"
          sleep $ROOT_SUPERVISOR_RESTART_SECONDS
        done
        flock -u 0
    """.trimIndent() + "\n"

    fun bootGuard(
        controllerId: String,
        targetPackage: String,
        userId: Int,
        generation: String,
    ): String? {
        val blockAttempt = blockAttempt(controllerId) ?: return null
        val runtimePrerequisites = runtimePrerequisiteCondition(controllerId) ?: return null
        return """
            #!/system/bin/sh
            MB_DIR=$ROOT_DIR
            GENERATION=${RootShell.quote(generation)}
            GENERATION_FILE=$GENERATION_FILE
            STATUS_FILE=$BOOT_STATUS_FILE
            LOCK_FILE=$LOCK_FILE
            LEASE_FILE=$LEASE_FILE
            META_FILE=$LEASE_META_FILE
            PID_FILE="${'$'}MB_DIR/boot-${'$'}GENERATION.pid"
            PKG=${RootShell.quote(targetPackage)}
            USER_ID=${RootShell.quote(userId.toString())}
            LOCK_HELD=0
            STATUS_TMP=
            write_status() {
              STATUS_TMP="${'$'}STATUS_FILE.tmp.${'$'}${'$'}"
              if printf %s "${'$'}1" > "${'$'}STATUS_TMP" && mv -f "${'$'}STATUS_TMP" "${'$'}STATUS_FILE"; then
                STATUS_TMP=
                return 0
              fi
              return 1
            }
            acquire_lock() {
              LOCK_TRY=0
              while [ ${'$'}LOCK_TRY -lt $ROOT_FLOCK_ATTEMPTS ]; do
                if flock -n -x 0; then
                  LOCK_HELD=1
                  return 0
                fi
                LOCK_TRY=${'$'}((LOCK_TRY + 1))
                sleep $ROOT_FLOCK_RETRY_DELAY_SECONDS
              done
              return 1
            }
            release_lock() {
              if [ "${'$'}LOCK_HELD" = 1 ]; then
                flock -u 0 || true
                LOCK_HELD=0
              fi
            }
            cleanup() {
              release_lock
              if [ -n "${'$'}STATUS_TMP" ]; then
                rm -f "${'$'}STATUS_TMP"
              fi
              PID_RECORD=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null)
              if [ "${'$'}{PID_RECORD%%\|*}" = "${'$'}${'$'}" ]; then
                rm -f "${'$'}PID_FILE"
              fi
            }
            trap cleanup EXIT
            trap 'exit 1' HUP INT TERM
            if ! command -v flock >/dev/null 2>&1 ||
               ! command -v timeout >/dev/null 2>&1 ||
               ! command -v nohup >/dev/null 2>&1 ||
               ! command -v tr >/dev/null 2>&1; then
              write_status "prerequisite-failed-${'$'}GENERATION"
              exit 1
            fi
            if ! { $runtimePrerequisites; }; then
              write_status "prerequisite-failed-${'$'}GENERATION"
              exit 1
            fi
            SELF_START=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}${'$'}/stat" 2>/dev/null)
            case "${'$'}SELF_START" in ''|*[!0-9]*) write_status "pid-start-failed-${'$'}GENERATION"; exit 1;; esac
            PID_TMP="${'$'}PID_FILE.tmp.${'$'}${'$'}"
            if ! printf '%s|%s\n' "${'$'}${'$'}" "${'$'}SELF_START" > "${'$'}PID_TMP" || ! mv -f "${'$'}PID_TMP" "${'$'}PID_FILE"; then
              rm -f "${'$'}PID_TMP"
              write_status "pid-file-failed-${'$'}GENERATION"
              exit 1
            fi
            exec 0>>"${'$'}LOCK_FILE" || exit 1
            BOOT_NOW_MS=${'$'}(awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null)
            BOOT_CLOCK_FAILED=0
            BOOT_CLOCK_FAILURE_TRY=0
            INITIAL_BLOCKED=0
            BASE_USER_CONTEXT=
            LAST=unverified
            case "${'$'}BOOT_NOW_MS" in
              ''|*[!0-9]*)
                BOOT_CLOCK_FAILED=1
                BOOT_DEADLINE_MS=0
                LAST=deadline-clock-failed
                write_status "deadline-clock-failed-${'$'}GENERATION"
                ;;
              *)
                BOOT_DEADLINE_MS=${'$'}((BOOT_NOW_MS + $ROOT_BOOT_RETRY_WINDOW_MS))
                ;;
            esac
            while :; do
              if [ "${'$'}BOOT_CLOCK_FAILED" = 0 ]; then
                BOOT_NOW_MS=${'$'}(awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null)
                case "${'$'}BOOT_NOW_MS" in
                  ''|*[!0-9]*)
                    BOOT_CLOCK_FAILED=1
                    LAST=deadline-clock-failed
                    write_status "deadline-clock-failed-${'$'}GENERATION"
                    ;;
                esac
              fi
              if [ "${'$'}BOOT_CLOCK_FAILED" = 1 ]; then
                if [ "${'$'}BOOT_CLOCK_FAILURE_TRY" -ge $ROOT_BOOT_CLOCK_FAILURE_ATTEMPTS ]; then
                  break
                fi
                BOOT_CLOCK_FAILURE_TRY=${'$'}((BOOT_CLOCK_FAILURE_TRY + 1))
              elif [ "${'$'}BOOT_NOW_MS" -ge "${'$'}BOOT_DEADLINE_MS" ]; then
                  break
              fi
              if ! acquire_lock; then
                LAST=lock-contended
                write_status "lock-contended-${'$'}GENERATION"
                sleep 1
                continue
              fi
              CURRENT_GENERATION=${'$'}(cat "${'$'}GENERATION_FILE" 2>/dev/null)
              if [ "${'$'}CURRENT_GENERATION" != "${'$'}GENERATION" ]; then
                release_lock
                exit 0
              fi
              BLOCK_VERIFIED=0
              $blockAttempt
              if [ "${'$'}BLOCK_VERIFIED" = 1 ]; then
                BASE_USER_CONTEXT=${'$'}(printf '%s|%s' "${'$'}CURRENT_USER" "${'$'}USER_IDS" | tr '\n' ',')
                write_status "blocked-${'$'}GENERATION"
                release_lock
                INITIAL_BLOCKED=1
                break
              fi
              release_lock
              sleep 1
            done
            if [ "${'$'}INITIAL_BLOCKED" != 1 ]; then
              if acquire_lock; then
                CURRENT_GENERATION=${'$'}(cat "${'$'}GENERATION_FILE" 2>/dev/null)
                if [ "${'$'}CURRENT_GENERATION" = "${'$'}GENERATION" ]; then
                  write_status "block-unverified-${'$'}LAST-${'$'}GENERATION"
                fi
                release_lock
              fi
              exit 1
            fi

            # Remain outside the app UID for this generation. Active leases are checked at a
            # short cadence using only procfs/files; expensive framework user discovery runs
            # once per second while active and once per idle interval. Any ambiguous active
            # health signal causes an immediate BLOCK+readback+terminal marker, never a silent
            # watcher restart. `am force-stop` cannot suspend this root process.
            SUPERVISOR_SLEEP=$ROOT_SUPERVISOR_ACTIVE_POLL_SECONDS
            DISCOVERY_TICKS=0
            while :; do
              sleep "${'$'}SUPERVISOR_SLEEP"
              PRELOCK_LEASE=${'$'}(cat "${'$'}LEASE_FILE" 2>/dev/null)
              case "${'$'}PRELOCK_LEASE" in
                ''|cancel-*|expired-blocked-*|removed-*)
                  SUPERVISOR_SLEEP=$ROOT_SUPERVISOR_IDLE_POLL_SECONDS
                  DISCOVERY_INTERVAL=$ROOT_SUPERVISOR_IDLE_DISCOVERY_TICKS
                  ;;
                *)
                  SUPERVISOR_SLEEP=$ROOT_SUPERVISOR_ACTIVE_POLL_SECONDS
                  DISCOVERY_INTERVAL=$ROOT_SUPERVISOR_ACTIVE_DISCOVERY_TICKS
                  ;;
              esac
              if [ "${'$'}DISCOVERY_TICKS" -gt "${'$'}DISCOVERY_INTERVAL" ]; then
                DISCOVERY_TICKS=${'$'}DISCOVERY_INTERVAL
              fi
              DISCOVERY_TICKS=${'$'}((DISCOVERY_TICKS - 1))
              DISCOVERY_DUE=0
              DISCOVERED_USER_CONTEXT=
              DISCOVERY_LAST=not-due
              if [ "${'$'}DISCOVERY_TICKS" -le 0 ]; then
                DISCOVERY_DUE=1
                DISCOVERY_TICKS=${'$'}DISCOVERY_INTERVAL
                # Framework calls stay outside lease.lock so emergency BLOCK/authorized OPEN
                # cannot be starved by an OEM service timeout.
                ${SensorPrivacyRootProtocol.discoverUsersAttempt(COMMAND_TIMEOUT)}
                DISCOVERY_LAST=${'$'}LAST
                if [ "${'$'}USER_CONTEXT_READY" = 1 ]; then
                  DISCOVERED_USER_CONTEXT=${'$'}(printf '%s|%s' "${'$'}CURRENT_USER" "${'$'}USER_IDS" | tr '\n' ',')
                fi
              fi
              if ! acquire_lock; then
                write_status "supervisor-lock-contended-${'$'}GENERATION"
                continue
              fi
              CURRENT_GENERATION=${'$'}(cat "${'$'}GENERATION_FILE" 2>/dev/null)
              if [ "${'$'}CURRENT_GENERATION" != "${'$'}GENERATION" ]; then
                release_lock
                exit 0
              fi

              CURRENT_LEASE=${'$'}(cat "${'$'}LEASE_FILE" 2>/dev/null)
              CLOSE_REASON=
              case "${'$'}CURRENT_LEASE" in
                ''|cancel-*|expired-blocked-*|removed-*)
                  if [ "${'$'}DISCOVERY_DUE" = 1 ]; then
                    if [ "${'$'}USER_CONTEXT_READY" = 1 ]; then
                      if [ "${'$'}DISCOVERED_USER_CONTEXT" != "${'$'}BASE_USER_CONTEXT" ]; then
                        BLOCK_VERIFIED=0
                        $blockAttempt
                        if [ "${'$'}BLOCK_VERIFIED" = 1 ]; then
                          BASE_USER_CONTEXT=${'$'}(printf '%s|%s' "${'$'}CURRENT_USER" "${'$'}USER_IDS" | tr '\n' ',')
                          write_status "idle-user-context-blocked-${'$'}GENERATION"
                        else
                          write_status "idle-user-context-unverified-${'$'}LAST-${'$'}GENERATION"
                        fi
                      fi
                    else
                      write_status "idle-user-context-unverified-${'$'}DISCOVERY_LAST-${'$'}GENERATION"
                    fi
                  fi
                  ;;
                expired-*|*[!A-Za-z0-9._-]*)
                  CLOSE_REASON=unsafe-marker
                  ;;
                *)
                  if [ "${'$'}DISCOVERY_DUE" = 1 ]; then
                    if [ "${'$'}USER_CONTEXT_READY" != 1 ]; then
                      CLOSE_REASON=user-context-unverified
                    elif [ "${'$'}DISCOVERED_USER_CONTEXT" != "${'$'}BASE_USER_CONTEXT" ]; then
                      CLOSE_REASON=user-context-changed
                    fi
                  fi
                  META_REQUEST= META_BLOCK_AT= META_DEADLINE= META_APP_PID= META_APP_START= META_GENERATION= META_ARM_BY= META_EXTRA=
                  IFS='|' read -r META_REQUEST META_BLOCK_AT META_DEADLINE META_APP_PID META_APP_START META_GENERATION META_ARM_BY META_EXTRA < "${'$'}META_FILE" || CLOSE_REASON=meta-read
                  if [ -z "${'$'}CLOSE_REASON" ]; then
                    [ "${'$'}META_REQUEST" = "${'$'}CURRENT_LEASE" ] || CLOSE_REASON=meta-request
                    [ "${'$'}META_GENERATION" = "${'$'}GENERATION" ] || CLOSE_REASON=meta-generation
                    [ -z "${'$'}META_EXTRA" ] || CLOSE_REASON=meta-extra
                    case "${'$'}META_BLOCK_AT" in ''|*[!0-9]*) CLOSE_REASON=meta-block-at;; esac
                    case "${'$'}META_DEADLINE" in ''|*[!0-9]*) CLOSE_REASON=meta-deadline;; esac
                    case "${'$'}META_APP_PID" in ''|*[!0-9]*) CLOSE_REASON=meta-app-pid;; esac
                    case "${'$'}META_APP_START" in ''|*[!0-9]*) CLOSE_REASON=meta-app-start;; esac
                    case "${'$'}META_ARM_BY" in ''|*[!0-9]*) CLOSE_REASON=meta-arm-by;; esac
                  fi
                  META_PERSISTENT=0
                  if [ -z "${'$'}CLOSE_REASON" ] && [ "${'$'}META_BLOCK_AT" = 0 ]; then
                    if [ "${'$'}META_DEADLINE" = 0 ]; then
                      META_PERSISTENT=1
                    else
                      CLOSE_REASON=meta-window
                    fi
                  elif [ -z "${'$'}CLOSE_REASON" ] && [ "${'$'}META_BLOCK_AT" -ge "${'$'}META_DEADLINE" ]; then
                    CLOSE_REASON=meta-window
                  fi
                  NOW_MS=${'$'}(awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null)
                  case "${'$'}NOW_MS" in ''|*[!0-9]*) CLOSE_REASON=deadline-clock;; esac
                  if [ -z "${'$'}CLOSE_REASON" ] && [ "${'$'}META_PERSISTENT" != 1 ] && [ "${'$'}NOW_MS" -ge "${'$'}META_BLOCK_AT" ]; then
                    CLOSE_REASON=deadline
                  fi
                  if [ -z "${'$'}CLOSE_REASON" ]; then
                    APP_PROC=${'$'}(awk '{print ${'$'}3 "|" ${'$'}22}' "/proc/${'$'}META_APP_PID/stat" 2>/dev/null)
                    APP_STATE=${'$'}{APP_PROC%%\|*}
                    APP_START=${'$'}{APP_PROC#*\|}
                    kill -0 "${'$'}META_APP_PID" 2>/dev/null || CLOSE_REASON=app-dead
                    [ "${'$'}APP_START" = "${'$'}META_APP_START" ] || CLOSE_REASON=app-replaced
                    case "${'$'}APP_STATE" in R|S) ;; *) CLOSE_REASON=app-unhealthy;; esac
                  fi
                  WATCH_SCRIPT="${'$'}MB_DIR/watch-${'$'}CURRENT_LEASE.sh"
                  WATCH_PID_FILE="${'$'}MB_DIR/watch-${'$'}CURRENT_LEASE.pid"
                  WATCH_STATUS_FILE="${'$'}MB_DIR/watch-${'$'}CURRENT_LEASE.status"
                  WATCH_PID=${'$'}(cat "${'$'}WATCH_PID_FILE" 2>/dev/null)
                  if [ -z "${'$'}CLOSE_REASON" ]; then
                    [ -x "${'$'}WATCH_SCRIPT" ] || CLOSE_REASON=watch-script
                    case "${'$'}WATCH_PID" in ''|*[!0-9]*) CLOSE_REASON=watch-pid;; esac
                  fi
                  if [ -z "${'$'}CLOSE_REASON" ]; then
                    WATCH_PROC=${'$'}(awk '{print ${'$'}3}' "/proc/${'$'}WATCH_PID/stat" 2>/dev/null)
                    kill -0 "${'$'}WATCH_PID" 2>/dev/null || CLOSE_REASON=watch-dead
                    case "${'$'}WATCH_PROC" in R|S) ;; *) CLOSE_REASON=watch-unhealthy;; esac
                    tr '\000' ' ' < "/proc/${'$'}WATCH_PID/cmdline" 2>/dev/null | grep -Fq "${'$'}WATCH_SCRIPT" || CLOSE_REASON=watch-replaced
                    WATCH_STATUS=${'$'}(cat "${'$'}WATCH_STATUS_FILE" 2>/dev/null)
                    case "${'$'}WATCH_STATUS" in
                      "armed-${'$'}CURRENT_LEASE-${'$'}WATCH_PID") ;;
                      '')
                        if [ "${'$'}NOW_MS" -ge "${'$'}META_ARM_BY" ]; then
                          CLOSE_REASON=watch-arm-timeout
                        fi
                        ;;
                      *) CLOSE_REASON=watch-status ;;
                    esac
                  fi
                  ;;
              esac

              if [ -n "${'$'}CLOSE_REASON" ]; then
                BLOCK_VERIFIED=0
                $blockAttempt
                if [ "${'$'}BLOCK_VERIFIED" = 1 ]; then
                  LEASE_TMP="${'$'}LEASE_FILE.supervisor.tmp.${'$'}${'$'}"
                  if printf %s "expired-blocked-supervisor-${'$'}CLOSE_REASON-${'$'}CURRENT_LEASE" > "${'$'}LEASE_TMP" &&
                     mv -f "${'$'}LEASE_TMP" "${'$'}LEASE_FILE"; then
                    write_status "lease-closed-${'$'}CLOSE_REASON-${'$'}GENERATION"
                  else
                    rm -f "${'$'}LEASE_TMP"
                    write_status "lease-close-marker-failed-${'$'}GENERATION"
                  fi
                else
                  write_status "lease-close-unverified-${'$'}CLOSE_REASON-${'$'}LAST-${'$'}GENERATION"
                fi
              fi
              release_lock
            done
        """.trimIndent() + "\n"
    }

    fun watcher(
        controllerId: String,
        targetPackage: String,
        userId: Int,
        requestId: String,
        deadlineElapsedRealtimeMs: Long,
        blockAtElapsedRealtimeMs: Long,
        watcherPath: String,
        watcherStatusPath: String,
        wakeTag: String,
        persistent: Boolean = false,
    ): String? {
        val blockAttempt = blockAttempt(controllerId) ?: return null
        // Before OPEN, only the frozen foreground user can be mutated. Prove that exact native
        // block path quickly; terminal enforcement below retains the full all-user attempt.
        val preflightBlockAttempt = when (controllerId) {
            SettingsRepository.CONTROLLER_APP_OPS,
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY ->
                SensorPrivacyRootProtocol.blockTargetUserAttempt(COMMAND_TIMEOUT)
            else -> return null
        }
        val runtimePrerequisites = runtimePrerequisiteCondition(controllerId) ?: return null
        val watcherPidPath = watcherPath.removeSuffix(".sh") + ".pid"
        return """
            #!/system/bin/sh
            REQUEST_ID=${RootShell.quote(requestId)}
            DEADLINE_MS=${RootShell.quote(deadlineElapsedRealtimeMs.toString())}
            BLOCK_AT_MS=${RootShell.quote(blockAtElapsedRealtimeMs.toString())}
            PERSISTENT=${if (persistent) "1" else "0"}
            WAKE_TAG=${RootShell.quote(wakeTag)}
            STATUS_FILE=${RootShell.quote(watcherStatusPath)}
            WATCHER_PATH=${RootShell.quote(watcherPath)}
            PID_FILE=${RootShell.quote(watcherPidPath)}
            LOCK_FILE=$LOCK_FILE
            LEASE_FILE=$LEASE_FILE
            PKG=${RootShell.quote(targetPackage)}
            USER_ID=${RootShell.quote(userId.toString())}
            LOCK_HELD=0
            WAKE_HELD=0
            STATUS_TMP=
            LEASE_TMP=
            boottime_ms() {
              awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null
            }
            write_status() {
              STATUS_TMP="${'$'}STATUS_FILE.tmp.${'$'}${'$'}"
              if printf %s "${'$'}1" > "${'$'}STATUS_TMP" && mv -f "${'$'}STATUS_TMP" "${'$'}STATUS_FILE"; then
                STATUS_TMP=
                return 0
              fi
              return 1
            }
            write_lease() {
              LEASE_TMP="${'$'}LEASE_FILE.tmp.${'$'}${'$'}"
              if ! printf %s "${'$'}1" > "${'$'}LEASE_TMP"; then
                return 1
              fi
              if ! mv -f "${'$'}LEASE_TMP" "${'$'}LEASE_FILE"; then
                return 1
              fi
              LEASE_TMP=
              return 0
            }
            acquire_lock() {
              LOCK_TRY=0
              while [ ${'$'}LOCK_TRY -lt $ROOT_FLOCK_ATTEMPTS ]; do
                if flock -n -x 0; then
                  LOCK_HELD=1
                  return 0
                fi
                LOCK_TRY=${'$'}((LOCK_TRY + 1))
                sleep $ROOT_FLOCK_RETRY_DELAY_SECONDS
              done
              return 1
            }
            release_lock() {
              if [ "${'$'}LOCK_HELD" = 1 ]; then
                flock -u 0 || true
                LOCK_HELD=0
              fi
            }
            cleanup() {
              release_lock
              if [ "${'$'}WAKE_HELD" = 1 ]; then
                printf %s "${'$'}WAKE_TAG" > /sys/power/wake_unlock 2>/dev/null || true
                WAKE_HELD=0
              fi
              if [ -n "${'$'}STATUS_TMP" ]; then
                rm -f "${'$'}STATUS_TMP"
              fi
              if [ -n "${'$'}LEASE_TMP" ]; then
                rm -f "${'$'}LEASE_TMP"
              fi
            }
            trap cleanup EXIT
            trap 'exit 1' HUP INT TERM
            if ! command -v flock >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1 || ! command -v awk >/dev/null 2>&1; then
              write_status "prerequisite-failed-${'$'}REQUEST_ID"
              exit 1
            fi
            if ! { command -v grep >/dev/null 2>&1 && $runtimePrerequisites; }; then
              write_status "prerequisite-failed-${'$'}REQUEST_ID"
              exit 1
            fi
            SELF_PID=${'$'}${'$'}
            exec 0>>"${'$'}LOCK_FILE" || exit 1
            if ! acquire_lock; then
              write_status "arm-lock-failed-${'$'}REQUEST_ID"
              exit 1
            fi
            RECORDED_PID=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null)
            if [ "${'$'}RECORDED_PID" != "${'$'}SELF_PID" ]; then
              write_status "pid-mismatch-${'$'}REQUEST_ID-${'$'}SELF_PID"
              release_lock
              exit 1
            fi
            CURRENT=${'$'}(cat "${'$'}LEASE_FILE" 2>/dev/null)
            if [ "${'$'}CURRENT" != "${'$'}REQUEST_ID" ]; then
              write_status "marker-replaced-${'$'}REQUEST_ID"
              release_lock
                exit 0
            fi
            LAST=unverified
            BLOCK_VERIFIED=0
            $preflightBlockAttempt
            if [ "${'$'}BLOCK_VERIFIED" != 1 ]; then
              write_status "preflight-block-unverified-${'$'}LAST-${'$'}REQUEST_ID"
              release_lock
              exit 1
            fi
            NOW_MS=${'$'}(boottime_ms)
            case "${'$'}NOW_MS" in
              ''|*[!0-9]*)
                write_status "deadline-clock-failed-${'$'}REQUEST_ID"
                release_lock
                exit 1
                ;;
            esac
            CLOCK_FAILED=0
            if [ "${'$'}PERSISTENT" = 1 ]; then
              write_status "armed-${'$'}REQUEST_ID-${'$'}SELF_PID"
              release_lock
              while :; do
                if ! acquire_lock; then
                  write_status "lock-contended-${'$'}REQUEST_ID"
                  sleep 0.05
                  continue
                fi
                CURRENT=${'$'}(cat "${'$'}LEASE_FILE" 2>/dev/null)
                if [ "${'$'}CURRENT" != "${'$'}REQUEST_ID" ]; then
                  release_lock
                  exit 0
                fi
                release_lock
                sleep 0.20
              done
            elif [ "${'$'}NOW_MS" -lt "${'$'}BLOCK_AT_MS" ]; then
              if [ ! -w /sys/power/wake_lock ] || [ ! -w /sys/power/wake_unlock ]; then
                write_status "wake-lock-unavailable-${'$'}REQUEST_ID"
                release_lock
                exit 1
              fi
              REMAINING_MS=${'$'}((DEADLINE_MS - NOW_MS))
              WAKE_TIMEOUT_MS=${'$'}((REMAINING_MS + $ROOT_WATCHDOG_TAIL_MS))
              WAKE_TIMEOUT_NANOS=${'$'}(awk -v ms="${'$'}WAKE_TIMEOUT_MS" 'BEGIN {printf "%.0f\n", ms * 1000000}')
              # Some Android shells issue one write per printf conversion. Sysfs requires the
              # tag and timeout to arrive in one write, so build a single payload first.
              WAKE_PAYLOAD="${'$'}WAKE_TAG ${'$'}WAKE_TIMEOUT_NANOS"
              if ! printf %s "${'$'}WAKE_PAYLOAD" > /sys/power/wake_lock; then
                write_status "wake-lock-failed-${'$'}REQUEST_ID"
                release_lock
                exit 1
              fi
              WAKE_HELD=1
              if ! grep -Fq "${'$'}WAKE_TAG" /sys/power/wake_lock; then
                write_status "wake-lock-unverified-${'$'}REQUEST_ID"
                release_lock
                exit 1
              fi
              write_status "armed-${'$'}REQUEST_ID-${'$'}SELF_PID"
              release_lock
              while :; do
                NOW_MS=${'$'}(boottime_ms)
                case "${'$'}NOW_MS" in
                  ''|*[!0-9]*)
                    CLOCK_FAILED=1
                    write_status "deadline-clock-failed-${'$'}REQUEST_ID"
                    break
                    ;;
                esac
                if [ "${'$'}NOW_MS" -ge "${'$'}BLOCK_AT_MS" ]; then
                  break
                fi
                if ! acquire_lock; then
                  write_status "lock-contended-${'$'}REQUEST_ID"
                  sleep 0.05
                  continue
                fi
                CURRENT=${'$'}(cat "${'$'}LEASE_FILE" 2>/dev/null)
                if [ "${'$'}CURRENT" != "${'$'}REQUEST_ID" ]; then
                  release_lock
                  exit 0
                fi
                release_lock
                sleep 0.05
              done
            else
              write_status "deadline-guard-window-expired-${'$'}REQUEST_ID"
              release_lock
            fi
            LAST=unverified
            while :; do
              NOW_MS=${'$'}(boottime_ms)
              case "${'$'}NOW_MS" in
                ''|*[!0-9]*)
                  CLOCK_FAILED=1
                  LAST=deadline-clock-failed
                  write_status "deadline-clock-failed-${'$'}REQUEST_ID"
                  ;;
              esac
              if ! acquire_lock; then
                LAST=lock-contended
                write_status "lock-contended-${'$'}REQUEST_ID"
                sleep $ROOT_BLOCK_RETRY_DELAY_SECONDS
                continue
              fi
              CURRENT=${'$'}(cat "${'$'}LEASE_FILE" 2>/dev/null)
              if [ "${'$'}CURRENT" != "${'$'}REQUEST_ID" ]; then
                release_lock
                exit 0
              fi
              BLOCK_VERIFIED=0
              $blockAttempt
              if [ "${'$'}BLOCK_VERIFIED" = 1 ]; then
                write_status "preblocked-${'$'}REQUEST_ID"
                # The fresh BLOCK readback and terminal marker commit share one flock. No
                # stale success bit can survive a later OPEN between verification and commit.
                if write_lease "expired-blocked-${'$'}REQUEST_ID"; then
                  write_status "expired-blocked-${'$'}REQUEST_ID"
                  release_lock
                  exit 0
                fi
                LAST=marker-write-failed
                write_status "expired-marker-write-failed-${'$'}REQUEST_ID"
              else
                write_status "expired-${'$'}LAST-${'$'}REQUEST_ID"
              fi
              release_lock
              sleep $ROOT_BLOCK_RETRY_DELAY_SECONDS
            done
        """.trimIndent() + "\n"
    }

    private fun blockAttempt(controllerId: String): String? = when (controllerId) {
        // Legacy root_appops leases are retired by closing only the global sensor gate. AppOps
        // exposes no trustworthy last-writer provenance, so a fail-safe must never mutate it.
        SettingsRepository.CONTROLLER_APP_OPS ->
            SensorPrivacyRootProtocol.blockAllUsersAttempt(COMMAND_TIMEOUT)
        SettingsRepository.CONTROLLER_SENSOR_PRIVACY ->
            SensorPrivacyRootProtocol.blockAllUsersAttempt(COMMAND_TIMEOUT)
        else -> null
    }

    private fun runtimePrerequisiteCondition(controllerId: String): String? = when (controllerId) {
        SettingsRepository.CONTROLLER_APP_OPS,
        SettingsRepository.CONTROLLER_SENSOR_PRIVACY -> SensorPrivacyRootProtocol.prerequisites()
        else -> null
    }

}
