package com.lifeos.ai.data

import com.lifeos.ai.data.remote.api.AiError
import com.lifeos.ai.data.remote.api.AiPrompts
import com.lifeos.ai.data.remote.api.ChatStyle
import com.lifeos.ai.data.remote.api.FailureKind
import com.lifeos.ai.data.remote.api.GeminiRequests
import com.lifeos.ai.data.remote.api.GeminiRoute
import com.lifeos.ai.data.remote.api.HttpReply
import com.lifeos.ai.data.remote.api.NoteTool
import com.lifeos.ai.data.remote.api.RouteResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class GeminiRequestsTest {

    private fun JsonObject.config() = getValue("generationConfig").jsonObject
    private fun JsonObject.thinking() = config()["thinkingConfig"]?.jsonObject
    private fun JsonObject.system() = getValue("systemInstruction").jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content

    @Test fun `voice chat uses the spoken prompt with minimal reasoning per model family`() {
        val history = listOf(mapOf("role" to "user", "content" to "hi"), mapOf("role" to "model", "content" to "hello"))
        val prompt = AiPrompts.chat("aaj kya karun?", history, ChatStyle.VOICE)
        val flash25 = GeminiRequests.body(prompt, "gemini-2.5-flash")
        assertTrue(flash25.system().contains("read aloud"))
        assertEquals(0, flash25.thinking()!!.getValue("thinkingBudget").jsonPrimitive.int)
        val roles = flash25.getValue("contents").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }
        assertEquals(listOf("user", "model", "user"), roles)

        val lite = GeminiRequests.body(prompt, "gemini-3.5-flash-lite")
        assertEquals("minimal", lite.thinking()!!.getValue("thinkingLevel").jsonPrimitive.content)
        assertFalse("Gemini 3 keeps its default temperature", lite.config().containsKey("temperature"))
        assertEquals("low", GeminiRequests.body(prompt, "gemini-3-pro-preview").thinking()!!.getValue("thinkingLevel").jsonPrimitive.content)
        assertNull("aliases keep their defaults", GeminiRequests.body(prompt, "gemini-flash-latest").thinking())
    }

    @Test fun `text chat keeps a bounded thinking budget with room for the answer`() {
        val body = GeminiRequests.body(AiPrompts.chat("explain", emptyList(), ChatStyle.TEXT), "gemini-2.5-flash")
        assertFalse(body.system().contains("read aloud"))
        assertEquals(512, body.thinking()!!.getValue("thinkingBudget").jsonPrimitive.int)
        assertTrue(body.config().getValue("maxOutputTokens").jsonPrimitive.int >= 2048)
        assertEquals("low", GeminiRequests.body(AiPrompts.chat("x", emptyList(), ChatStyle.TEXT), "gemini-3-flash-preview").thinking()!!.getValue("thinkingLevel").jsonPrimitive.content)
    }

    @Test fun `intent requests ask for JSON and sanitise the time zone`() {
        val body = GeminiRequests.body(AiPrompts.intent("kal 7 baje \"gym\"", "2026-11-07T10:00:00.123", "bad zone!!"), "gemini-2.5-flash")
        assertEquals("application/json", body.config().getValue("responseMimeType").jsonPrimitive.content)
        assertEquals(0, body.thinking()!!.getValue("thinkingBudget").jsonPrimitive.int)
        val text = body.getValue("contents").jsonArray[0].jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text.contains("2026-11-07T10:00:00 (time zone Asia/Kolkata)"))
        assertTrue(text.contains("User command: \"kal 7 baje \\\"gym\\\"\""))
        assertTrue(AiPrompts.tool(NoteTool.SUMMARIZE, "note").quick)
    }

    @Test fun `responses are joined, and safety blocks are reported`() {
        val ok = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"Hi"},{"text":" there "}]}}]}"""
        assertEquals(RouteResult.Answer("Hi there"), GeminiRequests.classify(200, ok, null))
        assertEquals(RouteResult.Blocked, GeminiRequests.classify(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""", null))
        assertEquals(RouteResult.Blocked, GeminiRequests.classify(200, """{"candidates":[{"finishReason":"SAFETY"}]}""", null))
        assertEquals(FailureKind.TRANSIENT, (GeminiRequests.classify(200, "{}", null) as RouteResult.Failure).kind)
    }

    @Test fun `a daily free-tier 429 is told apart from a per-minute one`() {
        val daily = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"15769s"}]}}"""
        assertEquals(RouteResult.Failure(AiError.DAILY_LIMIT, FailureKind.QUOTA_EXHAUSTED, 15_769_000L), GeminiRequests.classify(429, daily, null))

        val perMinute = """{"error":{"details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7.5s"}]}}"""
        assertEquals(RouteResult.Failure(AiError.RATE_LIMITED, FailureKind.RATE_LIMITED, 7_500L), GeminiRequests.classify(429, perMinute, null))
        assertEquals(RouteResult.Failure(AiError.RATE_LIMITED, FailureKind.RATE_LIMITED, 3_000L), GeminiRequests.classify(429, "{}", "3"))
    }

    @Test fun `HTTP errors map to retry, skip or stop`() {
        assertEquals(FailureKind.TRANSIENT, (GeminiRequests.classify(503, "{}", null) as RouteResult.Failure).kind)
        assertEquals(FailureKind.TRANSIENT, (GeminiRequests.classify(504, "{}", null) as RouteResult.Failure).kind)
        assertEquals(FailureKind.UNAVAILABLE, (GeminiRequests.classify(404, "{}", null) as RouteResult.Failure).kind)
        assertEquals(FailureKind.UNAVAILABLE, (GeminiRequests.classify(403, "{}", null) as RouteResult.Failure).kind)
        assertEquals(FailureKind.UNAVAILABLE, (GeminiRequests.classify(400, """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}""", null) as RouteResult.Failure).kind)
        assertEquals(RouteResult.Failure(AiError.INVALID_INPUT, FailureKind.INVALID_REQUEST), GeminiRequests.classify(400, "{}", null))
    }

    @Test fun `the route sends the key only as a header and maps timeouts to a retry`() = runBlocking {
        var seenUrl = ""
        var seenHeaders = emptyMap<String, String>()
        val ok = GeminiRoute("gemini-3.5-flash-lite", "test-key", transport = { url, headers, _, _ ->
            seenUrl = url
            seenHeaders = headers
            HttpReply(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""", null)
        })
        assertEquals(RouteResult.Answer("ok"), ok.send(AiPrompts.chat("hi", emptyList(), ChatStyle.VOICE)))
        assertTrue(seenUrl.endsWith("/models/gemini-3.5-flash-lite:generateContent"))
        assertFalse(seenUrl.contains("test-key"))
        assertEquals("test-key", seenHeaders["x-goog-api-key"])

        val slow = GeminiRoute("m", "k", transport = { _, _, _, _ -> throw SocketTimeoutException() })
        assertEquals(RouteResult.Failure(AiError.TIMEOUT, FailureKind.TRANSIENT), slow.send(AiPrompts.chat("hi", emptyList(), ChatStyle.VOICE)))
    }

    @Test fun `model lists are cleaned up`() {
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-2.5-flash"), GeminiRoute.parseModels(" gemini-3.5-flash-lite, ,gemini-2.5-flash,gemini-2.5-flash,bad model"))
    }

    @Test fun `intent JSON is whitelisted field by field`() {
        assertNull(AiPrompts.parseIntent("not json"))
        assertNull(AiPrompts.parseIntent("""{"type":"delete_everything"}"""))
        assertNull(AiPrompts.parseIntent("""{"type":"none"}"""))
        val tx = AiPrompts.parseIntent("```json\n{\"type\":\"add_transaction\",\"amount\":200,\"kind\":\"expense\",\"category\":\"food\",\"evil\":{\"x\":1}}\n```")
        assertEquals(mapOf("type" to "add_transaction", "kind" to "expense", "category" to "food", "amount" to 200.0), tx)
        val list = AiPrompts.parseIntent("""{"type":"add_shopping_items","items":["milk",3,"${"x".repeat(80)}"]}""")!!
        assertEquals(listOf("milk", "x".repeat(60)), list["items"])
        assertEquals(1, AiPrompts.parseIntent("""{"type":"query_schedule","day_offset":1}""")!!["day_offset"])
    }
}
