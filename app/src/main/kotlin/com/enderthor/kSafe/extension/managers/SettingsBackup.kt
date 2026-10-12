package com.enderthor.kSafe.extension.managers

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.jsonWithUnknownKeys
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Last-known-good mirror of the "settings" DataStore, kept in a second DataStore
 * ("settings_backup"). A corrupt settings file is reset to empty by its corruptionHandler and
 * then refilled from the mirror by [Restore] when the store opens, so a reset never wipes the
 * rider's contacts and providers. Restoring on "config key missing" also covers a zero-length
 * settings file, which parses as empty without ever reaching the corruptionHandler.
 */
object SettingsBackup {
    private val configKey = stringPreferencesKey("ksafeconfig")
    private val senderKey = stringPreferencesKey("sender")

    /**
     * A snapshot is worth mirroring only when its config decodes to a non-empty list (senders,
     * when present, must decode too). A reset store has no config key and a damaged one does not
     * decode, so neither can overwrite a good mirror; riders without a provider are still covered.
     */
    fun isBackupWorthy(prefs: Preferences): Boolean {
        val config = prefs[configKey] ?: return false
        return runCatching {
            check(jsonWithUnknownKeys.decodeFromString<List<KSafeConfig>>(config.replace("\"SIMPLEPUSH\"", "\"NTFY\"")).isNotEmpty())
            prefs[senderKey]?.let { jsonWithUnknownKeys.decodeFromString<List<SenderConfig>>(it.replace("\"SIMPLEPUSH\"", "\"NTFY\"")) }
        }.isSuccess
    }

    /** Ride state that lives in the runtime store; never mirrored, so a restore can't revive it. */
    private val notMirrored = setOf("fuelingstate", "emergencystate")

    /**
     * Replaces the mirror with [prefs] in one transaction, if [prefs] is worth keeping. Throws on
     * a storage failure so the caller's collector can retry with the latest snapshot.
     */
    suspend fun mirror(backup: DataStore<Preferences>, prefs: Preferences) {
        if (!isBackupWorthy(prefs)) {
            Timber.w("SettingsBackup: no decodable config, keeping the previous backup")
            return
        }
        backup.edit { it.clear(); it.putAllUnchecked(prefs.asMap().filterKeys { k -> k.name !in notMirrored }) }
    }

    /**
     * Refills a settings store whose config is missing (reset, zero-length file) or no longer
     * decodes. No-op on a fresh install and on healthy settings. Never throws: a failure here would
     * fail the settings store's init and crash every reader, so it logs and opens as-is instead,
     * and the restore is retried on the next process start.
     */
    class Restore(private val backup: DataStore<Preferences>) : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = try {
            !isBackupWorthy(currentData) && isBackupWorthy(backup.data.first())
        } catch (e: Exception) {
            Timber.e(e, "SettingsBackup: backup unreadable, opening settings without restore")
            false
        }

        override suspend fun migrate(currentData: Preferences): Preferences = try {
            val restored = backup.data.first()
            Timber.e("settings DataStore came up without a usable config, restoring from backup")
            currentData.toMutablePreferences().apply { putAllUnchecked(restored.asMap()) }
        } catch (e: Exception) {
            Timber.e(e, "SettingsBackup: restore failed, opening settings as-is")
            currentData
        }

        override suspend fun cleanUp() {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.putAllUnchecked(entries: Map<Preferences.Key<*>, Any>) =
        entries.forEach { (k, v) -> this[k as Preferences.Key<Any>] = v }
}
