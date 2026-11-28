package com.mapeador.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.b13SettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "b13_settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.b13SettingsDataStore

    val showIndicators: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[Keys.showIndicators] ?: true }
        .distinctUntilChanged()

    val selectedGamePackage: Flow<String?> = dataStore.data
        .map { preferences -> preferences[Keys.selectedGamePackage] }
        .distinctUntilChanged()

    val legacyMigrationCompleted: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[Keys.legacyMigrationCompleted] ?: false }
        .distinctUntilChanged()

    val legacyKeymapBackup: Flow<String?> = dataStore.data
        .map { preferences -> preferences[Keys.legacyKeymapBackup] }
        .distinctUntilChanged()

    suspend fun setShowIndicators(show: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.showIndicators] = show }
    }

    suspend fun setSelectedGamePackage(packageName: String?) {
        dataStore.edit { preferences ->
            if (packageName == null) {
                preferences.remove(Keys.selectedGamePackage)
            } else {
                preferences[Keys.selectedGamePackage] = packageName
            }
        }
    }

    suspend fun completeLegacyMigration(showIndicators: Boolean, legacyKeymap: String?) {
        dataStore.edit { preferences ->
            preferences[Keys.showIndicators] = showIndicators
            preferences[Keys.legacyMigrationCompleted] = true
            if (legacyKeymap.isNullOrBlank()) {
                preferences.remove(Keys.legacyKeymapBackup)
            } else {
                preferences[Keys.legacyKeymapBackup] = legacyKeymap
            }
        }
    }

    private object Keys {
        val showIndicators = booleanPreferencesKey("show_indicators")
        val selectedGamePackage = stringPreferencesKey("selected_game_package")
        val legacyMigrationCompleted = booleanPreferencesKey("legacy_migration_completed")
        val legacyKeymapBackup = stringPreferencesKey("legacy_keymap_backup")
    }
}
