package com.lifeos.ai.feature.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.ai.R
import com.lifeos.ai.core.common.newId
import com.lifeos.ai.data.remote.api.AiError
import com.lifeos.ai.data.remote.api.AiLimits
import com.lifeos.ai.data.remote.api.AiResult
import com.lifeos.ai.data.remote.api.AiService
import com.lifeos.ai.data.remote.api.ChatStyle
import com.lifeos.ai.domain.model.ChatMessage
import com.lifeos.ai.domain.model.MessageRole
import com.lifeos.ai.domain.repository.ConversationRepository
import com.lifeos.ai.domain.usecase.AssistantIntent
import com.lifeos.ai.feature.assistant.AssistantActionExecutor
import com.lifeos.ai.feature.assistant.AssistantViewModel
import com.lifeos.ai.feature.assistant.IntentResolver
import com.lifeos.ai.feature.assistant.PendingAction
import com.lifeos.ai.feature.assistant.aiErrorMessage
import com.lifeos.ai.feature.assistant.editableTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

enum class VoiceLanguage(val tag: String, @StringRes val label: Int) {
    HINDI("hi-IN", R.string.lang_hindi),
    ENGLISH("en-IN", R.string.lang_english),
}

enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val language: VoiceLanguage = VoiceLanguage.HINDI,
    val muted: Boolean = false,
    /** Live transcript while the user is still speaking. */
    val partial: String = "",
    /** The user's last complete utterance. */
    val userText: String = "",
    /** The assistant's last reply, as shown in the captions. */
    val reply: String = "",
    val replyIsError: Boolean = false,
    /** How much of [reply] has been spoken (display offset), or -1 when not tracked. */
    val spokenUpTo: Int = -1,
    @StringRes val hint: Int? = null,
    val pending: PendingAction? = null,
    val conversationId: String? = null,
    val recognizerAvailable: Boolean = true,
    val needsPermission: Boolean = false,
) {
    val orbMode: OrbMode
        get() = when {
            phase == VoicePhase.LISTENING -> OrbMode.LISTENING
            phase == VoicePhase.THINKING -> OrbMode.THINKING
            phase == VoicePhase.SPEAKING -> OrbMode.SPEAKING
            muted || needsPermission -> OrbMode.MUTED
            else -> OrbMode.IDLE
        }
}

