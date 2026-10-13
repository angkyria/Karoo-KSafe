package com.enderthor.kSafe.activity

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.enderthor.kSafe.extension.managers.SettingsBackup
import com.enderthor.kSafe.screens.TabLayout
import kotlinx.coroutines.flow.first
import timber.log.Timber

// A corrupt settings file (e.g. zero-filled after power loss) otherwise crash-loops the service
// on every start. The handler resets it to empty; SettingsBackup.Restore then refills it from the
// last-known-good mirror as the store opens, so no consumer ever sees the reset.
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Timber.e(e, "settings DataStore corrupt, resetting before restore from backup")
        emptyPreferences()
    },
    produceMigrations = { context -> listOf(SettingsBackup.Restore(context.settingsBackup)) },
)

// Mirror of "settings" (see SettingsBackup). Written by its own collector, never by hand.
val Context.settingsBackup: DataStore<Preferences> by preferencesDataStore(
    name = "settings_backup",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Timber.e(e, "settings backup corrupt, resetting it")
        emptyPreferences()
    },
)

// High-churn ride state (fueling snapshot every ~30 s, emergency resume state) lives in its
// own file so its rewrites never touch the rider's settings/contacts. Losing it is cheap:
// a corrupt file just restarts with no snapshot to resume.
val Context.runtimeStore: DataStore<Preferences> by preferencesDataStore(
    name = "runtime",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Timber.e(e, "runtime DataStore corrupt, discarding ride state")
        emptyPreferences()
    },
    produceMigrations = { context -> listOf(MoveRideStateFromSettings(context.dataStore)) },
)

/**
 * One-time move of the ride-state keys that lived in "settings" before the runtime store
 * existed, so an update mid-ride or mid-countdown still resumes. The done-marker is committed
 * together with the copied values; the old keys are only removed from settings afterwards.
 */
internal class MoveRideStateFromSettings(private val settings: DataStore<Preferences>) : DataMigration<Preferences> {
    private val keys = listOf(stringPreferencesKey("fuelingstate"), stringPreferencesKey("emergencystate"))
    private val done = booleanPreferencesKey("moved_from_settings")

    override suspend fun shouldMigrate(currentData: Preferences) = currentData[done] != true

    // Never throws: a failure here would fail the runtime store's init and crash its writers
    // mid-ride. Without the marker the move is simply retried on the next process start.
    override suspend fun migrate(currentData: Preferences): Preferences = try {
        val old = settings.data.first()
        currentData.toMutablePreferences().apply {
            keys.forEach { key -> old[key]?.let { this[key] = it } }
            this[done] = true
        }
    } catch (e: Exception) {
        Timber.e(e, "runtime: could not read settings, ride state not moved yet")
        currentData
    }

    // Leftover keys in settings are harmless: nothing reads them and they are never mirrored.
    override suspend fun cleanUp() {
        try {
            settings.edit { prefs -> keys.forEach { prefs.remove(it) } }
        } catch (e: Exception) {
            Timber.e(e, "runtime: could not remove moved keys from settings")
        }
    }
}

@Composable
fun Main() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        TabLayout()
    }
}

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Main() }
        // The previous version auto-launched the system Settings page from onCreate if the
        // SYSTEM_ALERT_WINDOW permission was missing — but TabLayout also shows an in-app
        // banner with a "Grant" button, so the rider was getting a double-prompt at first
        // launch (Settings dialog appears before the app UI is even visible, then the
        // banner is also there when they return). The banner is the gentler, more
        // explanatory prompt; rely on it alone. Logged for first-launch diagnostics so the
        // rider's logcat shows why the overlay isn't appearing yet.
        if (!Settings.canDrawOverlays(this)) {
            Timber.w("KSafe: SYSTEM_ALERT_WINDOW not granted — banner in TabLayout will prompt the rider")
        }
    }
}
