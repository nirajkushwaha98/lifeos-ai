package com.lifeos.ai.feature.documents

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil.compose.AsyncImage
import com.lifeos.ai.R
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.common.newId
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.ui.ConfirmationDialog
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.EmptyState
import com.lifeos.ai.core.ui.FilterChipRow
import com.lifeos.ai.core.ui.FullScreenLoading
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.IconBadge
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.LoadingAnimation
import com.lifeos.ai.core.ui.LocalDatePickerDialog
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.data.local.files.PrivateFileStore
import com.lifeos.ai.domain.model.Document
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.repository.DocumentRepository
import com.lifeos.ai.domain.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class DocumentsUiState(
    val loading: Boolean = true,
    val documents: List<Document> = emptyList(),
    val query: String = "",
    val folders: List<String> = emptyList(),
    val folder: String? = null,
    val importing: Boolean = false,
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val repo: DocumentRepository,
    private val reminders: ReminderRepository,
    private val files: PrivateFileStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val folder = MutableStateFlow<String?>(null)
    private val importing = MutableStateFlow(false)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val state: StateFlow<DocumentsUiState> = combine(repo.observeDocuments(), query, folder, importing) { all, q, f, imp ->
        DocumentsUiState(
            loading = false,
            documents = all.filter { (q.isBlank() || it.name.contains(q, true) || it.folder.contains(q, true)) && (f == null || it.folder == f) },
            query = q,
            folders = all.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted(),
            folder = f,
            importing = imp,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsUiState())

    fun setQuery(q: String) { query.value = q }
    fun setFolder(f: String?) { folder.value = f }

    fun import(uri: android.net.Uri) {
        viewModelScope.launch {
            importing.value = true
            runCatching {
                val f = files.importDocument(uri)
                val now = System.currentTimeMillis()
                repo.upsert(Document(newId(), f.displayName, f.storedName, f.mimeType, f.size, createdAt = now, updatedAt = now))
            }.onSuccess { _messages.send(context.getString(R.string.doc_imported, it.name)) }
                .onFailure { _messages.send(context.getString(R.string.doc_import_failed)) }
            importing.value = false
        }
    }

    fun rename(doc: Document, name: String, folderName: String) = viewModelScope.launch {
        runCatching { repo.upsert(doc.copy(name = name, folder = folderName.trim().take(30))) }
    }

    fun delete(doc: Document) = viewModelScope.launch { repo.delete(doc.id) }

    /** Sets an expiry date and creates a reminder 1 day before at 9:00 (or on the day if that's past). */
    fun setExpiry(doc: Document, date: LocalDate?) = viewModelScope.launch {
        doc.expiryReminderId?.let { reminders.delete(it) }
        if (date == null) { repo.upsert(doc.copy(expiryAt = null, expiryReminderId = null)); return@launch }
        val now = System.currentTimeMillis()
        var remindAt = TimeUtils.toEpoch(date.minusDays(1), LocalTime.of(9, 0))
        if (remindAt <= now) remindAt = TimeUtils.toEpoch(date, LocalTime.of(9, 0))
        val reminderId = if (remindAt > now) {
            reminders.upsert(
                Reminder(newId(), context.getString(R.string.doc_expiry_reminder, doc.name), scheduledAt = remindAt, createdAt = now, updatedAt = now)
            ).id
        } else null
        repo.upsert(doc.copy(expiryAt = TimeUtils.toEpoch(date, LocalTime.NOON), expiryReminderId = reminderId))
        _messages.send(context.getString(if (reminderId != null) R.string.doc_expiry_set else R.string.doc_expiry_past))
    }
}

@Composable
fun DocumentsScreen(onBack: () -> Unit, navigate: (Any) -> Unit, viewModel: DocumentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<Document?>(null) }
    var deleting by remember { mutableStateOf<Document?>(null) }
    var expiryFor by remember { mutableStateOf<Document?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { Column(Modifier.statusBarsPadding()) { CustomTopBar(stringResource(R.string.documents_title), onBack = onBack) } },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { picker.launch(arrayOf("application/pdf", "image/*")) },
                containerColor = LifeColors.Cyan, modifier = Modifier.navigationBarsPadding(),
            ) { Icon(Icons.Outlined.Add, stringResource(R.string.doc_import), tint = Color.Black) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            CustomTextField(state.query, viewModel::setQuery, stringResource(R.string.search_documents), leadingIcon = Icons.Outlined.Search)
            if (state.folders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val opts = listOf(stringResource(R.string.all_folders) to (state.folder == null)) + state.folders.map { it to (state.folder == it) }
                FilterChipRow(opts, { i -> viewModel.setFolder(if (i == 0) null else state.folders[i - 1]) })
            }
            if (state.importing) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadingAnimation(size = 24.dp); Spacer(Modifier.width(8.dp)); SecondaryText(stringResource(R.string.doc_importing))
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.loading -> FullScreenLoading()
                state.documents.isEmpty() -> EmptyState(
                    LifeIcons.Documents, stringResource(R.string.documents_empty_title), stringResource(R.string.documents_empty_body),
                    actionLabel = stringResource(R.string.doc_import), onAction = { picker.launch(arrayOf("application/pdf", "image/*")) },
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.documents, key = { it.id }) { doc ->
                        DocumentRow(doc, onOpen = { navigate(Routes.DocumentPreview(doc.id)) }, onRename = { renaming = doc },
                            onDelete = { deleting = doc }, onExpiry = { expiryFor = doc })
                    }
                    item { SecondaryText(stringResource(R.string.doc_privacy_note), Modifier.padding(vertical = 8.dp)) }
                }
            }
        }
    }

    renaming?.let { doc ->
        var name by remember(doc.id) { mutableStateOf(doc.name) }
        var folderName by remember(doc.id) { mutableStateOf(doc.folder) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.doc_rename)) },
            text = {
                Column {
                    CustomTextField(name, { name = it }, stringResource(R.string.field_name), maxLength = 120)
                    Spacer(Modifier.height(8.dp))
                    CustomTextField(folderName, { folderName = it }, stringResource(R.string.field_folder), maxLength = 30)
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { viewModel.rename(doc, name, folderName); renaming = null }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deleting?.let { doc ->
        ConfirmationDialog(
            title = stringResource(R.string.doc_delete_title), message = stringResource(R.string.doc_delete_body, doc.name),
            confirmLabel = stringResource(R.string.action_delete), destructive = true,
            onConfirm = { viewModel.delete(doc); deleting = null }, onDismiss = { deleting = null },
        )
    }
    expiryFor?.let { doc ->
        LocalDatePickerDialog(doc.expiryAt?.let { TimeUtils.toLocalDate(it) } ?: LocalDate.now().plusMonths(1), { expiryFor = null }) { d ->
            viewModel.setExpiry(doc, d)
        }
    }
}

@Composable
private fun DocumentRow(doc: Document, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onExpiry: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (doc.isPdf) Icons.Outlined.PictureAsPdf else Icons.Outlined.Image, if (doc.isPdf) LifeColors.Error else LifeColors.Cyan)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SecondaryText(listOfNotNull(doc.folder.takeIf { it.isNotBlank() }, formatSize(doc.sizeBytes), TimeUtils.formatDate(doc.createdAt)).joinToString(" · "), maxLines = 1)
                doc.expiryAt?.let {
                    val expired = it < System.currentTimeMillis()
                    Text(
                        stringResource(if (expired) R.string.doc_expired_on else R.string.doc_expires_on, TimeUtils.formatDate(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (expired) MaterialTheme.colorScheme.error else LifeColors.Warning,
                    )
                }
            }
            IconButton(onClick = onExpiry) { Icon(Icons.Outlined.EventBusy, stringResource(R.string.doc_set_expiry)) }
            IconButton(onClick = onRename) { Icon(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.doc_rename)) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

// ============================================================ preview

@HiltViewModel
class DocumentPreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repo: DocumentRepository,
    val files: PrivateFileStore,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<Routes.DocumentPreview>().documentId
    val document = MutableStateFlow<Document?>(null)
    val missing = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val d = repo.getDocument(id)
            if (d == null) missing.value = true else document.value = d
        }
    }
}

