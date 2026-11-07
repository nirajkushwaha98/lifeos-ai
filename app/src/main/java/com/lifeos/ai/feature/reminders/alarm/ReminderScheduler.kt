package com.lifeos.ai.feature.reminders.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.local.entity.LOCAL_USER_ID
import com.lifeos.ai.data.local.toDomain
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.usecase.ReminderScheduleCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Schedules reminder alarms with AlarmManager. Exact alarms are used when the user/OS allows
 * them (Android 12+ requires the "Alarms & reminders" special access); otherwise an inexact
 * while-idle alarm is used, which may be delivered a few minutes late.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dbProvider: Provider<LifeOSDatabase>,
    private val sessionUserId: Provider<com.lifeos.ai.data.repository.SessionManager>,
) {
    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Schedules (or cancels) the alarm for [reminder]. Returns the trigger time, or null if none. */
    fun schedule(reminder: Reminder, now: Long = System.currentTimeMillis()): Long? {
        cancel(reminder.id)
        if (!reminder.enabled || reminder.completed) return null
        val trigger = ReminderScheduleCalculator.nextTrigger(
            reminder.scheduledAt, reminder.repeatType, reminder.repeatIntervalDays, now,
        ) ?: return null
        val pi = pendingIntent(reminder.id, create = true) ?: return null
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Exact alarm not permitted, falling back to inexact", se)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
        return trigger
    }

    fun cancel(reminderId: String) {
        pendingIntent(reminderId, create = false)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /** Re-registers alarms for the current user's active reminders (boot, time change, login). */
    suspend fun rescheduleAll() {
        val userId = sessionUserId.get().currentUserId()
        val dao = dbProvider.get().reminderDao()
        dao.allActive().forEach { entity ->
            if (entity.userId == userId) schedule(entity.toDomain()) else cancel(entity.reminderId)
        }
    }

    suspend fun cancelAllFor(userId: String) {
        dbProvider.get().reminderDao().all(userId).forEach { cancel(it.reminderId) }
    }

    private fun pendingIntent(reminderId: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_REMINDER_ID, reminderId)
            // Unique data uri so each reminder has a distinct PendingIntent.
            data = android.net.Uri.parse("lifeos://reminder/$reminderId")
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, reminderId.hashCode(), intent, flags)
    }

    companion object {
        private const val TAG = "ReminderScheduler"
        const val ACTION_FIRE = "com.lifeos.ai.action.REMINDER_FIRE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        @Suppress("unused") private const val GUEST = LOCAL_USER_ID
    }
}
