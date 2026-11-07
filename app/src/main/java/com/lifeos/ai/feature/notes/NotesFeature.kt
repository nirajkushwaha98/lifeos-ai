package com.lifeos.ai.feature.notes

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lifeos.ai.R
import com.lifeos.ai.core.common.newId
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.ui.ConfirmationDialog
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.EmptyState
import com.lifeos.ai.core.ui.FilterChipRow
import com.lifeos.ai.core.ui.FullScreenLoading
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.LoadingAnimation
import com.lifeos.ai.core.ui.MarkdownText
import com.lifeos.ai.core.ui.NoteCard
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.data.remote.api.AiResult
import com.lifeos.ai.data.remote.api.AiService
import com.lifeos.ai.data.remote.api.NoteTool
import com.lifeos.ai.domain.model.ChecklistItem
import com.lifeos.ai.domain.model.Note
import com.lifeos.ai.domain.repository.NoteRepository
import com.lifeos.ai.feature.assistant.aiErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ============================================================ list

enum class NotesFilter { ACTIVE, PINNED, CHECKLISTS, ARCHIVED }

data class NotesUiState(
    val loading: Boolean = true,
    val notes: List<Note> = emptyList(),
    val filter: NotesFilter = NotesFilter.ACTIVE,
    val query: String = "",
    val categories: List<String> = emptyList(),
    val category: String? = null,
)

@HiltViewModel
class NotesViewModel @Inject constructor(repo: NoteRepository) : ViewModel() {
    private val filter = MutableStateFlow(NotesFilter.ACTIVE)
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<String?>(null)

    val state: StateFlow<NotesUiState> = combine(repo.observeNotes(), filter, query, category) { all, f, q, c ->
        val filtered = all.filter { n ->
            val byFilter = when (f) {
                NotesFilter.ACTIVE -> !n.isArchived
                NotesFilter.PINNED -> n.isPinned && !n.isArchived
                NotesFilter.CHECKLISTS -> n.isChecklist && !n.isArchived
                NotesFilter.ARCHIVED -> n.isArchived
            }
            val byQuery = q.isBlank() || n.title.contains(q, true) || n.content.contains(q, true) ||
                n.checklist.any { it.text.contains(q, true) } || n.category.contains(q, true)
            byFilter && byQuery && (c == null || n.category == c)
        }
        NotesUiState(false, filtered, f, q, all.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(), c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun setFilter(f: NotesFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun setCategory(c: String?) { category.value = c }
}

@Composable
fun NotesScreen(onBack: () -> Unit, navigate: (Any) -> Unit, viewModel: NotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val filters = listOf(
        NotesFilter.ACTIVE to R.string.notes_filter_all, NotesFilter.PINNED to R.string.notes_filter_pinned,
        NotesFilter.CHECKLISTS to R.string.notes_filter_checklists, NotesFilter.ARCHIVED to R.string.notes_filter_archived,
    )
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { Column(Modifier.statusBarsPadding()) { CustomTopBar(stringResource(R.string.notes_title), onBack = onBack) } },
        floatingActionButton = {
            Box(Modifier.navigationBarsPadding()) {
                FloatingActionButton(onClick = { menu = true }, containerColor = LifeColors.Warning) {
                    Icon(Icons.Filled.Add, stringResource(R.string.qa_create_note), tint = Color.White)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.note_type_text)) }, { menu = false; navigate(Routes.NoteEditor()) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.note_type_checklist)) }, { menu = false; navigate(Routes.NoteEditor(checklist = true)) },
                        leadingIcon = { Icon(Icons.Filled.Checklist, null) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            CustomTextField(state.query, viewModel::setQuery, stringResource(R.string.search_notes), leadingIcon = Icons.Filled.Search)
            Spacer(Modifier.height(8.dp))
            FilterChipRow(filters.map { (f, l) -> stringResource(l) to (state.filter == f) }, { viewModel.setFilter(filters[it].first) })
            if (state.categories.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val opts = listOf(stringResource(R.string.all_categories) to (state.category == null)) + state.categories.map { it to (state.category == it) }
                FilterChipRow(opts, { i -> viewModel.setCategory(if (i == 0) null else state.categories[i - 1]) })
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.loading -> FullScreenLoading()
                state.notes.isEmpty() -> EmptyState(
                    LifeIcons.Note, stringResource(R.string.notes_empty_title), stringResource(R.string.notes_empty_body),
                    actionLabel = stringResource(R.string.qa_create_note), onAction = { navigate(Routes.NoteEditor()) },
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.notes, key = { it.id }) { n -> NoteCard(n, onClick = { navigate(Routes.NoteEditor(n.id)) }) }
                }
            }
        }
    }
}

// ============================================================ editor

data class AiToolState(val tool: NoteTool, val loading: Boolean = true, val result: String? = null, @StringRes val error: Int? = null)

