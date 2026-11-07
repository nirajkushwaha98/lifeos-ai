package com.lifeos.ai.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lifeos.ai.R
import com.lifeos.ai.core.ads.AdsManager
import com.lifeos.ai.core.ads.BannerAd
import com.lifeos.ai.core.common.Money
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors
import com.lifeos.ai.core.ui.FullScreenLoading
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.GradientProgressBar
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.NeonIcon
import com.lifeos.ai.core.ui.NoteCard
import com.lifeos.ai.core.ui.ReminderCard
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.core.ui.SectionHeader
import com.lifeos.ai.core.ui.TaskCard
import com.lifeos.ai.domain.model.UserProfile
import com.lifeos.ai.domain.usecase.TaskFilters
import java.time.LocalTime

@Composable
fun HomeScreen(navigate: (Any) -> Unit, adsManager: AdsManager, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) { FullScreenLoading(); return }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Greeting(state.profile, state, onAvatar = { navigate(Routes.Profile) }) }
            item { AssistantCard(onAsk = { navigate(Routes.Assistant()) }, onVoice = { navigate(Routes.Voice) }) }
            item { QuickActions(navigate) }
            item { ProductivityCard(state) }

            item { SectionHeader(stringResource(R.string.home_todays_tasks), action = stringResource(R.string.action_see_all), onAction = { navigate(Routes.Tasks) }) }
            if (state.todayTasks.isEmpty()) {
                item { EmptyHint(stringResource(R.string.home_no_tasks_today)) }
            } else {
                items(state.todayTasks, key = { "t" + it.id }) { task ->
                    TaskCard(task, TaskFilters.isOverdue(task, now), onToggle = { viewModel.toggleTask(task) }, onClick = { navigate(Routes.TaskEditor(task.id)) })
                }
            }

            item { SectionHeader(stringResource(R.string.home_upcoming_reminders), action = stringResource(R.string.action_see_all), onAction = { navigate(Routes.Reminders) }) }
            if (state.upcomingReminders.isEmpty()) {
                item { EmptyHint(stringResource(R.string.home_no_reminders)) }
            } else {
                items(state.upcomingReminders, key = { "r" + it.id }) { r ->
                    ReminderCard(r, onClick = { navigate(Routes.ReminderEditor(r.id)) }, onToggleEnabled = { viewModel.setReminderEnabled(r, it) })
                }
            }

            item { SectionHeader(stringResource(R.string.home_monthly_expenses), action = stringResource(R.string.action_see_all), onAction = { navigate(Routes.Expenses) }) }
            item { ExpenseSummaryCard(state, onClick = { navigate(Routes.ExpenseReports) }) }

            item { SectionHeader(stringResource(R.string.home_recent_notes), action = stringResource(R.string.action_see_all), onAction = { navigate(Routes.Notes) }) }
            if (state.recentNotes.isEmpty()) {
                item { EmptyHint(stringResource(R.string.home_no_notes)) }
            } else {
                items(state.recentNotes, key = { "n" + it.id }) { n -> NoteCard(n, onClick = { navigate(Routes.NoteEditor(n.id)) }) }
            }
        }
        // Banner sits above the bottom navigation, never overlapping it.
        BannerAd(adsManager)
    }
}

@Composable
private fun Greeting(profile: UserProfile?, state: HomeUiState, onAvatar: () -> Unit) {
    val hour = LocalTime.now().hour
    val greeting = stringResource(
        when (hour) { in 5..11 -> R.string.greeting_morning; in 12..16 -> R.string.greeting_afternoon; else -> R.string.greeting_evening }
    )
    val name = profile?.name?.takeIf { it.isNotBlank() }?.substringBefore(' ') ?: stringResource(R.string.friend)
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SecondaryText(TimeUtils.formatFullDate(state.today))
            Text("$greeting, $name", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Avatar(profile, 48, onAvatar)
    }
}

