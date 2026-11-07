package com.lifeos.ai.domain

import android.speech.SpeechRecognizer
import com.lifeos.ai.R
import com.lifeos.ai.feature.voice.ContinuousListening
import com.lifeos.ai.feature.voice.ContinuousListening.Next
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousListeningTest {

    private val minute = 60_000L

    @Test fun `a quiet moment just restarts listening, like a call`() {
        assertEquals(Next.Restart(ContinuousListening.RESTART_DELAY_MS),
            ContinuousListening.afterError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, failuresSoFar = 0, listenedForMs = 8_000, silentForMs = minute))
        assertEquals(Next.Restart(ContinuousListening.RESTART_DELAY_MS),
            ContinuousListening.afterError(SpeechRecognizer.ERROR_NO_MATCH, failuresSoFar = 2, listenedForMs = 6_000, silentForMs = 4 * minute))
    }

    @Test fun `a long silence pauses the conversation and frees the microphone`() {
        assertEquals(Next.Pause(R.string.voice_paused_idle),
            ContinuousListening.afterError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, 0, 8_000, 5 * minute))
    }

    @Test fun `recognizer hiccups back off, then give up with a clear hint`() {
        val network = SpeechRecognizer.ERROR_NETWORK
        assertEquals(Next.Restart(500), ContinuousListening.afterError(network, 0, 200, minute))
        assertEquals(Next.Restart(1_000), ContinuousListening.afterError(network, 1, 200, minute))
        assertEquals(Next.Restart(2_000), ContinuousListening.afterError(network, 2, 200, minute))
        assertEquals(Next.Pause(R.string.voice_error_network), ContinuousListening.afterError(network, 3, 200, minute))
    }

    @Test fun `an instant no-match counts as a failure, not as silence`() {
        assertEquals(Next.Restart(500), ContinuousListening.afterError(SpeechRecognizer.ERROR_NO_MATCH, 0, 300, minute))
        assertEquals(Next.Pause(R.string.voice_error_generic), ContinuousListening.afterError(SpeechRecognizer.ERROR_NO_MATCH, 3, 300, minute))
    }

    @Test fun `permission and language problems stop right away`() {
        assertEquals(Next.Pause(R.string.voice_error_permission),
            ContinuousListening.afterError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, 0, 8_000, minute))
        assertEquals(Next.Pause(R.string.voice_error_language),
            ContinuousListening.afterError(ContinuousListening.ERROR_LANGUAGE_UNAVAILABLE, 0, 8_000, minute))
    }
}
