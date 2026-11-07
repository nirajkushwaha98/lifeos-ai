package com.lifeos.ai.domain

import com.lifeos.ai.core.ui.MarkdownParser
import com.lifeos.ai.core.ui.MdBlock
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.usecase.TaskFilter
import com.lifeos.ai.domain.usecase.TaskFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TaskFiltersAndMarkdownTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-11-06T12:00")

    private val tasks = listOf(
        Task("today", "Pay electricity bill", dueAt = at("2026-11-06T18:00"), hasDueTime = true, createdAt = 0, updatedAt = 0),
        Task("overdueTime", "Call bank", dueAt = at("2026-11-06T09:00"), hasDueTime = true, createdAt = 0, updatedAt = 0),
        Task("todayNoTime", "Groceries", dueAt = at("2026-11-06T23:59"), hasDueTime = false, createdAt = 0, updatedAt = 0),
        Task("overdueDate", "Return book", dueAt = at("2026-11-04T23:59"), createdAt = 0, updatedAt = 0),
        Task("future", "Dentist", description = "teeth cleaning", dueAt = at("2026-11-10T10:00"), hasDueTime = true, createdAt = 0, updatedAt = 0),
        Task("done", "Old task", completed = true, completedAt = at("2026-11-06T08:00"), dueAt = at("2026-11-06T07:00"), createdAt = 0, updatedAt = 0),
        Task("nodue", "Someday idea", createdAt = 0, updatedAt = 0),
    )

    private fun ids(filter: TaskFilter, q: String = "") = TaskFilters.apply(tasks, filter, q, now, zone).map { it.id }.toSet()

    @Test fun `today excludes completed`() = assertEquals(setOf("today", "overdueTime", "todayNoTime"), ids(TaskFilter.TODAY))
    @Test fun `overdue respects due time vs date-only`() = assertEquals(setOf("overdueTime", "overdueDate"), ids(TaskFilter.OVERDUE))
    @Test fun `upcoming means a later date`() = assertEquals(setOf("future"), ids(TaskFilter.UPCOMING))
    @Test fun `completed filter`() = assertEquals(setOf("done"), ids(TaskFilter.COMPLETED))
    @Test fun `search matches title and description case-insensitively`() {
        assertEquals(setOf("future"), ids(TaskFilter.ALL, "TEETH"))
        assertEquals(setOf("today"), ids(TaskFilter.ALL, "electricity"))
    }

    @Test fun `completed tasks are never overdue`() = assertFalse(TaskFilters.isOverdue(tasks.first { it.id == "done" }, now, zone))

    @Test fun `today progress counts due-today and completed-today`() {
        val (done, total) = TaskFilters.todayProgress(tasks, now, zone)
        assertEquals(1, done); assertEquals(4, total)
    }

    @Test fun `markdown parser recognises blocks`() {
        val blocks = MarkdownParser.parse("# Plan\n\nSome **bold** text\nwrapped.\n\n- one\n2. two\n```\ncode\n```\n> quote")
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(MdBlock.Paragraph("Some **bold** text wrapped."), blocks[1])
        assertEquals(MdBlock.Bullet("one", "•"), blocks[2])
        assertEquals(MdBlock.Bullet("two", "2."), blocks[3])
        assertEquals(MdBlock.Code("code"), blocks[4])
        assertEquals(MdBlock.Quote("quote"), blocks[5])
    }
}
