package com.lifeos.ai.data.remote.api

import com.lifeos.ai.domain.model.ChatMessage

/**
 * DEBUG BUILDS ONLY — answers straight from the device using the developer keys in
 * local.properties, trying each configured model in turn (see [FallbackChain]), so AI chat and
 * Voice mode can be tested before the Firebase backend is deployed. Release builds never contain
 * a key and always use [FirebaseAiService].
 *
 * A key inside an APK can be extracted, so never share or publish a debug build that has one.
 */
class DirectAiService(private val chain: FallbackChain) : AiService {

    override val isAvailable: Boolean get() = !chain.isEmpty

    /** The developer keys authorise calls, so guests can use AI in debug builds. */
    override val requiresSignIn: Boolean get() = false

    override suspend fun chat(message: String, history: List<ChatMessage>, style: ChatStyle): AiResult<String> {
        val text = message.trim()
        if (text.isEmpty() || text.length > AiLimits.MAX_MESSAGE_CHARS) return AiResult.Failure(AiError.INVALID_INPUT)
        return chain.send(AiPrompts.chat(text, buildHistoryPayload(history), style))
    }

    override suspend fun interpret(message: String, nowIso: String, timeZone: String): AiResult<Map<String, Any?>> {
        val text = message.trim()
        if (text.isEmpty() || text.length > MAX_INTENT_CHARS) return AiResult.Failure(AiError.INVALID_INPUT)
        return when (val result = chain.send(AiPrompts.intent(text, nowIso, timeZone))) {
            is AiResult.Success -> AiPrompts.parseIntent(result.value)?.let { AiResult.Success(it) }
                ?: AiResult.Failure(AiError.UNKNOWN)
            is AiResult.Failure -> result
        }
    }

    override suspend fun noteTool(tool: NoteTool, text: String): AiResult<String> {
        val t = text.trim()
        if (t.isEmpty() || t.length > AiLimits.MAX_MESSAGE_CHARS * 2) return AiResult.Failure(AiError.INVALID_INPUT)
        return chain.send(AiPrompts.tool(tool, t))
    }

    private companion object {
        const val MAX_INTENT_CHARS = 1000
    }
}
