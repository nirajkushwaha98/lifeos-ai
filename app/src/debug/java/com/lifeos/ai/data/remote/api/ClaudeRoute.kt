package com.lifeos.ai.data.remote.api

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Claude through the official Anthropic Java SDK — DEBUG BUILDS ONLY. It is the last model in the
 * fallback chain: Claude is billed per request, so it only answers once every free Gemini model
 * is out of quota. The SDK's own retries are off ([com.anthropic.client.okhttp.AnthropicOkHttpClient]
 * is built with maxRetries(0)) so [FallbackChain] is the single retry layer.
 */
class ClaudeRoute(private val client: AnthropicClient, private val model: String) : AiRoute {

    override val id: String = model

    /** Haiku has no server-side refusal fallback (the parameter is rejected or ignored there). */
    private val serverFallback = "haiku" !in model

    override suspend fun send(prompt: AiPrompt): RouteResult = try {
        val message = runInterruptible(Dispatchers.IO) { client.beta().messages().create(params(prompt)) }
        // Check the stop reason before reading content: a declined request carries no answer.
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            RouteResult.Blocked
        } else {
            val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            if (text.isEmpty()) RouteResult.Failure(AiError.UNKNOWN, FailureKind.TRANSIENT) else RouteResult.Answer(text)
        }
    } catch (e: RateLimitException) {
        RouteResult.Failure(AiError.RATE_LIMITED, FailureKind.RATE_LIMITED, retryAfterMs(e))
    } catch (e: InternalServerException) { // 500 api_error, 529 overloaded_error
        RouteResult.Failure(AiError.NETWORK, FailureKind.TRANSIENT, retryAfterMs(e))
    } catch (e: UnauthorizedException) {
        RouteResult.Failure(AiError.SERVER_NOT_CONFIGURED, FailureKind.UNAVAILABLE)
    } catch (e: PermissionDeniedException) {
        RouteResult.Failure(AiError.SERVER_NOT_CONFIGURED, FailureKind.UNAVAILABLE)
    } catch (e: NotFoundException) { // model id not available to this key
        RouteResult.Failure(AiError.SERVER_NOT_CONFIGURED, FailureKind.UNAVAILABLE)
    } catch (e: BadRequestException) {
        // An empty prepaid balance is a 400 invalid_request_error with no distinct type — only its
        // message says so. Treat it like an exhausted quota (skip Claude) rather than a bad request.
        if (isCreditExhausted(e)) RouteResult.Failure(AiError.DAILY_LIMIT, FailureKind.QUOTA_EXHAUSTED)
        else RouteResult.Failure(AiError.INVALID_INPUT, FailureKind.INVALID_REQUEST)
    } catch (e: AnthropicServiceException) { // e.g. 402 billing_error, 413 request_too_large
        if (e.statusCode() == 402) RouteResult.Failure(AiError.DAILY_LIMIT, FailureKind.QUOTA_EXHAUSTED)
        else RouteResult.Failure(AiError.INVALID_INPUT, FailureKind.INVALID_REQUEST)
    } catch (e: AnthropicIoException) { // network failure or timeout before a response
        RouteResult.Failure(AiError.NETWORK, FailureKind.TRANSIENT)
    } catch (e: AnthropicException) {
        RouteResult.Failure(AiError.UNKNOWN, FailureKind.TRANSIENT)
    }

    private fun params(prompt: AiPrompt): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            // Adaptive thinking is always on and counts toward max_tokens, so leave it room.
            .maxTokens(if (prompt.quick) QUICK_MAX_TOKENS else MAX_TOKENS)
            // No temperature: current Claude models reject sampling parameters.
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(if (prompt.quick) BetaOutputConfig.Effort.LOW else BetaOutputConfig.Effort.MEDIUM)
                    .build(),
            )
        if (serverFallback) {
            // If a safety classifier declines, the API re-runs the request on Anthropic's recommended model.
            builder.addBeta(SERVER_FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        prompt.system?.let { builder.system(it) }
        // The first message must come from the user; consecutive same-role turns are merged by the API.
        prompt.turns.dropWhile { !it.fromUser }.forEach { turn ->
            if (turn.fromUser) builder.addUserMessage(turn.text) else builder.addAssistantMessage(turn.text)
        }
        return builder.build()
    }

    private fun isCreditExhausted(e: AnthropicServiceException): Boolean =
        listOf(e.message.orEmpty(), runCatching { e.body().toString() }.getOrDefault(""))
            .any { it.contains("credit balance", ignoreCase = true) }

    private fun retryAfterMs(e: AnthropicServiceException): Long? =
        e.headers().values("retry-after").firstOrNull()?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }

    private companion object {
        const val QUICK_MAX_TOKENS = 4_096L
        const val MAX_TOKENS = 16_000L
        const val SERVER_FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
