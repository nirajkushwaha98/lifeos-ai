package com.lifeos.ai.feature.reminders

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lifeos.ai.R
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.common.newId
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.notifications.Notifications
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.ui.ConfirmationDialog
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.DateTimeRow
import com.lifeos.ai.core.ui.EmptyState
import com.lifeos.ai.core.ui.FullScreenLoading
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.GradientButton
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.LocalDatePickerDialog
import com.lifeos.ai.core.ui.LocalTimePickerDialog
import com.lifeos.ai.core.ui.ReminderCard
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.core.ui.SectionHeader
import com.lifeos.ai.core.ui.repeatLabel
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.repository.ReminderRepository
import com.lifeos.ai.feature.reminders.alarm.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

// ============================================================ permissions banner

@Composable
fun ReminderPermissionBanner(scheduler: ReminderScheduler) {
    val context = LocalContext.current
    var notifGranted by remember { mutableStateOf(Notifications.canPost(context)) }
    var exactAllowed by remember { mutableStateOf(scheduler.canScheduleExact()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = Notifications.canPost(context) }
    // Refresh when returning from system settings.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notifGranted = Notifications.canPost(context)
        exactAllowed = scheduler.canScheduleExact()
        onPauseOrDispose { }
    }
    if (notifGranted && exactAllowed) return
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = LifeColors.Warning)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.reminder_permission_title), style = MaterialTheme.typography.titleSmall)
        }
        if (!notifGranted) {
            SecondaryText(stringResource(R.string.reminder_permission_notifications))
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(stringResource(R.string.action_allow_notifications)) }
        }
        if (!exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SecondaryText(stringResource(R.string.reminder_permission_exact))
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.action_allow_exact_alarms)) }
        }
    }
}

// ============================================================ list

data class RemindersUiState(
    val loading: Boolean = true,
    val upcoming: List<Reminder> = emptyList(),
    val past: List<Reminder> = emptyList(),
)

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val repo: ReminderRepository,
    val scheduler: ReminderScheduler,
) : ViewModel() {
    val state: StateFlow<RemindersUiState> = repo.observeReminders().map { list ->
        val now = System.currentTimeMillis()
        val (active, done) = list.partition { !it.completed && (it.scheduledAt >= now || it.repeatType != RepeatType.NONE) }
        RemindersUiState(false, active.sortedBy { it.scheduledAt }, done.sortedByDescending { it.scheduledAt })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemindersUiState())

    fun setEnabled(r: Reminder, enabled: Boolean) = viewModelScope.launch { repo.setEnabled(r.id, enabled) }
}

@Composable
fun RemindersScreen(onBack: () -> Unit, navigate: (Any) -> Unit, viewModel: RemindersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { Column(Modifier.statusBarsPadding()) { CustomTopBar(stringResource(R.string.reminders_title), onBack = onBack) } },
        floatingActionButton = {
            FloatingActionButton(onClick = { navigate(Routes.ReminderEditor()) }, containerColor = LifeColors.Purple, modifier = Modifier.navigationBarsPadding()) {
                Icon(Icons.Filled.Add, stringResource(R.string.qa_add_reminder), tint = Color.White)
            }
        },
    ) { padding ->
        if (state.loading) { FullScreenLoading(); return@Scaffold }
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { ReminderPermissionBanner(viewModel.scheduler) }
            if (state.upcoming.isEmpty() && state.past.isEmpty()) {
                item {
                    EmptyState(
                        LifeIcons.Reminder, stringResource(R.string.reminders_empty_title), stringResource(R.string.reminders_empty_body),
                        actionLabel = stringResource(R.string.qa_add_reminder), onAction = { navigate(Routes.ReminderEditor()) },
                    )
                }
            }
            if (state.upcoming.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.reminders_upcoming)) }
                items(state.upcoming, key = { it.id }) { r ->
                    ReminderCard(r, onClick = { navigate(Routes.ReminderEditor(r.id)) }, onToggleEnabled = { viewModel.setEnabled(r, it) })
                }
            }
            if (state.past.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.reminders_past)) }
                items(state.past, key = { it.id }) { r ->
                    ReminderCard(r, onClick = { navigate(Routes.ReminderEditor(r.id)) }, onToggleEnabled = { viewModel.setEnabled(r, it) })
                }
            }
        }
    }
}

// ============================================================ editor

