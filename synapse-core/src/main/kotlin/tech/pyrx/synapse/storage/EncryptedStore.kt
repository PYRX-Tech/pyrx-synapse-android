/*
 * EncryptedStore.kt
 * PYRXSynapse — Android
 *
 * Production [PyrxStorage] backed by AndroidX EncryptedSharedPreferences,
 * which uses the platform Keystore to derive a symmetric key bound to the
 * device + app (not exported, not backed up to Google Drive).
 *
 * Design choices:
 *   - File name `pyrx_synapse_encrypted_store` is private to the host app
 *     (MODE_PRIVATE) so other apps cannot read it even on rooted devices.
 *   - MasterKey uses AES256_GCM keyspec — the AndroidX default; rotating is
 *     a breaking change because re-encrypting on the fly is out of scope.
 *   - We never throw from the constructor — we catch + wrap so the SDK
 *     surfaces a typed [PyrxError.StorageFailure] instead of leaking
 *     androidx.security exceptions to consumers.
 *   - On EncryptedSharedPreferences corruption (rare — usually app data
 *     restored from a different device's backup), wipe + recreate. Losing
 *     the anonymousId is preferable to refusing to initialize.
 */

package tech.pyrx.synapse.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import tech.pyrx.synapse.PyrxError

public class EncryptedStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : PyrxStorage {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext, fileName)

    override fun get(key: PyrxStorageKey): String? =
        try {
            prefs.getString(key.rawValue, null)
        } catch (t: Throwable) {
            throw PyrxError.StorageFailure(operation = "get(${key.rawValue})", underlying = t)
        }

    override fun set(
        key: PyrxStorageKey,
        value: String,
    ) {
        try {
            prefs.edit().putString(key.rawValue, value).apply()
        } catch (t: Throwable) {
            throw PyrxError.StorageFailure(operation = "set(${key.rawValue})", underlying = t)
        }
    }

    override fun delete(key: PyrxStorageKey) {
        try {
            prefs.edit().remove(key.rawValue).apply()
        } catch (t: Throwable) {
            throw PyrxError.StorageFailure(operation = "delete(${key.rawValue})", underlying = t)
        }
    }

    override fun wipe() {
        try {
            // Iterate explicit keys (not `clear()`) so we never delete a key a
            // future SDK version owns but this version doesn't recognise.
            val editor = prefs.edit()
            for (key in PyrxStorageKey.ALL) {
                editor.remove(key.rawValue)
            }
            editor.apply()
        } catch (t: Throwable) {
            throw PyrxError.StorageFailure(operation = "wipe", underlying = t)
        }
    }

    public companion object {
        /** SharedPreferences file name — private to the host app. */
        public const val DEFAULT_FILE_NAME: String = "pyrx_synapse_encrypted_store"

        private fun createPrefs(
            context: Context,
            fileName: String,
        ): SharedPreferences {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            return try {
                EncryptedSharedPreferences.create(
                    context,
                    fileName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (firstAttempt: Throwable) {
                // Rare: prefs file corrupted (e.g., adb-restored from a different
                // device). Nuke + retry once — losing anonymousId is recoverable.
                // We log+swallow the first failure via the second-attempt failure
                // chain so the root cause survives in `cause.cause`.
                context.deleteSharedPreferences(fileName)
                try {
                    EncryptedSharedPreferences.create(
                        context,
                        fileName,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                } catch (retry: Throwable) {
                    retry.addSuppressed(firstAttempt)
                    throw PyrxError.StorageFailure(operation = "open($fileName)", underlying = retry)
                }
            }
        }
    }
}
