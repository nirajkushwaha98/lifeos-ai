package com.lifeos.ai.feature.voice

import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import com.lifeos.ai.R

/**
 * What voice mode does when the recognizer stops without a usable result. Like ChatGPT's voice
 * mode, a quiet moment doesn't end the conversation — listening simply restarts — until a long
 * silence, a mute, or the user ends it. Recognizer hiccups get a few retries with backoff.
 */
internal object ContinuousListening {

    /** A conversation pauses after this much silence, releasing the microphone. */
    const val IDLE_LIMIT_MS = 5 * 60_000L

    /** A "no speech" result sooner than this means the recognizer failed to start, not silence. */
    const val MIN_LISTEN_MS = 1_500L

    const val RESTART_DELAY_MS = 150L
    const val BACKOFF_BASE_MS = 500L
    const val MAX_FAILURES = 3

    // SpeechRecognizer codes added in API 31, declared here so older devices compile cleanly.
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    sealed interface Next {
        data class Restart(val delayMs: Long) : Next
        data class Pause(@StringRes val hint: Int) : Next
    }

    fun isSilence(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    /** True when the recognizer listened normally and simply heard nothing. */
    fun isQuietStretch(error: Int, listenedForMs: Long): Boolean = isSilence(error) && listenedForMs >= MIN_LISTEN_MS

    /**
     * @param failuresSoFar recognizer failures in a row before this one (quiet stretches don't count)
     * @param listenedForMs how long this listening attempt ran
     * @param silentForMs time since the user last spoke or the assistant last replied
     */
    fun afterError(error: Int, failuresSoFar: Int, listenedForMs: Long, silentForMs: Long): Next = when {
        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Next.Pause(R.string.voice_error_permission)
        error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE -> Next.Pause(R.string.voice_error_language)
        isSilence(error) && silentForMs >= IDLE_LIMIT_MS -> Next.Pause(R.string.voice_paused_idle)
        isQuietStretch(error, listenedForMs) -> Next.Restart(RESTART_DELAY_MS)
        failuresSoFar < MAX_FAILURES -> Next.Restart(BACKOFF_BASE_MS shl failuresSoFar)
        else -> Next.Pause(hintFor(error))
    }

    @StringRes
    private fun hintFor(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_error_network
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
        else -> R.string.voice_error_generic
    }
}
