package com.lifeos.ai.feature.assistant

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lifeos.ai.R
import com.lifeos.ai.core.common.newId
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.data.remote.api.AiLimits
import com.lifeos.ai.data.remote.api.AiResult
import com.lifeos.ai.data.remote.api.AiService
import com.lifeos.ai.data.repository.SessionManager
import com.lifeos.ai.domain.model.ChatMessage
import com.lifeos.ai.domain.model.Conversation
import com.lifeos.ai.domain.model.MessageRole
import com.lifeos.ai.domain.repository.ConversationRepository
import com.lifeos.ai.domain.usecase.AssistantIntent
import com.lifeos.ai.domain.usecase.CommandParser
import com.lifeos.ai.domain.usecase.RemoteIntentMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Converts free text into a validated intent: offline parser first, then the AI backend. */
@Singleton
class IntentResolver @Inject constructor(
    private val parser: CommandParser,
    private val ai: AiService,
) {
    suspend fun resolve(text: String, allowRemote: Boolean = true): AssistantIntent? {
        parser.parse(text)?.let { return it }
        // Ask the AI to interpret only text that names a command — each check costs a request.
        if (!allowRemote || !ai.isAvailable || !looksLikeCommand(text)) return null
        val zone = ZoneId.systemDefault()
        return when (val r = ai.interpret(text, LocalDateTime.now(zone).withNano(0).toString(), zone.id)) {
            is AiResult.Success -> RemoteIntentMapper.map(r.value, zone)
            is AiResult.Failure -> null
        }
    }
}

private val COMMAND_WORDS = setOf(
    "remind", "reminder", "reminders", "alarm", "yaad", "expense", "expenses", "kharch", "kharcha", "spent", "paid",
    "income", "salary", "shopping", "note", "notes", "task", "tasks", "todo", "schedule", "add", "create", "save",
)
private val COMMAND_WORDS_DEVANAGARI = listOf("याद", "रिमाइंडर", "अलार्म", "खर्च", "नोट", "टास्क", "शेड्यूल")
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/**
 * True when text the offline parser couldn't handle still names an action (reminder, expense,
 * note, task…). Matches whole words, so everyday questions ("creative idea", "mera kaam") go
 * straight to chat instead of costing an extra interpretation request.
 */
internal fun looksLikeCommand(text: String): Boolean {
    val lower = text.lowercase()
    if (COMMAND_WORDS_DEVANAGARI.any { it in lower }) return true
    return lower.split(NON_WORD).any { it in COMMAND_WORDS }
}

data class PendingAction(val intent: AssistantIntent, val summary: String, val editableTitle: String?)

/** The text the user may tweak in the confirmation step before an action is saved. */
fun AssistantIntent.editableTitle(): String? = when (this) {
    is AssistantIntent.CreateReminder -> title
    is AssistantIntent.CreateTask -> title
    is AssistantIntent.CreateNote -> title
    is AssistantIntent.AddTransaction -> description
    else -> null
}

