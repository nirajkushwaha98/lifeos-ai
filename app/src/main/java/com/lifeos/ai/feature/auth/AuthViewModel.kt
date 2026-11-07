package com.lifeos.ai.feature.auth

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.lifeos.ai.R
import com.lifeos.ai.data.repository.AuthError
import com.lifeos.ai.data.repository.AuthRepository
import com.lifeos.ai.data.repository.AuthResult
import com.lifeos.ai.data.repository.SessionManager
import com.lifeos.ai.domain.usecase.AuthValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    @StringRes val nameError: Int? = null,
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,
    @StringRes val confirmError: Int? = null,
    @StringRes val generalError: Int? = null,
    @StringRes val info: Int? = null,
    val loading: Boolean = false,
)

sealed interface AuthEvent {
    data object SignedIn : AuthEvent
    data object NeedsVerification : AuthEvent
    data object Registered : AuthEvent
    data object Verified : AuthEvent
    data object SignedOut : AuthEvent
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repo: AuthRepository,
    private val session: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _events = MutableStateFlow<AuthEvent?>(null)
    val events: StateFlow<AuthEvent?> = _events.asStateFlow()
    fun consumeEvent() { _events.value = null }

    val firebaseConfigured: Boolean get() = repo.isFirebaseConfigured
    val googleConfigured: Boolean get() = repo.isGoogleSignInConfigured
    val currentEmail: String? get() = session.authUser.value?.email

    fun onName(v: String) = _state.update { it.copy(name = v, nameError = null, generalError = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v, emailError = null, generalError = null, info = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, generalError = null) }
    fun onConfirm(v: String) = _state.update { it.copy(confirmPassword = v, confirmError = null) }

    fun login() {
        val s = _state.value
        val emailErr = emailMessage(AuthValidator.validateEmail(s.email))
        val pwErr = if (AuthValidator.validateLoginPassword(s.password) != null) R.string.error_password_required else null
        _state.update { it.copy(emailError = emailErr, passwordError = pwErr) }
        if (emailErr != null || pwErr != null) return
        run({ repo.signIn(s.email, s.password) }) {
            val user = session.authUser.value
            _events.value = if (user != null && user.isPasswordProvider && !user.emailVerified) AuthEvent.NeedsVerification else AuthEvent.SignedIn
        }
    }

    fun register() {
        val s = _state.value
        val nameErr = when (AuthValidator.validateName(s.name)) {
            AuthValidator.NameError.EMPTY -> R.string.error_name_required
            AuthValidator.NameError.TOO_LONG -> R.string.error_name_too_long
            null -> null
        }
        val emailErr = emailMessage(AuthValidator.validateEmail(s.email))
        val pwErr = when (AuthValidator.validateNewPassword(s.password)) {
            AuthValidator.PasswordError.EMPTY -> R.string.error_password_required
            AuthValidator.PasswordError.TOO_SHORT -> R.string.error_password_short
            AuthValidator.PasswordError.NO_LETTER, AuthValidator.PasswordError.NO_DIGIT -> R.string.error_password_weak
            null -> null
        }
        val confirmErr = if (!AuthValidator.passwordsMatch(s.password, s.confirmPassword)) R.string.error_password_mismatch else null
        _state.update { it.copy(nameError = nameErr, emailError = emailErr, passwordError = pwErr, confirmError = confirmErr) }
        if (listOf(nameErr, emailErr, pwErr, confirmErr).any { it != null }) return
        run({ repo.register(s.name, s.email, s.password) }) { _events.value = AuthEvent.Registered }
    }

    fun sendReset() {
        val s = _state.value
        val emailErr = emailMessage(AuthValidator.validateEmail(s.email))
        _state.update { it.copy(emailError = emailErr) }
        if (emailErr != null) return
        run({ repo.sendPasswordReset(s.email) }) { _state.update { it.copy(info = R.string.reset_email_sent) } }
    }

    fun resendVerification() = run({ repo.resendVerification() }) {
        _state.update { it.copy(info = R.string.verification_sent) }
    }

    fun checkVerified() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, info = null, generalError = null) }
            val user = session.reloadUser()
            _state.update { it.copy(loading = false) }
            if (user?.emailVerified == true) _events.value = AuthEvent.Verified
            else _state.update { it.copy(generalError = R.string.error_not_verified_yet) }
        }
    }

    fun continueAsGuest() {
        viewModelScope.launch {
            repo.continueAsGuest()
            _events.value = AuthEvent.SignedIn
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repo.signOut()
            _events.value = AuthEvent.SignedOut
        }
    }

    /** Google sign-in via Credential Manager. Requires the activity context for the bottom sheet. */
    fun signInWithGoogle(activityContext: Context) {
        val clientId = repo.googleWebClientId()
        if (clientId == null) {
            _state.update { it.copy(generalError = R.string.error_google_not_configured) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, generalError = null) }
            try {
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val response = CredentialManager.create(activityContext).getCredential(activityContext, request)
                val credential = response.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    handle(repo.signInWithGoogleIdToken(token)) { _events.value = AuthEvent.SignedIn }
                } else {
                    _state.update { it.copy(loading = false, generalError = R.string.error_generic) }
                }
            } catch (e: GetCredentialCancellationException) {
                _state.update { it.copy(loading = false) }
            } catch (e: GetCredentialException) {
                Log.w("AuthViewModel", "Google credential error", e)
                _state.update { it.copy(loading = false, generalError = R.string.error_google_failed) }
            }
        }
    }

    private fun run(block: suspend () -> AuthResult, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, generalError = null, info = null) }
            handle(block(), onSuccess)
        }
    }

    private fun handle(result: AuthResult, onSuccess: () -> Unit) {
        when (result) {
            AuthResult.Success -> { _state.update { it.copy(loading = false) }; onSuccess() }
            is AuthResult.Failure -> _state.update { it.copy(loading = false, generalError = authErrorMessage(result.error)) }
        }
    }

    private fun emailMessage(e: AuthValidator.EmailError?): Int? = when (e) {
        AuthValidator.EmailError.EMPTY -> R.string.error_email_required
        AuthValidator.EmailError.INVALID -> R.string.error_email_invalid
        null -> null
    }
}

@StringRes
fun authErrorMessage(error: AuthError): Int = when (error) {
    AuthError.NOT_CONFIGURED -> R.string.error_firebase_not_configured
    AuthError.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
    AuthError.USER_NOT_FOUND -> R.string.error_user_not_found
    AuthError.EMAIL_IN_USE -> R.string.error_email_in_use
    AuthError.WEAK_PASSWORD -> R.string.error_password_weak
    AuthError.NETWORK -> R.string.error_network
    AuthError.TOO_MANY_REQUESTS -> R.string.error_too_many_requests
    AuthError.RECENT_LOGIN_REQUIRED -> R.string.error_recent_login
    AuthError.GOOGLE_NOT_CONFIGURED -> R.string.error_google_not_configured
    AuthError.CANCELLED -> R.string.error_generic
    AuthError.UNKNOWN -> R.string.error_generic
}
