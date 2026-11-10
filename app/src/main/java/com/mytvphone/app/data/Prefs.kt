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

/**
 * 只保留真正生效的设置。
 * 已移除：autoResume / lastChannelId（只写不读，从未实现「恢复上次频道」）、
 *        showLogo（开关意义不大，台标统一按有无 logo 自动显示）。
 */
data class AppSettings(
    val activeSubId: String = "",
    val favorites: Set<String> = emptySet(),
    val lockLandscape: Boolean = true,
    val gesturesEnabled: Boolean = true,
)

class Prefs(private val context: Context) {

    private object K {
        val ACTIVE = stringPreferencesKey("active_sub_id")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val LOCK_LANDSCAPE = booleanPreferencesKey("lock_landscape")
        val GESTURES = booleanPreferencesKey("gestures_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                activeSubId = p[K.ACTIVE] ?: "",
                favorites = p[K.FAVORITES] ?: emptySet(),
                lockLandscape = p[K.LOCK_LANDSCAPE] ?: true,
                gesturesEnabled = p[K.GESTURES] ?: true,
            )
        }

    suspend fun setActiveSub(id: String) = context.dataStore.edit { it[K.ACTIVE] = id }

    /** 在 DataStore 事务内基于最新值计算，避免旧代码传快照导致的并发丢收藏。 */
    suspend fun toggleFavorite(id: String) = context.dataStore.edit { p ->
        val current = p[K.FAVORITES] ?: emptySet()
        p[K.FAVORITES] = if (id in current) current - id else current + id
    }

    suspend fun setLockLandscape(v: Boolean) = context.dataStore.edit { it[K.LOCK_LANDSCAPE] = v }
    suspend fun setGestures(v: Boolean) = context.dataStore.edit { it[K.GESTURES] = v }
}
