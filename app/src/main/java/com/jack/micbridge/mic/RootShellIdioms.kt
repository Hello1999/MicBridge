package com.jack.micbridge.mic

/**
 * Fork-free POSIX-sh replacements for the tiny `cat`/`awk` reads performed by the two hot Root
 * scripts: [RootOpenAuthorizationGuard.command], which wraps every Root OPEN, and the read-only
 * active-guard proof that runs once per OPEN and then every 250 ms for as long as the microphone
 * stays OPEN.
 *
 * On the target device each fork costs roughly 5-10 ms, so a proof that spawns fifteen helper
 * processes keeps the single Root session busy long enough to delay a queued BLOCK. Every helper
 * below is a strict equivalent of the construct it replaces: the surrounding `case`/`test`
 * validation, its order and its exit codes are unchanged, and an unreadable file still yields an
 * empty value that fails those checks closed exactly as an empty `$(cat ...)` did.
 *
 * Each helper emits exactly one line, so it can be interpolated into a `trimIndent()` template
 * without collapsing the surrounding indentation.
 */
internal object RootShellIdioms {
    private const val STAT_LINE = "MB_STAT"
    private const val UPTIME_LINE = "MB_UP"
    private const val UPTIME_SECONDS = "MB_SEC"
    private const val UPTIME_INTEGER = "MB_INT"
    private const val UPTIME_FRACTION = "MB_FRAC"

    /**
     * Equivalent of `variable=$(cat path 2>/dev/null)` for the single-line files MicBridge writes
     * with `printf %s` (lease, boot-current, watch-*.pid, watch-*.status, boot-*.pid, owner-uid).
     *
     * `read -r` on a file without a trailing newline returns non-zero yet still assigns the
     * variable, so `|| true` is mandatory; it also keeps a script running under `set -e` from
     * aborting before the specific `case`/`test` that follows can report the real failure. A
     * missing or empty file leaves the variable empty, exactly like the `cat` substitution.
     * `2>/dev/null` is written before the input redirection because the shell applies
     * redirections left to right: with the opposite order a missing file would print the
     * shell's "cannot open" diagnostic to the original stderr before stderr is silenced.
     * `IFS=` suppresses whitespace trimming so the value is byte-identical to the file content.
     *
     * The lease-meta record is deliberately not read through this helper: it keeps its own
     * `IFS='|' read -r` field split.
     */
    fun readSingleLineFile(variable: String, path: String): String =
        "$variable=; IFS= read -r $variable 2>/dev/null < \"$path\" || true"

    /**
     * Equivalent of `variable=$(awk '{print $3 "|" $22}' statPath 2>/dev/null)` followed by the
     * `%%|*` / `#*|` split: process state (field 3) and starttime (field 22) of a `/proc/<pid>/stat`
     * line. `set --` reproduces awk's default whitespace field split; the scripts never use
     * positional parameters, and both run with `set -f`.
     */
    fun procStatStateAndStart(
        stateVariable: String,
        startVariable: String,
        statPath: String,
    ): String = procStatState(stateVariable, statPath) + "; $startVariable=\${22:-}"

    /** Equivalent of `variable=$(awk '{print $3}' statPath 2>/dev/null)`. */
    fun procStatState(stateVariable: String, statPath: String): String =
        readSingleLineFile(STAT_LINE, statPath) +
            "; set -- \$$STAT_LINE" +
            "; $stateVariable=\${3:-}"

    /**
     * Equivalent of `variable=$(awk '{printf "%.0f\n", $1 * 1000}' /proc/uptime 2>/dev/null)`.
     *
     * Linux prints uptime as `%lu.%02lu`, so the fraction is always two digits and `x * 1000` is
     * an exact integer; awk's `%.0f` never actually rounded. One leading zero is stripped from the
     * fraction so `$(( ))` cannot read `05` as octal (`00` becomes `0`, never empty). An
     * unreadable or non-numeric clock leaves the variable empty, which the caller's existing
     * `case "$variable" in ''|*[!0-9]*)` guard still rejects.
     */
    fun uptimeMillis(variable: String): String =
        readSingleLineFile(UPTIME_LINE, "/proc/uptime") +
            "; $UPTIME_SECONDS=\${$UPTIME_LINE%% *}" +
            "; $UPTIME_INTEGER=\${$UPTIME_SECONDS%.*}" +
            "; $UPTIME_FRACTION=\${$UPTIME_SECONDS#*.}" +
            "; $UPTIME_FRACTION=\${$UPTIME_FRACTION#0}" +
            "; case \"\$$UPTIME_INTEGER\$$UPTIME_FRACTION\" in ''|*[!0-9]*) $variable= ;; " +
            "*) $variable=\$(($UPTIME_INTEGER * 1000 + $UPTIME_FRACTION * 10)) ;; esac"
}
