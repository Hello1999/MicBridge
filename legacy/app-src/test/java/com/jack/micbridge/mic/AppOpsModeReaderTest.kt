package com.jack.micbridge.mic

import com.jack.micbridge.data.MicAccessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppOpsModeReaderTest {
    @Test
    fun `parses common appops modes`() {
        assertEquals("ignore", AppOpsModeReader.parseMode("RECORD_AUDIO: ignore"))
        assertEquals(
            "foreground",
            AppOpsModeReader.parseMode("RECORD_AUDIO: foreground; time=+1m"),
        )
        assertEquals("allow", AppOpsModeReader.parseMode("Uid mode: RECORD_AUDIO: allow"))
        assertEquals("default", AppOpsModeReader.parseMode("No operations."))
        assertNull(AppOpsModeReader.parseMode("camera: allow"))
    }

    @Test
    fun `uid mode overrides package mode unless uid mode is default`() {
        val overridden = AppOpsModeReader.parseModes(
            """
                Uid mode: RECORD_AUDIO: ignore
                RECORD_AUDIO: allow
            """.trimIndent(),
        )
        assertEquals("ignore", overridden?.uidMode)
        assertEquals("allow", overridden?.packageMode)
        assertEquals("ignore", overridden?.effectiveMode)

        val uidDefault = AppOpsModeReader.parseModes(
            """
                Uid mode: RECORD_AUDIO: default
                RECORD_AUDIO: foreground; time=+1m
            """.trimIndent(),
        )
        assertEquals("default", uidDefault?.uidMode)
        assertEquals("foreground", uidDefault?.packageMode)
        assertEquals("foreground", uidDefault?.effectiveMode)
    }

    @Test
    fun `maps only stable explicit modes to state`() {
        assertEquals(MicAccessState.BLOCKED, AppOpsModeReader.modeToState("ignore"))
        assertEquals(MicAccessState.BLOCKED, AppOpsModeReader.modeToState("deny"))
        assertEquals(MicAccessState.UNKNOWN, AppOpsModeReader.modeToState("foreground"))
        assertEquals(MicAccessState.UNKNOWN, AppOpsModeReader.modeToState(null))
    }

    @Test
    fun `unknown uid mode cannot fall through to package allow`() {
        assertNull(
            AppOpsModeReader.parseModes(
                """
                    Uid mode: RECORD_AUDIO: ask
                    RECORD_AUDIO: allow
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `unknown package mode cannot be folded into default`() {
        assertNull(
            AppOpsModeReader.parseModes(
                """
                    Uid mode: RECORD_AUDIO: default
                    RECORD_AUDIO: ask
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `shell quoting cannot break single quoted argument`() {
        assertEquals("'a'\\''b'", RootShell.quote("a'b"))
    }
}