@Composable
fun DocumentPreviewScreen(onBack: () -> Unit, viewModel: DocumentPreviewViewModel = hiltViewModel()) {
    val doc by viewModel.document.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        CustomTopBar(doc?.name ?: stringResource(R.string.documents_title), onBack = onBack, actions = {
            doc?.let { d ->
                IconButton(onClick = { openExternally(context, viewModel.files.documentFile(d.storedFileName), d.mimeType) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.doc_open_with))
                }
            }
        })
        when {
            missing -> EmptyState(LifeIcons.Documents, stringResource(R.string.doc_missing), "")
            doc == null -> FullScreenLoading()
            doc!!.isImage -> AsyncImage(
                model = viewModel.files.documentFile(doc!!.storedFileName), contentDescription = doc!!.name,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp),
            )
            doc!!.isPdf -> PdfPreview(viewModel.files.documentFile(doc!!.storedFileName))
        }
    }
}

/** Renders PDF pages on demand with the platform PdfRenderer (no third-party viewer). */
@Composable
private fun PdfPreview(file: File) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    val lock = remember { Any() }
    DisposableEffect(renderer) { onDispose { synchronized(lock) { renderer?.close() } } }
    if (renderer == null) {
        EmptyState(Icons.Outlined.PictureAsPdf, stringResource(R.string.doc_pdf_error), "")
        return
    }
    val pageCount = renderer.pageCount
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.navigationBarsPadding()) {
        items(pageCount) { index ->
            val bitmap by produceState<Bitmap?>(null, index) {
                value = withContext(Dispatchers.IO) {
                    synchronized(lock) {
                        runCatching {
                            renderer.openPage(index).use { page ->
                                val scale = 1080f / page.width
                                val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            }
                        }.getOrNull()
                    }
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.doc_page, index + 1), Modifier.fillMaxSize()) }
                    ?: LoadingAnimation(size = 32.dp)
            }
        }
    }
}

private fun openExternally(context: Context, file: File, mime: String) {
    // Temporary read grant via FileProvider - no public URL is ever created.
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
