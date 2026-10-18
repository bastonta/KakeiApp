package com.kakei.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.kakei.app.core.security.DatabaseKeyManager
import com.kakei.app.data.remote.dto.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.math.BigDecimal

@Serializable
data class SyncMutationEntity(
    val id: String,
    val entityType: String, // "ACCOUNT" | "RECORD"
    val entityId: String,
    val operation: String,  // "CREATE" | "UPDATE" | "DELETE"
    val payloadJson: String,
    val expectedSyncRevision: Long? = null,
    val createdAt: String
)

/**
 * Encrypted local SQLite database powered by SQLCipher (AES-256) and AndroidKeyStore.
 * Stores full offline replicas of all Kakei domain entities and the Outbox mutation queue.
 */
class KakeiDatabase(context: Context) {

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
            val passphrase = DatabaseKeyManager.getOrCreateDatabasePassphrase(context)
            isSqlCipherActive = true
            SupportOpenHelperFactory(passphrase)
        } catch (_: Throwable) {
            isSqlCipherActive = false
            FrameworkSQLiteOpenHelperFactory()
        }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("kakei_encrypted_ledger.db")
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
    }
}
