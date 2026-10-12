package com.enderthor.kSafe.extension.managers

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsBackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @After fun tearDown() = scope.cancel()

    private val config = stringPreferencesKey("ksafeconfig")
    private val sender = stringPreferencesKey("sender")
    private val settings = mutablePreferencesOf().apply {
        this[config] = """[{"isActive":true}]"""
        this[sender] = """[{"provider":"TELEGRAM","apiKey":"secret"}]"""
        this[intPreferencesKey("update_restart_count")] = 3
        this[longPreferencesKey("update_notice_epoch_day")] = 20_000L
    }

    private fun backupStore() = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings_backup.preferences_pb") }

    /** Same wiring as Context.dataStore in MainActivity.kt: reset on corruption, then restore. */
    private fun settingsStore(backup: DataStore<Preferences>) = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        migrations = listOf(SettingsBackup.Restore(backup)),
        scope = scope,
    ) { File(tmp.root, "settings.preferences_pb") }

    private fun settingsFile() = File(tmp.root, "settings.preferences_pb")

    /** The field incident: a non-empty, zero-filled settings file must reopen from the mirror. */
    @Test fun `zero-filled settings file is rebuilt from the mirror`() = runBlocking {
        val backup = backupStore()
        SettingsBackup.mirror(backup, settings)
        settingsFile().writeBytes(ByteArray(512))
        assertEquals(settings, settingsStore(backup).data.first())
    }

    @Test fun `zero-length settings file is rebuilt from the mirror too`() = runBlocking {
        val backup = backupStore()
        SettingsBackup.mirror(backup, settings)
        settingsFile().writeBytes(ByteArray(0))
        assertEquals(settings, settingsStore(backup).data.first())
    }

    @Test fun `no mirror falls back to empty instead of crashing`() = runBlocking {
        settingsFile().writeBytes(ByteArray(512))
        assertTrue(settingsStore(backupStore()).data.first().asMap().isEmpty())
    }

    @Test fun `healthy existing settings are never touched by the restore`() = runBlocking {
        val backup = backupStore()
        SettingsBackup.mirror(backup, settings)
        // Write a healthy, different settings file with a store that has no Restore, then reopen.
        val writerScope = CoroutineScope(Dispatchers.IO + Job())
        PreferenceDataStoreFactory.create(scope = writerScope) { settingsFile() }.edit { it[config] = "[{}]" }
        writerScope.cancel()
        assertEquals("[{}]", settingsStore(backup).data.first()[config])
    }

    @Test fun `an undecodable config is restored from the mirror`() = runBlocking {
        val backup = backupStore()
        SettingsBackup.mirror(backup, settings)
        val writerScope = CoroutineScope(Dispatchers.IO + Job())
        PreferenceDataStoreFactory.create(scope = writerScope) { settingsFile() }.edit { it[config] = "{damaged" }
        writerScope.cancel()
        assertEquals(settings, settingsStore(backup).data.first())
    }

    @Test fun `an unreadable mirror opens settings as-is instead of failing`() = runBlocking {
        settingsFile().writeBytes(ByteArray(512))
        assertTrue(settingsStore(ThrowingStore).data.first().asMap().isEmpty())
    }

    @Test fun `ride state is never mirrored`() = runBlocking {
        val backup = backupStore()
        SettingsBackup.mirror(backup, mutablePreferencesOf().apply {
            this[config] = "[{}]"
            this[stringPreferencesKey("fuelingstate")] = "old ride"
            this[stringPreferencesKey("emergencystate")] = "old countdown"
        })
        assertEquals(setOf("ksafeconfig"), backup.data.first().asMap().keys.map { it.name }.toSet())
    }

    @Test fun `a reset or broken snapshot never overwrites a good mirror`() = runBlocking {
        val backup = backupStore()
        SettingsBackup.mirror(backup, settings)
        SettingsBackup.mirror(backup, emptyPreferences())
        SettingsBackup.mirror(backup, mutablePreferencesOf().apply { this[config] = "not json" })
        SettingsBackup.mirror(backup, mutablePreferencesOf().apply { this[config] = "[{}]"; this[sender] = "{broken" })
        SettingsBackup.mirror(backup, mutablePreferencesOf().apply { this[config] = "[]" }) // decodes to defaults
        assertEquals(settings, backup.data.first())
    }

    @Test fun `a rider without a provider is still mirrored`() = runBlocking {
        val backup = backupStore()
        val noProvider = mutablePreferencesOf().apply { this[config] = "[{}]" }
        SettingsBackup.mirror(backup, noProvider)
        assertEquals(noProvider, backup.data.first())
        assertNull(backup.data.first()[sender])
        assertFalse(SettingsBackup.isBackupWorthy(emptyPreferences()))
    }
}

/** A store whose every read and write fails, standing in for a storage I/O error. */
private object ThrowingStore : DataStore<Preferences> {
    override val data = kotlinx.coroutines.flow.flow<Preferences> { throw java.io.IOException("disk") }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw java.io.IOException("disk")
}

class MoveRideStateFromSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `ride state moves to runtime once and leaves settings`() = runBlocking {
        val fueling = stringPreferencesKey("fuelingstate")
        val config = stringPreferencesKey("ksafeconfig")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val settings = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }
            settings.edit { it[fueling] = "snapshot"; it[config] = "[{}]" }
            val runtime = PreferenceDataStoreFactory.create(
                migrations = listOf(com.enderthor.kSafe.activity.MoveRideStateFromSettings(settings)),
                scope = scope,
            ) { File(tmp.root, "runtime.preferences_pb") }

            assertEquals("snapshot", runtime.data.first()[fueling])
            assertNull(settings.data.first()[fueling])
            assertEquals("[{}]", settings.data.first()[config])
        } finally {
            scope.cancel()
        }
    }

    @Test fun `unreadable settings leave runtime usable and the move pending`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val runtime = PreferenceDataStoreFactory.create(
                migrations = listOf(com.enderthor.kSafe.activity.MoveRideStateFromSettings(ThrowingStore)),
                scope = scope,
            ) { File(tmp.root, "runtime.preferences_pb") }
            assertTrue(runtime.data.first().asMap().isEmpty()) // no done-marker: retried next start
        } finally {
            scope.cancel()
        }
    }
}