data class ReminderEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val description: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0),
    val repeat: RepeatType = RepeatType.NONE,
    val intervalDays: String = "2",
    val enabled: Boolean = true,
    val completed: Boolean = false,
    val titleError: Boolean = false,
    val pastError: Boolean = false,
    val intervalError: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class ReminderEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ReminderRepository,
    val scheduler: ReminderScheduler,
) : ViewModel() {
    private val id: String? = savedStateHandle.toRoute<Routes.ReminderEditor>().reminderId
    private var original: Reminder? = null
    private val _state = MutableStateFlow(ReminderEditorState())
    val state: StateFlow<ReminderEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val r = id?.let { repo.getReminder(it) }
            original = r
            _state.value = if (r == null) ReminderEditorState(loading = false) else {
                val dt = TimeUtils.toLocalDateTime(r.scheduledAt)
                ReminderEditorState(
                    loading = false, isNew = false, title = r.title, description = r.description,
                    date = dt.toLocalDate(), time = dt.toLocalTime(), repeat = r.repeatType,
                    intervalDays = r.repeatIntervalDays.toString(), enabled = r.enabled, completed = r.completed,
                )
            }
        }
    }

    fun update(t: (ReminderEditorState) -> ReminderEditorState) = _state.update { t(it).copy(error = null, pastError = false) }

    fun save() {
        val s = _state.value
        val interval = s.intervalDays.toIntOrNull()
        val at = TimeUtils.toEpoch(s.date, s.time)
        val titleErr = s.title.isBlank()
        val intervalErr = s.repeat == RepeatType.CUSTOM && (interval == null || interval !in 1..365)
        // One-time reminders must be in the future (unless already completed).
        val pastErr = s.repeat == RepeatType.NONE && !s.completed && at <= System.currentTimeMillis()
        _state.update { it.copy(titleError = titleErr, intervalError = intervalErr, pastError = pastErr) }
        if (titleErr || intervalErr || pastErr) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val now = System.currentTimeMillis()
            val base = original ?: Reminder(id = newId(), title = "", scheduledAt = at, createdAt = now, updatedAt = now)
            runCatching {
                repo.upsert(
                    base.copy(
                        title = s.title, description = s.description, scheduledAt = at, repeatType = s.repeat,
                        repeatIntervalDays = if (s.repeat == RepeatType.CUSTOM) interval!! else 1,
                        enabled = s.enabled, completed = s.completed,
                    )
                )
            }.onSuccess { _state.update { it.copy(saving = false, done = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }

    fun delete() {
        val rid = id ?: return
        viewModelScope.launch { repo.delete(rid); _state.update { it.copy(done = true) } }
    }
}

@Composable
fun ReminderEditorScreen(onBack: () -> Unit, viewModel: ReminderEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onBack() }
    if (state.loading) { FullScreenLoading(); return }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        CustomTopBar(
            stringResource(if (state.isNew) R.string.reminder_new else R.string.reminder_edit), onBack = onBack,
            actions = {
                if (!state.isNew) IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ReminderPermissionBanner(viewModel.scheduler)
            Spacer(Modifier.height(8.dp))
            CustomTextField(state.title, { v -> viewModel.update { it.copy(title = v, titleError = false) } }, stringResource(R.string.field_title),
                error = if (state.titleError) stringResource(R.string.error_title_required) else null, maxLength = 200)
            Spacer(Modifier.height(8.dp))
            CustomTextField(state.description, { v -> viewModel.update { it.copy(description = v) } }, stringResource(R.string.field_description),
                singleLine = false, minLines = 2, maxLength = 2000)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.field_when), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            DateTimeRow(
                dateText = TimeUtils.formatDate(TimeUtils.toEpoch(state.date, LocalTime.NOON)),
                timeText = TimeUtils.formatTime(TimeUtils.toEpoch(state.date, state.time)),
                onDateClick = { showDate = true }, onTimeClick = { showTime = true },
            )
            if (state.pastError) Text(stringResource(R.string.error_time_in_past), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.field_repeat), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatType.entries.forEach { type ->
                    FilterChip(
                        selected = state.repeat == type, onClick = { viewModel.update { it.copy(repeat = type) } },
                        label = { Text(if (type == RepeatType.CUSTOM) stringResource(R.string.repeat_custom) else repeatLabel(type, 1)) },
                    )
                }
            }
            if (state.repeat == RepeatType.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                CustomTextField(
                    state.intervalDays, { v -> viewModel.update { it.copy(intervalDays = v.filter(Char::isDigit), intervalError = false) } },
                    stringResource(R.string.field_every_n_days), keyboardType = KeyboardType.Number, maxLength = 3,
                    error = if (state.intervalError) stringResource(R.string.error_interval) else null,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reminder_enabled), modifier = Modifier.weight(1f))
                Switch(state.enabled, { v -> viewModel.update { it.copy(enabled = v) } })
            }
            if (!state.isNew) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.reminder_completed), modifier = Modifier.weight(1f))
                    Switch(state.completed, { v -> viewModel.update { it.copy(completed = v) } })
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SecondaryText(stringResource(R.string.reminder_timezone_note, java.util.TimeZone.getDefault().id))
        }
        GradientButton(stringResource(R.string.action_save), viewModel::save, Modifier.fillMaxWidth().padding(20.dp), loading = state.saving)
    }

    if (showDate) LocalDatePickerDialog(state.date, { showDate = false }) { d -> viewModel.update { it.copy(date = d) } }
    if (showTime) LocalTimePickerDialog(state.time, { showTime = false }) { t -> viewModel.update { it.copy(time = t) } }
    if (confirmDelete) ConfirmationDialog(
        title = stringResource(R.string.reminder_delete_title), message = stringResource(R.string.delete_irreversible),
        confirmLabel = stringResource(R.string.action_delete), destructive = true,
        onConfirm = { confirmDelete = false; viewModel.delete() }, onDismiss = { confirmDelete = false },
    )
}

@Suppress("unused")
private fun nowLocal(): LocalDateTime = LocalDateTime.now()
