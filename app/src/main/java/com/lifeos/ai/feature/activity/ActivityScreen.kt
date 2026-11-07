package com.lifeos.ai.feature.activity

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lifeos.ai.R
import com.lifeos.ai.core.ads.AdsManager
import com.lifeos.ai.core.ads.NativeAdCard
import com.lifeos.ai.core.common.Money
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.EmptyState
import com.lifeos.ai.core.ui.FilterChipRow
import com.lifeos.ai.core.ui.FullScreenLoading
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.IconBadge
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.data.repository.AiActivityRepository
import com.lifeos.ai.domain.model.ActivityItem
import com.lifeos.ai.domain.model.ActivityType
import com.lifeos.ai.domain.model.TransactionType
import com.lifeos.ai.domain.repository.ConversationRepository
import com.lifeos.ai.domain.repository.NoteRepository
import com.lifeos.ai.domain.repository.ReminderRepository
import com.lifeos.ai.domain.repository.TaskRepository
import com.lifeos.ai.domain.repository.TransactionRepository
import com.lifeos.ai.feature.assistant.AssistantActionExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class DateRange(val days: Int?) { TODAY(0), WEEK(7), MONTH(30), ALL(null) }

data class ActivityUiState(
    val loading: Boolean = true,
    val items: List<ActivityItem> = emptyList(),
    val type: ActivityType? = null,
    val range: DateRange = DateRange.WEEK,
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    tasks: TaskRepository,
    reminders: ReminderRepository,
    transactions: TransactionRepository,
    notes: NoteRepository,
    conversations: ConversationRepository,
    aiActivity: AiActivityRepository,
    private val executor: AssistantActionExecutor,
) : ViewModel() {
    private val type = MutableStateFlow<ActivityType?>(null)
    private val range = MutableStateFlow(DateRange.WEEK)

    private val all = combine(
        tasks.observeTasks(), reminders.observeReminders(), transactions.observeTransactions(),
        notes.observeNotes(), combine(conversations.observeConversations(), aiActivity.observe()) { c, a -> c to a },
    ) { t, r, tx, n, (convs, acts) ->
        buildList {
            t.forEach {
                add(ActivityItem("t" + it.id, ActivityType.TASK, it.title,
                    context.getString(if (it.completed) R.string.activity_task_done else R.string.activity_task_created),
                    if (it.completed) it.completedAt ?: it.updatedAt else it.createdAt))
            }
            r.forEach {
                val triggered = it.lastTriggeredAt
                add(ActivityItem("r" + it.id, ActivityType.REMINDER, it.title,
                    context.getString(when {
                        it.completed -> R.string.activity_reminder_done
                        triggered != null -> R.string.activity_reminder_fired
                        else -> R.string.activity_reminder_created
                    }), triggered ?: it.createdAt))
            }
            tx.forEach {
                add(ActivityItem("x" + it.id, ActivityType.EXPENSE,
                    it.description.ifBlank { executor.categoryName(it.category) },
                    (if (it.type == TransactionType.INCOME) "+" else "−") + Money.format(it.amountMinor), it.date))
            }
            n.forEach {
                add(ActivityItem("n" + it.id, ActivityType.NOTE, it.title.ifBlank { context.getString(R.string.note_untitled) },
                    context.getString(R.string.activity_note_updated), it.updatedAt))
            }
            convs.forEach {
                add(ActivityItem("c" + it.id, ActivityType.AI, it.title, context.getString(R.string.activity_ai_chat), it.updatedAt))
            }
            acts.forEach {
                add(ActivityItem("a" + it.id, ActivityType.AI, it.detail, context.getString(R.string.activity_ai_action, it.action), it.timestamp))
            }
        }.sortedByDescending { it.timestamp }
    }

    val state: StateFlow<ActivityUiState> = combine(all, type, range) { items, t, r ->
        val cutoff = r.days?.let { d -> TimeUtils.startOfDay(java.time.LocalDate.now().minusDays(d.toLong())) }
        ActivityUiState(false, items.filter { (t == null || it.type == t) && (cutoff == null || it.timestamp >= cutoff) }.take(300), t, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun setType(t: ActivityType?) { type.value = t }
    fun setRange(r: DateRange) { range.value = r }
}

@Composable
fun ActivityScreen(navigate: (Any) -> Unit, adsManager: AdsManager, viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val types = listOf(null to R.string.filter_all, ActivityType.TASK to R.string.tasks_title, ActivityType.REMINDER to R.string.reminders_title,
        ActivityType.EXPENSE to R.string.expenses_title, ActivityType.NOTE to R.string.notes_title, ActivityType.AI to R.string.activity_ai)
    val ranges = listOf(DateRange.TODAY to R.string.range_today, DateRange.WEEK to R.string.range_week,
        DateRange.MONTH to R.string.range_month, DateRange.ALL to R.string.range_all)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        CustomTopBar(stringResource(R.string.tab_activity))
        Column(Modifier.padding(horizontal = 16.dp)) {
            FilterChipRow(types.map { (t, l) -> stringResource(l) to (state.type == t) }, { viewModel.setType(types[it].first) })
            Spacer(Modifier.height(6.dp))
            FilterChipRow(ranges.map { (r, l) -> stringResource(l) to (state.range == r) }, { viewModel.setRange(ranges[it].first) })
            Spacer(Modifier.height(8.dp))
        }
        when {
            state.loading -> FullScreenLoading()
            state.items.isEmpty() -> EmptyState(LifeIcons.Activity, stringResource(R.string.activity_empty_title), stringResource(R.string.activity_empty_body))
            else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                    ActivityRow(item, onClick = {
                        val id = item.id.drop(1)
                        when (item.id.first()) {
                            't' -> navigate(Routes.TaskEditor(id)); 'r' -> navigate(Routes.ReminderEditor(id))
                            'x' -> navigate(Routes.TransactionEditor(id)); 'n' -> navigate(Routes.NoteEditor(id))
                            'c' -> navigate(Routes.Assistant())
                        }
                    })
                    // A single, clearly-labelled native ad after the 6th item.
                    if (index == 5) { Spacer(Modifier.height(8.dp)); NativeAdCard(adsManager) }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, onClick: () -> Unit) {
    val (icon, color) = when (item.type) {
        ActivityType.TASK -> LifeIcons.Task to LifeColors.Blue
        ActivityType.REMINDER -> LifeIcons.Reminder to LifeColors.Purple
        ActivityType.EXPENSE -> LifeIcons.Expense to LifeColors.Success
        ActivityType.NOTE -> LifeIcons.Note to LifeColors.Warning
        ActivityType.AI -> LifeIcons.Spark to LifeColors.Cyan
    }
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SecondaryText(item.subtitle, maxLines = 1)
            }
            SecondaryText(TimeUtils.formatDateTime(item.timestamp))
        }
    }
}
