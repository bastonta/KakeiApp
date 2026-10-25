package com.kakei.app.core.security

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The Keystore could not be used even after retries (transient failure; data is untouched). */
class KeystoreUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The wrapped SQLCipher passphrase can never be decrypted again (Keystore key permanently
 * invalidated, deleted, or data restored onto another device). The local replica is lost;
 * the caller must run the controlled recovery (wipe local DB + key, resync from server).
 */
class DatabaseKeyInvalidatedException(cause: Throwable? = null) :
    Exception("Database key permanently invalidated", cause)

/**
 * Hardware-backed (AndroidKeyStore) AES-256-GCM wrapping shared by the SQLCipher
 * passphrase manager and [SecurePrefs].
 */
internal object KeystoreCipher {
    private const val PROVIDER = "AndroidKeyStore"
    private const val MAX_ATTEMPTS = 3

    /** True when the device Keystore is present (false only in plain JVM unit tests). */
    fun isAvailable(): Boolean = Security.getProvider(PROVIDER) != null

    /**
     * Failures that retrying cannot fix: the key or the ciphertext is gone for good.
     * Everything else (KeyStoreException, ProviderException, binder hiccups, a busy
     * StrongBox) is treated as transient.
     */
    fun isPermanentFailure(t: Throwable): Boolean =
        t is AEADBadTagException ||
            t is KeyPermanentlyInvalidatedException ||
            t is UnrecoverableKeyException ||
            t is IllegalArgumentException // malformed stored payload

    /** Runs [block], retrying transient Keystore failures with a short backoff. */
    fun <T> withRetry(block: () -> T): T {
        var last: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                if (isPermanentFailure(t) || t is Error) throw t
                last = t
                if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(100L * (attempt + 1))
            }
        }
        throw KeystoreUnavailableException("AndroidKeyStore unavailable after $MAX_ATTEMPTS attempts", last)
    }

    fun getOrCreateKey(alias: String, preferStrongBox: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        if (preferStrongBox) {
            try {
                return generateKey(alias, strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // No StrongBox on this device: fall through to the TEE-backed Keystore key.
            }
        }
        return generateKey(alias, strongBox = false)
    }

    private fun generateKey(alias: String, strongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun deleteKey(alias: String) {
        runCatching {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        }
    }

    /** Returns `iv:ciphertext` (both Base64). */
    fun encrypt(alias: String, plain: ByteArray, preferStrongBox: Boolean = false): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias, preferStrongBox))
        val encrypted = cipher.doFinal(plain)
        val enc = Base64.getEncoder()
        return enc.encodeToString(cipher.iv) + ":" + enc.encodeToString(encrypted)
    }

    /**
     * Decrypts a value produced by [encrypt]. Throws [AEADBadTagException] when the
     * Keystore key no longer matches (e.g. data restored onto another device).
     */
    fun decrypt(alias: String, payload: String): ByteArray {
        val parts = payload.split(":", limit = 2)
        require(parts.size == 2) { "Malformed encrypted payload" }
        val dec = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), GCMParameterSpec(128, dec.decode(parts[0])))
        return cipher.doFinal(dec.decode(parts[1]))
    }
}

/**
 * Manages the AES-256 SQLCipher passphrase. The random passphrase is wrapped with a
 * hardware-backed AndroidKeyStore key (StrongBox when present) and stored in private
 * SharedPreferences.
 *
 * There is deliberately no constant fallback passphrase. Transient Keystore failures are
 * retried and then reported as [KeystoreUnavailableException]; a passphrase that can
 * never be unwrapped again is reported as [DatabaseKeyInvalidatedException] so the
 * database layer can run its controlled recovery ([resetDatabaseKey] + wipe + resync).
 */
object DatabaseKeyManager {
    private const val KEYSTORE_ALIAS = "kakei_sqlcipher_master_key"
    private const val PREFS_NAME = "kakei_secure_db_key"
    private const val KEY_WRAPPED_PASSPHRASE = "wrapped_passphrase_v2"
    private const val LEGACY_KEY_WRAPPED = "wrapped_passphrase"
    private const val LEGACY_KEY_IV = "wrapped_iv"

    /**
     * The constant that builds before 1.0.1 silently used whenever the Keystore failed.
     * It is ONLY used to detect and re-key such databases to a Keystore-wrapped
     * passphrase; it is never used to create or open a database for normal use.
     */
    internal val LEGACY_FALLBACK_PASSPHRASE: ByteArray
        get() = "kakei_fallback_local_passphrase_32b".toByteArray(Charsets.UTF_8).copyOf(32)