data class NoteEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val content: String = "",
    val category: String = "",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isChecklist: Boolean = false,
    val checklist: List<ChecklistItem> = emptyList(),
    val emptyError: Boolean = false,
    val saving: Boolean = false,
    val done: Boolean = false,
    val aiTool: AiToolState? = null,
    val aiAvailable: Boolean = false,
)

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: NoteRepository,
    private val ai: AiService,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Routes.NoteEditor>()
    private var original: Note? = null
    private val _state = MutableStateFlow(NoteEditorState(isChecklist = route.checklist, aiAvailable = ai.isAvailable))
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val n = route.noteId?.let { repo.getNote(it) }
            original = n
            _state.update { s ->
                if (n == null) s.copy(loading = false, checklist = if (s.isChecklist) listOf(ChecklistItem("")) else emptyList())
                else s.copy(
                    loading = false, isNew = false, title = n.title, content = n.content, category = n.category,
                    isPinned = n.isPinned, isArchived = n.isArchived, isChecklist = n.isChecklist, checklist = n.checklist,
                )
            }
        }
    }

    fun update(t: (NoteEditorState) -> NoteEditorState) = _state.update { t(it).copy(emptyError = false) }

    fun toggleChecklistMode() = _state.update { s ->
        if (s.isChecklist) {
            // Convert checklist -> text, keeping every item.
            s.copy(isChecklist = false, content = (listOf(s.content) + s.checklist.filter { it.text.isNotBlank() }
                .map { (if (it.checked) "[x] " else "- ") + it.text }).filter { it.isNotBlank() }.joinToString("\n"), checklist = emptyList())
        } else {
            val items = s.content.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .map { line -> ChecklistItem(line.removePrefix("[x] ").removePrefix("- ").removePrefix("• "), line.startsWith("[x]")) }
            s.copy(isChecklist = true, content = "", checklist = items.ifEmpty { listOf(ChecklistItem("")) })
        }
    }

    fun setItem(index: Int, item: ChecklistItem) = _state.update { s ->
        s.copy(checklist = s.checklist.toMutableList().also { if (index in it.indices) it[index] = item })
    }

    fun addItem() = _state.update { it.copy(checklist = it.checklist + ChecklistItem("")) }
    fun removeItem(index: Int) = _state.update { s -> s.copy(checklist = s.checklist.filterIndexed { i, _ -> i != index }) }

    fun save(andClose: Boolean = true) {
        val s = _state.value
        if (s.title.isBlank() && s.content.isBlank() && s.checklist.none { it.text.isNotBlank() }) {
            _state.update { it.copy(emptyError = true) }; return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val now = System.currentTimeMillis()
            val base = original ?: Note(id = newId(), title = "", createdAt = now, updatedAt = now)
            runCatching {
                repo.upsert(base.copy(
                    title = s.title, content = if (s.isChecklist) "" else s.content, category = s.category.trim().take(30),
                    isPinned = s.isPinned, isArchived = s.isArchived, isChecklist = s.isChecklist,
                    checklist = if (s.isChecklist) s.checklist else emptyList(),
                ))
            }.onSuccess { saved ->
                original = saved
                _state.update { it.copy(saving = false, isNew = false, done = andClose) }
            }.onFailure { _state.update { it.copy(saving = false) } }
        }
    }

    fun delete() {
        val id = original?.id ?: run { _state.update { it.copy(done = true) }; return }
        viewModelScope.launch { repo.delete(id); _state.update { it.copy(done = true) } }
    }

    fun runAiTool(tool: NoteTool) {
        val s = _state.value
        val text = buildString {
            if (s.title.isNotBlank()) appendLine(s.title)
            if (s.isChecklist) s.checklist.forEach { appendLine("- ${it.text}") } else append(s.content)
        }.trim()
        if (text.isEmpty()) { _state.update { it.copy(emptyError = true) }; return }
        _state.update { it.copy(aiTool = AiToolState(tool)) }
        viewModelScope.launch {
            when (val r = ai.noteTool(tool, text)) {
                is AiResult.Success -> _state.update { it.copy(aiTool = AiToolState(tool, false, r.value)) }
                is AiResult.Failure -> _state.update { it.copy(aiTool = AiToolState(tool, false, error = aiErrorMessage(r.error))) }
            }
        }
    }

    fun dismissAi() = _state.update { it.copy(aiTool = null) }

    /** Applies the AI result: checklist tool produces items; others replace or append text. */
    fun applyAi(replace: Boolean) {
        val tool = _state.value.aiTool ?: return
        val result = tool.result ?: return
        _state.update { s ->
            if (tool.tool == NoteTool.CHECKLIST) {
                val items = result.lines().map { it.trim().trimStart('-', '*', '•', ' ').removePrefix("[ ]").removePrefix("[x]").trim() }
                    .filter { it.isNotEmpty() }.map { ChecklistItem(it) }
                s.copy(isChecklist = true, checklist = if (replace || !s.isChecklist) items else s.checklist + items, content = "", aiTool = null)
            } else if (s.isChecklist) {
                s.copy(isChecklist = false, checklist = emptyList(), content = result, aiTool = null)
            } else {
                s.copy(content = if (replace) result else s.content.trimEnd() + "\n\n" + result, aiTool = null)
            }
        }
    }
}

