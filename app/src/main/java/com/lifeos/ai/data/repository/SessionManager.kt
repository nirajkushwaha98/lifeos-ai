package com.lifeos.ai.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.lifeos.ai.data.local.entity.LOCAL_USER_ID
import com.lifeos.ai.data.remote.firebase.FirebaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val emailVerified: Boolean,
    val isPasswordProvider: Boolean,
)

/**
 * Exposes the current owner of local data. Signed-in users own rows under their Firebase uid;
 * guests and local-only installs use [LOCAL_USER_ID].
 */
@Singleton
class SessionManager @Inject constructor(private val firebase: FirebaseProvider) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _authUser = MutableStateFlow(firebase.auth?.currentUser?.toAuthUser())
    val authUser: StateFlow<AuthUser?> = _authUser.asStateFlow()

    val userId: StateFlow<String> = _authUser.map { it?.uid ?: LOCAL_USER_ID }
        .stateIn(scope, SharingStarted.Eagerly, _authUser.value?.uid ?: LOCAL_USER_ID)

    init {
        firebase.auth?.addAuthStateListener(FirebaseAuth.AuthStateListener {
            _authUser.value = it.currentUser?.toAuthUser()
        })
    }

    fun currentUserId(): String = firebase.auth?.currentUser?.uid ?: LOCAL_USER_ID

    val isSignedIn: Boolean get() = firebase.auth?.currentUser != null

    /** Refreshes the user (e.g. after email verification, which doesn't trigger the auth listener). */
    suspend fun reloadUser(): AuthUser? {
        val user = firebase.auth?.currentUser ?: return null
        runCatching { user.reload().await() }
        val refreshed = firebase.auth?.currentUser?.toAuthUser()
        _authUser.value = refreshed
        return refreshed
    }

    fun refresh() {
        _authUser.value = firebase.auth?.currentUser?.toAuthUser()
    }
}

fun FirebaseUser.toAuthUser() = AuthUser(
    uid = uid,
    email = email,
    displayName = displayName,
    emailVerified = isEmailVerified,
    isPasswordProvider = providerData.any { it.providerId == "password" },
)