    fun hasStoredPassphrase(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_WRAPPED_PASSPHRASE) || prefs.contains(LEGACY_KEY_WRAPPED)
    }

    /**
     * Returns the database passphrase, creating and wrapping a fresh random one when
     * none is stored yet.
     *
     * @throws DatabaseKeyInvalidatedException the stored passphrase is permanently unreadable
     * @throws KeystoreUnavailableException the Keystore kept failing (retry on next launch)
     */
    fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
        if (!KeystoreCipher.isAvailable()) {
            throw KeystoreUnavailableException("AndroidKeyStore is not available on this device")
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyFormat(prefs)

        val wrapped = prefs.getString(KEY_WRAPPED_PASSPHRASE, null)
        if (wrapped != null) {
            try {
                return KeystoreCipher.withRetry { KeystoreCipher.decrypt(KEYSTORE_ALIAS, wrapped) }
            } catch (e: KeystoreUnavailableException) {
                throw e
            } catch (e: Exception) {
                if (KeystoreCipher.isPermanentFailure(e)) {
                    throw DatabaseKeyInvalidatedException(e)
                }
                throw KeystoreUnavailableException("Could not unwrap database key", e)
            }
        }

        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val newWrapped = KeystoreCipher.withRetry {
            KeystoreCipher.encrypt(KEYSTORE_ALIAS, rawKey, preferStrongBox = true)
        }
        // commit(): the passphrase must be durable before any database is keyed with it.
        prefs.edit().putString(KEY_WRAPPED_PASSPHRASE, newWrapped).commit()
        return rawKey
    }

    /** Forgets the wrapped passphrase and its Keystore key (controlled recovery only). */
    fun resetDatabaseKey(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_WRAPPED_PASSPHRASE)
            .remove(LEGACY_KEY_WRAPPED)
            .remove(LEGACY_KEY_IV)
            .commit()
        KeystoreCipher.deleteKey(KEYSTORE_ALIAS)
    }

    /** Converts the pre-v2 two-field layout (`wrapped_iv` + `wrapped_passphrase`) in place. */
    private fun migrateLegacyFormat(prefs: android.content.SharedPreferences) {
        val legacyWrapped = prefs.getString(LEGACY_KEY_WRAPPED, null)
        val legacyIv = prefs.getString(LEGACY_KEY_IV, null)
        if (legacyWrapped == null || legacyIv == null) return
        val editor = prefs.edit().remove(LEGACY_KEY_WRAPPED).remove(LEGACY_KEY_IV)
        if (!prefs.contains(KEY_WRAPPED_PASSPHRASE)) {
            editor.putString(KEY_WRAPPED_PASSPHRASE, "$legacyIv:$legacyWrapped")
        }
        editor.commit()
    }
}

/**
 * Small encrypted key/value store for session secrets (access JWT, refresh cookie).
 * Values are AES-256-GCM encrypted with a hardware-backed Keystore key, so a copy of
 * the preferences file is useless off-device. Without a Keystore (JVM unit tests)
 * values are kept in memory only.
 */
class SecurePrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryFallback = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getString(key: String): String? {
        if (!KeystoreCipher.isAvailable()) return memoryFallback[key]
        val payload = prefs.getString(key, null) ?: return null
        return try {
            String(KeystoreCipher.decrypt(KEY_ALIAS, payload), Charsets.UTF_8)
        } catch (_: Exception) {
            // Unreadable (key rotated or data from another device): drop it.
            prefs.edit().remove(key).apply()
            null
        }
    }

    fun putString(key: String, value: String?) {
        if (value == null) {
            remove(key)
            return
        }
        if (!KeystoreCipher.isAvailable()) {
            memoryFallback[key] = value
            return
        }
        prefs.edit()
            .putString(key, KeystoreCipher.encrypt(KEY_ALIAS, value.toByteArray(Charsets.UTF_8)))
            .apply()
    }

    fun remove(key: String) {
        memoryFallback.remove(key)
        prefs.edit().remove(key).apply()
    }

    companion object {
        private const val PREFS_NAME = "kakei_secure_store"
        private const val KEY_ALIAS = "kakei_secure_store_key"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_COOKIE = "refresh_cookie"
    }
}

/**
 * Client-side AES-256-GCM + PBKDF2 backup encryption helper.
 */
object BackupCryptoHelper {
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    fun encryptBackup(plainJson: String, passphrase: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val secretKey = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(salt.size + iv.size + cipherBytes.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(iv, 0, combined, salt.size, iv.size)
        System.arraycopy(cipherBytes, 0, combined, salt.size + iv.size, cipherBytes.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decryptBackup(encryptedBase64: String, passphrase: String): String {
        val combined = Base64.getDecoder().decode(encryptedBase64.trim())
        require(combined.size > 28) { "Invalid backup payload" }
        val salt = combined.copyOfRange(0, 16)
        val iv = combined.copyOfRange(16, 28)
        val cipherBytes = combined.copyOfRange(28, combined.size)
        val secretKey = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}

/**
 * Biometric prompt helper and screen-off auto-lock manager.
 */
object BiometricHelper {
    fun canAuthenticate(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        val result = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }
}

/**
 * Calibrated LRA / ERM haptic feedback helper for hardware-aware haptics.
 */
object HapticHelper {
    fun tick(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
        } catch (_: Exception) {
            // Ignore vibration permission or hardware absence
        }
    }
}

data class SystemHealthStatus(
    val sqlCipherActive: Boolean,
    val notificationListenerGranted: Boolean,
    val overlayPermissionGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val biometricAvailable: Boolean
)

/**
 * Audits system permissions and security status for the Diagnostic Panel.
 */
object HealthCheckHelper {
    fun inspect(context: Context, sqlCipherActive: Boolean): SystemHealthStatus {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        val notificationGranted = enabledListeners.contains(context.packageName)
        val overlayGranted = Settings.canDrawOverlays(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val biometricAvailable = BiometricHelper.canAuthenticate(context)

        return SystemHealthStatus(
            sqlCipherActive = sqlCipherActive,
            notificationListenerGranted = notificationGranted,
            overlayPermissionGranted = overlayGranted,
            batteryOptimizationIgnored = batteryIgnored,
            biometricAvailable = biometricAvailable
        )
    }
}
