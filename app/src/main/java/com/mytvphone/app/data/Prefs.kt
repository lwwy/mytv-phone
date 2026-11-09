package com.mytvphone.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mytv_settings")

data class AppSettings(
    val activeSubId: String = "",
    val lastChannelId: String = "",
    val favorites: Set<String> = emptySet(),
    val lockLandscape: Boolean = true,
    val gesturesEnabled: Boolean = true,
    val autoResume: Boolean = true,
    val showLogo: Boolean = true,
)

class Prefs(private val context: Context) {

    private object K {
        val ACTIVE = stringPreferencesKey("active_sub_id")
        val LAST_CHANNEL = stringPreferencesKey("last_channel_id")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val LOCK_LANDSCAPE = booleanPreferencesKey("lock_landscape")
        val GESTURES = booleanPreferencesKey("gestures_enabled")
        val AUTO_RESUME = booleanPreferencesKey("auto_resume")
        val SHOW_LOGO = booleanPreferencesKey("show_logo")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                activeSubId = p[K.ACTIVE] ?: "",
                lastChannelId = p[K.LAST_CHANNEL] ?: "",
                favorites = p[K.FAVORITES] ?: emptySet(),
                lockLandscape = p[K.LOCK_LANDSCAPE] ?: true,
                gesturesEnabled = p[K.GESTURES] ?: true,
                autoResume = p[K.AUTO_RESUME] ?: true,
                showLogo = p[K.SHOW_LOGO] ?: true,
            )
        }

    suspend fun setActiveSub(id: String) = context.dataStore.edit { it[K.ACTIVE] = id }
    suspend fun setLastChannel(id: String) = context.dataStore.edit { it[K.LAST_CHANNEL] = id }

    suspend fun toggleFavorite(id: String, current: Set<String>) {
        val next = if (id in current) current - id else current + id
        context.dataStore.edit { it[K.FAVORITES] = next }
    }

    suspend fun setLockLandscape(v: Boolean) = context.dataStore.edit { it[K.LOCK_LANDSCAPE] = v }
    suspend fun setGestures(v: Boolean) = context.dataStore.edit { it[K.GESTURES] = v }
    suspend fun setAutoResume(v: Boolean) = context.dataStore.edit { it[K.AUTO_RESUME] = v }
    suspend fun setShowLogo(v: Boolean) = context.dataStore.edit { it[K.SHOW_LOGO] = v }
}
