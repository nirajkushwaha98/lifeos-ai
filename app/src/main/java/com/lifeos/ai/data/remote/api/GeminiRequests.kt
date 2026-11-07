package com.lifeos.ai.data.remote.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini generateContent wire format for [GeminiRoute]: renders an [AiPrompt] for a given model and
 * turns a response (or error) into a [RouteResult]. Mirrors backend/functions/src/validation.js.
 */
object GeminiRequests {

    private val RETRY_DELAY = Regex("""^(\d+(?:\.\d+)?)s$""")

    fun body(prompt: AiPrompt, model: String): JsonObject = buildJsonObject {
        prompt.system?.let { system ->
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
        }
        putJsonArray("contents") {
            for (turn in prompt.turns) addJsonObject {
                put("role", if (turn.fromUser) "user" else "model")
                putJsonArray("parts") { addJsonObject { put("text", turn.text) } }
            }
        }
        putJsonObject("generationConfig") {
            // Google recommends the default temperature (1.0) for Gemini 3 models.
            if (!model.startsWith("gemini-3")) put("temperature", prompt.temperature)
            put("maxOutputTokens", prompt.maxTokens)
            if (prompt.json) put("responseMimeType", "application/json")
            thinkingConfig(model, prompt.quick)?.let { put("thinkingConfig", it) }
        }
    }

    /**
     * Thinking tokens count against maxOutputTokens, so an unbounded budget can use up the whole
     * limit before any answer is written. Quick jobs keep reasoning minimal; unknown model
     * families keep their own defaults.
     */
    internal fun thinkingConfig(model: String, quick: Boolean): JsonObject? = when {
        model.startsWith("gemini-2.5-flash") -> buildJsonObject { put("thinkingBudget", if (quick) 0 else 512) }
        model.startsWith("gemini-2.5-pro") -> buildJsonObject { put("thinkingBudget", if (quick) 128 else 1024) }
        model.startsWith("gemini-3") && "pro" in model -> buildJsonObject { put("thinkingLevel", "low") }
        model.startsWith("gemini-3") -> buildJsonObject { put("thinkingLevel", if (quick) "minimal" else "low") }
        else -> null
    }

    sealed interface Reply {
        data class Text(val text: String) : Reply
        data object Blocked : Reply
    }

    /** Extracts the answer text from a generateContent response, or [Reply.Blocked] for safety blocks. */
    fun extractText(response: String): Reply {
        val json = Json.parseToJsonElement(response).jsonObject
        val blockReason = (json["promptFeedback"] as? JsonObject)?.get("blockReason")
        if (blockReason != null && blockReason !is JsonNull) return Reply.Blocked
        val candidate = (json["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return Reply.Text("")
        val finish = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull
        if (finish == "SAFETY" || finish == "PROHIBITED_CONTENT") return Reply.Blocked
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return Reply.Text(text.trim())
    }

    /**
     * Classifies one HTTP exchange. Free-tier 429s come in two kinds: a per-minute limit (worth a
     * short wait) and a per-day quota (`...PerDay...` quota id), which only resets at midnight
     * Pacific time — that model is skipped until then.
     */
    fun classify(status: Int, body: String, retryAfterHeader: String?): RouteResult {
        val headerDelayMs = retryAfterHeader?.trim()?.toLongOrNull()?.times(1000)
        return when {
            status in 200..299 -> when (val reply = runCatching { extractText(body) }.getOrNull()) {
                null -> RouteResult.Failure(AiError.UNKNOWN, FailureKind.TRANSIENT)
                Reply.Blocked -> RouteResult.Blocked
                is Reply.Text ->
                    if (reply.text.isBlank()) RouteResult.Failure(AiError.UNKNOWN, FailureKind.TRANSIENT)
                    else RouteResult.Answer(reply.text)
            }
            status == 429 -> {
                val quota = quotaInfo(body)
                val delay = quota.retryDelayMs ?: headerDelayMs
                if (quota.perDay) RouteResult.Failure(AiError.DAILY_LIMIT, FailureKind.QUOTA_EXHAUSTED, delay)
                else RouteResult.Failure(AiError.RATE_LIMITED, FailureKind.RATE_LIMITED, delay)
            }
            status == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) ->
                RouteResult.Failure(AiError.SERVER_NOT_CONFIGURED, FailureKind.UNAVAILABLE)
            status == 401 || status == 403 || status == 404 ->
                RouteResult.Failure(AiError.SERVER_NOT_CONFIGURED, FailureKind.UNAVAILABLE)
            status == 408 || status >= 500 -> RouteResult.Failure(AiError.NETWORK, FailureKind.TRANSIENT, headerDelayMs)
            else -> RouteResult.Failure(AiError.INVALID_INPUT, FailureKind.INVALID_REQUEST)
        }
    }

    private class QuotaInfo(val perDay: Boolean, val retryDelayMs: Long?)

    /** Reads google.rpc.QuotaFailure / RetryInfo details from a 429 body. */
    private fun quotaInfo(body: String): QuotaInfo {
        val details = runCatching {
            (Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("details") as? JsonArray
        }.getOrNull().orEmpty()
        var perDay = false
        var delayMs: Long? = null
        for (detail in details) {
            val obj = detail as? JsonObject ?: continue
            val type = (obj["@type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (type.endsWith("QuotaFailure")) {
                val violations = (obj["violations"] as? JsonArray).orEmpty()
                perDay = perDay || violations.any { v ->
                    ((v as? JsonObject)?.get("quotaId") as? JsonPrimitive)?.contentOrNull?.contains("PerDay") == true
                }
            }
            if (type.endsWith("RetryInfo")) {
                val raw = (obj["retryDelay"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                RETRY_DELAY.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()?.let { delayMs = (it * 1000).toLong() }
            }
        }
        return QuotaInfo(perDay, delayMs)
    }
}
