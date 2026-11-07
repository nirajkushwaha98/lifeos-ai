package com.lifeos.ai.feature.reminders.alarm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lifeos.ai.R
import com.lifeos.ai.core.notifications.Notifications
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.local.toDomain
import com.lifeos.ai.data.local.toEntity
import com.lifeos.ai.data.preferences.UserPreferencesRepository
import com.lifeos.ai.data.sync.SyncScheduler
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.usecase.ReminderScheduleCalculator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Fires when a reminder alarm goes off: shows the notification and schedules the next repeat. */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var db: LifeOSDatabase
    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var prefs: UserPreferencesRepository
    @Inject lateinit var syncScheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val pending = goAsync()
        receiverScope.launch {
            try {
                val dao = db.reminderDao()
                val entity = dao.get(id) ?: return@launch
                if (!entity.enabled || entity.completed) return@launch
                val reminder = entity.toDomain()
                val now = System.currentTimeMillis()
                if (prefs.current().notificationsEnabled) showNotification(context, reminder.id, reminder.title, reminder.description)

                val updated = if (reminder.repeatType != RepeatType.NONE) {
                    val next = ReminderScheduleCalculator.nextTrigger(
                        reminder.scheduledAt, reminder.repeatType, reminder.repeatIntervalDays, now,
                    ) ?: reminder.scheduledAt
                    reminder.copy(scheduledAt = next, lastTriggeredAt = now, updatedAt = now)
                } else {
                    reminder.copy(lastTriggeredAt = now, updatedAt = now)
                }
                dao.upsert(updated.toEntity(entity.userId))
                scheduler.schedule(updated, now)
                syncScheduler.requestSync()
            } catch (e: Exception) {
                Log.e("ReminderAlarmReceiver", "Failed to process reminder", e)
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun showNotification(context: Context, id: String, title: String, body: String) {
        if (!Notifications.canPost(context)) return
        val notifId = id.hashCode()
        val doneIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_DONE
            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, id)
            data = android.net.Uri.parse("lifeos://reminder-done/$id")
        }
        val donePi = PendingIntent.getBroadcast(
            context, notifId, doneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.ifBlank { context.getString(R.string.reminder_notification_body) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(Notifications.openAppIntent(context, notifId, "reminders"))
            .addAction(0, context.getString(R.string.action_mark_done), donePi)
            .build()
        NotificationManagerCompat.from(context).notify(notifId, notification)
    }
}

/** Handles the "Mark done" notification action. */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    @Inject lateinit var db: LifeOSDatabase
    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var syncScheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        NotificationManagerCompat.from(context).cancel(id.hashCode())
        val pending = goAsync()
        receiverScope.launch {
            try {
                val entity = db.reminderDao().get(id) ?: return@launch
                // Repeating reminders stay active (the next occurrence is already scheduled);
                // one-time reminders are marked completed.
                if (entity.repeatType == RepeatType.NONE.name) {
                    db.reminderDao().upsert(
                        entity.copy(completed = true, updatedAt = System.currentTimeMillis(), isSynced = false)
                    )
                    scheduler.cancel(id)
                    syncScheduler.requestSync()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val ACTION_DONE = "com.lifeos.ai.action.REMINDER_DONE" }
}

/** Re-registers alarms after reboot, app update, or clock/time-zone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                RescheduleRemindersWorker.enqueue(context)
            }
        }
    }
}

@HiltWorker
class RescheduleRemindersWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        scheduler.rescheduleAll()
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reschedule_reminders",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RescheduleRemindersWorker>().build(),
            )
        }
    }
}
