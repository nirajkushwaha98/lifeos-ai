package com.lifeos.ai.feature.tasks

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.res.stringResource
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
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.ui.ConfirmationDialog
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.DateTimeRow
import com.lifeos.ai.core.ui.EmptyState
import com.lifeos.ai.core.ui.FilterChipRow
import com.lifeos.ai.core.ui.FullScreenLoading
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.GradientButton
import com.lifeos.ai.core.ui.GradientProgressBar
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.LocalDatePickerDialog
import com.lifeos.ai.core.ui.LocalTimePickerDialog
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.core.ui.TaskCard
import com.lifeos.ai.core.ui.priorityColor
import com.lifeos.ai.core.ui.priorityLabel
import com.lifeos.ai.domain.model.Priority
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.repository.TaskRepository
import com.lifeos.ai.domain.usecase.TaskFilter
import com.lifeos.ai.domain.usecase.TaskFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

// ============================================================ list

data class TasksUiState(
    val loading: Boolean = true,
    val tasks: List<Task> = emptyList(),
    val filter: TaskFilter = TaskFilter.ALL,
    val query: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
)

@HiltViewModel
class TasksViewModel @Inject constructor(private val repo: TaskRepository) : ViewModel() {
    private val filter = MutableStateFlow(TaskFilter.ALL)
    private val query = MutableStateFlow("")

    val state: StateFlow<TasksUiState> = combine(repo.observeTasks(), filter, query) { tasks, f, q ->
        TasksUiState(
            loading = false,
            tasks = TaskFilters.apply(tasks, f, q, System.currentTimeMillis()),
            filter = f, query = q,
            completedCount = tasks.count { it.completed }, totalCount = tasks.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    fun setFilter(f: TaskFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun toggle(task: Task) = viewModelScope.launch { repo.setCompleted(task.id, !task.completed) }
    fun delete(task: Task) = viewModelScope.launch { repo.delete(task.id) }
    fun restore(task: Task) = viewModelScope.launch { repo.upsert(task) }
}

@Composable
fun TasksScreen(onBack: () -> Unit, navigate: (Any) -> Unit, viewModel: TasksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    val filterLabels = listOf(
        TaskFilter.ALL to R.string.filter_all, TaskFilter.TODAY to R.string.filter_today,
        TaskFilter.UPCOMING to R.string.filter_upcoming, TaskFilter.OVERDUE to R.string.filter_overdue,
        TaskFilter.COMPLETED to R.string.filter_completed,
    )
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                CustomTopBar(stringResource(R.string.tasks_title), onBack = onBack, actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Search, stringResource(R.string.cd_search))
                    }
                })
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navigate(Routes.TaskEditor()) }, containerColor = LifeColors.Blue, modifier = Modifier.navigationBarsPadding()) {
                Icon(Icons.Filled.Add, stringResource(R.string.qa_add_task), tint = Color.White)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            if (searching) {
                CustomTextField(state.query, viewModel::setQuery, stringResource(R.string.search_tasks), leadingIcon = Icons.Filled.Search)
                Spacer(Modifier.height(8.dp))
            }
            if (state.totalCount > 0) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tasks_progress, state.completedCount, state.totalCount), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    GradientProgressBar(state.completedCount.toFloat() / state.totalCount)
                }
                Spacer(Modifier.height(10.dp))
            }
            FilterChipRow(
                filterLabels.map { (f, label) -> stringResource(label) to (f == state.filter) },
                onSelect = { viewModel.setFilter(filterLabels[it].first) },
            )
            Spacer(Modifier.height(10.dp))
            when {
                state.loading -> FullScreenLoading()
                state.tasks.isEmpty() -> EmptyState(
                    LifeIcons.Task, stringResource(R.string.tasks_empty_title), stringResource(R.string.tasks_empty_body),
                    actionLabel = stringResource(R.string.qa_add_task), onAction = { navigate(Routes.TaskEditor()) },
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                    val now = System.currentTimeMillis()
                    items(state.tasks, key = { it.id }) { task ->
                        TaskCard(task, TaskFilters.isOverdue(task, now), onToggle = { viewModel.toggle(task) }, onClick = { navigate(Routes.TaskEditor(task.id)) })
                    }
                }
            }
        }
    }
}

// ============================================================ editor

