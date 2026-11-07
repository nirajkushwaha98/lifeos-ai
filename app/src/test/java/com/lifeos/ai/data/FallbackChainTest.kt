package com.lifeos.ai.data

import com.lifeos.ai.data.remote.api.AiError
import com.lifeos.ai.data.remote.api.AiPrompt
import com.lifeos.ai.data.remote.api.AiPrompts
import com.lifeos.ai.data.remote.api.AiResult
import com.lifeos.ai.data.remote.api.AiRoute
import com.lifeos.ai.data.remote.api.ChatStyle
import com.lifeos.ai.data.remote.api.FailureKind
import com.lifeos.ai.data.remote.api.FallbackChain
import com.lifeos.ai.data.remote.api.RouteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackChainTest {

    private var now = 0L
    private val waits = mutableListOf<Long>()
    private val prompt = AiPrompts.chat("Aaj kya karun?", emptyList(), ChatStyle.VOICE)

    /** A fake model that returns scripted results; each call can take [costMs] of virtual time. */
    private inner class Model(override val id: String, vararg results: RouteResult, private val costMs: Long = 100) : AiRoute {
        var calls = 0
        private val script = ArrayDeque(results.toList())
        override suspend fun send(prompt: AiPrompt): RouteResult {
            calls++
            now += costMs
            return script.removeFirstOrNull() ?: error("$id called more often than expected")
        }
    }

    private fun chain(vararg models: AiRoute) =
        FallbackChain(models.toList(), clock = { now }, pause = { waits += it; now += it }, jitter = { 0 })

    private fun failure(kind: FailureKind, retryAfterMs: Long? = null) = RouteResult.Failure(
        when (kind) {
            FailureKind.QUOTA_EXHAUSTED -> AiError.DAILY_LIMIT
            FailureKind.RATE_LIMITED -> AiError.RATE_LIMITED
            FailureKind.UNAVAILABLE -> AiError.SERVER_NOT_CONFIGURED
            FailureKind.INVALID_REQUEST -> AiError.INVALID_INPUT
            FailureKind.TRANSIENT -> AiError.NETWORK
        },
        kind, retryAfterMs,
    )

    @Test fun `a model out of daily quota is skipped now and on later turns`() = runBlocking {
        val first = Model("gemini-2.5-flash", failure(FailureKind.QUOTA_EXHAUSTED, retryAfterMs = 4 * 3_600_000L))
        val second = Model("gemini-3.5-flash-lite", RouteResult.Answer("A"), RouteResult.Answer("B"))
        val chain = chain(first, second)
        assertEquals(AiResult.Success("A"), chain.send(prompt))
        assertEquals(AiResult.Success("B"), chain.send(prompt))
        assertEquals("the exhausted model is not called again", 1, first.calls)
        assertTrue("no waiting for a daily quota", waits.isEmpty())
    }

    @Test fun `a transient error is retried once on the same model with backoff`() = runBlocking {
        val model = Model("gemini-3.5-flash-lite", failure(FailureKind.TRANSIENT), RouteResult.Answer("ok"))
        assertEquals(AiResult.Success("ok"), chain(model).send(prompt))
        assertEquals(2, model.calls)
        assertEquals(listOf(500L), waits)
    }

    @Test fun `repeated transient errors move on to the next model`() = runBlocking {
        val busy = Model("gemini-flash-latest", failure(FailureKind.TRANSIENT, retryAfterMs = 800), failure(FailureKind.TRANSIENT))
        val next = Model("gemini-3.1-flash-lite", RouteResult.Answer("ok"))
        assertEquals(AiResult.Success("ok"), chain(busy, next).send(prompt))
        assertEquals(listOf(800L), waits) // the provider's retry hint beats the 500 ms base backoff
    }

    @Test fun `a short rate-limit pause is honoured but a long one switches models`() = runBlocking {
        val shortPause = Model("a", failure(FailureKind.RATE_LIMITED, retryAfterMs = 1_000), RouteResult.Answer("waited"))
        assertEquals(AiResult.Success("waited"), chain(shortPause).send(prompt))
        assertEquals(listOf(1_000L), waits)

        waits.clear()
        val longPause = Model("b", failure(FailureKind.RATE_LIMITED, retryAfterMs = 30_000))
        val other = Model("c", RouteResult.Answer("switched"), RouteResult.Answer("still switched"))
        val chain = chain(longPause, other)
        assertEquals(AiResult.Success("switched"), chain.send(prompt))
        assertEquals(AiResult.Success("still switched"), chain.send(prompt))
        assertTrue(waits.isEmpty())
        assertEquals("cooling down for the 30 s it asked for", 1, longPause.calls)
    }

    @Test fun `a cooled-down model is tried again once its pause is over`() = runBlocking {
        val model = Model("a", failure(FailureKind.QUOTA_EXHAUSTED, retryAfterMs = 1_000), RouteResult.Answer("back"))
        val chain = chain(model)
        assertEquals(AiResult.Failure(AiError.DAILY_LIMIT), chain.send(prompt))
        now += 2_000
        assertEquals(AiResult.Success("back"), chain.send(prompt))
    }

    @Test fun `invalid requests and safety blocks stop the chain`() = runBlocking {
        val invalid = Model("a", failure(FailureKind.INVALID_REQUEST))
        val untouched = Model("b")
        assertEquals(AiResult.Failure(AiError.INVALID_INPUT), chain(invalid, untouched).send(prompt))
        assertEquals(AiResult.Failure(AiError.BLOCKED), chain(Model("c", RouteResult.Blocked), untouched).send(prompt))
        assertEquals(0, untouched.calls)
    }

    @Test fun `when every model is out of quota the user hears it without new calls`() = runBlocking {
        val a = Model("a", failure(FailureKind.QUOTA_EXHAUSTED))
        val b = Model("b", failure(FailureKind.UNAVAILABLE))
        val chain = chain(a, b)
        assertEquals(AiResult.Failure(AiError.DAILY_LIMIT), chain.send(prompt))
        assertEquals(AiResult.Failure(AiError.DAILY_LIMIT), chain.send(prompt))
        assertEquals(1, a.calls)
        assertEquals(1, b.calls)
    }

    @Test fun `a per-minute limit is reported ahead of daily exhaustion`() = runBlocking {
        val daily = Model("a", failure(FailureKind.QUOTA_EXHAUSTED))
        val perMinute = Model("b", failure(FailureKind.RATE_LIMITED))
        assertEquals(AiResult.Failure(AiError.RATE_LIMITED), chain(daily, perMinute).send(prompt))
    }

    @Test fun `the overall deadline stops further attempts`() = runBlocking {
        val slow = Model("slow", RouteResult.Failure(AiError.TIMEOUT, FailureKind.TRANSIENT), costMs = 46_000)
        val never = Model("never")
        assertEquals(AiResult.Failure(AiError.TIMEOUT), chain(slow, never).send(prompt))
        assertEquals(0, never.calls)
    }

    @Test fun `no configured models means AI is not configured`() = runBlocking {
        assertEquals(AiResult.Failure(AiError.NOT_CONFIGURED), chain().send(prompt))
    }
}
