package com.lifeos.ai.feature.assistant

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.lifeos.ai.R
import com.lifeos.ai.core.common.Money
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.common.newId
import com.lifeos.ai.data.remote.api.AiError
import com.lifeos.ai.data.repository.AiActivityRepository
import com.lifeos.ai.domain.model.ChecklistItem
import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.Note
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.model.Transaction
import com.lifeos.ai.domain.model.TransactionType
import com.lifeos.ai.domain.repository.NoteRepository
import com.lifeos.ai.domain.repository.ReminderRepository
import com.lifeos.ai.domain.repository.TaskRepository
import com.lifeos.ai.domain.repository.TransactionRepository
import com.lifeos.ai.domain.usecase.AssistantIntent
import com.lifeos.ai.domain.usecase.ExpenseCalculator
import com.lifeos.ai.domain.usecase.TaskFilters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

data class ActionOutcome(val success: Boolean, val message: String)

/**
 * Executes confirmed assistant intents against the real repositories. Success is reported only
 * when the repository returns the persisted record (read back from Room).
 */
@Singleton
class AssistantActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tasks: TaskRepository,
    private val reminders: ReminderRepository,
    private val transactions: TransactionRepository,
    private val notes: NoteRepository,
    private val activity: AiActivityRepository,
) {
    private fun s(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    fun categoryName(key: String): String = when (key) {
        DefaultCategories.FOOD -> s(R.string.cat_food)
        DefaultCategories.SHOPPING -> s(R.string.cat_shopping)
        DefaultCategories.TRAVEL -> s(R.string.cat_travel)
        DefaultCategories.EDUCATION -> s(R.string.cat_education)
        DefaultCategories.BILLS -> s(R.string.cat_bills)
        DefaultCategories.HEALTH -> s(R.string.cat_health)
        DefaultCategories.ENTERTAINMENT -> s(R.string.cat_entertainment)
        DefaultCategories.SALARY -> s(R.string.cat_salary)
        DefaultCategories.BUSINESS -> s(R.string.cat_business)
        DefaultCategories.GIFT -> s(R.string.cat_gift)
        DefaultCategories.OTHER -> s(R.string.cat_other)
        else -> key
    }

    private fun repeatName(r: RepeatType) = when (r) {
        RepeatType.DAILY -> s(R.string.repeat_daily)
        RepeatType.WEEKLY -> s(R.string.repeat_weekly)
        RepeatType.MONTHLY -> s(R.string.repeat_monthly)
        else -> s(R.string.repeat_none)
    }

    /** Human-readable summary shown in the confirmation dialog. */
    fun describe(intent: AssistantIntent): String = when (intent) {
        is AssistantIntent.CreateReminder -> s(
            R.string.confirm_reminder, intent.title,
            intent.at?.let { TimeUtils.formatDateTime(it) } ?: s(R.string.time_not_set), repeatName(intent.repeat),
        )
        is AssistantIntent.AddTransaction -> s(
            if (intent.type == TransactionType.INCOME) R.string.confirm_income else R.string.confirm_expense,
            Money.format(intent.amountMinor), categoryName(intent.category),
        ) + if (intent.description.isNotBlank()) "\n“${intent.description}”" else ""
        is AssistantIntent.AddShoppingItems -> s(R.string.confirm_shopping, intent.items.joinToString(", "))
        is AssistantIntent.CreateNote -> s(R.string.confirm_note, intent.title)
        is AssistantIntent.CreateTask -> s(R.string.confirm_task, intent.title) +
            (intent.dueAt?.let { "\n" + s(R.string.due_label, TimeUtils.formatDateTime(it)) } ?: "")
        else -> ""
    }

    suspend fun execute(intent: AssistantIntent, editedTitle: String? = null): ActionOutcome = try {
        executeInternal(intent, editedTitle)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("AssistantActionExecutor", "Action failed", e)
        ActionOutcome(false, s(R.string.action_failed, e.message ?: ""))
    }

    private suspend fun executeInternal(intent: AssistantIntent, editedTitle: String?): ActionOutcome {
        val now = System.currentTimeMillis()
        return when (intent) {
            is AssistantIntent.CreateReminder -> {
                val at = intent.at ?: return ActionOutcome(false, s(R.string.action_need_time))
                if (at <= now && intent.repeat == RepeatType.NONE) return ActionOutcome(false, s(R.string.error_time_in_past))
                val saved = reminders.upsert(
                    Reminder(newId(), (editedTitle ?: intent.title).trim().ifBlank { intent.title }, scheduledAt = at,
                        repeatType = intent.repeat, createdAt = now, updatedAt = now)
                )
                activity.log("reminder", saved.title)
                ActionOutcome(true, s(R.string.done_reminder, saved.title, TimeUtils.formatDateTime(saved.scheduledAt)))
            }
            is AssistantIntent.AddTransaction -> {
                val saved = transactions.upsert(
                    Transaction(newId(), intent.amountMinor, intent.type, intent.category,
                        description = editedTitle?.takeIf { it.isNotBlank() } ?: intent.description,
                        date = now, createdAt = now, updatedAt = now)
                )
                activity.log("transaction", "${Money.format(saved.amountMinor)} ${categoryName(saved.category)}")
                ActionOutcome(true, s(
                    if (saved.type == TransactionType.INCOME) R.string.done_income else R.string.done_expense,
                    Money.format(saved.amountMinor), categoryName(saved.category),
                ))
            }
            is AssistantIntent.AddShoppingItems -> {
                val title = s(R.string.shopping_list_title)
                val existing = notes.findByTitle(title)
                val newItems = intent.items.map { ChecklistItem(it) }
                val saved = if (existing != null) {
                    notes.upsert(existing.copy(isChecklist = true, checklist = existing.checklist + newItems))
                } else {
                    notes.upsert(Note(newId(), title, category = s(R.string.cat_shopping), isChecklist = true, checklist = newItems, createdAt = now, updatedAt = now))
                }
                val confirmed = intent.items.all { item -> saved.checklist.any { it.text == item } }
                if (!confirmed) return ActionOutcome(false, s(R.string.error_generic))
                activity.log("shopping", intent.items.joinToString())
                ActionOutcome(true, s(R.string.done_shopping, intent.items.joinToString(", ")))
            }
            is AssistantIntent.CreateNote -> {
                val saved = notes.upsert(Note(newId(), editedTitle?.takeIf { it.isNotBlank() } ?: intent.title, intent.content, createdAt = now, updatedAt = now))
                activity.log("note", saved.title)
                ActionOutcome(true, s(R.string.done_note, saved.title))
            }
            is AssistantIntent.CreateTask -> {
                val saved = tasks.upsert(
                    Task(newId(), editedTitle?.takeIf { it.isNotBlank() } ?: intent.title, dueAt = intent.dueAt,
                        hasDueTime = intent.dueAt != null, createdAt = now, updatedAt = now)
                )
                activity.log("task", saved.title)
                ActionOutcome(true, s(R.string.done_task, saved.title))
            }
            else -> ActionOutcome(false, s(R.string.error_generic))
        }
    }

    /** Answers read-only queries from local data. */
    suspend fun answer(intent: AssistantIntent): String = when (intent) {
        is AssistantIntent.QueryExpenses -> {
            val all = transactions.observeTransactions().first()
            if (intent.period == AssistantIntent.Period.TODAY) {
                s(R.string.answer_today_expense, Money.format(ExpenseCalculator.totalExpenseOn(all, LocalDate.now())))
            } else {
                val m = ExpenseCalculator.monthlySummary(all, YearMonth.now(), transactions.observeBudget(YearMonth.now()).first())
                val top = m.byCategory.take(3).joinToString(", ") { "${categoryName(it.first)} ${Money.format(it.second)}" }
                s(R.string.answer_month_expense, Money.format(m.expenseMinor), Money.format(m.incomeMinor), Money.format(m.savingsMinor)) +
                    if (top.isNotEmpty()) "\n" + s(R.string.answer_top_categories, top) else ""
            }
        }
        is AssistantIntent.QuerySchedule -> {
            val day = LocalDate.now().plusDays(intent.dayOffset.toLong())
            val dayTasks = tasks.observeTasks().first().filter { !it.completed && TaskFilters.isDueOn(it, day) }
            val dayReminders = reminders.observeReminders().first().filter {
                it.enabled && !it.completed && TimeUtils.toLocalDate(it.scheduledAt) == day
            }.sortedBy { it.scheduledAt }
            if (dayTasks.isEmpty() && dayReminders.isEmpty()) s(R.string.answer_schedule_empty, TimeUtils.formatFullDate(day))
            else buildString {
                appendLine(s(R.string.answer_schedule_header, TimeUtils.formatFullDate(day)))
                dayReminders.forEach { appendLine("- ⏰ ${TimeUtils.formatTime(it.scheduledAt)} — ${it.title}") }
                dayTasks.forEach { appendLine("- ✅ ${it.title}" + if (it.hasDueTime && it.dueAt != null) " (${TimeUtils.formatTime(it.dueAt)})" else "") }
            }.trim()
        }
        else -> ""
    }
}

@StringRes
fun aiErrorMessage(error: AiError): Int = when (error) {
    AiError.NOT_CONFIGURED -> R.string.ai_error_not_configured
    AiError.SERVER_NOT_CONFIGURED -> R.string.ai_error_server_not_configured
    AiError.SIGN_IN_REQUIRED -> R.string.ai_error_sign_in
    AiError.RATE_LIMITED -> R.string.ai_error_rate_limited
    AiError.DAILY_LIMIT -> R.string.ai_error_daily_limit
    AiError.INVALID_INPUT -> R.string.ai_error_invalid_input
    AiError.NETWORK -> R.string.error_network
    AiError.TIMEOUT -> R.string.ai_error_timeout
    AiError.BLOCKED -> R.string.ai_error_blocked
    AiError.UNKNOWN -> R.string.error_generic
}
