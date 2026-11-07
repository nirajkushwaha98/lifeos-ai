package com.lifeos.ai.domain.usecase

import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.model.TransactionType
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Validates the structured intent JSON returned by the AI backend and converts it into an
 * [AssistantIntent]. Anything malformed, out of range or unknown is rejected (returns null), so
 * the model can never trigger arbitrary or invalid actions.
 */
object RemoteIntentMapper {

    fun map(raw: Map<String, Any?>, zone: ZoneId = ZoneId.systemDefault(), now: LocalDateTime = LocalDateTime.now(zone)): AssistantIntent? {
        return when (raw["type"] as? String) {
            "create_reminder" -> {
                val title = cleanText(raw["title"], 200) ?: return null
                val at = parseDateTime(raw["datetime"])?.takeIf { it.isAfter(now) }
                val repeat = when ((raw["repeat"] as? String)?.lowercase()) {
                    "daily" -> RepeatType.DAILY
                    "weekly" -> RepeatType.WEEKLY
                    "monthly" -> RepeatType.MONTHLY
                    else -> RepeatType.NONE
                }
                AssistantIntent.CreateReminder(title, at?.atZone(zone)?.toInstant()?.toEpochMilli(), repeat)
            }
            "add_transaction" -> {
                val amount = (raw["amount"] as? Number)?.toDouble() ?: (raw["amount"] as? String)?.toDoubleOrNull() ?: return null
                if (amount <= 0 || amount > 100_000_000 || amount.isNaN()) return null
                val minor = Math.round(amount * 100)
                val type = if ((raw["kind"] as? String)?.lowercase() == "income") TransactionType.INCOME else TransactionType.EXPENSE
                val cat = (raw["category"] as? String)?.lowercase()?.trim()
                val category = if (cat != null && cat in DefaultCategories.all) cat else DefaultCategories.OTHER
                AssistantIntent.AddTransaction(minor, type, category, cleanText(raw["description"], 200).orEmpty())
            }
            "add_shopping_items" -> {
                val items = (raw["items"] as? List<*>)?.mapNotNull { cleanText(it, 60) }?.take(20).orEmpty()
                if (items.isEmpty()) null else AssistantIntent.AddShoppingItems(items)
            }
            "create_note" -> {
                val content = cleanText(raw["content"], 5000) ?: return null
                AssistantIntent.CreateNote(cleanText(raw["title"], 100) ?: content.take(40), content)
            }
            "create_task" -> {
                val title = cleanText(raw["title"], 200) ?: return null
                val due = parseDateTime(raw["due"])?.atZone(zone)?.toInstant()?.toEpochMilli()
                AssistantIntent.CreateTask(title, due)
            }
            "query_expenses" -> AssistantIntent.QueryExpenses(
                if ((raw["period"] as? String) == "month") AssistantIntent.Period.MONTH else AssistantIntent.Period.TODAY
            )
            "query_schedule" -> {
                val offset = (raw["day_offset"] as? Number)?.toInt() ?: 0
                if (offset !in -1..7) null else AssistantIntent.QuerySchedule(offset)
            }
            else -> null
        }
    }

    private fun cleanText(v: Any?, max: Int): String? =
        (v as? String)?.replace(Regex("[\\p{Cntrl}&&[^\n]]"), "")?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    private fun parseDateTime(v: Any?): LocalDateTime? {
        val s = (v as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try { LocalDateTime.parse(s.removeSuffix("Z").take(19)) } catch (e: DateTimeParseException) { null }
    }
}
