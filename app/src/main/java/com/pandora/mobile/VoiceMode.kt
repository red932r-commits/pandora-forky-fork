package com.pandora.mobile

import com.pandora.mobile.linux.ChatMessage
import com.pandora.mobile.linux.ChatRole
import com.pandora.mobile.linux.CodexChatSession
import com.pandora.mobile.linux.CodexChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class VoiceModeState(
    val active: Boolean = false,
    val sessionId: String? = null,
    val status: String = "Voice Mode is off",
    val speaking: Boolean = false,
    val lastSpokenMessageId: String? = null,
)

internal interface VoiceModeSpeechAdapter {
    val dictation: StateFlow<DictationState>
    val playback: StateFlow<SpeechPlaybackState>

    fun startDictation(finishOnEndpoint: Boolean, stopPlaybackFirst: Boolean = true)
    fun clearDictation()
    fun cancelDictation()
    fun speak(text: String)
    fun stopSpeaking()
}

internal interface VoiceModeChatAdapter {
    val id: String
    val state: StateFlow<CodexChatState>
    val messages: StateFlow<List<ChatMessage>>
    val completedAssistantMessages: StateFlow<List<ChatMessage>>

    fun send(text: String): Boolean
    fun interrupt(): Boolean
}

internal class OnDeviceVoiceModeSpeechAdapter(
    private val speech: OnDeviceSpeech,
) : VoiceModeSpeechAdapter {
    override val dictation: StateFlow<DictationState> = speech.dictation
    override val playback: StateFlow<SpeechPlaybackState> = speech.playback

    override fun startDictation(finishOnEndpoint: Boolean, stopPlaybackFirst: Boolean) {
        speech.startDictation(finishOnEndpoint, stopPlaybackFirst)
    }

    override fun clearDictation() = speech.clearDictation()
    override fun cancelDictation() = speech.cancelDictation()
    override fun speak(text: String) = speech.speak(text)
    override fun stopSpeaking() = speech.stopSpeaking()
}

internal class CodexVoiceModeChatAdapter(
    private val session: CodexChatSession,
) : VoiceModeChatAdapter {
    override val id: String = session.id
    override val state: StateFlow<CodexChatState> = session.state
    override val messages: StateFlow<List<ChatMessage>> = session.messages
    override val completedAssistantMessages: StateFlow<List<ChatMessage>> =
        session.completedAssistantMessages

    override fun send(text: String): Boolean = session.send(text)
    override fun interrupt(): Boolean = session.interrupt()
}