/**
 * Hands-free voice conversation: listen → think → speak → listen again.
 *
 * Speech is turned into text by the platform [SpeechRecognizer] (no audio is recorded or kept by
 * the app). Commands go through the same offline parser + confirmation flow as the text assistant;
 * anything else is answered by the secure AI backend in a short, spoken style. Replies are read
 * aloud with the device's text-to-speech engine and the conversation is saved to the on-device
 * assistant history, so it can be continued in the text chat.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: IntentResolver,
    private val executor: AssistantActionExecutor,
    private val conversations: ConversationRepository,
    private val ai: AiService,
) : ViewModel(), RecognitionListener, TextToSpeech.OnInitListener {

    private val _state = MutableStateFlow(VoiceUiState(recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Live microphone level (0..1); kept out of [state] so ~20 updates a second never recompose the screen. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _wordPulse = MutableStateFlow(0)
    /** Counts words as the TTS engine starts them; the orb pulses on each one. */
    val wordPulse: StateFlow<Int> = _wordPulse.asStateFlow()

    private val _navigation = Channel<Any>(Channel.BUFFERED)
    val navigation = _navigation.receiveAsFlow()

    private val _close = Channel<Unit>(Channel.CONFLATED)
    /** Fires when the conversation was ended from outside the screen (the notification's End button). */
    val closeRequests = _close.receiveAsFlow()

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var hasMic = false
    private var foreground = false
    private var autoStart = true
    private var turn: Job? = null

    /**
     * A conversation in progress: listening restarts after every reply and every quiet moment, also
     * with the screen off (VoiceSessionService), until the user ends it, mutes, or stays silent
     * for [ContinuousListening.IDLE_LIMIT_MS].
     */
    private var sessionActive = false
    private var lastActivityAt = 0L
    private var listenStartedAt = 0L
    private var failures = 0
    private var restartJob: Job? = null
    private var watchdog: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var rangesSupported = false
    private var voiceLocale: Locale? = null
    private var activeUtterance: String? = null
    private var utteranceCount = 0
    private var script: SpeechScript? = null
    private var listenAfterSpeech = false

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) = onEngine(utteranceId) {
            rangesSupported = true
            _wordPulse.value += 1
            script?.let { s -> _state.update { it.copy(spokenUpTo = s.displayEnd(end)) } }
        }

        override fun onDone(utteranceId: String?) = onEngine(utteranceId) { speechFinished() }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onEngine(utteranceId) { speechFinished() }

        override fun onError(utteranceId: String?, errorCode: Int) = onEngine(utteranceId) { speechFinished() }
    }

    init {
        tts = TextToSpeech(context, this)
        viewModelScope.launch {
            VoiceSessionEvents.endRequests.collect {
                endSession()
                _close.trySend(Unit)
            }
        }
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(progress)
    }

    // ---------------------------------------------------------------- screen lifecycle

    /** The screen became visible. Voice mode opens ready to talk, so the first time it starts listening. */
    fun onForeground(micGranted: Boolean) {
        foreground = true
        hasMic = micGranted
        if (micGranted) _state.update { it.copy(needsPermission = false) }
        if (autoStart && micGranted) {
            autoStart = false
            startListening()
        }
    }

    /**
     * The app went to the background or the screen turned off. A conversation in progress carries
     * on (VoiceSessionService keeps the microphone available); otherwise everything stops so nothing
     * is heard or said while the app is hidden.
     */
    fun onBackground() {
        foreground = false
        if (sessionActive) return
        cancelListening()
        stopSpeaking()
        _state.update { if (it.phase == VoicePhase.LISTENING) it.copy(phase = VoicePhase.IDLE, partial = "") else it }
    }

    /** The user left voice mode (X, back, notification End): stop listening, speaking and the background service. */
    fun endSession() {
        sessionActive = false
        watchdog?.cancel()
        cancelListening()
        stopSpeaking()
        VoiceSessionService.stop(context)
        _state.update { it.copy(phase = VoicePhase.IDLE, partial = "") }
    }

    fun onPermissionResult(granted: Boolean) {
        hasMic = granted
        _state.update { it.copy(needsPermission = !granted, hint = null) }
        if (granted) {
            autoStart = false
            startListening()
        }
    }

    // ---------------------------------------------------------------- user actions

    fun onOrbTap() {
        val s = _state.value
        when (s.phase) {
            VoicePhase.LISTENING -> recognizer?.stopListening() // "I'm done" — finish the utterance now
            VoicePhase.SPEAKING -> if (s.pending == null) startListening() else stopSpeaking() // barge in
            VoicePhase.THINKING -> Unit
            VoicePhase.IDLE -> if (s.pending == null) startListening()
        }
    }

    fun onMicButton() {
        val s = _state.value
        when {
            s.muted -> {
                _state.update { it.copy(muted = false) }
                if (s.phase == VoicePhase.IDLE && s.pending == null) startListening()
            }
            s.phase == VoicePhase.IDLE -> if (s.pending == null) startListening()
            else -> {
                // Mute: release the microphone; a reply that is being spoken finishes first.
                listenAfterSpeech = false
                pauseSession(hint = null)
                _state.update { it.copy(muted = true) }
            }
        }
    }

    fun toggleLanguage() {
        val next = if (_state.value.language == VoiceLanguage.HINDI) VoiceLanguage.ENGLISH else VoiceLanguage.HINDI
        _state.update { it.copy(language = next) }
        if (listening) {
            cancelListening()
            startListening()
        }
    }

    fun updatePendingTitle(title: String) = _state.update { s -> s.copy(pending = s.pending?.copy(editableTitle = title.take(200))) }

    fun confirmPending() {
        val pending = _state.value.pending ?: return
        val convId = _state.value.conversationId ?: return
        stopSpeaking()
        _state.update { it.copy(pending = null, phase = VoicePhase.THINKING, reply = "") }
        turn = launchTurn {
            // The outcome message only says "saved" once the database has confirmed it.
            val outcome = executor.execute(pending.intent, pending.editableTitle)
            reply(convId, outcome.message, listenAfter = true, isError = !outcome.success)
        }
    }

    fun cancelPending() {
        val convId = _state.value.conversationId
        stopSpeaking()
        _state.update { it.copy(pending = null) }
        if (convId != null) turn = launchTurn { reply(convId, context.getString(R.string.action_cancelled), listenAfter = true) }
    }

    /** Runs one conversational turn for [text] — recognised speech, or a tapped starter prompt. */
    fun handleUtterance(text: String) {
        val utterance = text.trim().take(AiLimits.MAX_MESSAGE_CHARS)
        if (utterance.isEmpty() || turn?.isActive == true) return
        markActivity()
        cancelListening()
        stopSpeaking()
        _state.update {
            it.copy(
                phase = VoicePhase.THINKING, userText = utterance, partial = "",
                reply = "", replyIsError = false, hint = null, pending = null,
            )
        }
        turn = launchTurn {
            val convId = ensureConversation(utterance)
            conversations.addMessage(ChatMessage(newId(), convId, MessageRole.USER, utterance, System.currentTimeMillis()))
            val intent = resolver.resolve(utterance)
            when {
                intent == null -> answerWithAi(convId)
                intent is AssistantIntent.OpenEditor -> {
                    addReply(convId, context.getString(R.string.opening_editor))
                    _state.update { it.copy(phase = VoicePhase.IDLE) }
                    _navigation.send(AssistantViewModel.editorRoute(intent.target))
                }
                intent.requiresConfirmation -> {
                    // Nothing is saved until the user taps Confirm.
                    val pending = PendingAction(intent, executor.describe(intent), intent.editableTitle())
                    _state.update { it.copy(pending = pending) }
                    speak(pending.summary, listenAfter = false, caption = false)
                }
                else -> reply(convId, executor.answer(intent), listenAfter = true)
            }
        }
    }

    // ---------------------------------------------------------------- listening

    fun startListening() {
        if (!_state.value.recognizerAvailable) {
            _state.update { it.copy(phase = VoicePhase.IDLE, hint = R.string.voice_not_available) }
            return
        }
        if (!hasMic) {
            _state.update { it.copy(needsPermission = true) }
            return
        }
        if (!sessionActive) {
            if (!foreground) return // the microphone can't be opened from the background without a session
            beginSession()
        }
        restartJob?.cancel()
        stopSpeaking()
        val rec = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?.also { it.setRecognitionListener(this); recognizer = it }
        if (rec == null) {
            _state.update { it.copy(phase = VoicePhase.IDLE, hint = R.string.voice_error_generic) }
            return
        }
        if (listening) rec.cancel()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, _state.value.language.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        listening = true
        listenStartedAt = now()
        _level.value = 0f
        _state.update { it.copy(phase = VoicePhase.LISTENING, partial = "", hint = null, muted = false, needsPermission = false) }
        rec.startListening(intent)
    }

    private fun cancelListening() {
        restartJob?.cancel()
        if (listening) {
            listening = false
            recognizer?.cancel()
        }
        _level.value = 0f
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = markActivity()
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onRmsChanged(rmsdB: Float) {
        if (listening) _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onEndOfSpeech() {
        _level.value = 0f
        _state.update { if (it.phase == VoicePhase.LISTENING) it.copy(phase = VoicePhase.THINKING) else it }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!listening) return
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            markActivity()
            _state.update { it.copy(partial = text) }
        }
    }

    override fun onResults(results: Bundle?) {
        if (!listening) return
        listening = false
        _level.value = 0f
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (text.isNullOrBlank()) {
            recognizerStopped(SpeechRecognizer.ERROR_NO_MATCH)
        } else {
            failures = 0
            handleUtterance(text)
        }
    }

    override fun onError(error: Int) {
        if (!listening) return // our own cancel()
        listening = false
        _level.value = 0f
        recognizerStopped(error)
    }

    /** The recognizer stopped without a usable result. In a conversation that usually just means "listen again". */
    private fun recognizerStopped(error: Int) {
        if (error in RESET_RECOGNIZER_ERRORS) {
            recognizer?.destroy()
            recognizer = null
        }
        val noPermission = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        if (noPermission) hasMic = false
        if (!sessionActive) {
            _state.update { it.copy(phase = VoicePhase.IDLE, partial = "", hint = hintFor(error), needsPermission = noPermission) }
            return
        }
        val listenedFor = now() - listenStartedAt
        when (val next = ContinuousListening.afterError(error, failures, listenedFor, now() - lastActivityAt)) {
            is ContinuousListening.Next.Restart -> {
                failures = if (ContinuousListening.isQuietStretch(error, listenedFor)) 0 else failures + 1
                _state.update { it.copy(phase = VoicePhase.LISTENING, partial = "") }
                restartJob = viewModelScope.launch {
                    delay(next.delayMs)
                    val s = _state.value
                    if (sessionActive && !listening && !s.muted && s.pending == null && s.phase == VoicePhase.LISTENING) startListening()
                }
            }
            is ContinuousListening.Next.Pause -> {
                pauseSession(next.hint)
                if (noPermission) _state.update { it.copy(needsPermission = true) }
            }
        }
    }

    @StringRes
    private fun hintFor(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_hint_no_speech
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_error_network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_error_permission
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
        ContinuousListening.ERROR_LANGUAGE_NOT_SUPPORTED, ContinuousListening.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_error_language
        else -> R.string.voice_error_generic
    }

    // ---------------------------------------------------------------- conversation session

    private fun now() = SystemClock.elapsedRealtime()

    private fun markActivity() {
        lastActivityAt = now()
    }

    /** Starts a conversation; the background service keeps the microphone available if the screen turns off. */
    private fun beginSession() {
        sessionActive = true
        failures = 0
        markActivity()
        VoiceSessionService.start(context)
        watchdog?.cancel()
        watchdog = viewModelScope.launch {
            // Catches long waits the recognizer can't see, e.g. an unanswered confirmation with the screen off.
            while (sessionActive) {
                delay(WATCHDOG_TICK_MS)
                val busy = _state.value.phase == VoicePhase.THINKING || _state.value.phase == VoicePhase.SPEAKING
                if (sessionActive && !busy && now() - lastActivityAt >= ContinuousListening.IDLE_LIMIT_MS) {
                    pauseSession(R.string.voice_paused_idle)
                }
            }
        }
    }

    /** Pauses the conversation (long silence, mute, mic problem), releasing the microphone and the service. */
    private fun pauseSession(@StringRes hint: Int?) {
        sessionActive = false
        watchdog?.cancel()
        cancelListening()
        VoiceSessionService.stop(context)
        _state.update {
            it.copy(phase = if (it.phase == VoicePhase.SPEAKING) it.phase else VoicePhase.IDLE, partial = "", hint = hint ?: it.hint)
        }
    }

    // ---------------------------------------------------------------- answering

    private suspend fun answerWithAi(convId: String) {
        val history = conversations.recentMessages(convId, AiLimits.MAX_HISTORY_MESSAGES + 1)
        val last = history.lastOrNull { it.role == MessageRole.USER } ?: return
        val prior = history.takeWhile { it.id != last.id }
        when (val result = ai.chat(last.content, prior, ChatStyle.VOICE)) {
            is AiResult.Success -> reply(convId, result.value, listenAfter = true)
            // An AI hiccup doesn't end the conversation: say what happened, then keep listening.
            is AiResult.Failure -> reply(convId, context.getString(voiceErrorMessage(result.error)), listenAfter = true, isError = true)
        }
    }

    @StringRes
    private fun voiceErrorMessage(error: AiError): Int = when (error) {
        AiError.NOT_CONFIGURED -> R.string.voice_ai_not_configured
        AiError.SIGN_IN_REQUIRED -> R.string.voice_ai_sign_in
        else -> aiErrorMessage(error)
    }

    private suspend fun addReply(convId: String, text: String, isError: Boolean = false) {
        conversations.addMessage(ChatMessage(newId(), convId, MessageRole.ASSISTANT, text, System.currentTimeMillis(), isError = isError))
    }

    private suspend fun reply(convId: String, text: String, listenAfter: Boolean, isError: Boolean = false) {
        addReply(convId, text, isError)
        _state.update { it.copy(replyIsError = isError) }
        speak(text, listenAfter)
    }

    private suspend fun ensureConversation(firstUtterance: String): String {
        _state.value.conversationId?.let { return it }
        val conversation = conversations.createConversation(firstUtterance.lineSequence().first().take(40))
        _state.update { it.copy(conversationId = conversation.id) }
        return conversation.id
    }

    private fun launchTurn(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Voice turn failed", e)
            _state.update { it.copy(phase = VoicePhase.IDLE, hint = R.string.voice_error_generic) }
        }
    }

    // ---------------------------------------------------------------- speaking

    /** Shows [text] as the caption (unless [caption] is false) and reads it aloud. */
    private fun speak(text: String, listenAfter: Boolean, caption: Boolean = true) {
        val prepared = SpeechText.script(text)
        script = prepared
        if (caption) _state.update { it.copy(reply = prepared.display) }
        listenAfterSpeech = false
        val engine = tts
        // In a conversation replies are spoken even with the screen off; otherwise only while visible.
        if (engine == null || !ttsReady || !(foreground || sessionActive) || prepared.spoken.isBlank()) {
            _state.update { it.copy(phase = VoicePhase.IDLE, spokenUpTo = -1) }
            return
        }
        useVoiceFor(engine, prepared.hindi)
        val id = "lifeos-voice-${++utteranceCount}"
        activeUtterance = id
        listenAfterSpeech = listenAfter
        _state.update { it.copy(phase = VoicePhase.SPEAKING, spokenUpTo = if (rangesSupported && caption) 0 else -1) }
        if (engine.speak(prepared.spoken, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            activeUtterance = null
            listenAfterSpeech = false
            _state.update { it.copy(phase = VoicePhase.IDLE, spokenUpTo = -1) }
        }
    }

    private fun speechFinished() {
        activeUtterance = null
        markActivity()
        val resume = listenAfterSpeech
        listenAfterSpeech = false
        // When the conversation continues, stay in SPEAKING for the short gap so the status doesn't flicker.
        _state.update { it.copy(phase = if (resume) it.phase else VoicePhase.IDLE, spokenUpTo = -1) }
        if (!resume) return
        viewModelScope.launch {
            delay(RESUME_LISTENING_DELAY_MS) // let the speaker fall silent so the mic doesn't hear the reply's tail
            val s = _state.value
            if (s.phase != VoicePhase.SPEAKING || activeUtterance != null) return@launch // user already moved on
            if ((sessionActive || foreground) && hasMic && !s.muted && s.pending == null) startListening()
            else _state.update { it.copy(phase = VoicePhase.IDLE) }
        }
    }

    private fun stopSpeaking() {
        listenAfterSpeech = false
        if (activeUtterance != null) {
            activeUtterance = null
            tts?.stop()
        }
        _state.update { it.copy(phase = if (it.phase == VoicePhase.SPEAKING) VoicePhase.IDLE else it.phase, spokenUpTo = -1) }
    }

    /** Picks the best installed on-device voice for Hindi or Indian English. */
    private fun useVoiceFor(engine: TextToSpeech, hindi: Boolean) {
        val locale = Locale.forLanguageTag(if (hindi) "hi-IN" else "en-IN")
        if (locale == voiceLocale) return
        voiceLocale = locale
        val best = runCatching {
            engine.voices.orEmpty()
                .filter { v ->
                    v.locale.language == locale.language &&
                        v.locale.country.equals(locale.country, ignoreCase = true) &&
                        !v.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
                }
                .maxByOrNull { it.quality }
        }.getOrNull()
        if (best != null && engine.setVoice(best) == TextToSpeech.SUCCESS) return
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.forLanguageTag("en-IN"))
        }
    }

    /** TTS callbacks arrive on a binder thread: hop to the main thread and ignore stale utterances. */
    private fun onEngine(utteranceId: String?, block: () -> Unit) {
        viewModelScope.launch { if (utteranceId != null && utteranceId == activeUtterance) block() }
    }

    override fun onCleared() {
        sessionActive = false
        watchdog?.cancel()
        restartJob?.cancel()
        VoiceSessionService.stop(context)
        listening = false
        recognizer?.destroy()
        recognizer = null
        activeUtterance = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val TAG = "VoiceViewModel"
        const val RESUME_LISTENING_DELAY_MS = 350L
        const val WATCHDOG_TICK_MS = 15_000L
        val RESET_RECOGNIZER_ERRORS = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER, ContinuousListening.ERROR_SERVER_DISCONNECTED,
        )
    }
}