data class AssistantUiState(
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val pending: PendingAction? = null,
    val aiAvailable: Boolean = false,
    val signedIn: Boolean = false,
    val signInRequired: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssistantViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val conversations: ConversationRepository,
    private val ai: AiService,
    private val resolver: IntentResolver,
    private val executor: AssistantActionExecutor,
    private val session: SessionManager,
) : ViewModel() {

    private val route = runCatching { savedStateHandle.toRoute<Routes.Assistant>() }.getOrNull()
    private val conversationId = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(
        AssistantUiState(
            input = route?.prompt.orEmpty(),
            aiAvailable = ai.isAvailable,
            signedIn = session.isSignedIn,
            signInRequired = ai.requiresSignIn,
        )
    )
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private val _navigation = Channel<Any>(Channel.BUFFERED)
    val navigation = _navigation.receiveAsFlow()

    private val messagesFlow = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversations.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Opened from voice mode with "Continue in chat": show that conversation.
        route?.conversationId?.let { selectConversation(it) }
        viewModelScope.launch { messagesFlow.collect { m -> _state.update { it.copy(messages = m) } } }
        viewModelScope.launch {
            conversations.observeConversations().collect { list ->
                _state.update { it.copy(conversations = list) }
                // Open the most recent conversation by default.
                if (conversationId.value == null && list.isNotEmpty() && _state.value.messages.isEmpty()) {
                    selectConversation(list.first().id)
                }
            }
        }
        viewModelScope.launch {
            session.authUser.collect { u -> _state.update { it.copy(signedIn = u != null) } }
        }
    }

    fun onInput(text: String) = _state.update { it.copy(input = text.take(AiLimits.MAX_MESSAGE_CHARS)) }

    fun selectConversation(id: String) {
        conversationId.value = id
        _state.update { it.copy(conversationId = id, pending = null) }
    }

    fun newConversation() {
        conversationId.value = null
        _state.update { it.copy(conversationId = null, messages = emptyList(), pending = null) }
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        conversations.deleteConversation(id)
        if (conversationId.value == id) newConversation()
    }

    fun clearConversation() = viewModelScope.launch {
        conversationId.value?.let { conversations.clearMessages(it) }
        _state.update { it.copy(pending = null) }
    }

    fun send(textOverride: String? = null) {
        val text = (textOverride ?: _state.value.input).trim()
        if (text.isEmpty() || _state.value.sending) return
        _state.update { it.copy(input = "", sending = true, pending = null) }
        viewModelScope.launch {
            val convId = ensureConversation(text)
            conversations.addMessage(ChatMessage(newId(), convId, MessageRole.USER, text, System.currentTimeMillis()))
            val intent = resolver.resolve(text)
            when {
                intent == null -> respondWithAi(convId)
                intent is AssistantIntent.OpenEditor -> {
                    addAssistant(convId, context.getString(R.string.opening_editor))
                    _navigation.send(editorRoute(intent.target))
                }
                intent.requiresConfirmation -> _state.update {
                    it.copy(pending = PendingAction(intent, executor.describe(intent), intent.editableTitle()))
                }
                else -> addAssistant(convId, executor.answer(intent))
            }
            _state.update { it.copy(sending = false) }
        }
    }

    fun updatePendingTitle(title: String) = _state.update { s -> s.copy(pending = s.pending?.copy(editableTitle = title.take(200))) }

    fun confirmPending() {
        val pending = _state.value.pending ?: return
        val convId = conversationId.value ?: return
        _state.update { it.copy(pending = null, sending = true) }
        viewModelScope.launch {
            val outcome = executor.execute(pending.intent, pending.editableTitle)
            conversations.addMessage(ChatMessage(newId(), convId, MessageRole.ASSISTANT, outcome.message, System.currentTimeMillis(), isError = !outcome.success))
            _state.update { it.copy(sending = false) }
        }
    }

    fun cancelPending() {
        val convId = conversationId.value
        _state.update { it.copy(pending = null) }
        if (convId != null) viewModelScope.launch { addAssistant(convId, context.getString(R.string.action_cancelled)) }
    }

    /** Re-asks the AI for the user message preceding [message]. */
    fun regenerate(message: ChatMessage) {
        val convId = conversationId.value ?: return
        if (_state.value.sending) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            conversations.deleteMessage(message.id)
            respondWithAi(convId)
            _state.update { it.copy(sending = false) }
        }
    }

    private suspend fun respondWithAi(convId: String) {
        val history = conversations.recentMessages(convId, AiLimits.MAX_HISTORY_MESSAGES + 1)
        val last = history.lastOrNull { it.role == MessageRole.USER } ?: return
        val prior = history.takeWhile { it.id != last.id }
        when (val r = ai.chat(last.content, prior)) {
            is AiResult.Success -> addAssistant(convId, r.value)
            is AiResult.Failure -> conversations.addMessage(
                ChatMessage(newId(), convId, MessageRole.ASSISTANT, context.getString(aiErrorMessage(r.error)), System.currentTimeMillis(), isError = true)
            )
        }
    }

    private suspend fun addAssistant(convId: String, text: String) {
        conversations.addMessage(ChatMessage(newId(), convId, MessageRole.ASSISTANT, text, System.currentTimeMillis()))
    }

    private suspend fun ensureConversation(firstMessage: String): String {
        conversationId.value?.let { return it }
        val conv = conversations.createConversation(firstMessage.lineSequence().first().take(40))
        selectConversation(conv.id)
        return conv.id
    }

    companion object {
        fun editorRoute(target: AssistantIntent.Target): Any = when (target) {
            AssistantIntent.Target.REMINDER -> Routes.ReminderEditor()
            AssistantIntent.Target.NOTE -> Routes.NoteEditor()
            AssistantIntent.Target.TASK -> Routes.TaskEditor()
            AssistantIntent.Target.EXPENSE -> Routes.TransactionEditor()
        }
    }
}