/** Owns the hands-free conversation loop independently of any Activity or Compose lifecycle. */
internal class VoiceMode(
    private val speech: VoiceModeSpeechAdapter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private sealed interface Event {
        data class DictationChanged(val value: DictationState) : Event
        data class PlaybackChanged(val value: SpeechPlaybackState) : Event
        data class ChatChanged(val value: CodexChatState) : Event
        data class AssistantMessagesCompleted(val value: List<ChatMessage>) : Event
    }

    private class ActiveRun(
        val chat: VoiceModeChatAdapter,
        var dictation: DictationState,
        var playback: SpeechPlaybackState,
        var chatState: CodexChatState,
        val handledMessageIds: MutableSet<String>,
        val queuedMessages: ArrayDeque<ChatMessage> = ArrayDeque(),
        var pendingPrompt: String? = null,
        var lastSpokenMessageId: String? = null,
        var playbackRequested: Boolean = false,
        var job: Job? = null,
    )

    private val _state = MutableStateFlow(VoiceModeState())
    val state: StateFlow<VoiceModeState> = _state.asStateFlow()

    @Volatile private var activeRun: ActiveRun? = null

    @Synchronized
    fun start(chat: VoiceModeChatAdapter) {
        if (activeRun?.chat?.id == chat.id && _state.value.active) return
        stopLocked()

        val lastAssistant = chat.messages.value.lastOrNull {
            it.role == ChatRole.ASSISTANT && it.text.isNotBlank()
        }
        val run = ActiveRun(
            chat = chat,
            dictation = speech.dictation.value,
            playback = speech.playback.value,
            chatState = chat.state.value,
            handledMessageIds = chat.completedAssistantMessages.value.mapTo(mutableSetOf()) { it.id },
            lastSpokenMessageId = lastAssistant?.id,
        )
        lastAssistant?.let { run.handledMessageIds += it.id }
        activeRun = run
        publish(run)

        run.job = scope.launch { collectEvents(run) }
        speech.cancelDictation()
        speech.stopSpeaking()
        speech.startDictation(finishOnEndpoint = true)
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    fun interrupt() {
        val run = activeRun ?: return
        run.pendingPrompt = null
        run.playbackRequested = false
        speech.cancelDictation()
        speech.stopSpeaking()
        run.dictation = speech.dictation.value
        run.playback = speech.playback.value
        if (run.chatState is CodexChatState.Running) run.chat.interrupt()
        if (activeRun === run) {
            reconcile(run)
            publish(run)
        }
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun collectEvents(run: ActiveRun) = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        launch { speech.dictation.collect { events.send(Event.DictationChanged(it)) } }
        launch { speech.playback.collect { events.send(Event.PlaybackChanged(it)) } }
        launch { run.chat.state.collect { events.send(Event.ChatChanged(it)) } }
        launch {
            run.chat.completedAssistantMessages.collect {
                events.send(Event.AssistantMessagesCompleted(it))
            }
        }

        for (event in events) {
            if (activeRun !== run) break
            when (event) {
                is Event.DictationChanged -> onDictationChanged(run, event.value)
                is Event.PlaybackChanged -> onPlaybackChanged(run, event.value)
                is Event.ChatChanged -> {
                    run.chatState = event.value
                    if (event.value is CodexChatState.Failed || event.value is CodexChatState.Closed) {
                        stopRun(run)
                        break
                    }
                }
                is Event.AssistantMessagesCompleted -> onAssistantMessagesCompleted(run, event.value)
            }
            if (activeRun === run) {
                reconcile(run)
                publish(run)
            }
        }
    }

    private fun onDictationChanged(run: ActiveRun, value: DictationState) {
        run.dictation = value
        when (value) {
            is DictationState.Listening -> if (
                value.text.isNotBlank() &&
                (run.playbackRequested || run.playback is SpeechPlaybackState.Loading ||
                    run.playback is SpeechPlaybackState.Speaking)
            ) {
                run.playbackRequested = false
                speech.stopSpeaking()
                run.playback = speech.playback.value
            }
            is DictationState.Finished -> {
                val spoken = value.text.trim()
                speech.clearDictation()
                run.dictation = speech.dictation.value
                if (spoken.isBlank()) return

                run.playbackRequested = false
                speech.stopSpeaking()
                run.playback = speech.playback.value
                if (isVoiceStopCommand(spoken)) {
                    run.pendingPrompt = null
                    run.queuedMessages.clear()
                    if (run.chatState is CodexChatState.Running) run.chat.interrupt()
                } else if (run.chatState is CodexChatState.Ready && run.chat.send(spoken)) {
                    run.pendingPrompt = null
                    run.chatState = run.chat.state.value
                } else {
                    run.pendingPrompt = spoken
                    if (run.chatState is CodexChatState.Running) run.chat.interrupt()
                }
            }
            else -> Unit
        }
    }

    private fun onPlaybackChanged(run: ActiveRun, value: SpeechPlaybackState) {
        run.playback = value
        run.playbackRequested = value is SpeechPlaybackState.Loading || value is SpeechPlaybackState.Speaking
    }

    private fun onAssistantMessagesCompleted(run: ActiveRun, messages: List<ChatMessage>) {
        messages.forEach { message ->
            if (run.handledMessageIds.add(message.id) && run.pendingPrompt == null) {
                run.queuedMessages += message
            }
        }
    }

    private fun reconcile(run: ActiveRun) {
        val pending = run.pendingPrompt
        if (run.chatState is CodexChatState.Ready && !pending.isNullOrBlank()) {
            if (run.chat.send(pending)) {
                run.pendingPrompt = null
                run.chatState = run.chat.state.value
            }
        }

        if (run.chatState is CodexChatState.Ready && run.pendingPrompt == null) {
            val latest = run.chat.messages.value.lastOrNull {
                it.role == ChatRole.ASSISTANT && it.text.isNotBlank()
            }
            if (latest != null && run.handledMessageIds.add(latest.id)) {
                run.queuedMessages += latest
            }
        }

        if (
            run.pendingPrompt == null &&
            !run.hasLiveSpeech() &&
            !run.playbackRequested &&
            run.playback is SpeechPlaybackState.Idle &&
            run.queuedMessages.isNotEmpty()
        ) {
            val next = run.queuedMessages.removeFirst()
            run.lastSpokenMessageId = next.id
            run.playbackRequested = true
            speech.speak(next.text)
            run.dictation = speech.dictation.value
            run.playback = speech.playback.value
        }

        if (run.dictation is DictationState.Idle) {
            when (run.playback) {
                SpeechPlaybackState.Loading -> Unit
                SpeechPlaybackState.Speaking -> speech.startDictation(
                    finishOnEndpoint = true,
                    stopPlaybackFirst = false,
                )
                else -> if (!run.playbackRequested) speech.startDictation(finishOnEndpoint = true)
            }
            run.dictation = speech.dictation.value
        }
    }

    private fun ActiveRun.hasLiveSpeech(): Boolean =
        (dictation as? DictationState.Listening)?.text?.isNotBlank() == true ||
            dictation is DictationState.Transcribing

    private fun publish(run: ActiveRun) {
        if (activeRun !== run) return
        val speaking = run.playbackRequested ||
            run.playback is SpeechPlaybackState.Loading ||
            run.playback is SpeechPlaybackState.Speaking
        val status = when {
            run.playback is SpeechPlaybackState.Failed ->
                (run.playback as SpeechPlaybackState.Failed).detail
            run.dictation is DictationState.Failed ->
                (run.dictation as DictationState.Failed).detail
            speaking && run.playback is SpeechPlaybackState.Speaking ->
                "Codex is speaking · talk to interrupt"
            speaking -> "Preparing Codex’s voice…"
            run.dictation is DictationState.Loading -> "Getting the microphone ready…"
            run.dictation is DictationState.Transcribing -> "Finishing what you said…"
            (run.dictation as? DictationState.Listening)?.text?.isNotBlank() == true ->
                (run.dictation as DictationState.Listening).text
            run.chatState is CodexChatState.Running -> "Codex is working · you can interrupt"
            else -> "Listening · speak your request"
        }
        _state.value = VoiceModeState(
            active = true,
            sessionId = run.chat.id,
            status = status,
            speaking = speaking,
            lastSpokenMessageId = run.lastSpokenMessageId,
        )
    }

    @Synchronized
    private fun stopRun(run: ActiveRun) {
        if (activeRun !== run) return
        stopLocked()
    }

    private fun stopLocked() {
        val run = activeRun
        activeRun = null
        run?.job?.cancel()
        speech.cancelDictation()
        speech.stopSpeaking()
        _state.value = VoiceModeState()
    }
}

internal fun isVoiceStopCommand(text: String): Boolean {
    val normalized = text
        .lowercase()
        .replace(Regex("[^a-z ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return normalized in setOf(
        "stop",
        "cancel",
        "stop codex",
        "cancel that",
        "be quiet",
        "never mind",
        "nevermind",
    )
}
