package com.lifeos.ai.domain

import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.model.TransactionType
import com.lifeos.ai.domain.usecase.AssistantIntent
import com.lifeos.ai.domain.usecase.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class CommandParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.parse("2026-11-06T10:00")
    private val parser = CommandParser(zone) { now }

    private fun localOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    @Test fun `hinglish wake-up reminder for tomorrow 7 am`() {
        val i = parser.parse("Kal subah 7 baje mujhe uthne ka reminder laga do.") as AssistantIntent.CreateReminder
        assertEquals(LocalDateTime.parse("2026-11-07T07:00"), localOf(i.at!!))
        assertEquals("Uthne", i.title)
        assertEquals(RepeatType.NONE, i.repeat)
    }

    @Test fun `english reminder with pm time and title`() {
        val i = parser.parse("Remind me to call mom at 6:30 pm") as AssistantIntent.CreateReminder
        assertEquals(LocalDateTime.parse("2026-11-06T18:30"), localOf(i.at!!))
        assertEquals("Call mom", i.title)
    }

    @Test fun `devanagari reminder with shaam and daily repeat`() {
        val i = parser.parse("रोज़ शाम 6 बजे दवाई लेने का रिमाइंडर लगाओ") as AssistantIntent.CreateReminder
        assertEquals(RepeatType.DAILY, i.repeat)
        assertEquals(18, localOf(i.at!!).hour)
    }

    @Test fun `time already passed today with no day moves to tomorrow`() {
        val i = parser.parse("set a reminder at 8 am to water plants") as AssistantIntent.CreateReminder
        assertEquals(LocalDateTime.parse("2026-11-07T08:00"), localOf(i.at!!))
    }

    @Test fun `reminder without details opens editor`() {
        assertEquals(AssistantIntent.OpenEditor(AssistantIntent.Target.REMINDER), parser.parse("New reminder create karo"))
    }

    @Test fun `expense with amount and category`() {
        val i = parser.parse("Add 200 rupees expense for food.") as AssistantIntent.AddTransaction
        assertEquals(20000L, i.amountMinor)
        assertEquals(TransactionType.EXPENSE, i.type)
        assertEquals(DefaultCategories.FOOD, i.category)
    }

    @Test fun `hindi expense with rupee symbol and travel category`() {
        val i = parser.parse("₹150.50 auto ka kharcha add karo") as AssistantIntent.AddTransaction
        assertEquals(15050L, i.amountMinor)
        assertEquals(DefaultCategories.TRAVEL, i.category)
    }

    @Test fun `income is detected`() {
        val i = parser.parse("Received salary 45000 rupees") as AssistantIntent.AddTransaction
        assertEquals(TransactionType.INCOME, i.type)
        assertEquals(DefaultCategories.SALARY, i.category)
        assertEquals(4_500_000L, i.amountMinor)
    }

    @Test fun `shopping list item in hinglish`() {
        assertEquals(AssistantIntent.AddShoppingItems(listOf("Milk")), parser.parse("Shopping list mein milk add karo"))
    }

    @Test fun `multiple shopping items in english`() {
        val i = parser.parse("add eggs, bread and butter to my shopping list") as AssistantIntent.AddShoppingItems
        assertEquals(listOf("Eggs", "Bread", "Butter"), i.items)
    }

    @Test fun `monthly expense query`() {
        assertEquals(AssistantIntent.QueryExpenses(AssistantIntent.Period.MONTH), parser.parse("Show my monthly expenses"))
    }

    @Test fun `today expense query in hinglish and devanagari`() {
        assertEquals(AssistantIntent.QueryExpenses(AssistantIntent.Period.TODAY), parser.parse("Aaj ka expense kitna hai?"))
        assertEquals(AssistantIntent.QueryExpenses(AssistantIntent.Period.TODAY), parser.parse("आज का खर्च कितना है?"))
    }

    @Test fun `schedule query for tomorrow`() {
        assertEquals(AssistantIntent.QuerySchedule(1), parser.parse("Kal ka schedule dikhao"))
    }

    @Test fun `note without content opens the editor`() {
        assertEquals(AssistantIntent.OpenEditor(AssistantIntent.Target.NOTE), parser.parse("Ek note banao"))
    }

    @Test fun `note with content`() {
        val i = parser.parse("Create a note: buy gift for Riya's birthday") as AssistantIntent.CreateNote
        assertTrue(i.content.startsWith("buy gift"))
    }

    @Test fun `task with due day`() {
        val i = parser.parse("add task submit assignment tomorrow") as AssistantIntent.CreateTask
        assertEquals("Submit assignment", i.title)
        assertEquals(LocalDateTime.parse("2026-11-07T23:59").toLocalDate(), localOf(i.dueAt!!).toLocalDate())
    }

    @Test fun `general questions are not treated as commands`() {
        assertNull(parser.parse("Explain artificial intelligence"))
        assertNull(parser.parse("Translate Hindi into English"))
        assertNull(parser.parse("Create my daily schedule"))
    }

    @Test fun `confirmation is required for every write intent`() {
        assertTrue(parser.parse("Add 200 rupees expense for food")!!.requiresConfirmation)
        assertTrue(!parser.parse("Show my monthly expenses")!!.requiresConfirmation)
    }

    @Test fun `voice mode starter prompts parse in both scripts`() {
        for (prompt in listOf("Kal subah 7 baje gym ka reminder laga do", "कल सुबह 7 बजे जिम का रिमाइंडर लगा दो")) {
            val i = parser.parse(prompt) as AssistantIntent.CreateReminder
            assertEquals(prompt, LocalDateTime.parse("2026-11-07T07:00"), localOf(i.at!!))
            assertTrue(prompt, i.requiresConfirmation)
        }
        for (prompt in listOf("Aaj ka expense kitna hai?", "आज का खर्च कितना है?", "Kal ka schedule dikhao", "कल का शेड्यूल दिखाओ")) {
            val i = parser.parse(prompt)
            assertTrue(prompt, i != null && !i.requiresConfirmation)
        }
    }
}
