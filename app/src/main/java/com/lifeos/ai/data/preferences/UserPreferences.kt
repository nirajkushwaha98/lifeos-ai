package com.lifeos.ai.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lifeos.ai.core.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lifeos_prefs")

data class AppSettings(
    val onboardingDone: Boolean = false,
    val guestMode: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val notificationsEnabled: Boolean = true,
    val analyticsEnabled: Boolean = false,
    val crashReportsEnabled: Boolean = true,
    val cloudSyncEnabled: Boolean = true,
    val lastSyncAt: Long = 0L,
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val guest = booleanPreferencesKey("guest_mode")
        val theme = stringPreferencesKey("theme_mode")
        val notifications = booleanPreferencesKey("notifications_enabled")
        val analytics = booleanPreferencesKey("analytics_enabled")
        val crash = booleanPreferencesKey("crash_reports_enabled")
        val cloudSync = booleanPreferencesKey("cloud_sync_enabled")
        val lastSync = longPreferencesKey("last_sync_at")
        fun lastPull(collection: String) = longPreferencesKey("last_pull_$collection")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.onboarding] ?: false,
            guestMode = p[Keys.guest] ?: false,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            notificationsEnabled = p[Keys.notifications] ?: true,
            analyticsEnabled = p[Keys.analytics] ?: false,
            crashReportsEnabled = p[Keys.crash] ?: true,
            cloudSyncEnabled = p[Keys.cloudSync] ?: true,
            lastSyncAt = p[Keys.lastSync] ?: 0L,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.onboarding] = true }
    suspend fun setGuestMode(enabled: Boolean) = context.dataStore.edit { it[Keys.guest] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setNotificationsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.notifications] = v }
    suspend fun setAnalyticsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.analytics] = v }
    suspend fun setCrashReportsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.crash] = v }
    suspend fun setCloudSyncEnabled(v: Boolean) = context.dataStore.edit { it[Keys.cloudSync] = v }
    suspend fun setLastSyncAt(v: Long) = context.dataStore.edit { it[Keys.lastSync] = v }

    suspend fun lastPull(collection: String): Long = context.dataStore.data.first()[Keys.lastPull(collection)] ?: 0L
    suspend fun setLastPull(collection: String, v: Long) = context.dataStore.edit { it[Keys.lastPull(collection)] = v }

    /** Clears sync cursors (used on sign-out so the next account starts fresh). */
    suspend fun resetSyncState() = context.dataStore.edit { p ->
        p.asMap().keys.filter { it.name.startsWith("last_pull_") }
            .forEach { @Suppress("UNCHECKED_CAST") p.remove(it as Preferences.Key<Any>) }
        p.remove(Keys.lastSync)
    }
}
