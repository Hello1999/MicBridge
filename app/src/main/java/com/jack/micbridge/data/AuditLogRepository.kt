package com.jack.micbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jack.micbridge.mic.RootCommandDiagnostic
import java.security.MessageDigest

data class AuditEntry(
    val epochMs: Long,
    val source: String,
    val requestIdHash: String?,
    val result: MicAccessState,
    val verified: Boolean,
    val controller: String,
    val latencyMs: Long,
    val errorCode: String?,
    val diagnostic: String?,
)

class AuditLogRepository(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
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
                error_code TEXT,
                diagnostic TEXT
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE audit ADD COLUMN diagnostic TEXT")
    }

    @Synchronized
    fun append(source: String, result: OperationResult, diagnostic: String? = null) {
        val values = ContentValues().apply {
            put("epoch_ms", System.currentTimeMillis())
            put("source", source.take(64))
            put("request_hash", result.requestId?.let(::shortHash))
            put("result", result.micAccess.name)
            // The audit badge is an operation-success signal. A forced fail-closed recovery can
            // end in a freshly read BLOCKED state while the requested operation still failed;
            // recording that incident as verified would be misleading in the UI.
            put("verified", if (result.ok) 1 else 0)
            put("controller", result.controllerId.take(64))
            put("latency_ms", result.latencyMs)
            put("error_code", result.errorCode?.take(64))
            if (diagnostic == null) putNull("diagnostic") else put("diagnostic", diagnostic.take(240))
        }
        writableDatabase.insert("audit", null, values)
        writableDatabase.execSQL(
            "DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY id DESC LIMIT $MAX_ROWS)",
        )
    }

    @Synchronized
    fun appendStatus(snapshot: BridgeSnapshot) {
        val verified = !snapshot.transitioning &&
            snapshot.controlReadback &&
            snapshot.acousticCalibrationValid &&
            snapshot.micAccess != MicAccessState.UNKNOWN &&
            snapshot.lastError == null
        val values = ContentValues().apply {
            put("epoch_ms", System.currentTimeMillis())
            put("source", "http-status")
            putNull("request_hash")
            put("result", snapshot.micAccess.name)
            put("verified", if (verified) 1 else 0)
            put("controller", snapshot.controllerId.take(64))
            put("latency_ms", snapshot.lastLatencyMs ?: 0L)
            put("error_code", if (verified) null else "STATE_UNVERIFIED")
            putNull("diagnostic")
        }
        writableDatabase.insert("audit", null, values)
        writableDatabase.execSQL(
            "DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY id DESC LIMIT $MAX_ROWS)",
        )
    }

    @Synchronized
    fun appendRoot(diagnostic: RootCommandDiagnostic) {
        val values = ContentValues().apply {
            put("epoch_ms", System.currentTimeMillis())
            put("source", "root-${diagnostic.category}".take(64))
            putNull("request_hash")
            put("result", MicAccessState.UNKNOWN.name)
            // Exit status is useful diagnostics, but never proves the microphone state.
            put("verified", 0)
            put("controller", "root-shell")
            put("latency_ms", diagnostic.durationMs)
            put(
                "error_code",
                when {
                    diagnostic.timedOut -> "ROOT_TIMEOUT"
                    diagnostic.exitCode != 0 -> "ROOT_EXIT_${diagnostic.exitCode}"
                    else -> null
                },
            )
            put(
                "diagnostic",
                "exit=${diagnostic.exitCode}; timeout=${diagnostic.timedOut}; " +
                    "stderr=${diagnostic.sanitizedStderr.ifBlank { "-" }}",
            )
        }
        writableDatabase.insert("audit", null, values)
        writableDatabase.execSQL(
            "DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY id DESC LIMIT $MAX_ROWS)",
        )
    }

    @Synchronized
    fun recent(limit: Int = 20): List<AuditEntry> {
        val safeLimit = limit.coerceIn(1, MAX_ROWS)
        val result = mutableListOf<AuditEntry>()
        readableDatabase.query(
            "audit",
            null,
            null,
            null,
            null,
            null,
            "id DESC",
            safeLimit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AuditEntry(
                    epochMs = cursor.getLong(cursor.getColumnIndexOrThrow("epoch_ms")),
                    source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
                    requestIdHash = cursor.getString(cursor.getColumnIndexOrThrow("request_hash")),
                    result = runCatching {
                        MicAccessState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("result")))
                    }.getOrDefault(MicAccessState.UNKNOWN),
                    verified = cursor.getInt(cursor.getColumnIndexOrThrow("verified")) == 1,
                    controller = cursor.getString(cursor.getColumnIndexOrThrow("controller")),
                    latencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("latency_ms")),
                    errorCode = cursor.getString(cursor.getColumnIndexOrThrow("error_code")),
                    diagnostic = cursor.getString(cursor.getColumnIndexOrThrow("diagnostic")),
                )
            }
        }
        return result
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val DATABASE_NAME = "audit-log.db"
        private const val DATABASE_VERSION = 2
        private const val MAX_ROWS = 100
    }
}
