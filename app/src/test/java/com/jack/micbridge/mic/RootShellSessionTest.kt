package com.jack.micbridge.mic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class RootShellSessionTest {
    @Test
    fun `persistent shell is reused and frames stdout stderr and exit code`() = runTest {
        val shellBinary = File("/bin/sh")
        assumeTrue(shellBinary.isFile)
        val starts = AtomicInteger()
        val shell = RootShell(processFactory = {
            starts.incrementAndGet()
            ProcessBuilder(shellBinary.absolutePath).start()
        })

        val first = shell.execute("printf first; printf warning >&2")
        val second = shell.execute("printf second; exit 7")

        assertEquals(1, starts.get())
        assertEquals(0, first.exitCode)
        assertEquals("first", first.stdout)
        assertEquals("warning", first.stderr)
        assertFalse(first.timedOut)
        assertEquals(7, second.exitCode)
        assertEquals("second", second.stdout)
        assertTrue(second.stderr.isEmpty())
        assertFalse(second.timedOut)
    }

    @Test
    fun `command exit cannot terminate the persistent parent shell`() = runTest {
        val shellBinary = File("/bin/sh")
        assumeTrue(shellBinary.isFile)
        val shell = RootShell(processFactory = {
            ProcessBuilder(shellBinary.absolutePath).start()
        })

        assertEquals(9, shell.execute("exit 9").exitCode)
        assertEquals("alive", shell.execute("printf alive").stdout)
    }
}
