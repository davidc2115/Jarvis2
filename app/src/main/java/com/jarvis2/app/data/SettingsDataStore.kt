package com.jarvis2.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "jarvis2_settings")

/**
 * Thin wrapper over Jetpack DataStore for small app-wide settings: which
 * search API key/endpoint to use for [com.jarvis2.app.ai.WebSearchTool],
 * the chosen Obsidian vault URI, preferred AI engine override, etc.
 *
 * Deliberately not encrypting values here beyond what DataStore itself
 * offers — for anything sensitive (e.g. a mail account token, if that's
 * ever added), route it through EncryptedSharedPreferences
 * (androidx.security:security-crypto, already a dependency) instead.
 */
class SettingsDataStore(private val context: Context) {

    suspend fun <T> get(key: Preferences.Key<T>): T? =
        context.dataStore.data.first()[key]

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun <T> remove(key: Preferences.Key<T>) {
        context.dataStore.edit { it.remove(key) }
    }
}
