package com.lifeos.ai

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lifeos.ai.core.notifications.Notifications
import com.lifeos.ai.data.preferences.UserPreferencesRepository
import com.lifeos.ai.data.remote.firebase.FirebaseProvider
import com.lifeos.ai.data.sync.SyncScheduler
import com.lifeos.ai.feature.reminders.alarm.RescheduleRemindersWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LifeOSApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var firebase: FirebaseProvider
    @Inject lateinit var prefs: UserPreferencesRepository
    @Inject lateinit var syncScheduler: SyncScheduler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // Keep alarms consistent if the process was killed or the app was updated.
        RescheduleRemindersWorker.enqueue(this)
        appScope.launch {
            prefs.settings.map { it.analyticsEnabled to it.crashReportsEnabled }
                .distinctUntilChanged()
                .collect { (analytics, crash) -> firebase.applyPrivacySettings(analytics, crash) }
        }
        syncScheduler.requestSync()
    }
}
