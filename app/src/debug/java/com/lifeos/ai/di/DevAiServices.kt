package com.lifeos.ai.di

import android.util.Log
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.lifeos.ai.BuildConfig
import com.lifeos.ai.data.remote.api.AiRoute
import com.lifeos.ai.data.remote.api.AiService
import com.lifeos.ai.data.remote.api.ClaudeRoute
import com.lifeos.ai.data.remote.api.DirectAiService
import com.lifeos.ai.data.remote.api.FallbackChain
import com.lifeos.ai.data.remote.api.GeminiRoute
import java.time.Duration

private const val TAG = "AiFallback"

/**
 * DEBUG BUILDS: the direct AI client built from the developer keys in local.properties — every
 * listed Gemini model first (each has its own free daily quota), then Claude as the paid last
 * resort. Returns null when no key is set, so the app uses the Firebase backend instead.
 */
internal fun devAiService(): AiService? {
    val routes = buildList<AiRoute> {
        val geminiKey = BuildConfig.DEV_GEMINI_API_KEY
        if (geminiKey.isNotBlank()) {
            GeminiRoute.parseModels(BuildConfig.DEV_GEMINI_MODELS).forEach { add(GeminiRoute(it, geminiKey)) }
        }
        val claudeKey = BuildConfig.DEV_ANTHROPIC_API_KEY
        if (claudeKey.isNotBlank()) {
            val client = AnthropicOkHttpClient.builder()
                .apiKey(claudeKey)
                .maxRetries(0) // FallbackChain owns retries, so attempts don't multiply
                .timeout(Duration.ofSeconds(30))
                .build()
            add(ClaudeRoute(client, BuildConfig.DEV_ANTHROPIC_MODEL.ifBlank { "claude-opus-5-5" }))
        }
    }
    if (routes.isEmpty()) return null
    Log.w(TAG, "DEBUG build: AI calls go straight to ${routes.joinToString { it.id }} with developer keys. Never share this build.")
    return DirectAiService(FallbackChain(routes, log = { Log.i(TAG, it) }))
}
