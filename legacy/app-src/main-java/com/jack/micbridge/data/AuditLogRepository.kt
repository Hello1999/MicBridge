package com.jack.micbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jack.micbridge.mic.RootCommandDiagnostic
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    private val droppedWriteCount = AtomicLong(0L)

    /**
     * Audit rows are diagnostics, never a safety decision. Root commands run on the critical
     * control path, so an audit write must never block a mutation, a readback or a fail-closed
     * BLOCK. A single daemon thread keeps insertion order identical to call order; a bounded
     * queue keeps a stuck disk from growing memory without limit, and a full queue drops the
     * new entry instead of stalling the caller.
     */
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WRITE_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "MicBridge-audit").apply { isDaemon = true } },
        { _, _ -> droppedWriteCount.incrementAndGet() },
    )

    /** Number of audit entries discarded because the write queue was saturated. */
    val droppedWrites: Long
        get() = droppedWriteCount.get()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL plus synchronous=NORMAL removes the per-write fsync from the caller's critical
        // path. Losing the most recent diagnostic rows after a power loss is acceptable; the
        // microphone state itself is never derived from this table.
        runCatching { db.enableWriteAheadLogging() }
        runCatching { db.execSQL("PRAGMA synchronous=NORMAL") }
    }

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

    fun append(source: String, result: OperationResult) {
        // Built on the calling thread so the timestamp is the real event time, not the time the
        // background writer happened to reach this entry.
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
            putNull("diagnostic")
        }
        enqueueWrite(values)
    }

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
        enqueueWrite(values)
    }

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
        enqueueWrite(values)
    }

    fun recent(limit: Int = 20): List<AuditEntry> {
        // Read-your-writes: the UI and the instrumented tests expect an entry appended just
        // before this call to be visible.
        //
        // This must not run while holding this helper's monitor: the writer thread needs that
        // same monitor for getWritableDatabase(), so waiting on it from inside a @Synchronized
        // method would deadlock until the drain timeout.
        drainPendingWrites(DRAIN_TIMEOUT_MS)
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

    override fun close() {
        // Drained before super.close() and outside this helper's monitor, for the same reason
        // recent() is not synchronized.
        drainPendingWrites(DRAIN_TIMEOUT_MS)
        writer.shutdown()
        runCatching { writer.awaitTermination(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        super.close()
    }

    private fun enqueueWrite(values: ContentValues) {
        // The rejection handler absorbs and counts both a saturated queue and a writer already
        // shut down by close(); runCatching only guarantees that no audit write can ever throw
        // into a caller that is mid-mutation.
        runCatching { writer.execute { writeEntry(values) } }
            .onFailure { if (it is RejectedExecutionException) droppedWriteCount.incrementAndGet() }
    }

    private fun writeEntry(values: ContentValues) {
        runCatching {
            val database = writableDatabase
            database.beginTransaction()
            try {
                database.insert("audit", null, values)
                database.execSQL(TRIM_SQL)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    /** Blocks until every already-queued write has been applied, or the timeout elapses. */
    private fun drainPendingWrites(timeoutMs: Long) {
        runCatching { writer.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val DATABASE_NAME = "audit-log.db"
        private const val DATABASE_VERSION = 2
        private const val MAX_ROWS = 100
        private const val WRITE_QUEUE_CAPACITY = 1_024
        private const val DRAIN_TIMEOUT_MS = 2_000L

        // `id` is AUTOINCREMENT, so id order is insert order: the row at OFFSET MAX_ROWS of the
        // descending id list is the newest row that must go, and everything with a smaller or
        // equal id is older still. With MAX_ROWS or fewer rows the subquery yields NULL, the
        // predicate is NULL for every row and nothing is deleted.
        private const val TRIM_SQL =
            "DELETE FROM audit WHERE id <= " +
                "(SELECT id FROM audit ORDER BY id DESC LIMIT 1 OFFSET $MAX_ROWS)"
    }
}
