package com.lifeos.ai.feature.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.ai.R
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.ui.AIChatBubble
import com.lifeos.ai.core.ui.ConfirmationDialog
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.EmptyState
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.LoadingAnimation
import com.lifeos.ai.core.ui.NeonIcon
import com.lifeos.ai.core.ui.NeonIconButton
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.domain.model.MessageRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(navigate: (Any) -> Unit, viewModel: AssistantViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showHistory by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.navigation.collect { navigate(it) } }
    LaunchedEffect(state.messages.size, state.sending) {
        val count = state.messages.size + if (state.sending) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        CustomTopBar(
            title = stringResource(R.string.assistant_title),
            actions = {
                IconButton(onClick = { showHistory = true }) { Icon(Icons.Filled.History, stringResource(R.string.assistant_history)) }
                IconButton(onClick = viewModel::newConversation) { Icon(Icons.Filled.AddComment, stringResource(R.string.assistant_new_chat)) }
                if (state.messages.isNotEmpty()) IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Filled.ClearAll, stringResource(R.string.assistant_clear))
                }
            },
        )
        if (!state.aiAvailable || (state.signInRequired && !state.signedIn)) {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    stringResource(if (!state.aiAvailable) R.string.ai_error_not_configured else R.string.ai_error_sign_in),
                    style = MaterialTheme.typography.bodySmall,
                )
                SecondaryText(stringResource(R.string.assistant_offline_actions))
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty() && !state.sending) {
                item { Suggestions(onPick = { viewModel.send(it) }) }
            }
            items(state.messages, key = { it.id }) { msg ->
                val isLastAssistant = msg.role == MessageRole.ASSISTANT && msg.id == state.messages.lastOrNull()?.id
                AIChatBubble(
                    msg,
                    onCopy = { copy(context, msg.content) },
                    onRegenerate = if (isLastAssistant && state.aiAvailable) ({ viewModel.regenerate(msg) }) else null,
                )
            }
            if (state.sending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingAnimation(size = 32.dp)
                        Spacer(Modifier.width(8.dp))
                        SecondaryText(stringResource(R.string.assistant_thinking))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInput,
                placeholder = { Text(stringResource(R.string.assistant_input_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LifeColors.Blue),
            )
            Spacer(Modifier.width(8.dp))
            if (state.input.isBlank()) {
                NeonIconButton(LifeIcons.Mic, stringResource(R.string.qa_voice), onClick = { navigate(Routes.Voice) }, size = 52.dp)
            } else {
                NeonIconButton(Icons.AutoMirrored.Filled.Send, stringResource(R.string.cd_send), onClick = { viewModel.send() }, size = 52.dp, tint = LifeColors.Blue)
            }
        }
    }

    state.pending?.let { pending ->
        ConfirmationDialog(
            title = stringResource(R.string.confirm_action_title),
            message = pending.summary,
            confirmLabel = stringResource(R.string.action_confirm),
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::cancelPending,
        ) {
            if (pending.editableTitle != null) {
                Spacer(Modifier.height(12.dp))
                CustomTextField(pending.editableTitle, viewModel::updatePendingTitle, stringResource(R.string.field_title_or_note))
            }
        }
    }
    if (confirmClear) ConfirmationDialog(
        title = stringResource(R.string.assistant_clear), message = stringResource(R.string.assistant_clear_body),
        confirmLabel = stringResource(R.string.action_clear), destructive = true,
        onConfirm = { confirmClear = false; viewModel.clearConversation() }, onDismiss = { confirmClear = false },
    )
    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.assistant_history), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (state.conversations.isEmpty()) {
                    EmptyState(LifeIcons.Spark, stringResource(R.string.assistant_no_history), stringResource(R.string.assistant_no_history_body))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.conversations, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.selectConversation(c.id); showHistory = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = if (c.id == state.conversationId) LifeColors.Cyan else LifeColors.Blue)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SecondaryText(TimeUtils.formatDateTime(c.updatedAt))
                            }
                            IconButton(onClick = { viewModel.deleteConversation(c.id) }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    val suggestions = listOf(
        R.string.suggest_explain_ai, R.string.suggest_schedule, R.string.suggest_shopping,
        R.string.suggest_organize, R.string.suggest_translate, R.string.suggest_reminder, R.string.suggest_expense,
    ).map { stringResource(it) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        NeonIcon(LifeIcons.Spark, LifeColors.Purple, size = 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.assistant_welcome), style = MaterialTheme.typography.titleMedium)
        SecondaryText(stringResource(R.string.assistant_welcome_body))
        Spacer(Modifier.height(16.dp))
        suggestions.forEach { s ->
            GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { onPick(s) }, contentPadding = PaddingValues(12.dp)) {
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("LifeOS AI", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
