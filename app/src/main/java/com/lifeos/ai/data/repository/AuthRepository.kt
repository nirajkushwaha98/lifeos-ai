package com.lifeos.ai.data.repository

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.SetOptions
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.local.entity.LOCAL_USER_ID
import com.lifeos.ai.data.local.entity.UserEntity
import com.lifeos.ai.data.preferences.UserPreferencesRepository
import com.lifeos.ai.data.remote.firebase.FirebaseProvider
import com.lifeos.ai.data.sync.SyncScheduler
import com.lifeos.ai.feature.reminders.alarm.ReminderScheduler
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthError {
    NOT_CONFIGURED, INVALID_CREDENTIALS, USER_NOT_FOUND, EMAIL_IN_USE, WEAK_PASSWORD,
    NETWORK, TOO_MANY_REQUESTS, RECENT_LOGIN_REQUIRED, GOOGLE_NOT_CONFIGURED, CANCELLED, UNKNOWN
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

@Singleton
class AuthRepository @Inject constructor(
    private val firebase: FirebaseProvider,
    private val session: SessionManager,
    private val db: LifeOSDatabase,
    private val prefs: UserPreferencesRepository,
    private val syncScheduler: SyncScheduler,
    private val reminderScheduler: ReminderScheduler,
    private val localData: LocalDataManager,
) {
    val isFirebaseConfigured: Boolean get() = firebase.isConfigured
    val isGoogleSignInConfigured: Boolean get() = firebase.isConfigured && firebase.googleWebClientId() != null
    fun googleWebClientId(): String? = firebase.googleWebClientId()

    suspend fun signIn(email: String, password: String): AuthResult = guarded {
        val auth = firebase.auth ?: return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        onSignedIn()
    }

    suspend fun register(name: String, email: String, password: String): AuthResult = guarded {
        val auth = firebase.auth ?: return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: return AuthResult.Failure(AuthError.UNKNOWN)
        user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
        runCatching { user.sendEmailVerification().await() }
            .onFailure { Log.w(TAG, "Verification email failed", it) }
        firebase.firestore?.collection("users")?.document(user.uid)?.set(
            mapOf("name" to name.trim(), "email" to user.email, "createdAt" to System.currentTimeMillis()),
            SetOptions.merge(),
        )
        onSignedIn(name.trim())
    }

    suspend fun signInWithGoogleIdToken(idToken: String): AuthResult = guarded {
        val auth = firebase.auth ?: return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = auth.signInWithCredential(credential).await().user
            ?: return AuthResult.Failure(AuthError.UNKNOWN)
        firebase.firestore?.collection("users")?.document(user.uid)?.set(
            mapOf("name" to (user.displayName ?: ""), "email" to user.email),
            SetOptions.merge(),
        )
        onSignedIn()
    }

    suspend fun sendPasswordReset(email: String): AuthResult = guarded {
        val auth = firebase.auth ?: return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        auth.sendPasswordResetEmail(email.trim()).await()
        AuthResult.Success
    }

    suspend fun resendVerification(): AuthResult = guarded {
        val user = firebase.auth?.currentUser ?: return AuthResult.Failure(AuthError.USER_NOT_FOUND)
        user.sendEmailVerification().await()
        AuthResult.Success
    }

    suspend fun updateDisplayName(name: String): AuthResult = guarded {
        val uid = session.currentUserId()
        val existing = db.userDao().get(uid)
        db.userDao().upsert(
            (existing ?: UserEntity(uid, name, firebase.auth?.currentUser?.email, null, System.currentTimeMillis()))
                .copy(name = name)
        )
        firebase.auth?.currentUser?.let { user ->
            user.updateProfile(userProfileChangeRequest { displayName = name }).await()
            firebase.firestore?.collection("users")?.document(user.uid)
                ?.set(mapOf("name" to name), SetOptions.merge())
            session.refresh()
        }
        AuthResult.Success
    }

    suspend fun continueAsGuest() {
        prefs.setGuestMode(true)
    }

    suspend fun signOut() {
        val uid = session.currentUserId()
        if (uid != LOCAL_USER_ID) {
            syncScheduler.cancel()
            reminderScheduler.cancelAllFor(uid)
        }
        firebase.auth?.signOut()
        prefs.setGuestMode(false)
        prefs.resetSyncState()
        reminderScheduler.rescheduleAll()
    }

    /**
     * Deletes the user's cloud data, local data and Firebase account. Firebase requires a recent
     * login for account deletion; in that case [AuthError.RECENT_LOGIN_REQUIRED] is returned and
     * nothing local is removed.
     */
    suspend fun deleteAccount(): AuthResult = guarded {
        val user = firebase.auth?.currentUser
        val uid = session.currentUserId()
        if (user != null) {
            // Firebase only allows deleting accounts that signed in recently (~5 min). Check first
            // so cloud data is never removed while the account itself survives.
            val lastSignIn = user.metadata?.lastSignInTimestamp ?: 0L
            if (System.currentTimeMillis() - lastSignIn > RECENT_LOGIN_WINDOW_MS) {
                return AuthResult.Failure(AuthError.RECENT_LOGIN_REQUIRED)
            }
            syncScheduler.cancel()
            deleteCloudData(user.uid)
            user.delete().await()
        }
        reminderScheduler.cancelAllFor(uid)
        localData.wipeUser(uid)
        prefs.setGuestMode(false)
        prefs.resetSyncState()
        AuthResult.Success
    }

    private suspend fun deleteCloudData(uid: String) {
        val fs = firebase.firestore ?: return
        val userDoc = fs.collection("users").document(uid)
        for (collection in SyncCollections.ALL) {
            while (true) {
                val snap = userDoc.collection(collection).limit(300).get().await()
                if (snap.isEmpty) break
                val batch = fs.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
        userDoc.delete().await()
    }

    private suspend fun onSignedIn(name: String? = null): AuthResult {
        val user = firebase.auth?.currentUser ?: return AuthResult.Failure(AuthError.UNKNOWN)
        session.refresh()
        prefs.setGuestMode(false)
        // Move data created while offline/guest into the account so nothing is lost.
        localData.reassign(LOCAL_USER_ID, user.uid)
        val existing = db.userDao().get(user.uid)
        db.userDao().upsert(
            UserEntity(
                userId = user.uid,
                name = name ?: existing?.name ?: user.displayName ?: "",
                email = user.email,
                profileImage = existing?.profileImage,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        )
        reminderScheduler.rescheduleAll()
        syncScheduler.requestSync(immediate = true)
        firebase.logEvent("login")
        return AuthResult.Success
    }

    private inline fun guarded(block: () -> AuthResult): AuthResult = try {
        block()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "Auth operation failed", e)
        AuthResult.Failure(mapError(e))
    }

    private fun mapError(e: Exception): AuthError = when (e) {
        is FirebaseAuthWeakPasswordException -> AuthError.WEAK_PASSWORD
        is FirebaseAuthInvalidUserException -> AuthError.USER_NOT_FOUND
        is FirebaseAuthInvalidCredentialsException -> AuthError.INVALID_CREDENTIALS
        is FirebaseAuthUserCollisionException -> AuthError.EMAIL_IN_USE
        is FirebaseAuthRecentLoginRequiredException -> AuthError.RECENT_LOGIN_REQUIRED
        is FirebaseNetworkException -> AuthError.NETWORK
        is FirebaseTooManyRequestsException -> AuthError.TOO_MANY_REQUESTS
        else -> AuthError.UNKNOWN
    }

    companion object {
        private const val TAG = "AuthRepository"
        private const val RECENT_LOGIN_WINDOW_MS = 4 * 60 * 1000L
    }
}

object SyncCollections {
    const val TASKS = "tasks"
    const val REMINDERS = "reminders"
    const val EXPENSES = "expenses"
    const val NOTES = "notes"
    val ALL = listOf(TASKS, REMINDERS, EXPENSES, NOTES)
}
