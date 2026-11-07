package com.lifeos.ai.feature.voice

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lifeos.ai.R
import com.lifeos.ai.core.notifications.Notifications
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keeps a voice conversation going while LifeOS is in the background or the screen is locked —
 * Android only lets an app use the microphone there through a foreground service. It does no
 * audio work itself (VoiceViewModel runs the conversation); it shows the "LifeOS Voice is on"
 * notification with an End button, and stops as soon as the conversation ends or pauses.
 */
class VoiceSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            VoiceSessionEvents.requestEnd()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.createChannels(this)
        val end = PendingIntent.getService(
            this, REQUEST_END,
            Intent(this, VoiceSessionService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_VOICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.voice_session_title))
            .setContentText(getString(R.string.voice_session_text))
            .setContentIntent(Notifications.openAppIntent(this, REQUEST_OPEN))
            .addAction(0, getString(R.string.voice_session_end), end)
            // Shows how long the conversation has been running, like a call.
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // E.g. started without mic permission: the conversation still works while the app is open.
            Log.w(TAG, "Voice mode can't continue in the background", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "VoiceSession"
        private const val ACTION_END = "com.lifeos.ai.voice.END"
        private const val NOTIFICATION_ID = 4_201
        private const val REQUEST_OPEN = 4_202
        private const val REQUEST_END = 4_203

        /** Call only while the app is visible and the mic permission is granted. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VoiceSessionService::class.java)) }
                .onFailure { Log.w(TAG, "Couldn't start the voice session service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceSessionService::class.java))
        }
    }
}

/** Lets the notification's End button reach the running conversation. */
object VoiceSessionEvents {
    private val ends = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val endRequests: SharedFlow<Unit> = ends.asSharedFlow()
    fun requestEnd() {
        ends.tryEmit(Unit)
    }
}