data class TaskEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val description: String = "",
    val priority: Priority = Priority.MEDIUM,
    val category: String = "",
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val completed: Boolean = false,
    val titleError: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class TaskEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: TaskRepository,
) : ViewModel() {
    private val taskId: String? = savedStateHandle.toRoute<Routes.TaskEditor>().taskId
    private var original: Task? = null
    private val _state = MutableStateFlow(TaskEditorState())
    val state: StateFlow<TaskEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val t = taskId?.let { repo.getTask(it) }
            original = t
            _state.value = if (t == null) TaskEditorState(loading = false) else TaskEditorState(
                loading = false, isNew = false, title = t.title, description = t.description, priority = t.priority,
                category = t.category, dueDate = t.dueAt?.let { TimeUtils.toLocalDate(it) },
                dueTime = if (t.hasDueTime) t.dueAt?.let { TimeUtils.toLocalDateTime(it).toLocalTime() } else null,
                completed = t.completed,
            )
        }
    }

    fun update(transform: (TaskEditorState) -> TaskEditorState) = _state.update { transform(it).copy(error = null) }

    fun save() {
        val s = _state.value
        if (s.title.isBlank()) { _state.update { it.copy(titleError = true) }; return }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val now = System.currentTimeMillis()
            val dueAt = s.dueDate?.let { TimeUtils.toEpoch(it, s.dueTime ?: LocalTime.of(23, 59)) }
            val base = original ?: Task(id = newId(), title = "", createdAt = now, updatedAt = now)
            runCatching {
                repo.upsert(
                    base.copy(
                        title = s.title, description = s.description, priority = s.priority, category = s.category.trim(),
                        dueAt = dueAt, hasDueTime = s.dueTime != null && s.dueDate != null, completed = s.completed,
                        completedAt = if (s.completed) (base.completedAt ?: now) else null,
                    )
                )
            }.onSuccess { _state.update { it.copy(saving = false, done = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }

    fun delete() {
        val id = taskId ?: return
        viewModelScope.launch { repo.delete(id); _state.update { it.copy(done = true) } }
    }
}

@Composable
fun TaskEditorScreen(onBack: () -> Unit, viewModel: TaskEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onBack() }
    if (state.loading) { FullScreenLoading(); return }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        CustomTopBar(
            stringResource(if (state.isNew) R.string.task_new else R.string.task_edit), onBack = onBack,
            actions = {
                if (!state.isNew) IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            CustomTextField(
                state.title, { v -> viewModel.update { it.copy(title = v, titleError = false) } }, stringResource(R.string.field_title),
                error = if (state.titleError) stringResource(R.string.error_title_required) else null, maxLength = 200,
            )
            Spacer(Modifier.height(8.dp))
            CustomTextField(
                state.description, { v -> viewModel.update { it.copy(description = v) } }, stringResource(R.string.field_description),
                singleLine = false, minLines = 3, maxLength = 5000,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.field_priority), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p ->
                    FilterChip(
                        selected = state.priority == p, onClick = { viewModel.update { it.copy(priority = p) } },
                        label = { Text(priorityLabel(p), color = if (state.priority == p) priorityColor(p) else MaterialTheme.colorScheme.onSurface) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            CustomTextField(state.category, { v -> viewModel.update { it.copy(category = v) } }, stringResource(R.string.field_category),
                placeholder = stringResource(R.string.task_category_hint), maxLength = 30)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.field_due), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            DateTimeRow(
                dateText = state.dueDate?.let { TimeUtils.formatDate(TimeUtils.toEpoch(it, LocalTime.NOON)) } ?: stringResource(R.string.no_date),
                timeText = state.dueTime?.let { TimeUtils.formatTime(TimeUtils.toEpoch(LocalDate.now(), it)) } ?: stringResource(R.string.no_time),
                onDateClick = { showDate = true },
                onTimeClick = if (state.dueDate != null) ({ showTime = true }) else null,
            )
            if (state.dueDate != null) {
                TextButton(onClick = { viewModel.update { it.copy(dueDate = null, dueTime = null) } }) { Text(stringResource(R.string.clear_due)) }
            }
            if (!state.isNew) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.task_completed), modifier = Modifier.weight(1f))
                    Switch(state.completed, { v -> viewModel.update { it.copy(completed = v) } })
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SecondaryText(stringResource(R.string.task_offline_note))
        }
        GradientButton(stringResource(R.string.action_save), viewModel::save, Modifier.fillMaxWidth().padding(20.dp), loading = state.saving)
    }

    if (showDate) LocalDatePickerDialog(state.dueDate ?: LocalDate.now(), { showDate = false }) { d -> viewModel.update { it.copy(dueDate = d) } }
    if (showTime) LocalTimePickerDialog(state.dueTime ?: LocalTime.of(9, 0), { showTime = false }) { t -> viewModel.update { it.copy(dueTime = t) } }
    if (confirmDelete) ConfirmationDialog(
        title = stringResource(R.string.task_delete_title), message = stringResource(R.string.delete_irreversible),
        confirmLabel = stringResource(R.string.action_delete), destructive = true,
        onConfirm = { confirmDelete = false; viewModel.delete() }, onDismiss = { confirmDelete = false },
    )
}
