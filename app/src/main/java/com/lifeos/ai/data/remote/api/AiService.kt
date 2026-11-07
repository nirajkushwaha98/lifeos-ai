package com.lifeos.ai.data.remote.api

import android.util.Log
import com.google.firebase.functions.FirebaseFunctionsException
import com.lifeos.ai.data.remote.firebase.FirebaseProvider
import com.lifeos.ai.domain.model.ChatMessage
import com.lifeos.ai.domain.model.MessageRole
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class AiError {
    /** Firebase / backend not configured in this build. */
    NOT_CONFIGURED,
    /** Backend reachable but the Gemini key is missing on the server. */
    SERVER_NOT_CONFIGURED,
    SIGN_IN_REQUIRED,
    /** Too many requests right now (per-minute limits); worth trying again shortly. */
    RATE_LIMITED,
    /** The daily AI quota is used up on every available model; it resets later. */
    DAILY_LIMIT,
    INVALID_INPUT,
    NETWORK,
    TIMEOUT,
    BLOCKED,
    UNKNOWN,
}

sealed interface AiResult<out T> {
    data class Success<T>(val value: T) : AiResult<T>
    data class Failure(val error: AiError) : AiResult<Nothing>
}

enum class NoteTool { SUMMARIZE, REWRITE, TRANSLATE_HI, TRANSLATE_EN, CHECKLIST }

/** How a chat answer will be presented: on screen (Markdown allowed) or read aloud in voice mode. */
enum class ChatStyle { TEXT, VOICE }

/** Abstraction over the secure AI backend (Firebase Cloud Function -> Gemini). */
interface AiService {
    val isAvailable: Boolean
    /** Whether a signed-in account is needed (the backend authorises calls with the user's Firebase ID token). */
    val requiresSignIn: Boolean get() = true
    suspend fun chat(message: String, history: List<ChatMessage>, style: ChatStyle = ChatStyle.TEXT): AiResult<String>
    /** Returns the backend's structured intent JSON (as a raw map) for a natural-language command. */
    suspend fun interpret(message: String, nowIso: String, timeZone: String): AiResult<Map<String, Any?>>
    suspend fun noteTool(tool: NoteTool, text: String): AiResult<String>
}

object AiLimits {
    const val MAX_MESSAGE_CHARS = 4000
    const val MAX_HISTORY_MESSAGES = 10
    const val MAX_HISTORY_CHARS_EACH = 1500
    const val TIMEOUT_MS = 45_000L
}

/** Maps a Firebase Functions error code name to an [AiError]. Kept pure for unit testing. */
fun mapFunctionsErrorCode(code: String, message: String? = null): AiError = when (code) {
    "UNAUTHENTICATED", "PERMISSION_DENIED" -> AiError.SIGN_IN_REQUIRED
    "RESOURCE_EXHAUSTED" ->
        if (message?.contains("daily", ignoreCase = true) == true) AiError.DAILY_LIMIT else AiError.RATE_LIMITED
    "INVALID_ARGUMENT", "OUT_OF_RANGE" -> AiError.INVALID_INPUT
    "FAILED_PRECONDITION" ->
        if (message?.contains("blocked", ignoreCase = true) == true) AiError.BLOCKED else AiError.SERVER_NOT_CONFIGURED
    "NOT_FOUND", "UNIMPLEMENTED" -> AiError.SERVER_NOT_CONFIGURED
    "DEADLINE_EXCEEDED" -> AiError.TIMEOUT
    "UNAVAILABLE" -> AiError.NETWORK
    else -> AiError.UNKNOWN
}

/** Trims the conversation to what the model actually needs (privacy + cost). */
fun buildHistoryPayload(history: List<ChatMessage>): List<Map<String, String>> =
    history.filter { !it.isError && it.content.isNotBlank() }
        .takeLast(AiLimits.MAX_HISTORY_MESSAGES)
        .map {
            mapOf(
                "role" to if (it.role == MessageRole.USER) "user" else "model",
                "content" to it.content.take(AiLimits.MAX_HISTORY_CHARS_EACH),
            )
        }

@Singleton
class FirebaseAiService @Inject constructor(
    private val firebase: FirebaseProvider,
) : AiService {

    override val isAvailable: Boolean get() = firebase.isConfigured

    override suspend fun chat(message: String, history: List<ChatMessage>, style: ChatStyle): AiResult<String> {
        val text = message.trim()
        if (text.isEmpty() || text.length > AiLimits.MAX_MESSAGE_CHARS) return AiResult.Failure(AiError.INVALID_INPUT)
        val payload = mapOf(
            "mode" to "chat",
            "message" to text,
            "history" to buildHistoryPayload(history),
            "style" to style.name.lowercase(),
        )
        return call(payload) { data ->
            (data["text"] as? String)?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun interpret(message: String, nowIso: String, timeZone: String): AiResult<Map<String, Any?>> {
        val text = message.trim()
        if (text.isEmpty() || text.length > 1000) return AiResult.Failure(AiError.INVALID_INPUT)
        return call(mapOf("mode" to "intent", "message" to text, "now" to nowIso, "timeZone" to timeZone)) { data ->
            @Suppress("UNCHECKED_CAST")
            data["intent"] as? Map<String, Any?>
        }
    }

    override suspend fun noteTool(tool: NoteTool, text: String): AiResult<String> {
        val t = text.trim()
        if (t.isEmpty() || t.length > AiLimits.MAX_MESSAGE_CHARS * 2) return AiResult.Failure(AiError.INVALID_INPUT)
        return call(mapOf("mode" to "tool", "tool" to tool.name.lowercase(), "message" to t)) { data ->
            (data["text"] as? String)?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun <T> call(payload: Map<String, Any?>, parse: (Map<*, *>) -> T?): AiResult<T> {
        val functions = firebase.functions ?: return AiResult.Failure(AiError.NOT_CONFIGURED)
        if (firebase.auth?.currentUser == null) return AiResult.Failure(AiError.SIGN_IN_REQUIRED)
        return try {
            val result = withTimeout(AiLimits.TIMEOUT_MS) {
                functions.getHttpsCallable(FUNCTION_NAME)
                    .apply { setTimeout(AiLimits.TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                    .call(payload).await()
            }
            val data = result.data as? Map<*, *> ?: return AiResult.Failure(AiError.UNKNOWN)
            parse(data)?.let { AiResult.Success(it) } ?: AiResult.Failure(AiError.UNKNOWN)
        } catch (e: TimeoutCancellationException) {
            AiResult.Failure(AiError.TIMEOUT)
        } catch (e: FirebaseFunctionsException) {
            Log.w(TAG, "AI function error: ${e.code}", e)
            AiResult.Failure(mapFunctionsErrorCode(e.code.name, e.message))
        } catch (e: IOException) {
            AiResult.Failure(AiError.NETWORK)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI call failed", e)
            AiResult.Failure(AiError.UNKNOWN)
        }
    }

    companion object {
        private const val TAG = "FirebaseAiService"
        const val FUNCTION_NAME = "aiAssist"
    }
}
