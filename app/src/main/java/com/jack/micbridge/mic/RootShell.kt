package com.jack.micbridge.mic

import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One long-lived `su` process so each toggle costs a pipe write instead of a fork + Magisk
 * round trip. Calls are serialized; a timed-out command kills the shell and the next call
 * starts a fresh one.
 */
class RootShell {
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "root-shell").apply { isDaemon = true } }
    private var process: java.lang.Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null
    private var counter = 0

    /** Returns the command's exit code, or null if root is unavailable or it timed out. */
    @Synchronized
    fun run(command: String, timeoutMs: Long = 3_000): Int? {
        val firstStart = process == null
        if (!ensureStarted()) return null
        val marker = "__MICBRIDGE_${++counter}__"
        return try {
            stdin!!.apply {
                write("$command </dev/null >/dev/null 2>&1; echo \"$marker \$?\"\n")
                flush()
            }
            val out = stdout!!
            // The first call may sit behind the Magisk grant dialog.
            val budget = if (firstStart) 30_000 else timeoutMs
            reader.submit<Int?> {
                var exitCode: Int? = null
                while (true) {
                    val line = out.readLine() ?: break
                    if (line.startsWith(marker)) {
                        exitCode = line.substringAfter(' ').trim().toIntOrNull()
                        break
                    }
                }
                exitCode
            }.get(budget, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            close()
            null
        }
    }

    private fun ensureStarted(): Boolean {
        if (process?.isAlive == true) return true
        close()
        return runCatching {
            val p = ProcessBuilder(suBinary()).redirectErrorStream(true).start()
            process = p
            stdin = p.outputStream.bufferedWriter()
            stdout = p.inputStream.bufferedReader()
            true
        }.getOrDefault(false)
    }

    /**
     * Recent Magisk builds may not mount `su` onto an app's PATH; it then only lives in
     * /debug_ramdisk. Fall back to the known locations of Magisk, KernelSU and APatch.
     */
    private fun suBinary(): String = SU_CANDIDATES.firstOrNull { java.io.File(it).canExecute() } ?: "su"

    @Synchronized
    fun close() {
        runCatching { stdin?.write("exit\n"); stdin?.flush() }
        process?.destroy()
        process = null
        stdin = null
        stdout = null
    }

    private companion object {
        val SU_CANDIDATES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
        )
    }
}
