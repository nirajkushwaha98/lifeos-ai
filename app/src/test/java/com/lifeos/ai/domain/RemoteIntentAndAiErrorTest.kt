package com.lifeos.ai.domain

import com.lifeos.ai.data.remote.api.AiError
import com.lifeos.ai.data.remote.api.AiLimits
import com.lifeos.ai.data.remote.api.buildHistoryPayload
import com.lifeos.ai.data.remote.api.mapFunctionsErrorCode
import com.lifeos.ai.domain.model.ChatMessage
import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.MessageRole
import com.lifeos.ai.domain.model.TransactionType
import com.lifeos.ai.domain.usecase.AssistantIntent
import com.lifeos.ai.domain.usecase.RemoteIntentMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RemoteIntentAndAiErrorTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.parse("2026-11-06T10:00")

    @Test fun `firebase function error codes map to user-facing AI errors`() {
        assertEquals(AiError.SIGN_IN_REQUIRED, mapFunctionsErrorCode("UNAUTHENTICATED"))
        assertEquals(AiError.RATE_LIMITED, mapFunctionsErrorCode("RESOURCE_EXHAUSTED"))
        assertEquals(AiError.DAILY_LIMIT, mapFunctionsErrorCode("RESOURCE_EXHAUSTED", "Daily AI quota used up on every model"))
        assertEquals(AiError.INVALID_INPUT, mapFunctionsErrorCode("INVALID_ARGUMENT"))
        assertEquals(AiError.SERVER_NOT_CONFIGURED, mapFunctionsErrorCode("FAILED_PRECONDITION", "Gemini API key is not configured"))
        assertEquals(AiError.BLOCKED, mapFunctionsErrorCode("FAILED_PRECONDITION", "Response blocked by safety filters"))
        assertEquals(AiError.SERVER_NOT_CONFIGURED, mapFunctionsErrorCode("NOT_FOUND"))
        assertEquals(AiError.TIMEOUT, mapFunctionsErrorCode("DEADLINE_EXCEEDED"))
        assertEquals(AiError.NETWORK, mapFunctionsErrorCode("UNAVAILABLE"))
        assertEquals(AiError.UNKNOWN, mapFunctionsErrorCode("INTERNAL"))
    }

    @Test fun `history payload excludes errors, is capped, and truncates long messages`() {
        val msgs = (1..20).map { i ->
            ChatMessage("m$i", "c", if (i % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER, "x".repeat(if (i == 20) 5000 else 10), i.toLong(), isError = i == 19)
        }
        val payload = buildHistoryPayload(msgs)
        assertEquals(AiLimits.MAX_HISTORY_MESSAGES, payload.size)
        assertTrue(payload.all { it["content"]!!.length <= AiLimits.MAX_HISTORY_CHARS_EACH })
        assertTrue(payload.all { it["role"] == "user" || it["role"] == "model" })
    }

    @Test fun `valid remote transaction is accepted and normalized`() {
        val i = RemoteIntentMapper.map(mapOf("type" to "add_transaction", "amount" to 200, "kind" to "expense", "category" to "FOOD"), zone, now)
        assertEquals(AssistantIntent.AddTransaction(20000, TransactionType.EXPENSE, DefaultCategories.FOOD, ""), i)
    }

    @Test fun `unknown category falls back to other, invalid amounts rejected`() {
        val i = RemoteIntentMapper.map(mapOf("type" to "add_transaction", "amount" to 10.5, "category" to "crypto"), zone, now) as AssistantIntent.AddTransaction
        assertEquals(DefaultCategories.OTHER, i.category)
        assertNull(RemoteIntentMapper.map(mapOf("type" to "add_transaction", "amount" to -5), zone, now))
        assertNull(RemoteIntentMapper.map(mapOf("type" to "add_transaction", "amount" to 1e12), zone, now))
        assertNull(RemoteIntentMapper.map(mapOf("type" to "add_transaction"), zone, now))
    }

    @Test fun `reminder in the past loses its time so the user must confirm a new one`() {
        val i = RemoteIntentMapper.map(mapOf("type" to "create_reminder", "title" to "Gym", "datetime" to "2026-11-05T07:00"), zone, now) as AssistantIntent.CreateReminder
        assertNull(i.at)
        val ok = RemoteIntentMapper.map(mapOf("type" to "create_reminder", "title" to "Gym", "datetime" to "2026-11-07T07:00", "repeat" to "daily"), zone, now) as AssistantIntent.CreateReminder
        assertEquals(LocalDateTime.parse("2026-11-07T07:00").atZone(zone).toInstant().toEpochMilli(), ok.at)
    }

    @Test fun `unknown or destructive intent types are rejected`() {
        assertNull(RemoteIntentMapper.map(mapOf("type" to "delete_all_data"), zone, now))
        assertNull(RemoteIntentMapper.map(mapOf("type" to "create_reminder", "title" to "  "), zone, now))
        assertNull(RemoteIntentMapper.map(mapOf("type" to "query_schedule", "day_offset" to 400), zone, now))
    }
}
