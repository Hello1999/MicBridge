package com.jack.micbridge.mic

/**
 * Makes a Root OPEN mutation conditional at the last possible instruction boundary.
 *
 * The external expiry watcher uses the same kernel flock. Therefore it is impossible for the
 * watcher to finish a BLOCK and then have an already-authorized Root shell perform a late OPEN:
 * either OPEN owns the lock first (the watcher blocks immediately after it), or the watcher owns
 * it first and the marker/deadline check rejects OPEN after the lock becomes available.
 */
internal object RootOpenAuthorizationGuard {
    private const val ROOT_DIR = "/data/adb/micbridge"
    private const val LEASE_FILE = "$ROOT_DIR/lease"
    private const val LEASE_META_FILE = "$ROOT_DIR/lease-meta"
    private const val GENERATION_FILE = "$ROOT_DIR/boot-current"
    private const val LOCK_FILE = "$ROOT_DIR/lease.lock"

    fun command(rawOpenCommand: String, authorization: OpenAuthorization): String {
        val requestId = RootShell.requireOpaqueId(authorization.requestId)
        require(
            authorization.persistent || authorization.validUntilElapsedRealtimeMs > 0L,
        )
        return """
            OPEN_REQUEST_ID=${RootShell.quote(requestId)}
            OPEN_VALID_UNTIL_MS=${RootShell.quote(authorization.validUntilElapsedRealtimeMs.toString())}
            OPEN_PERSISTENT=${if (authorization.persistent) "1" else "0"}
            OPEN_LOCKED=0
            cleanup_open_guard() {
              if [ "${'$'}OPEN_LOCKED" = 1 ]; then
                flock -u 0 || true
                OPEN_LOCKED=0
              fi
            }
            trap cleanup_open_guard EXIT
            trap 'exit 1' HUP INT TERM
            command -v flock >/dev/null 2>&1 || exit 71
            command -v awk >/dev/null 2>&1 || exit 71
            command -v grep >/dev/null 2>&1 || exit 71
            command -v tr >/dev/null 2>&1 || exit 71
            exec 0>>$LOCK_FILE || exit 71
            OPEN_LOCK_TRY=0
            while [ ${'$'}OPEN_LOCK_TRY -lt 20 ]; do
              if flock -n -x 0; then
                OPEN_LOCKED=1
                break
              fi
              OPEN_LOCK_TRY=${'$'}((OPEN_LOCK_TRY + 1))
              sleep 0.025
            done
            [ "${'$'}OPEN_LOCKED" = 1 ] || exit 72
            CURRENT_REQUEST=${'$'}(cat $LEASE_FILE 2>/dev/null)
            [ "${'$'}CURRENT_REQUEST" = "${'$'}OPEN_REQUEST_ID" ] || exit 73
            META_REQUEST= META_BLOCK_AT= META_DEADLINE= META_APP_PID= META_APP_START= META_GENERATION= META_ARM_BY= META_EXTRA=
            IFS='|' read -r META_REQUEST META_BLOCK_AT META_DEADLINE META_APP_PID META_APP_START META_GENERATION META_ARM_BY META_EXTRA < $LEASE_META_FILE || exit 73
            [ "${'$'}META_REQUEST" = "${'$'}OPEN_REQUEST_ID" ] || exit 73
            if [ "${'$'}OPEN_PERSISTENT" = 1 ]; then
              [ "${'$'}META_BLOCK_AT" = 0 ] || exit 73
              [ "${'$'}META_DEADLINE" = 0 ] || exit 73
            else
              [ "${'$'}META_BLOCK_AT" = "${'$'}OPEN_VALID_UNTIL_MS" ] || exit 73
            fi
            [ -z "${'$'}META_EXTRA" ] || exit 73
            case "${'$'}META_DEADLINE" in ''|*[!0-9]*) exit 73;; esac
            case "${'$'}META_APP_PID" in ''|*[!0-9]*) exit 73;; esac
            case "${'$'}META_APP_START" in ''|*[!0-9]*) exit 73;; esac
            case "${'$'}META_ARM_BY" in ''|*[!0-9]*) exit 73;; esac
            case "${'$'}META_GENERATION" in ''|*[!A-Za-z0-9._-]*) exit 73;; esac
            if [ "${'$'}OPEN_PERSISTENT" != 1 ]; then
              [ "${'$'}META_BLOCK_AT" -lt "${'$'}META_DEADLINE" ] || exit 73
            fi
            [ "${'$'}(cat $GENERATION_FILE 2>/dev/null)" = "${'$'}META_GENERATION" ] || exit 73
            APP_PROC=${'$'}(awk '{print ${'$'}3 "|" ${'$'}22}' "/proc/${'$'}META_APP_PID/stat" 2>/dev/null)
            APP_STATE=${'$'}{APP_PROC%%\|*}
            APP_START=${'$'}{APP_PROC#*\|}
            kill -0 "${'$'}META_APP_PID" 2>/dev/null || exit 73
            [ "${'$'}APP_START" = "${'$'}META_APP_START" ] || exit 73
            case "${'$'}APP_STATE" in R|S) ;; *) exit 73;; esac
            WATCH_SCRIPT=$ROOT_DIR/watch-${'$'}OPEN_REQUEST_ID.sh
            WATCH_PID=${'$'}(cat $ROOT_DIR/watch-${'$'}OPEN_REQUEST_ID.pid 2>/dev/null)
            case "${'$'}WATCH_PID" in ''|*[!0-9]*) exit 73;; esac
            kill -0 "${'$'}WATCH_PID" 2>/dev/null || exit 73
            WATCH_STATE=${'$'}(awk '{print ${'$'}3}' "/proc/${'$'}WATCH_PID/stat" 2>/dev/null)
            case "${'$'}WATCH_STATE" in R|S) ;; *) exit 73;; esac
            tr '\000' ' ' < "/proc/${'$'}WATCH_PID/cmdline" 2>/dev/null | grep -Fq "${'$'}WATCH_SCRIPT" || exit 73
            [ "${'$'}(cat $ROOT_DIR/watch-${'$'}OPEN_REQUEST_ID.status 2>/dev/null)" = "armed-${'$'}OPEN_REQUEST_ID-${'$'}WATCH_PID" ] || exit 73
            BOOT_RECORD=${'$'}(cat $ROOT_DIR/boot-${'$'}META_GENERATION.pid 2>/dev/null)
            BOOT_PID=${'$'}{BOOT_RECORD%%\|*}
            BOOT_START=${'$'}{BOOT_RECORD#*\|}
            case "${'$'}BOOT_PID" in ''|*[!0-9]*) exit 73;; esac
            case "${'$'}BOOT_START" in ''|*[!0-9]*) exit 73;; esac
            kill -0 "${'$'}BOOT_PID" 2>/dev/null || exit 73
            BOOT_PROC=${'$'}(awk '{print ${'$'}3 "|" ${'$'}22}' "/proc/${'$'}BOOT_PID/stat" 2>/dev/null)
            case "${'$'}{BOOT_PROC%%\|*}" in R|S) ;; *) exit 73;; esac
            [ "${'$'}{BOOT_PROC#*\|}" = "${'$'}BOOT_START" ] || exit 73
            tr '\000' ' ' < "/proc/${'$'}BOOT_PID/cmdline" 2>/dev/null | grep -Fq "$ROOT_DIR/boot-${'$'}META_GENERATION.sh" || exit 73
            OPEN_NOW_MS=${'$'}(awk '{printf "%.0f\n", ${'$'}1 * 1000}' /proc/uptime 2>/dev/null)
            case "${'$'}OPEN_NOW_MS" in ''|*[!0-9]*) exit 74;; esac
            if [ "${'$'}OPEN_PERSISTENT" != 1 ]; then
              [ "${'$'}OPEN_NOW_MS" -lt "${'$'}OPEN_VALID_UNTIL_MS" ] || exit 75
            fi
            # fd 0 owns lease.lock here. Never pass that descriptor through Binder to
            # system_server: several OEM SELinux policies reject the inherited adb_data_file.
            $rawOpenCommand </dev/null
            OPEN_RESULT=${'$'}?
            flock -u 0
            OPEN_LOCKED=0
            trap - EXIT HUP INT TERM
            exit "${'$'}OPEN_RESULT"
        """.trimIndent()
    }
}
