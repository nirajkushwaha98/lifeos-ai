package com.lifeos.ai.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lifeos.ai.R
import com.lifeos.ai.core.ads.AdsManager
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.ThemeMode
import com.lifeos.ai.core.ui.ConfirmationDialog
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.IconBadge
import com.lifeos.ai.core.ui.MarkdownText
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.core.ui.SectionHeader
import com.lifeos.ai.data.local.files.PrivateFileStore
import com.lifeos.ai.data.preferences.AppSettings
import com.lifeos.ai.data.preferences.UserPreferencesRepository
import com.lifeos.ai.data.repository.AuthRepository
import com.lifeos.ai.data.repository.AuthResult
import com.lifeos.ai.data.repository.SessionManager
import com.lifeos.ai.data.sync.SyncScheduler
import com.lifeos.ai.feature.auth.authErrorMessage
import com.lifeos.ai.feature.reminders.alarm.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    private val auth: AuthRepository,
    private val session: SessionManager,
    private val sync: SyncScheduler,
    private val files: PrivateFileStore,
    private val exporter: DataExporter,
    val reminderScheduler: ReminderScheduler,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val signedIn: Boolean get() = session.isSignedIn
    val firebaseConfigured: Boolean get() = auth.isFirebaseConfigured

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _signedOut = Channel<Unit>(Channel.CONFLATED)
    val signedOut = _signedOut.receiveAsFlow()

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { prefs.setNotificationsEnabled(v) }
    fun setAnalytics(v: Boolean) = viewModelScope.launch { prefs.setAnalyticsEnabled(v) }
    fun setCrash(v: Boolean) = viewModelScope.launch { prefs.setCrashReportsEnabled(v) }
    fun setCloudSync(v: Boolean) = viewModelScope.launch { prefs.setCloudSyncEnabled(v); if (v) sync.requestSync(immediate = true) }

    fun syncNow() {
        if (!firebaseConfigured || !signedIn) { viewModelScope.launch { _messages.send(context.getString(R.string.sync_requires_sign_in)) }; return }
        sync.requestSync(immediate = true)
        viewModelScope.launch { _messages.send(context.getString(R.string.sync_started)) }
    }

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching { exporter.exportTo(uri) }
            .onSuccess { _messages.send(context.getString(R.string.export_done, it)) }
            .onFailure { _messages.send(context.getString(R.string.export_failed)) }
    }

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) { files.cacheSize() }

    fun clearCache() = viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) { files.clearCache() }
        _messages.send(context.getString(R.string.cache_cleared, formatBytes(freed)))
    }

    fun logout() = viewModelScope.launch { auth.signOut(); _signedOut.send(Unit) }

    fun deleteAccount() = viewModelScope.launch {
        when (val r = auth.deleteAccount()) {
            AuthResult.Success -> _signedOut.send(Unit)
            is AuthResult.Failure -> _messages.send(context.getString(authErrorMessage(r.error)))
        }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onPrivacyPolicy: () -> Unit, onSignedOut: () -> Unit, adsManager: AdsManager, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val privacyOptionsRequired by adsManager.privacyOptionsRequired.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cacheVersion by remember { mutableStateOf(0) }
    val cacheSize by produceState(0L, cacheVersion) { value = viewModel.cacheSize() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::export) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.signedOut.collect { onSignedOut() } }

    androidx.compose.material3.Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { Column(Modifier.statusBarsPadding()) { CustomTopBar(stringResource(R.string.settings_title), onBack = onBack) } },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            SectionHeader(stringResource(R.string.settings_appearance))
            GlassCard(Modifier.fillMaxWidth()) {
                RowLabel(Icons.Outlined.DarkMode, stringResource(R.string.settings_theme))
                Row {
                    listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark).forEach { (m, l) ->
                        FilterChip(settings.themeMode == m, { viewModel.setTheme(m) }, { Text(stringResource(l)) }, Modifier.padding(end = 8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                RowLabel(Icons.Outlined.Language, stringResource(R.string.settings_language))
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                Row {
                    listOf("" to R.string.lang_system, "en" to R.string.lang_english, "hi" to R.string.lang_hindi).forEach { (tag, l) ->
                        FilterChip(current.startsWith(tag) && (tag.isNotEmpty() || current.isEmpty()), {
                            AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                        }, { Text(stringResource(l)) }, Modifier.padding(end = 8.dp))
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_notifications))
            GlassCard(Modifier.fillMaxWidth()) {
                ToggleRow(Icons.Outlined.Notifications, stringResource(R.string.settings_reminder_notifications), settings.notificationsEnabled, viewModel::setNotifications)
                ActionRow(Icons.Outlined.Notifications, stringResource(R.string.settings_system_notifications)) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActionRow(Icons.Outlined.Alarm, stringResource(R.string.settings_exact_alarms),
                        subtitle = stringResource(if (viewModel.reminderScheduler.canScheduleExact()) R.string.status_allowed else R.string.status_not_allowed)) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_privacy))
            GlassCard(Modifier.fillMaxWidth()) {
                ToggleRow(Icons.Outlined.Analytics, stringResource(R.string.settings_analytics), settings.analyticsEnabled, viewModel::setAnalytics,
                    subtitle = stringResource(R.string.settings_analytics_sub))
                ToggleRow(Icons.Outlined.BugReport, stringResource(R.string.settings_crash), settings.crashReportsEnabled, viewModel::setCrash,
                    subtitle = stringResource(R.string.settings_crash_sub))
                if (privacyOptionsRequired) {
                    ActionRow(Icons.Outlined.PrivacyTip, stringResource(R.string.settings_ad_privacy)) {
                        (context as? Activity)?.let { adsManager.showPrivacyOptions(it) }
                    }
                }
                ActionRow(Icons.Outlined.Policy, stringResource(R.string.privacy_policy), onClick = onPrivacyPolicy)
            }

            SectionHeader(stringResource(R.string.settings_data))
            GlassCard(Modifier.fillMaxWidth()) {
                ToggleRow(Icons.Outlined.CloudSync, stringResource(R.string.settings_cloud_sync), settings.cloudSyncEnabled, viewModel::setCloudSync,
                    subtitle = if (settings.lastSyncAt > 0) stringResource(R.string.last_synced, TimeUtils.formatDateTime(settings.lastSyncAt))
                    else stringResource(if (viewModel.signedIn) R.string.never_synced else R.string.sync_requires_sign_in))
                ActionRow(Icons.Outlined.CloudSync, stringResource(R.string.settings_backup_now), onClick = viewModel::syncNow)
                ActionRow(Icons.Outlined.FileDownload, stringResource(R.string.settings_export), subtitle = stringResource(R.string.settings_export_sub)) {
                    exportLauncher.launch("lifeos-export-${java.time.LocalDate.now()}.json")
                }
                ActionRow(Icons.Outlined.CleaningServices, stringResource(R.string.settings_clear_cache), subtitle = formatBytes(cacheSize)) {
                    viewModel.clearCache(); cacheVersion++
                }
            }

            SectionHeader(stringResource(R.string.settings_account))
            GlassCard(Modifier.fillMaxWidth()) {
                ActionRow(Icons.AutoMirrored.Outlined.Logout, stringResource(if (viewModel.signedIn) R.string.action_logout else R.string.action_exit_guest)) { confirmLogout = true }
                ActionRow(Icons.Outlined.DeleteForever, stringResource(R.string.settings_delete_account), subtitle = stringResource(R.string.settings_delete_account_sub), danger = true) {
                    confirmDelete = true
                }
            }
            Spacer(Modifier.height(12.dp))
            SecondaryText(stringResource(R.string.app_version, com.lifeos.ai.BuildConfig.VERSION_NAME), Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmLogout) ConfirmationDialog(
        title = stringResource(R.string.action_logout), message = stringResource(if (viewModel.signedIn) R.string.logout_body else R.string.exit_guest_body),
        confirmLabel = stringResource(R.string.action_logout), onConfirm = { confirmLogout = false; viewModel.logout() }, onDismiss = { confirmLogout = false },
    )
    if (confirmDelete) ConfirmationDialog(
        title = stringResource(R.string.settings_delete_account), message = stringResource(R.string.delete_account_body),
        confirmLabel = stringResource(R.string.action_delete_everything), destructive = true,
        onConfirm = { confirmDelete = false; viewModel.deleteAccount() }, onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun RowLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Icon(icon, null, tint = LifeColors.Cyan); Spacer(Modifier.width(10.dp)); Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, LifeColors.Blue, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { SecondaryText(it) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, if (danger) MaterialTheme.colorScheme.error else LifeColors.Purple, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            subtitle?.let { SecondaryText(it) }
        }
    }
}

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("privacy_policy.md").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        CustomTopBar(stringResource(R.string.privacy_policy), onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            MarkdownText(text)
        }
    }
}
