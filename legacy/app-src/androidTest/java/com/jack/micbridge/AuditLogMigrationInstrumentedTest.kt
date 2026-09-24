package com.jack.micbridge

import android.content.ContentValues
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jack.micbridge.data.AuditLogRepository
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.mic.RootCommandDiagnostic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuditLogMigrationInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun versionOneDatabaseMigratesWithoutLosingRowsAndAcceptsRootDiagnostics() {
        val databaseName = "audit-migration-${System.nanoTime()}.db"
        createVersionOneDatabase(databaseName)

        val repository = AuditLogRepository(context, databaseName)
        try {
            val migrated = repository.recent(10)
            assertEquals(1, migrated.size)
            assertEquals(1_234_567L, migrated.single().epochMs)
            assertEquals("http-toggle", migrated.single().source)
            assertEquals("abcdef123456", migrated.single().requestIdHash)
            assertEquals(MicAccessState.BLOCKED, migrated.single().result)
            assertTrue(migrated.single().verified)
            assertEquals("audio_manager", migrated.single().controller)
            assertEquals(17L, migrated.single().latencyMs)
            assertNull(migrated.single().errorCode)
            assertNull(migrated.single().diagnostic)

            val columns = repository.readableDatabase.rawQuery("PRAGMA table_info(audit)", null).use {
                buildSet {
                    val nameIndex = it.getColumnIndexOrThrow("name")
                    while (it.moveToNext()) add(it.getString(nameIndex))
                }
            }
            assertTrue(columns.contains("diagnostic"))
            assertEquals(2, repository.readableDatabase.version)

            repository.appendRoot(
                RootCommandDiagnostic(
                    category = "appops",
                    exitCode = 7,
                    timedOut = false,
                    durationMs = 23L,
                    sanitizedStderr = "permission denied",
                ),
            )
            val afterAppend = repository.recent(10)
            assertEquals(2, afterAppend.size)
            assertEquals("root-appops", afterAppend.first().source)
            assertEquals("ROOT_EXIT_7", afterAppend.first().errorCode)
            assertEquals(
                "exit=7; timeout=false; stderr=permission denied",
                afterAppend.first().diagnostic,
            )
            assertEquals("http-toggle", afterAppend.last().source)
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun createVersionOneDatabase(databaseName: String) {
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
                """
                CREATE TABLE audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    epoch_ms INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    request_hash TEXT,
                    result TEXT NOT NULL,
                    verified INTEGER NOT NULL,
                    controller TEXT NOT NULL,
                    latency_ms INTEGER NOT NULL,
                    error_code TEXT
                )
                """.trimIndent(),
            )
            database.insertOrThrow(
                "audit",
                null,
                ContentValues().apply {
                    put("epoch_ms", 1_234_567L)
                    put("source", "http-toggle")
                    put("request_hash", "abcdef123456")
                    put("result", MicAccessState.BLOCKED.name)
                    put("verified", 1)
                    put("controller", "audio_manager")
                    put("latency_ms", 17L)
                    putNull("error_code")
                },
            )
            database.version = 1
        } finally {
            database.close()
        }
    }
}
