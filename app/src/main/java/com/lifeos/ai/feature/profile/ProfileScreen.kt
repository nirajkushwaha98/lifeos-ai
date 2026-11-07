package com.lifeos.ai.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lifeos.ai.R
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors
import com.lifeos.ai.core.ui.ConfirmationDialog
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.FullScreenLoading
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.IconBadge
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.data.repository.AuthRepository
import com.lifeos.ai.data.repository.ProfileRepository
import com.lifeos.ai.domain.model.UserProfile
import com.lifeos.ai.domain.repository.NoteRepository
import com.lifeos.ai.domain.repository.ReminderRepository
import com.lifeos.ai.domain.repository.TaskRepository
import com.lifeos.ai.domain.usecase.AuthValidator
import com.lifeos.ai.feature.home.Avatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileStats(val tasks: Int = 0, val reminders: Int = 0, val notes: Int = 0)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepo: ProfileRepository,
    private val auth: AuthRepository,
    tasks: TaskRepository,
    reminders: ReminderRepository,
    notes: NoteRepository,
) : ViewModel() {
    val profile: StateFlow<UserProfile?> = profileRepo.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val stats: StateFlow<ProfileStats> = combine(tasks.observeTasks(), reminders.observeReminders(), notes.observeNotes()) { t, r, n ->
        ProfileStats(t.count { it.completed }, r.size, n.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileStats())

    private val _signedOut = Channel<Unit>(Channel.CONFLATED)
    val signedOut = _signedOut.receiveAsFlow()

    fun setPhoto(uri: android.net.Uri) = viewModelScope.launch { runCatching { profileRepo.setPhoto(uri) } }

    fun setName(name: String) = viewModelScope.launch {
        if (auth.isFirebaseConfigured && profile.value?.isGuest == false) auth.updateDisplayName(name.trim())
        else profileRepo.setLocalName(name.trim())
    }

    fun logout() = viewModelScope.launch { auth.signOut(); _signedOut.send(Unit) }
}

@Composable
fun ProfileScreen(navigate: (Any) -> Unit, onSignedOut: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var editName by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhoto) }
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.signedOut.collect { onSignedOut() } }

    val p = profile ?: run { FullScreenLoading(); return }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        CustomTopBar(stringResource(R.string.tab_profile), actions = {
            IconButton(onClick = { navigate(Routes.Settings) }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title)) }
        })
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(p, 104) { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(stringResource(R.string.profile_change_photo))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name.ifBlank { stringResource(R.string.profile_add_name) }, style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { editName = p.name }) { Icon(Icons.Outlined.Edit, stringResource(R.string.profile_edit_name)) }
            }
            if (p.isGuest) {
                SecondaryText(stringResource(R.string.profile_guest))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryText(p.email.orEmpty())
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (p.emailVerified) Icons.Outlined.Verified else Icons.Outlined.Warning, null,
                        tint = if (p.emailVerified) LocalLifeColors.current.success else LifeColors.Warning,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(stats.tasks, stringResource(R.string.profile_tasks_done), Modifier.weight(1f))
            StatCard(stats.reminders, stringResource(R.string.reminders_title), Modifier.weight(1f))
            StatCard(stats.notes, stringResource(R.string.notes_title), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (p.isGuest) {
                NavRow(Icons.AutoMirrored.Outlined.Login, stringResource(R.string.profile_sign_in_to_sync)) { confirmLogout = true }
            }
            NavRow(Icons.Outlined.Settings, stringResource(R.string.settings_title)) { navigate(Routes.Settings) }
            NavRow(Icons.Outlined.Policy, stringResource(R.string.privacy_policy)) { navigate(Routes.PrivacyPolicy) }
            if (!p.isGuest) NavRow(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.action_logout)) { confirmLogout = true }
        }
        Spacer(Modifier.height(24.dp))
    }

    editName?.let { value ->
        val error = AuthValidator.validateName(value)
        AlertDialog(
            onDismissRequest = { editName = null },
            title = { Text(stringResource(R.string.profile_edit_name)) },
            text = { CustomTextField(value, { editName = it }, stringResource(R.string.field_name), maxLength = 50) },
            confirmButton = { TextButton(enabled = error == null, onClick = { viewModel.setName(value); editName = null }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { editName = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmLogout) ConfirmationDialog(
        title = stringResource(if (p.isGuest) R.string.profile_sign_in_to_sync else R.string.action_logout),
        message = stringResource(if (p.isGuest) R.string.exit_guest_body else R.string.logout_body),
        confirmLabel = stringResource(R.string.action_continue),
        onConfirm = { confirmLogout = false; viewModel.logout() }, onDismiss = { confirmLogout = false },
    )
}

@Composable
private fun StatCard(value: Int, label: String, modifier: Modifier) {
    GlassCard(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = LifeColors.Cyan)
        SecondaryText(label, maxLines = 1)
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, LifeColors.Blue, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
    }
}
