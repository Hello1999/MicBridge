package com.jack.micbridge.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRouteCommandTest {
    private val apk = "/data/app/~~AbC==/com.jack.micbridge-1/base.apk"

    // Command construction ---------------------------------------------------------------------

    @Test
    fun `set command is quoted exactly`() {
        assertEquals(
            "CLASSPATH='/data/app/~~AbC==/com.jack.micbridge-1/base.apk' " +
                "exec app_process /system/bin " +
                "'com.jack.micbridge.routing.CaptureRouteMain' 'set' '1,6,7' 'builtin_mic'",
            CaptureRouteCommand.setBuiltInMicCommand(apk),
        )
    }

    @Test
    fun `get and clear commands use the same layout`() {
        assertEquals(
            "CLASSPATH='$apk' exec app_process /system/bin " +
                "'com.jack.micbridge.routing.CaptureRouteMain' 'get' '1,6,7'",
            CaptureRouteCommand.getCommand(apk),
        )
        assertEquals(
            "CLASSPATH='$apk' exec app_process /system/bin " +
                "'com.jack.micbridge.routing.CaptureRouteMain' 'clear' '1'",
            CaptureRouteCommand.clearCommand(apk, listOf(CapturePreset.MIC)),
        )
    }

    @Test
    fun `arguments are single quoted so shell metacharacters cannot escape`() {
        val command = CaptureRouteCommand.command(apk, listOf("get", "1;reboot", "a'b"))
        assertTrue(command.contains("'1;reboot'"))
        assertTrue(command.contains("'a'\\''b'"))
        assertFalse(command.contains(" 1;reboot"))
    }

    @Test
    fun `command without arguments has no trailing space`() {
        val command = CaptureRouteCommand.command(apk, emptyList())
        assertTrue(command.endsWith("'com.jack.micbridge.routing.CaptureRouteMain'"))
    }

    // APK path validation ----------------------------------------------------------------------

    @Test
    fun `valid apk paths are accepted`() {
        listOf(
            "/data/app/~~AbC==/com.jack.micbridge-1/base.apk",
            "/data/app/com.jack.micbridge-2/base.apk",
            "/product/priv-app/Foo/Foo.apk",
        ).forEach { path -> assertEquals(path, CaptureRouteCommand.requireApkPath(path)) }
    }

    @Test
    fun `paths with spaces or quotes are rejected before quoting`() {
        listOf(
            "/data/app/my app/base.apk",
            "/data/app/it's/base.apk",
            "/data/app/\$(reboot)/base.apk",
            "/data/app/a;reboot/base.apk",
            "/data/app/a\nb/base.apk",
        ).forEach(::assertRejected)
    }

    @Test
    fun `relative, non-apk and dot-dot paths are rejected`() {
        listOf(
            "data/app/com.jack.micbridge-1/base.apk",
            "/data/app/com.jack.micbridge-1/base.jar",
            "/data/app/com.jack.micbridge-1/base.apk.bak",
            "/data/app/../../system/base.apk",
            "/..",
            "/",
            "",
        ).forEach(::assertRejected)
    }

    @Test
    fun `dot-dot is only rejected as a whole segment`() {
        assertEquals(
            "/data/app/a..b/base.apk",
            CaptureRouteCommand.requireApkPath("/data/app/a..b/base.apk"),
        )
    }

    @Test
    fun `empty preset list is rejected`() {
        assertRejected { CaptureRouteCommand.presetArgument(emptyList()) }
    }

    // Parsing ----------------------------------------------------------------------------------

    @Test
    fun `get output parses into readbacks without status`() {
        val parsed = CaptureRouteCommand.parse(
            """
                preset=1 devices=15
                preset=6 devices=
                preset=7 devices=15,7
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                PresetReadback(1, null, listOf(15)),
                PresetReadback(6, null, emptyList()),
                PresetReadback(7, null, listOf(15, 7)),
            ),
            parsed,
        )
    }

    @Test
    fun `set output parses status and tolerates blank lines and trailing whitespace`() {
        val parsed = CaptureRouteCommand.parse(
            "\npreset=1 status=0 devices=15   \n\npreset=6 status=-1 devices=\n\n",
        )

        assertEquals(
            listOf(
                PresetReadback(1, 0, listOf(15)),
                PresetReadback(6, -1, emptyList()),
            ),
            parsed,
        )
    }

    @Test
    fun `empty output parses to an empty list`() {
        assertEquals(emptyList<PresetReadback>(), CaptureRouteCommand.parse(""))
        assertEquals(emptyList<PresetReadback>(), CaptureRouteCommand.parse("\n \n"))
    }

    @Test
    fun `malformed output parses to null instead of a partial list`() {
        listOf(
            "preset=1 devices",
            "preset=x devices=15",
            "preset=1 status= devices=15",
            "preset=1 devices=15,",
            "preset=1 devices=abc",
            "preset=1 status=0",
            "device=1 devices=15",
            "preset=1 devices=15 extra=1",
            "preset=1 devices=15\nSegmentation fault",
        ).forEach { output ->
            assertNull("expected null for: $output", CaptureRouteCommand.parse(output))
        }
    }

    // Truth tables -----------------------------------------------------------------------------

    @Test
    fun `allBuiltInMic requires status zero and exactly one built-in mic per preset`() {
        assertTrue(CaptureRouteCommand.allBuiltInMic(builtInMicReadbacks()))

        assertFalse(
            "non-zero status",
            CaptureRouteCommand.allBuiltInMic(
                builtInMicReadbacks().replaceFirst(PresetReadback(1, -1, listOf(15))),
            ),
        )
        assertFalse(
            "extra routed device",
            CaptureRouteCommand.allBuiltInMic(
                builtInMicReadbacks().replaceFirst(PresetReadback(1, 0, listOf(15, 7))),
            ),
        )
        assertFalse(
            "wrong device type",
            CaptureRouteCommand.allBuiltInMic(
                builtInMicReadbacks().replaceFirst(PresetReadback(1, 0, listOf(7))),
            ),
        )
        assertFalse(
            "empty readback",
            CaptureRouteCommand.allBuiltInMic(
                builtInMicReadbacks().replaceFirst(PresetReadback(1, 0, emptyList())),
            ),
        )
        assertFalse(
            "missing preset",
            CaptureRouteCommand.allBuiltInMic(builtInMicReadbacks().drop(1)),
        )
        assertFalse(
            "unrequested preset reported",
            CaptureRouteCommand.allBuiltInMic(
                builtInMicReadbacks() + PresetReadback(9, 0, listOf(15)),
            ),
        )
        assertFalse(
            "duplicate preset",
            CaptureRouteCommand.allBuiltInMic(
                builtInMicReadbacks().dropLast(1) + PresetReadback(1, 0, listOf(15)),
            ),
        )
        assertFalse(
            "get output has no status",
            CaptureRouteCommand.allBuiltInMic(
                builtInMicReadbacks().map { it.copy(status = null) },
            ),
        )
        assertFalse("no presets requested", CaptureRouteCommand.allBuiltInMic(emptyList(), emptyList()))
    }

    @Test
    fun `allBuiltInMic honours a narrower preset selection`() {
        val single = listOf(PresetReadback(1, 0, listOf(15)))
        assertTrue(CaptureRouteCommand.allBuiltInMic(single, listOf(CapturePreset.MIC)))
        assertFalse(CaptureRouteCommand.allBuiltInMic(single))
    }

    @Test
    fun `allCleared requires status zero and an empty readback per preset`() {
        assertTrue(CaptureRouteCommand.allCleared(clearedReadbacks()))
        assertFalse(
            CaptureRouteCommand.allCleared(
                clearedReadbacks().replaceFirst(PresetReadback(1, 0, listOf(15))),
            ),
        )
        assertFalse(
            CaptureRouteCommand.allCleared(
                clearedReadbacks().replaceFirst(PresetReadback(1, 1, emptyList())),
            ),
        )
        assertFalse(CaptureRouteCommand.allCleared(clearedReadbacks().drop(1)))
    }

    @Test
    fun `allReported only accepts status-free lines for every requested preset`() {
        val reported = CapturePreset.DEFAULT_SET.map { PresetReadback(it.id, null, listOf(7)) }
        assertTrue(CaptureRouteCommand.allReported(reported))
        assertFalse(CaptureRouteCommand.allReported(reported.replaceFirst(PresetReadback(1, 0, listOf(7)))))
        assertFalse(CaptureRouteCommand.allReported(reported.drop(1)))
    }

    @Test
    fun `default preset set matches the documented audio source ids`() {
        assertEquals(listOf(1, 6, 7), CapturePreset.DEFAULT_SET.map { it.id })
        assertEquals(CapturePreset.MIC, CapturePreset.fromId(1))
        assertNull(CapturePreset.fromId(2))
        assertEquals(15, CaptureRouteCommand.TYPE_BUILTIN_MIC)
    }

    private fun builtInMicReadbacks(): List<PresetReadback> =
        CapturePreset.DEFAULT_SET.map { PresetReadback(it.id, 0, listOf(15)) }

    private fun clearedReadbacks(): List<PresetReadback> =
        CapturePreset.DEFAULT_SET.map { PresetReadback(it.id, 0, emptyList()) }

    private fun List<PresetReadback>.replaceFirst(replacement: PresetReadback): List<PresetReadback> =
        listOf(replacement) + drop(1)

    private fun assertRejected(path: String) {
        assertRejected { CaptureRouteCommand.requireApkPath(path) }
        assertRejected { CaptureRouteCommand.getCommand(path) }
    }

    private fun assertRejected(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue(
            "expected IllegalArgumentException but got $thrown",
            thrown is IllegalArgumentException,
        )
    }
}
