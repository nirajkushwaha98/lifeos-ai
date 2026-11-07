package com.lifeos.ai.data.remote.api

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.random.Random

/** One model on one provider that can answer an [AiPrompt]. */
interface AiRoute {
    /** Name for logs and cool-downs, e.g. "gemini-3.5-flash-lite". Never contains secrets. */
    val id: String
    suspend fun send(prompt: AiPrompt): RouteResult
}

sealed interface RouteResult {
    data class Answer(val text: String) : RouteResult

    /** The provider's safety system declined; other models would decline too. */
    data object Blocked : RouteResult

    data class Failure(val error: AiError, val kind: FailureKind, val retryAfterMs: Long? = null) : RouteResult
}

enum class FailureKind {
    /** 5xx / 529 overloaded / timeout / network: retry with backoff, then try the next model. */
    TRANSIENT,

    /** 429 per-minute limit: wait only if the provider asks for a short pause, otherwise move on. */
    RATE_LIMITED,

    /** 429 daily quota (free tier): skip this model until its quota resets. */
    QUOTA_EXHAUSTED,

    /** Model retired / key rejected: skip this model for a long time. */
    UNAVAILABLE,

    /** 400: the request itself is wrong, so neither retrying nor switching models helps. */
    INVALID_REQUEST,
}

/**
 * Tries [routes] in order until one answers. Transient errors are retried with exponential
 * backoff + jitter (honouring the provider's retry hint); rate-limited or exhausted models are
 * skipped and remembered, so the next voice turn goes straight to a model that still has quota.
 */
class FallbackChain(
    private val routes: List<AiRoute>,
    private val policy: Policy = Policy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { bound -> if (bound <= 0) 0 else Random.nextLong(bound + 1) },
    private val log: (String) -> Unit = {},
) {
    data class Policy(
        val attemptsPerRoute: Int = 2,
        /** Longest we wait before retrying the same model; longer hints mean "use another model". */
        val maxInlineWaitMs: Long = 2_500,
        val backoffBaseMs: Long = 500,
        val jitterMs: Long = 300,
        /** Overall budget for one request across every retry and fallback. */
        val deadlineMs: Long = AiLimits.TIMEOUT_MS,
        val transientCooldownMs: Long = 20_000,
        val rateLimitCooldownMs: Long = 60_000,
        val quotaCooldownMs: Long = 3 * 60 * 60_000L,
        val unavailableCooldownMs: Long = 6 * 60 * 60_000L,
    )

    private class Cooldown(val until: Long, val kind: FailureKind)

    private val cooldowns = ConcurrentHashMap<String, Cooldown>()

    val isEmpty: Boolean get() = routes.isEmpty()

    suspend fun send(prompt: AiPrompt): AiResult<String> {
        if (routes.isEmpty()) return AiResult.Failure(AiError.NOT_CONFIGURED)
        val start = clock()
        val reasons = mutableListOf<FailureKind>()
        var lastError: AiError? = null
        for (route in routes) {
            val cooling = cooldowns[route.id]
            if (cooling != null && cooling.until > clock()) {
                reasons += cooling.kind
                continue
            }
            var attempt = 0
            while (true) {
                attempt++
                val remaining = policy.deadlineMs - (clock() - start)
                if (remaining <= 0) {
                    log("AI request ran out of time before ${route.id}")
                    return AiResult.Failure(lastError ?: AiError.TIMEOUT)
                }
                when (val result = route.send(prompt)) {
                    is RouteResult.Answer -> {
                        cooldowns.remove(route.id)
                        if (reasons.isNotEmpty() || attempt > 1) {
                            log("AI answered by ${route.id} (attempt $attempt; skipped: ${reasons.joinToString()})")
                        }
                        return AiResult.Success(result.text)
                    }
                    RouteResult.Blocked -> return AiResult.Failure(AiError.BLOCKED)
                    is RouteResult.Failure -> {
                        lastError = result.error
                        if (result.kind == FailureKind.INVALID_REQUEST) return AiResult.Failure(result.error)
                        val wait = retryWait(result, attempt)
                        if (wait != null && wait < remaining - MIN_ATTEMPT_MS) {
                            log("${route.id}: ${result.kind}, retrying in ${wait}ms")
                            pause(wait)
                            continue
                        }
                        coolDown(route.id, result)
                        reasons += result.kind
                        log("${route.id}: ${result.kind}, trying the next model")
                        break
                    }
                }
            }
        }
        log("AI unavailable on all ${routes.size} model(s): ${reasons.joinToString()}")
        return AiResult.Failure(finalError(reasons, lastError))
    }

    /** How long to wait before retrying the same model, or null to move on to the next one. */
    private fun retryWait(failure: RouteResult.Failure, attempt: Int): Long? {
        if (attempt >= policy.attemptsPerRoute) return null
        val wait = when (failure.kind) {
            FailureKind.TRANSIENT -> max(policy.backoffBaseMs shl (attempt - 1), failure.retryAfterMs ?: 0)
            // Only worth waiting when the provider says the window reopens shortly.
            FailureKind.RATE_LIMITED -> failure.retryAfterMs ?: return null
            else -> return null
        }
        if (wait > policy.maxInlineWaitMs) return null
        return wait + jitter(policy.jitterMs)
    }

    private fun coolDown(routeId: String, failure: RouteResult.Failure) {
        val duration = failure.retryAfterMs ?: when (failure.kind) {
            FailureKind.TRANSIENT -> policy.transientCooldownMs
            FailureKind.RATE_LIMITED -> policy.rateLimitCooldownMs
            FailureKind.QUOTA_EXHAUSTED -> policy.quotaCooldownMs
            else -> policy.unavailableCooldownMs
        }
        cooldowns[routeId] = Cooldown(clock() + duration, failure.kind)
    }

    /** The most useful message when nothing answered: "try again in a minute" beats "come back tomorrow". */
    private fun finalError(reasons: List<FailureKind>, lastError: AiError?): AiError = when {
        FailureKind.RATE_LIMITED in reasons -> AiError.RATE_LIMITED
        FailureKind.TRANSIENT in reasons -> if (lastError == AiError.TIMEOUT) AiError.TIMEOUT else AiError.NETWORK
        FailureKind.QUOTA_EXHAUSTED in reasons -> AiError.DAILY_LIMIT
        FailureKind.UNAVAILABLE in reasons -> AiError.SERVER_NOT_CONFIGURED
        else -> lastError ?: AiError.UNKNOWN
    }

    private companion object {
        /** Don't start a retry that couldn't finish within the remaining budget. */
        const val MIN_ATTEMPT_MS = 3_000L
    }
}
