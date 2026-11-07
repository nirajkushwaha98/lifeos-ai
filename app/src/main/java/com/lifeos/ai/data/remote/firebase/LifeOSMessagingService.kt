package com.lifeos.ai.data.remote.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lifeos.ai.R
import com.lifeos.ai.core.notifications.Notifications

/**
 * Firebase Cloud Messaging entry point (product announcements / re-engagement pushes sent from
 * the Firebase console). Only active when Firebase is configured.
 */
class LifeOSMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: return
        runCatching {
            FirebaseFirestore.getInstance().collection("users").document(uid)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: return
        Notifications.showGeneral(this, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), title, body)
    }
}
