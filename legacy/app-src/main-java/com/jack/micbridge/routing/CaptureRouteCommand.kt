package com.jack.micbridge.routing

import com.jack.micbridge.mic.RootShell

/**
 * The capture presets a voice assistant plausibly opens. The integers are
 * `android.media.MediaRecorder.AudioSource` values and are repeated here as plain constants so this
 * file stays free of Android class references and remains unit-testable on the JVM.
 */
enum class CapturePreset(val id: Int) {
    /** `MediaRecorder.AudioSource.MIC`. */
    MIC(1),

    /** `MediaRecorder.AudioSource.VOICE_RECOGNITION`. */
    VOICE_RECOGNITION(6),

    /** `MediaRecorder.AudioSource.VOICE_COMMUNICATION`. */
    VOICE_COMMUNICATION(7),
    ;

    companion object {
        /** The presets stage 1 always operates on together. */
        val DEFAULT_SET: List<CapturePreset> = listOf(MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION)

        fun fromId(id: Int): CapturePreset? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One `preset=…` line of [CaptureRouteMain] output.
 *
 * [status] is `null` for the read-only `get` command, which prints no `status=` field, and is the
 * raw `IAudioService` return value (0 == `AudioSystem.SUCCESS`) for `set` and `clear`.
 * [deviceTypes] holds `AudioDeviceInfo` type integers read back after the mutation.
 */
data class PresetReadback(
    val preset: Int,
    val status: Int?,
    val deviceTypes: List<Int>,
)

/**
 * Builds and parses the `app_process` command line implemented by [CaptureRouteMain].
 *
 * Pure string handling: no Android types, no coroutines, no I/O. Nothing here participates in the
 * fail-closed microphone BLOCK path — capture-preset routing only decides *which* input device an
 * already-permitted recorder gets, never whether recording is permitted at all.
 */
object CaptureRouteCommand {
    /** Entry-point class name handed to `app_process`. */
    const val MAIN_CLASS: String = "com.jack.micbridge.routing.CaptureRouteMain"

    /** `AudioDeviceInfo.TYPE_BUILTIN_MIC`, repeated as a plain constant. */
    const val TYPE_BUILTIN_MIC: Int = 15

    /** Exit code for a malformed command line. */
    const val EXIT_USAGE: Int = 2

    /** Exit code when the readback did not match what was requested. */
    const val EXIT_UNVERIFIED: Int = 3

    /** Exit code for a reflection or binder failure. */
    const val EXIT_FAILURE: Int = 4

    private val APK_PATH_PATTERN = Regex("^/[A-Za-z0-9._~=+/-]+\\.apk$")

    private val LINE_PATTERN =
        Regex("^preset=(-?\\d+)(?: status=(-?\\d+))? devices=((?:\\d+(?:,\\d+)*)?)$")

    /**
     * Validates an absolute APK path before it is spliced into a root command line. The pattern
     * excludes whitespace, quotes and shell metacharacters outright, and `..` segments are rejected
     * separately because the pattern has to allow `.` for the extension.
     *
     * @throws IllegalArgumentException when the path is not a plain absolute `*.apk` path.
     */
    fun requireApkPath(apkPath: String): String {
        require(APK_PATH_PATTERN.matches(apkPath)) { "Invalid apk path" }
        require(apkPath.split('/').none { it == ".." }) { "Invalid apk path" }
        return apkPath
    }

    /**
     * `CLASSPATH=<apk> exec app_process /system/bin <main class> <args…>`, every interpolated value
     * single-quoted through [RootShell.quote].
     */
    fun command(apkPath: String, args: List<String>): String {
        val validated = requireApkPath(apkPath)
        val quotedArgs = args.joinToString(" ") { RootShell.quote(it) }
        val prefix = "CLASSPATH=${RootShell.quote(validated)} exec app_process /system/bin " +
            RootShell.quote(MAIN_CLASS)
        return if (quotedArgs.isEmpty()) prefix else "$prefix $quotedArgs"
    }

    fun getCommand(
        apkPath: String,
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): String = command(apkPath, listOf("get", presetArgument(presets)))

    fun setBuiltInMicCommand(
        apkPath: String,
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): String = command(apkPath, listOf("set", presetArgument(presets), "builtin_mic"))

    fun clearCommand(
        apkPath: String,
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): String = command(apkPath, listOf("clear", presetArgument(presets)))

    fun presetArgument(presets: List<CapturePreset>): String {
        require(presets.isNotEmpty()) { "At least one capture preset is required" }
        return presets.joinToString(",") { it.id.toString() }
    }

    /**
     * Parses the whole stdout of [CaptureRouteMain].
     *
     * Returns `null` — never a partial list — when any non-blank line does not match the exact
     * `preset=<n>[ status=<n>] devices=[<n>[,<n>…]]` format. A `null` result is the caller's signal
     * that the helper produced something it does not understand, which must never be treated as a
     * successful readback. Trailing whitespace and blank lines are tolerated.
     */
    fun parse(stdout: String): List<PresetReadback>? {
        val readbacks = mutableListOf<PresetReadback>()
        stdout.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val match = LINE_PATTERN.matchEntire(line) ?: return null
            val preset = match.groupValues[1].toIntOrNull() ?: return null
            val status = match.groupValues[2].takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull() ?: return null
            }
            val devicesField = match.groupValues[3]
            val deviceTypes = if (devicesField.isEmpty()) {
                emptyList()
            } else {
                devicesField.split(',').map { it.toIntOrNull() ?: return null }
            }
            readbacks += PresetReadback(preset, status, deviceTypes)
        }
        return readbacks
    }

    /**
     * True only when every requested preset is reported exactly once, with `status == 0` and a
     * readback of exactly one device of type [TYPE_BUILTIN_MIC], and no unexpected preset is
     * reported alongside them.
     */
    fun allBuiltInMic(
        readbacks: List<PresetReadback>,
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): Boolean = matches(readbacks, presets) { readback ->
        readback.status == 0 && readback.deviceTypes == listOf(TYPE_BUILTIN_MIC)
    }

    /**
     * True only when every requested preset is reported exactly once, with `status == 0` and an
     * empty readback.
     */
    fun allCleared(
        readbacks: List<PresetReadback>,
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): Boolean = matches(readbacks, presets) { readback ->
        readback.status == 0 && readback.deviceTypes.isEmpty()
    }

    /**
     * True only when the read-only `get` output reports every requested preset exactly once. The
     * routed device is deliberately not constrained: reading is allowed to observe any state.
     */
    fun allReported(
        readbacks: List<PresetReadback>,
        presets: List<CapturePreset> = CapturePreset.DEFAULT_SET,
    ): Boolean = matches(readbacks, presets) { readback -> readback.status == null }

    private fun matches(
        readbacks: List<PresetReadback>,
        presets: List<CapturePreset>,
        predicate: (PresetReadback) -> Boolean,
    ): Boolean {
        if (presets.isEmpty()) return false
        val expected = presets.map { it.id }.distinct()
        if (readbacks.size != expected.size) return false
        val byPreset = readbacks.associateBy { it.preset }
        if (byPreset.size != readbacks.size) return false
        return expected.all { id ->
            val readback = byPreset[id]
            readback != null && predicate(readback)
        }
    }
}
