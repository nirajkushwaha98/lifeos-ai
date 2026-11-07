package com.lifeos.ai.domain

import com.lifeos.ai.core.common.Money
import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.Transaction
import com.lifeos.ai.domain.model.TransactionType
import com.lifeos.ai.domain.usecase.ExpenseCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class ExpenseCalculatorTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var seq = 0

    private fun tx(amountRupees: Double, type: TransactionType, category: String, date: String) = Transaction(
        id = "t${seq++}", amountMinor = Math.round(amountRupees * 100), type = type, category = category,
        date = LocalDateTime.parse(date).atZone(zone).toInstant().toEpochMilli(), createdAt = 0, updatedAt = 0,
    )

    private val data = listOf(
        tx(50000.0, TransactionType.INCOME, DefaultCategories.SALARY, "2026-11-01T10:00"),
        tx(200.0, TransactionType.EXPENSE, DefaultCategories.FOOD, "2026-11-02T13:00"),
        tx(350.50, TransactionType.EXPENSE, DefaultCategories.FOOD, "2026-11-03T20:00"),
        tx(1200.0, TransactionType.EXPENSE, DefaultCategories.TRAVEL, "2026-11-04T09:00"),
        tx(999.99, TransactionType.EXPENSE, DefaultCategories.BILLS, "2026-11-30T23:59"),
        // Other months must be excluded
        tx(5000.0, TransactionType.EXPENSE, DefaultCategories.SHOPPING, "2026-10-31T23:59"),
        tx(100.0, TransactionType.EXPENSE, DefaultCategories.FOOD, "2026-12-01T00:00"),
    )

    @Test fun `monthly summary uses only that month's records with exact paise`() {
        val s = ExpenseCalculator.monthlySummary(data, YearMonth.of(2026, 11), budgetMinor = 300000, zone = zone)
        assertEquals(5_000_000L, s.incomeMinor)
        assertEquals(20000L + 35050L + 120000L + 99999L, s.expenseMinor)
        assertEquals(5_000_000L - 275049L, s.savingsMinor)
        assertEquals(0.91683f, s.budgetUsedFraction!!, 0.0001f)
    }

    @Test fun `category breakdown is sorted by amount`() {
        val s = ExpenseCalculator.monthlySummary(data, YearMonth.of(2026, 11), null, zone)
        assertEquals(listOf(DefaultCategories.TRAVEL, DefaultCategories.BILLS, DefaultCategories.FOOD), s.byCategory.map { it.first })
        assertEquals(55050L, s.byCategory.first { it.first == DefaultCategories.FOOD }.second)
        assertNull(s.budgetUsedFraction)
    }

    @Test fun `empty month yields zeros`() {
        val s = ExpenseCalculator.monthlySummary(data, YearMonth.of(2025, 1), null, zone)
        assertEquals(0L, s.incomeMinor); assertEquals(0L, s.expenseMinor); assertEquals(0, s.byCategory.size)
    }

    @Test fun `monthly totals for trend chart, oldest first`() {
        val totals = ExpenseCalculator.monthlyTotals(data, YearMonth.of(2026, 12), 3, zone)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12)), totals.map { it.month })
        assertEquals(listOf(500000L, 275049L, 10000L), totals.map { it.expenseMinor })
        assertEquals(listOf(0L, 5_000_000L, 0L), totals.map { it.incomeMinor })
    }

    @Test fun `daily expense total`() {
        assertEquals(35050L, ExpenseCalculator.totalExpenseOn(data, LocalDate.of(2026, 11, 3), zone))
    }

    @Test fun `money parsing rejects invalid input and keeps paise exact`() {
        assertEquals(25050L, Money.parseToMinor("250.50"))
        assertEquals(120000L, Money.parseToMinor("1,200"))
        assertEquals(9999L, Money.parseToMinor("₹99.99"))
        listOf("", "abc", "-5", "0", "1.234", "999999999999").forEach { assertNull(it, Money.parseToMinor(it)) }
    }

    @Test fun `money plain formatting round trips`() {
        assertEquals("250", Money.toPlain(25000))
        assertEquals("99.50", Money.toPlain(9950))
    }
}
