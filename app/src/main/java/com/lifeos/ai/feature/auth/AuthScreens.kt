package com.lifeos.ai.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.ai.R
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.GradientButton
import com.lifeos.ai.feature.splash.LifeOSLogo

@Composable
private fun AuthMessages(state: AuthUiState) {
    state.generalError?.let {
        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
    state.info?.let {
        Text(stringResource(it), color = LocalLifeColors.current.success, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun NotConfiguredBanner() {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.firebase_missing_title), style = MaterialTheme.typography.titleSmall, color = LifeColors.Cyan)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.firebase_missing_body), style = MaterialTheme.typography.bodySmall, color = LocalLifeColors.current.textSecondary)
    }
}

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onNeedsVerification: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(event) {
        when (event) {
            AuthEvent.SignedIn -> onLoggedIn()
            AuthEvent.NeedsVerification -> onNeedsVerification()
            else -> Unit
        }
        if (event != null) viewModel.consumeEvent()
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        LifeOSLogo(72.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.login_subtitle), color = LocalLifeColors.current.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (!viewModel.firebaseConfigured) { NotConfiguredBanner(); Spacer(Modifier.height(16.dp)) }
        CustomTextField(
            state.email, viewModel::onEmail, stringResource(R.string.field_email), leadingIcon = Icons.Filled.Email,
            error = state.emailError?.let { stringResource(it) }, keyboardType = KeyboardType.Email,
        )
        Spacer(Modifier.height(8.dp))
        CustomTextField(
            state.password, viewModel::onPassword, stringResource(R.string.field_password), leadingIcon = Icons.Filled.Lock,
            error = state.passwordError?.let { stringResource(it) }, isPassword = true, keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done, onImeAction = viewModel::login,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onForgotPassword) { Text(stringResource(R.string.forgot_password)) }
        }
        AuthMessages(state)
        GradientButton(stringResource(R.string.action_login), viewModel::login, Modifier.fillMaxWidth(), loading = state.loading)
        Spacer(Modifier.height(12.dp))
        if (viewModel.googleConfigured) {
            OutlinedButton(onClick = { viewModel.signInWithGoogle(context) }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !state.loading) {
                Text(stringResource(R.string.action_google_sign_in))
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text(stringResource(R.string.or), Modifier.padding(horizontal = 12.dp), color = LocalLifeColors.current.textSecondary)
            HorizontalDivider(Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = viewModel::continueAsGuest, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.action_continue_guest))
        }
        Text(
            stringResource(R.string.guest_explainer), style = MaterialTheme.typography.bodySmall,
            color = LocalLifeColors.current.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.no_account))
            TextButton(onClick = onRegister) { Text(stringResource(R.string.action_register)) }
        }
    }
}

@Composable
fun RegisterScreen(onRegistered: () -> Unit, onBack: () -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    LaunchedEffect(event) {
        if (event == AuthEvent.Registered) { viewModel.consumeEvent(); onRegistered() }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        CustomTopBar(stringResource(R.string.register_title), onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.register_subtitle), color = LocalLifeColors.current.textSecondary)
            Spacer(Modifier.height(20.dp))
            if (!viewModel.firebaseConfigured) { NotConfiguredBanner(); Spacer(Modifier.height(16.dp)) }
            CustomTextField(state.name, viewModel::onName, stringResource(R.string.field_name), leadingIcon = Icons.Filled.Person,
                error = state.nameError?.let { stringResource(it) }, maxLength = 50)
            Spacer(Modifier.height(8.dp))
            CustomTextField(state.email, viewModel::onEmail, stringResource(R.string.field_email), leadingIcon = Icons.Filled.Email,
                error = state.emailError?.let { stringResource(it) }, keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(8.dp))
            CustomTextField(state.password, viewModel::onPassword, stringResource(R.string.field_password), leadingIcon = Icons.Filled.Lock,
                error = state.passwordError?.let { stringResource(it) }, isPassword = true, keyboardType = KeyboardType.Password)
            Text(stringResource(R.string.password_rules), style = MaterialTheme.typography.bodySmall, color = LocalLifeColors.current.textSecondary)
            Spacer(Modifier.height(8.dp))
            CustomTextField(state.confirmPassword, viewModel::onConfirm, stringResource(R.string.field_confirm_password), leadingIcon = Icons.Filled.Lock,
                error = state.confirmError?.let { stringResource(it) }, isPassword = true, keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done, onImeAction = viewModel::register)
            AuthMessages(state)
            Spacer(Modifier.height(8.dp))
            GradientButton(stringResource(R.string.action_create_account), viewModel::register, Modifier.fillMaxWidth(), loading = state.loading)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        CustomTopBar(stringResource(R.string.forgot_title), onBack = onBack)
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.forgot_subtitle), color = LocalLifeColors.current.textSecondary)
            Spacer(Modifier.height(20.dp))
            CustomTextField(state.email, viewModel::onEmail, stringResource(R.string.field_email), leadingIcon = Icons.Filled.Email,
                error = state.emailError?.let { stringResource(it) }, keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done, onImeAction = viewModel::sendReset)
            AuthMessages(state)
            Spacer(Modifier.height(8.dp))
            GradientButton(stringResource(R.string.action_send_reset), viewModel::sendReset, Modifier.fillMaxWidth(), loading = state.loading)
        }
    }
}

@Composable
fun VerifyEmailScreen(onVerified: () -> Unit, onSignedOut: () -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    LaunchedEffect(event) {
        when (event) {
            AuthEvent.Verified -> onVerified()
            AuthEvent.SignedOut -> onSignedOut()
            else -> Unit
        }
        if (event != null) viewModel.consumeEvent()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.MarkEmailRead, null, tint = LifeColors.Cyan, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.verify_body, viewModel.currentEmail ?: ""),
                textAlign = TextAlign.Center, color = LocalLifeColors.current.textSecondary,
            )
            Spacer(Modifier.height(20.dp))
            AuthMessages(state)
            GradientButton(stringResource(R.string.action_i_verified), viewModel::checkVerified, Modifier.fillMaxWidth(), loading = state.loading)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::resendVerification, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) {
                Text(stringResource(R.string.action_resend_email))
            }
            TextButton(onClick = onVerified) { Text(stringResource(R.string.action_verify_later)) }
            TextButton(onClick = viewModel::signOut) { Text(stringResource(R.string.action_logout)) }
        }
    }
}
