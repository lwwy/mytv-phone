package com.mytvphone.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mytv_settings")

/**
 * 只保留真正生效的设置。
 * 已移除：autoResume / lastChannelId（只写不读，从未实现「恢复上次频道」）、
 *        showLogo（开关意义不大，台标统一按有无 logo 自动显示）。
 */
data class AppSettings(
    val activeSubId: String = "",
    val lockLandscape: Boolean = true,
    val dynamicColor: Boolean = false,
)

class Prefs(private val context: Context) {

    private object K {
        val ACTIVE = stringPreferencesKey("active_sub_id")
        val LOCK_LANDSCAPE = booleanPreferencesKey("lock_landscape")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                activeSubId = p[K.ACTIVE] ?: "",
                lockLandscape = p[K.LOCK_LANDSCAPE] ?: true,
                dynamicColor = p[K.DYNAMIC_COLOR] ?: false,
            )
        }

    suspend fun setActiveSub(id: String) = context.dataStore.edit { it[K.ACTIVE] = id }

    suspend fun setLockLandscape(v: Boolean) = context.dataStore.edit { it[K.LOCK_LANDSCAPE] = v }

    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[K.DYNAMIC_COLOR] = v }
}
