package com.lifeos.ai.domain.usecase

import com.lifeos.ai.domain.model.MonthlySummary
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.model.Transaction
import com.lifeos.ai.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** All money math is done on integer minor units from real database rows. */
object ExpenseCalculator {

    fun monthlySummary(
        transactions: List<Transaction>,
        month: YearMonth,
        budgetMinor: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MonthlySummary {
        val inMonth = transactions.filter { YearMonth.from(Instant.ofEpochMilli(it.date).atZone(zone)) == month }
        val income = inMonth.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor }
        val expenses = inMonth.filter { it.type == TransactionType.EXPENSE }
        val byCategory = expenses.groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amountMinor } }
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }
        return MonthlySummary(income, expenses.sumOf { it.amountMinor }, byCategory, budgetMinor)
    }

    /** Totals per month for the [count] months ending at [endMonth] (oldest first). */
    fun monthlyTotals(
        transactions: List<Transaction>,
        endMonth: YearMonth,
        count: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthTotals> {
        val months = (count - 1 downTo 0).map { endMonth.minusMonths(it.toLong()) }
        val grouped = transactions.groupBy { YearMonth.from(Instant.ofEpochMilli(it.date).atZone(zone)) }
        return months.map { m ->
            val list = grouped[m].orEmpty()
            MonthTotals(
                month = m,
                incomeMinor = list.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor },
                expenseMinor = list.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor },
            )
        }
    }

    fun totalExpenseOn(transactions: List<Transaction>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        transactions.filter {
            it.type == TransactionType.EXPENSE && Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() == date
        }.sumOf { it.amountMinor }
}

data class MonthTotals(val month: YearMonth, val incomeMinor: Long, val expenseMinor: Long)

enum class TaskFilter { ALL, TODAY, UPCOMING, OVERDUE, COMPLETED }

object TaskFilters {
    fun isOverdue(task: Task, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val due = task.dueAt ?: return false
        if (task.completed) return false
        return if (task.hasDueTime) due < now
        else Instant.ofEpochMilli(due).atZone(zone).toLocalDate() < Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }

    fun isDueOn(task: Task, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == date } ?: false

    fun apply(
        tasks: List<Task>,
        filter: TaskFilter,
        query: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Task> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val q = query.trim()
        return tasks.filter { t ->
            val matchesFilter = when (filter) {
                TaskFilter.ALL -> true
                TaskFilter.TODAY -> !t.completed && isDueOn(t, today, zone)
                TaskFilter.UPCOMING -> !t.completed && t.dueAt != null &&
                    Instant.ofEpochMilli(t.dueAt).atZone(zone).toLocalDate() > today
                TaskFilter.OVERDUE -> isOverdue(t, now, zone)
                TaskFilter.COMPLETED -> t.completed
            }
            matchesFilter && (q.isEmpty() || t.title.contains(q, true) || t.description.contains(q, true) || t.category.contains(q, true))
        }
    }

    /** Fraction of tasks due today (or completed today) that are done. */
    fun todayProgress(tasks: List<Task>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val relevant = tasks.filter { t ->
            isDueOn(t, today, zone) ||
                (t.completedAt != null && Instant.ofEpochMilli(t.completedAt).atZone(zone).toLocalDate() == today)
        }
        return relevant.count { it.completed } to relevant.size
    }
}
