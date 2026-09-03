package com.jack.micbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LedgerEntry(
    val requestId: String,
    val endpoint: String,
    val tokenGeneration: Int,
    val status: String,
    val outcome: MicAccessState?,
    val ok: Boolean?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAtEpochMs: Long,
)

sealed interface BeginRequestResult {
    data object New : BeginRequestResult
    data class Existing(val entry: LedgerEntry) : BeginRequestResult
    data class Conflict(val reason: String) : BeginRequestResult
}

interface IdempotencyStore {
    fun begin(
        requestId: String,
        endpoint: String,
        tokenGeneration: Int,
        nowEpochMs: Long,
    ): BeginRequestResult

    fun complete(
        requestId: String,
        outcome: MicAccessState,
        ok: Boolean,
        errorCode: String?,
        errorMessage: String?,
        nowEpochMs: Long,
    )

    fun recoverInProgress(): List<LedgerEntry>
}

class RequestLedger(
    context: Context,
    databaseName: String = DATABASE_NAME,
) :
    SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION),
    IdempotencyStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE requests (
                request_id TEXT PRIMARY KEY,
                endpoint TEXT NOT NULL,
                token_generation INTEGER NOT NULL,
                status TEXT NOT NULL,
                outcome TEXT,
                ok INTEGER,
                error_code TEXT,
                error_message TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX requests_created_at ON requests(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    override fun begin(
        requestId: String,
        endpoint: String,
        tokenGeneration: Int,
        nowEpochMs: Long,
    ): BeginRequestResult {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues().apply {
                put("request_id", requestId)
                put("endpoint", endpoint)
                put("token_generation", tokenGeneration)
                put("status", STATUS_IN_PROGRESS)
                put("created_at", nowEpochMs)
                put("updated_at", nowEpochMs)
            }
            val inserted = db.insertWithOnConflict(
                "requests",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            val result = if (inserted != -1L) {
                BeginRequestResult.New
            } else {
                val existing = find(db, requestId)
                    ?: return BeginRequestResult.Conflict("请求账本读取失败")
                when {
                    existing.endpoint != endpoint ->
                        BeginRequestResult.Conflict("request_id 已用于其他端点")
                    existing.tokenGeneration != tokenGeneration ->
                        BeginRequestResult.Conflict("request_id 属于旧令牌代次")
                    else -> BeginRequestResult.Existing(existing)
                }
            }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun complete(
        requestId: String,
        outcome: MicAccessState,
        ok: Boolean,
        errorCode: String?,
        errorMessage: String?,
        nowEpochMs: Long,
    ) {
        val values = ContentValues().apply {
            put("status", STATUS_COMPLETE)
            put("outcome", outcome.name)
            put("ok", if (ok) 1 else 0)
            put("error_code", errorCode)
            put("error_message", errorMessage?.take(MAX_ERROR_LENGTH))
            put("updated_at", nowEpochMs)
        }
        writableDatabase.update(
            "requests",
            values,
            "request_id = ?",
            arrayOf(requestId),
        )
    }

    @Synchronized
    override fun recoverInProgress(): List<LedgerEntry> {
        val result = mutableListOf<LedgerEntry>()
        readableDatabase.query(
            "requests",
            COLUMNS,
            "status = ?",
            arrayOf(STATUS_IN_PROGRESS),
            null,
            null,
            "created_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEntry()
        }
        return result
    }

    private fun find(db: SQLiteDatabase, requestId: String): LedgerEntry? =
        db.query(
            "requests",
            COLUMNS,
            "request_id = ?",
            arrayOf(requestId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toEntry() else null }

    private fun android.database.Cursor.toEntry(): LedgerEntry = LedgerEntry(
        requestId = getString(getColumnIndexOrThrow("request_id")),
        endpoint = getString(getColumnIndexOrThrow("endpoint")),
        tokenGeneration = getInt(getColumnIndexOrThrow("token_generation")),
        status = getString(getColumnIndexOrThrow("status")),
        outcome = getString(getColumnIndexOrThrow("outcome"))?.let {
            runCatching { MicAccessState.valueOf(it) }.getOrNull()
        },
        ok = if (isNull(getColumnIndexOrThrow("ok"))) null
        else getInt(getColumnIndexOrThrow("ok")) == 1,
        errorCode = getString(getColumnIndexOrThrow("error_code")),
        errorMessage = getString(getColumnIndexOrThrow("error_message")),
        createdAtEpochMs = getLong(getColumnIndexOrThrow("created_at")),
    )

    companion object {
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_COMPLETE = "COMPLETE"
        private const val DATABASE_NAME = "request-ledger.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_ERROR_LENGTH = 512
        private val COLUMNS = arrayOf(
            "request_id",
            "endpoint",
            "token_generation",
            "status",
            "outcome",
            "ok",
            "error_code",
            "error_message",
            "created_at",
        )
    }
}
