package com.kakei.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.kakei.app.core.security.DatabaseKeyInvalidatedException
import com.kakei.app.core.security.DatabaseKeyManager
import com.kakei.app.core.security.KeystoreUnavailableException
import com.kakei.app.data.remote.dto.DecimalStr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

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
 * A bank SMS / push that was parsed but could not be filed automatically: no account is
 * mapped to its sender (and no default capture account is set), its currency does not
 * match the mapped account, or the server rejected the record. It waits for the user to
 * pick an account instead of being booked against a guessed or non-existent one.
 */
@Serializable
data class PendingCaptureEntity(
    val id: String,
    val sourceChannel: String,
    /** SMS originating address or notification package; key for the sender→account mapping. */
    val sender: String? = null,
    val rawText: String,
    val operationType: String,
    val amount: DecimalStr,
    val currency: String,
    val merchant: String? = null,
    val bookingDate: String,
    /** NO_ACCOUNT | CURRENCY_MISMATCH | SERVER_REJECTED */
    val reason: String,
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

    /** How the encrypted database was opened (see [resolvePassphrase]). */
    enum class OpenState {
        OK,
        /** A database created with the legacy constant passphrase was re-keyed. */
        MIGRATED_FROM_LEGACY_KEY,
        /** The local replica was unreadable and was wiped; it is rebuilt by the next sync. */
        RECOVERED_AFTER_KEY_LOSS,
        /** Keystore unusable this launch; an in-memory database is used and nothing persists. */
        KEYSTORE_UNAVAILABLE
    }

    var openState: OpenState = OpenState.OK
        private set

    private val openHelper: SupportSQLiteOpenHelper

    init {
        val callback = object : SupportSQLiteOpenHelper.Callback(SCHEMA_VERSION) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createSchema(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // The open helper already wraps this call in a transaction.
                for (version in (oldVersion + 1)..newVersion) {
                    val migration = MIGRATIONS[version]
                        ?: error("No local schema migration to v$version (from v$oldVersion)")
                    migration(db)
                }
            }
        }

        var dbName: String? = DB_NAME
        val factory: SupportSQLiteOpenHelper.Factory = try {
            System.loadLibrary("sqlcipher")
            val passphrase = try {
                resolvePassphrase(context)
            } catch (e: KeystoreUnavailableException) {
                // Transient Keystore failure that survived the retries: leave the encrypted
                // file untouched (it opens normally once the Keystore recovers) and run this
                // session on an ephemeral in-memory database keyed with a random passphrase.
                openState = OpenState.KEYSTORE_UNAVAILABLE
                dbName = null
                ByteArray(32).also { SecureRandom().nextBytes(it) }
            }
            isSqlCipherActive = true
            SupportOpenHelperFactory(passphrase)
        } catch (_: UnsatisfiedLinkError) {
            // SQLCipher native library missing (JVM / instrumentation environments only).
            isSqlCipherActive = false
            FrameworkSQLiteOpenHelperFactory()
        }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()

        openHelper = factory.create(config)
    }

    private val db: SupportSQLiteDatabase
        get() = openHelper.writableDatabase

    /**
     * Obtains the Keystore-wrapped passphrase and makes sure the database file on disk
     * actually opens with it, handling the three ways it may not:
     * 1. the wrapped key is permanently unreadable → wipe DB + key, start empty (resync);
     * 2. the file was created by an old build with the constant fallback passphrase →
     *    re-key it in place to the Keystore passphrase (no data loss);
     * 3. the file opens with neither → it is unrecoverable: wipe it, start empty (resync).
     * The local replica is only a cache of server data plus the Outbox, so (1) and (3)
     * lose at most unsynced offline changes, and never leave the app crash-looping.
     */
    private fun resolvePassphrase(context: Context): ByteArray {
        val passphrase = try {
            DatabaseKeyManager.getOrCreateDatabasePassphrase(context)
        } catch (_: DatabaseKeyInvalidatedException) {
            deleteDatabaseFiles(context)
            DatabaseKeyManager.resetDatabaseKey(context)
            openState = OpenState.RECOVERED_AFTER_KEY_LOSS
            return DatabaseKeyManager.getOrCreateDatabasePassphrase(context)
        }

        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists() || canOpen(file, passphrase)) return passphrase

        val legacy = DatabaseKeyManager.LEGACY_FALLBACK_PASSPHRASE
        if (canOpen(file, legacy) && runCatching { rekey(file, legacy, passphrase) }.isSuccess) {
            openState = OpenState.MIGRATED_FROM_LEGACY_KEY
            return passphrase
        }

        deleteDatabaseFiles(context)
        openState = OpenState.RECOVERED_AFTER_KEY_LOSS
        return passphrase
    }

    /** Probes a key without the default error handler (which would delete a "corrupt" file). */
    private fun canOpen(file: java.io.File, passphrase: ByteArray): Boolean = runCatching {
        CipherDatabase.openDatabase(
            file.path, passphrase.copyOf(), null, CipherDatabase.OPEN_READWRITE, NO_OP_ERROR_HANDLER, null
        ).use { probe ->
            probe.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() }
        }
    }.getOrDefault(false)

    private fun rekey(file: java.io.File, oldPassphrase: ByteArray, newPassphrase: ByteArray) {
        CipherDatabase.openDatabase(
            file.path, oldPassphrase.copyOf(), null, CipherDatabase.OPEN_READWRITE, NO_OP_ERROR_HANDLER, null
        ).use { it.changePassword(newPassphrase.copyOf()) }
        check(canOpen(file, newPassphrase)) { "Re-key of legacy database failed" }
    }

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
            "SELECT id, payload_json FROM kv_store WHERE collection = ? ORDER BY sort_key $order",
            arrayOf(collection)
        )
        val result = mutableListOf<T>()
        cursor.use { c ->
            while (c.moveToNext()) {
                decodeRow<T>(collection, c.getString(0), c.getString(1))?.let { result.add(it) }
            }
        }
        return result
    }

    inline fun <reified T> getItem(collection: String, id: String): T? {
        val cursor = readableOpenHelper.readableDatabase.query(
            "SELECT payload_json FROM kv_store WHERE collection = ? AND id = ?",
            arrayOf(collection, id)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) decodeRow<T>(collection, id, c.getString(0)) else null
        }
    }

    /**
     * Decodes one stored payload. A row that no longer matches its model is skipped but
     * never silently: it is reported (and kept on disk, so an outbox entry is not lost)
     * until a schema migration rewrites it or the next sync replaces it.
     */
    @PublishedApi
    internal inline fun <reified T> decodeRow(collection: String, id: String, raw: String): T? =
        try {
            json.decodeFromString<T>(raw)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            reportUndecodableRow(collection, id, e)
            null
        }

    private val undecodableRows = AtomicInteger()

    /** Count of rows skipped because their payload could not be decoded, since launch. */
    val undecodableRowCount: Int
        get() = undecodableRows.get()

    @PublishedApi
    internal fun reportUndecodableRow(collection: String, id: String, error: Exception) {
        undecodableRows.incrementAndGet()
        // Payloads hold financial data: log only where the row is and why it failed.
        Log.e(TAG, "Skipping undecodable row $collection/$id: ${error::class.simpleName}: ${error.message?.take(200)}")
    }

    @PublishedApi
    internal val readableOpenHelper: SupportSQLiteOpenHelper
        get() = openHelper

    companion object {
        private const val TAG = "KakeiDatabase"
        const val DB_NAME = "kakei_encrypted_ledger.db"

        /**
         * Version of the local schema (table layout and the JSON shape of stored payloads).
         * To change either: bump this and register a step in [MIGRATIONS] for the new
         * version, e.g. rewriting payloads of a collection, or clearing a server-replicated
         * collection so the next sync rebuilds it. Never clear [COL_MUTATIONS] — it holds
         * offline changes that exist nowhere else.
         */
        const val SCHEMA_VERSION = 1

        /** Upgrade steps keyed by the version they upgrade *to*; applied in order. */
        private val MIGRATIONS: Map<Int, (SupportSQLiteDatabase) -> Unit> = emptyMap()
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
        const val COL_EXCHANGE_RATES = "exchange_rates"
        const val COL_PENDING_CAPTURES = "pending_captures"

        private val NO_OP_ERROR_HANDLER = DatabaseErrorHandler { _, _ -> }

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
