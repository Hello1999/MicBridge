package com.jack.micbridge.mic

/**
 * Strictly read-only AppOps parser/reader. Release code deliberately contains no AppOps write
 * controller: AppOps is only an OPEN veto and a legacy-metadata inspection source.
 */
class AppOpsModeReader(
    private val shell: RootShell,
) {
    suspend fun currentMode(packageName: String, userId: Int): String? {
        val safePackage = runCatching { RootShell.requirePackageName(packageName) }.getOrNull()
            ?: return null
        if (userId < 0) return null
        val currentUser = shell.execute("am get-current-user")
        if (!currentUser.succeeded || currentUser.stdout.trim().toIntOrNull() != userId) return null
        val result = shell.execute(
            "cmd appops get --user $userId ${RootShell.quote(safePackage)} RECORD_AUDIO",
        )
        if (!result.succeeded) return null
        return parseModes(result.stdout)?.effectiveMode
    }

    companion object {
        const val MODE_ALLOW = "allow"
        const val MODE_FOREGROUND = "foreground"
        const val MODE_DEFAULT = "default"
        const val MODE_IGNORE = "ignore"
        const val MODE_DENY = "deny"
        const val MODE_ERRORED = "errored"

        private val KNOWN_MODES = setOf(
            MODE_ALLOW,
            MODE_FOREGROUND,
            MODE_DEFAULT,
            MODE_IGNORE,
            MODE_DENY,
            MODE_ERRORED,
        )
        private val UID_MODE_LABEL_PATTERN = Regex(
            "(?im)^\\s*Uid mode:\\s*RECORD_AUDIO(?:\\s*\\([^)]*\\))?\\s*:",
        )
        private val UID_MODE_VALUE_PATTERN = Regex(
            "(?im)^\\s*Uid mode:\\s*RECORD_AUDIO(?:\\s*\\([^)]*\\))?\\s*:\\s*" +
                "([^\\s;]+)",
        )
        private val PACKAGE_MODE_LABEL_PATTERN = Regex(
            "(?im)^\\s*RECORD_AUDIO(?:\\s*\\([^)]*\\))?\\s*:",
        )
        private val PACKAGE_MODE_VALUE_PATTERN = Regex(
            "(?im)^\\s*RECORD_AUDIO(?:\\s*\\([^)]*\\))?\\s*:\\s*([^\\s;]+)",
        )
        private val NO_OPERATIONS_PATTERN = Regex("(?i)^No operations\\.?$")

        data class ParsedModes(
            val packageMode: String?,
            val uidMode: String?,
        ) {
            val effectiveMode: String?
                get() = uidMode?.takeUnless { it == MODE_DEFAULT } ?: packageMode
        }

        fun parseModes(output: String): ParsedModes? {
            val normalized = output.trim()
            val uidLabels = UID_MODE_LABEL_PATTERN.findAll(normalized).count()
            val uidValues = UID_MODE_VALUE_PATTERN.findAll(normalized)
                .map { it.groupValues[1].lowercase() }
                .toList()
            val packageLabels = PACKAGE_MODE_LABEL_PATTERN.findAll(normalized).count()
            val packageValues = PACKAGE_MODE_VALUE_PATTERN.findAll(normalized)
                .map { it.groupValues[1].lowercase() }
                .toList()

            // Never ignore an unrecognized or ambiguous scope and then fall through to a more
            // permissive one. A future/OEM mode such as `ask` must veto OPEN as UNKNOWN.
            if (uidLabels != uidValues.size || packageLabels != packageValues.size) return null
            if ((uidValues + packageValues).any { it !in KNOWN_MODES }) return null
            if (uidValues.distinct().size > 1 || packageValues.distinct().size > 1) return null

            val uidMode = uidValues.firstOrNull()
            val packageMode = packageValues.firstOrNull() ?: when {
                packageLabels > 0 -> return null
                uidMode != null -> MODE_DEFAULT
                NO_OPERATIONS_PATTERN.matches(normalized) -> MODE_DEFAULT
                else -> null
            }
            if (uidMode == null && packageMode == null) return null
            return ParsedModes(packageMode, uidMode)
        }

        fun parseMode(output: String): String? = parseModes(output)?.effectiveMode

        fun modeToState(mode: String?): com.jack.micbridge.data.MicAccessState = when (mode) {
            MODE_IGNORE, MODE_DENY, MODE_ERRORED ->
                com.jack.micbridge.data.MicAccessState.BLOCKED
            MODE_ALLOW, MODE_DEFAULT -> com.jack.micbridge.data.MicAccessState.OPEN
            else -> com.jack.micbridge.data.MicAccessState.UNKNOWN
        }
    }
}
