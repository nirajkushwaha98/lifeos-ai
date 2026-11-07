package com.lifeos.ai.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.ai.data.repository.ProfileRepository
import com.lifeos.ai.domain.model.MonthlySummary
import com.lifeos.ai.domain.model.Note
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.model.UserProfile
import com.lifeos.ai.domain.repository.NoteRepository
import com.lifeos.ai.domain.repository.ReminderRepository
import com.lifeos.ai.domain.repository.TaskRepository
import com.lifeos.ai.domain.repository.TransactionRepository
import com.lifeos.ai.domain.usecase.ExpenseCalculator
import com.lifeos.ai.domain.usecase.TaskFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val today: LocalDate = LocalDate.now(),
    val todayTasks: List<Task> = emptyList(),
    val overdueCount: Int = 0,
    val upcomingReminders: List<Reminder> = emptyList(),
    val monthSummary: MonthlySummary? = null,
    val recentNotes: List<Note> = emptyList(),
    val tasksDoneToday: Int = 0,
    val tasksTotalToday: Int = 0,
    val pendingTasks: Int = 0,
    val completedThisWeek: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val taskRepo: TaskRepository,
    private val reminderRepo: ReminderRepository,
    transactionRepo: TransactionRepository,
    noteRepo: NoteRepository,
    profileRepo: ProfileRepository,
) : ViewModel() {

    private val month = YearMonth.now()
    private data class Money(val tx: List<com.lifeos.ai.domain.model.Transaction>, val budget: Long?)

    private val moneyFlow = combine(transactionRepo.observeTransactions(), transactionRepo.observeBudget(month)) { tx, b -> Money(tx, b) }

    val state: StateFlow<HomeUiState> = combine(
        profileRepo.profile,
        taskRepo.observeTasks(),
        reminderRepo.observeReminders(),
        moneyFlow,
        noteRepo.observeNotes(),
    ) { profile, tasks, reminders, money, notes ->
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val (done, total) = TaskFilters.todayProgress(tasks, now)
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        HomeUiState(
            loading = false,
            profile = profile,
            today = today,
            todayTasks = tasks.filter { !it.completed && (TaskFilters.isDueOn(it, today) || TaskFilters.isOverdue(it, now)) }.take(5),
            overdueCount = tasks.count { TaskFilters.isOverdue(it, now) },
            upcomingReminders = reminders.filter { it.enabled && !it.completed && it.scheduledAt >= now - 60_000 }
                .sortedBy { it.scheduledAt }.take(3),
            monthSummary = ExpenseCalculator.monthlySummary(money.tx, month, money.budget),
            recentNotes = notes.filter { !it.isArchived }.take(3),
            tasksDoneToday = done,
            tasksTotalToday = total,
            pendingTasks = tasks.count { !it.completed },
            completedThisWeek = tasks.count { it.completed && (it.completedAt ?: 0) >= weekAgo },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setReminderEnabled(reminder: Reminder, enabled: Boolean) {
        viewModelScope.launch { reminderRepo.setEnabled(reminder.id, enabled) }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch { taskRepo.setCompleted(task.id, !task.completed) }
    }
}