@Composable
fun Avatar(profile: UserProfile?, sizeDp: Int, onClick: (() -> Unit)? = null) {
    val colors = LocalLifeColors.current
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(colors.brandGradient)
            .border(2.dp, LifeColors.Cyan.copy(0.5f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (profile?.photoPath != null) {
            AsyncImage(
                model = java.io.File(profile.photoPath), contentDescription = stringResource(R.string.cd_profile_photo),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                profile?.name?.firstOrNull()?.uppercase() ?: "L",
                color = Color.White, style = if (sizeDp > 60) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun AssistantCard(onAsk: () -> Unit, onVoice: () -> Unit) {
    val colors = LocalLifeColors.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.brandGradient).clickable(onClick = onAsk).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(LifeIcons.Spark, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_ai_title), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.home_ai_subtitle), color = Color.White.copy(0.85f), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(0.16f))
                    .border(1.dp, Color.White.copy(0.45f), CircleShape)
                    .clickable(role = Role.Button, onClick = onVoice),
                contentAlignment = Alignment.Center,
            ) { Icon(LifeIcons.Mic, stringResource(R.string.qa_voice), tint = Color.White, modifier = Modifier.size(26.dp)) }
        }
    }
}

private data class QuickAction(val icon: ImageVector, val label: Int, val color: Color, val route: Any)

@Composable
private fun QuickActions(navigate: (Any) -> Unit) {
    val actions = listOf(
        QuickAction(LifeIcons.Task, R.string.qa_add_task, LifeColors.Blue, Routes.TaskEditor()),
        QuickAction(LifeIcons.Reminder, R.string.qa_add_reminder, LifeColors.Purple, Routes.ReminderEditor()),
        QuickAction(LifeIcons.Expense, R.string.qa_add_expense, LifeColors.Success, Routes.TransactionEditor()),
        QuickAction(LifeIcons.Note, R.string.qa_create_note, LifeColors.Warning, Routes.NoteEditor()),
        QuickAction(LifeIcons.Spark, R.string.qa_ask_ai, LifeColors.Cyan, Routes.Assistant()),
        QuickAction(LifeIcons.Mic, R.string.qa_voice, LifeColors.Pink, Routes.Voice),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { a ->
                    GlassCard(Modifier.weight(1f), onClick = { navigate(a.route) }, contentPadding = PaddingValues(vertical = 14.dp, horizontal = 6.dp)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            NeonIcon(a.icon, a.color, size = 44.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(a.label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductivityCard(state: HomeUiState) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_productivity), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        val fraction = if (state.tasksTotalToday == 0) 0f else state.tasksDoneToday.toFloat() / state.tasksTotalToday
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_tasks_done_today, state.tasksDoneToday, state.tasksTotalToday), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${(fraction * 100).toInt()}%", color = LifeColors.Cyan, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        GradientProgressBar(fraction)
        Spacer(Modifier.height(12.dp))
        Row {
            Stat(stringResource(R.string.home_pending), state.pendingTasks.toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.home_overdue), state.overdueCount.toString(), Modifier.weight(1f), if (state.overdueCount > 0) MaterialTheme.colorScheme.error else null)
            Stat(stringResource(R.string.home_done_week), state.completedThisWeek.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color? = null) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color ?: MaterialTheme.colorScheme.onSurface)
        SecondaryText(label)
    }
}

@Composable
private fun ExpenseSummaryCard(state: HomeUiState, onClick: () -> Unit) {
    val s = state.monthSummary ?: return
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row {
            Column(Modifier.weight(1f)) {
                SecondaryText(stringResource(R.string.expense_spent))
                Text(Money.format(s.expenseMinor), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                SecondaryText(stringResource(R.string.expense_income))
                Text(Money.format(s.incomeMinor), style = MaterialTheme.typography.titleLarge, color = LocalLifeColors.current.success)
            }
            Column(Modifier.weight(1f)) {
                SecondaryText(stringResource(R.string.expense_savings))
                Text(
                    Money.format(s.savingsMinor), style = MaterialTheme.typography.titleLarge,
                    color = if (s.savingsMinor < 0) MaterialTheme.colorScheme.error else LifeColors.Cyan,
                )
            }
        }
        s.budgetUsedFraction?.let { f ->
            Spacer(Modifier.height(12.dp))
            SecondaryText(stringResource(R.string.expense_budget_used, (f * 100).toInt(), Money.format(s.budgetMinor ?: 0)))
            Spacer(Modifier.height(6.dp))
            GradientProgressBar(f)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    GlassCard(Modifier.fillMaxWidth()) { SecondaryText(text) }
}
