package com.kakei.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.kakei.app.core.security.DatabaseKeyManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * One queued offline mutation (Outbox pattern). Mutations are drained FIFO by
 * `KakeiRepository.synchronizeWithServer()`; per-item HTTP 207 statuses decide
 * whether an entry is applied, rejected or retried.
 */
@Serializable
data class SyncMutationEntity(
    val id: String,
    /** ACCOUNT, RECORD, CATEGORY, LABEL, RULE, BUDGET, OBLIGATION, LENDING, SAVINGS, GROUP_MEMBER, PROFILE */
    val entityType: String,
    /** Local (client-generated) id of the entity the mutation targets. */
    val entityId: String,
    /** CREATE, UPDATE, DELETE, REVIEW, EXECUTE, REPAYMENT, FORGIVE, SETTLE_ALL, CONTRIBUTION */
    val operation: String,
    val payloadJson: String,
    val expectedSyncRevision: Long? = null,
    val createdAt: String,
    val attempts: Int = 0,
    val lastError: String? = null
)

/** A mutation the server rejected; kept so the user can see what did not sync. */
@Serializable
data class SyncErrorEntity(
    val id: String,
    val entityType: String,
    val operation: String,
    val entityId: String,
    val status: Int,
    val error: String,
    val createdAt: String
)

/**
 * Encrypted local SQLite database powered by SQLCipher (AES-256) and AndroidKeyStore.
 * Stores full offline replicas of all Kakei domain entities and the Outbox mutation queue.
 */
class KakeiDatabase(private val context: Context) {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var isSqlCipherActive: Boolean = false
        private set

    private val openHelper: SupportSQLiteOpenHelper

    init {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createSchema(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // Schema v1
            }
        }

        val factory: SupportSQLiteOpenHelper.Factory = try {
            System.loadLibrary("sqlcipher")
            // If the wrapped key cannot be unwrapped (restored onto another device or the
            // Keystore key was lost) the encrypted file is unreadable: discard it so the
            // app starts with an empty replica instead of crashing on every launch.
            val passphrase = DatabaseKeyManager.getOrCreateDatabasePassphrase(context) {
                deleteDatabaseFiles(context)
            }
            isSqlCipherActive = true
            SupportOpenHelperFactory(passphrase)
        } catch (_: UnsatisfiedLinkError) {
            // SQLCipher native library missing (JVM / instrumentation environments only).
            isSqlCipherActive = false
            FrameworkSQLiteOpenHelperFactory()
        }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(callback)
            .build()

        openHelper = factory.create(config)
    }

    private val db: SupportSQLiteDatabase
        get() = openHelper.writableDatabase

    private fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS kv_store (
                collection TEXT NOT NULL,
                id TEXT NOT NULL,
                sort_key TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                PRIMARY KEY (collection, id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_kv_collection_sort ON kv_store(collection, sort_key DESC)")
    }

    fun putItem(collection: String, id: String, sortKey: String, payloadJson: String) {
        val cv = ContentValues().apply {
            put("collection", collection)
            put("id", id)
            put("sort_key", sortKey)
            put("payload_json", payloadJson)
        }
        db.insert("kv_store", SQLiteDatabase.CONFLICT_REPLACE, cv)
    }

    fun deleteItem(collection: String, id: String) {
        db.delete("kv_store", "collection = ? AND id = ?", arrayOf(collection, id))
    }

    fun clearCollection(collection: String) {
        db.delete("kv_store", "collection = ?", arrayOf(collection))
    }

    fun clearAll() {
        db.delete("kv_store", null, null)
    }

    fun listIds(collection: String): Set<String> {
        val cursor = openHelper.readableDatabase.query(
            "SELECT id FROM kv_store WHERE collection = ?",
            arrayOf(collection)
        )
        val result = mutableSetOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) result.add(c.getString(0))
        }
        return result
    }

    /**
     * Replaces the server view of a collection while preserving local-only entities
     * that still have pending Outbox mutations (otherwise an offline create would be
     * wiped by the next pull). Everything else that is absent from the server is removed.
     */
    fun mergeCollection(
        collection: String,
        serverItems: List<Triple<String, String, String>>,
        protectedIds: Set<String>
    ) {
        val serverIds = serverItems.map { it.first }.toSet()
        runInTransaction {
            for ((id, sortKey, payload) in serverItems) {
                putItem(collection, id, sortKey, payload)
            }
            for (localId in listIds(collection)) {
                if (localId !in serverIds && localId !in protectedIds) {
                    deleteItem(collection, localId)
                }
            }
        }
    }

    /**
     * Rewrites every reference to a client-generated id after the server assigned the
     * canonical one: row ids, and the id wherever it appears inside stored JSON
     * payloads (foreign keys and queued mutation bodies). UUIDs are globally unique,
     * so a textual replacement is exact.
     */
    fun replaceIdReferences(oldId: String, newId: String) {
        if (oldId == newId || oldId.isBlank()) return
        runInTransaction {
            db.execSQL(
                "UPDATE kv_store SET payload_json = REPLACE(payload_json, ?, ?)",
                arrayOf(oldId, newId)
            )
            db.execSQL(
                "UPDATE OR REPLACE kv_store SET id = ? WHERE id = ?",
                arrayOf(newId, oldId)
            )
        }
    }

    fun <T> runInTransaction(block: () -> T): T {
        val database = db
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    inline fun <reified T> listItems(collection: String, descending: Boolean = true): List<T> {
        val order = if (descending) "DESC" else "ASC"
        val cursor = readableOpenHelper.readableDatabase.query(
            "SELECT payload_json FROM kv_store WHERE collection = ? ORDER BY sort_key $order",
            arrayOf(collection)
        )
        val result = mutableListOf<T>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(0)
                runCatching { json.decodeFromString<T>(raw) }.getOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    @PublishedApi
    internal val readableOpenHelper: SupportSQLiteOpenHelper
        get() = openHelper

    companion object {
        const val DB_NAME = "kakei_encrypted_ledger.db"
        const val COL_ACCOUNTS = "accounts"
        const val COL_CATEGORIES = "categories"
        const val COL_LABELS = "labels"
        const val COL_RULES = "rules"
        const val COL_TRANSACTIONS = "transactions"
        const val COL_BUDGETS = "budgets"
        const val COL_OBLIGATIONS = "obligations"
        const val COL_LENDING = "lending"
        const val COL_SAVINGS = "savings"
        const val COL_GROUP_MEMBERS = "group_members"
        const val COL_MUTATIONS = "sync_mutations"
        const val COL_SYNC_ERRORS = "sync_errors"

        /** Removes the encrypted database and its journal files. */
        fun deleteDatabaseFiles(context: Context) {
            val base = context.getDatabasePath(DB_NAME)
            listOf("", "-journal", "-wal", "-shm").forEach { suffix ->
                val f = java.io.File(base.path + suffix)
                if (f.exists()) f.delete()
            }
        }
    }
}