@Composable
fun NoteEditorScreen(onBack: () -> Unit, viewModel: NoteEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var aiMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onBack() }
    if (state.loading) { FullScreenLoading(); return }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        CustomTopBar(
            title = stringResource(if (state.isNew) R.string.note_new else R.string.note_edit),
            onBack = onBack,
            actions = {
                IconButton(onClick = { viewModel.update { it.copy(isPinned = !it.isPinned) } }) {
                    Icon(if (state.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        stringResource(if (state.isPinned) R.string.note_unpin else R.string.note_pin), tint = if (state.isPinned) LifeColors.Cyan else LocalContentColorValue())
                }
                Box {
                    IconButton(onClick = { aiMenu = true }) { Icon(LifeIcons.Spark, stringResource(R.string.note_ai_tools), tint = LifeColors.Purple) }
                    DropdownMenu(aiMenu, { aiMenu = false }) {
                        listOf(
                            NoteTool.SUMMARIZE to R.string.ai_summarize, NoteTool.REWRITE to R.string.ai_rewrite,
                            NoteTool.TRANSLATE_HI to R.string.ai_translate_hi, NoteTool.TRANSLATE_EN to R.string.ai_translate_en,
                            NoteTool.CHECKLIST to R.string.ai_checklist,
                        ).forEach { (tool, label) ->
                            DropdownMenuItem({ Text(stringResource(label)) }, { aiMenu = false; viewModel.runAiTool(tool) })
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            { Text(stringResource(if (state.isChecklist) R.string.note_to_text else R.string.note_to_checklist)) },
                            { menu = false; viewModel.toggleChecklistMode() }, leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                        )
                        DropdownMenuItem(
                            { Text(stringResource(if (state.isArchived) R.string.note_unarchive else R.string.note_archive)) },
                            { menu = false; viewModel.update { it.copy(isArchived = !it.isArchived) } },
                            leadingIcon = { Icon(if (state.isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive, null) },
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        )
                    }
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val fieldColors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            )
            TextField(
                state.title, { v -> viewModel.update { it.copy(title = v.take(200)) } },
                placeholder = { Text(stringResource(R.string.field_title)) }, textStyle = MaterialTheme.typography.headlineSmall,
                colors = fieldColors, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                state.category, { v -> viewModel.update { it.copy(category = v.take(30)) } },
                label = { Text(stringResource(R.string.field_category)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (state.isChecklist) {
                state.checklist.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.checked, { c -> viewModel.setItem(i, item.copy(checked = c)) })
                        TextField(
                            item.text, { v -> viewModel.setItem(i, item.copy(text = v.take(200))) },
                            placeholder = { Text(stringResource(R.string.checklist_item_hint)) }, colors = fieldColors,
                            modifier = Modifier.weight(1f), singleLine = true,
                        )
                        IconButton(onClick = { viewModel.removeItem(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.action_remove)) }
                    }
                }
                TextButton(onClick = viewModel::addItem) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.padding(2.dp)); Text(stringResource(R.string.checklist_add_item))
                }
            } else {
                TextField(
                    state.content, { v -> viewModel.update { it.copy(content = v.take(20_000)) } },
                    placeholder = { Text(stringResource(R.string.note_content_hint)) }, colors = fieldColors,
                    textStyle = TextStyle.Default.merge(MaterialTheme.typography.bodyLarge),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                )
            }
            if (state.emptyError) Text(stringResource(R.string.error_note_empty), color = MaterialTheme.colorScheme.error)
        }
        com.lifeos.ai.core.ui.GradientButton(stringResource(R.string.action_save), { viewModel.save() }, Modifier.fillMaxWidth().padding(16.dp), loading = state.saving)
    }

    state.aiTool?.let { tool -> AiResultDialog(tool, state.aiAvailable, onDismiss = viewModel::dismissAi, onApply = viewModel::applyAi) }
    if (confirmDelete) ConfirmationDialog(
        title = stringResource(R.string.note_delete_title), message = stringResource(R.string.delete_irreversible),
        confirmLabel = stringResource(R.string.action_delete), destructive = true,
        onConfirm = { confirmDelete = false; viewModel.delete() }, onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun LocalContentColorValue(): Color = androidx.compose.material3.LocalContentColor.current

@Composable
private fun AiResultDialog(tool: AiToolState, available: Boolean, onDismiss: () -> Unit, onApply: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_ai_tools)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                when {
                    tool.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingAnimation() }
                    tool.error != null -> Text(stringResource(tool.error), color = MaterialTheme.colorScheme.error)
                    tool.result != null -> MarkdownText(tool.result)
                }
                if (!available) SecondaryText(stringResource(R.string.ai_not_configured_short))
            }
        },
        confirmButton = {
            if (tool.result != null) TextButton(onClick = { onApply(true) }) { Text(stringResource(R.string.action_replace)) }
        },
        dismissButton = {
            Row {
                if (tool.result != null) TextButton(onClick = { onApply(false) }) { Text(stringResource(R.string.action_append)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
