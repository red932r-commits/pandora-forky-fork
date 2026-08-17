package com.pandora.mobile

import android.media.AudioManager
import com.pandora.mobile.linux.ChatMessage
import com.pandora.mobile.linux.ChatRole
import com.pandora.mobile.linux.CodexChatState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModeTest {
    @Test
    fun ttsBufferIsFrameAlignedForMediumPiperSampleRate() {
        val bufferBytes = ttsAudioBufferSizeBytes(minimumBytes = 4_096, sampleRate = 22_050)

        assertEquals(11_028, bufferBytes)
        assertEquals(0, bufferBytes % Float.SIZE_BYTES)
    }

    @Test
    fun `voice over speed snaps and stays in supported range`() {
        assertEquals(0.5f, normalizeVoiceOverSpeed(0.1f), 0.001f)
        assertEquals(1.25f, normalizeVoiceOverSpeed(1.27f), 0.001f)
        assertEquals(2f, normalizeVoiceOverSpeed(3f), 0.001f)
    }

    @Test
    fun `ducking audio focus does not cancel voice playback`() {
        assertFalse(shouldStopSpeechForAudioFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertTrue(shouldStopSpeechForAudioFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertTrue(shouldStopSpeechForAudioFocus(AudioManager.AUDIOFOCUS_LOSS))
    }

    @Test
    fun `short stop phrases are handled locally`() {
        assertTrue(isVoiceStopCommand("Stop!"))
        assertTrue(isVoiceStopCommand("Never mind."))
        assertTrue(isVoiceStopCommand("cancel that"))
    }

    @Test
    fun `requests containing stop are still sent to Codex`() {
        assertFalse(isVoiceStopCommand("Stop the server and show me the logs"))
        assertFalse(isVoiceStopCommand("Can you cancel the download?"))
    }

    @Test
    fun `voice mode owns an active chat outside the UI lifecycle`() = runBlocking {
        val speech = FakeVoiceModeSpeech()
        val chat = FakeVoiceModeChat()
        val voiceMode = VoiceMode(speech)
        try {
            voiceMode.start(chat)

            voiceMode.awaitState { it.active && it.sessionId == chat.id }
            eventually { speech.startDictationCalls.get() > 0 }
            assertTrue(speech.dictation.value is DictationState.Listening)
        } finally {
            voiceMode.close()
        }
    }

    @Test
    fun `speech during a running turn interrupts then sends when ready`() = runBlocking {
        val speech = FakeVoiceModeSpeech()
        val chat = FakeVoiceModeChat()
        val voiceMode = VoiceMode(speech)
        try {
            voiceMode.start(chat)
            voiceMode.awaitState { it.active }
            chat.setRunning()
            speech.finishDictation("Use the latest logs")

            eventually { chat.interruptCalls.get() == 1 }
            assertTrue(chat.sentPrompts.isEmpty())

            chat.setReady()
            eventually { chat.sentPrompts == listOf("Use the latest logs") }
        } finally {
            voiceMode.close()
        }
    }

    @Test
    fun `completed replies are spoken once and in order`() = runBlocking {
        val speech = FakeVoiceModeSpeech()
        val chat = FakeVoiceModeChat()
        val voiceMode = VoiceMode(speech)
        val first = ChatMessage(id = "first", role = ChatRole.ASSISTANT, text = "First reply")
        val second = ChatMessage(id = "second", role = ChatRole.ASSISTANT, text = "Second reply")
        try {
            voiceMode.start(chat)
            voiceMode.awaitState { it.active }
            chat.complete(first)
            eventually { speech.spoken == listOf("First reply") }

            chat.complete(second)
            chat.republishCompleted(second.copy(text = "Duplicate event"))
            speech.finishPlayback()

            eventually { speech.spoken == listOf("First reply", "Second reply") }
            assertEquals("second", voiceMode.state.value.lastSpokenMessageId)
        } finally {
            voiceMode.close()
        }
    }

    @Test
    fun `speaking can be interrupted by new live speech`() = runBlocking {
        val speech = FakeVoiceModeSpeech()
        val chat = FakeVoiceModeChat()
        val voiceMode = VoiceMode(speech)
        try {
            voiceMode.start(chat)
            voiceMode.awaitState { it.active }
            chat.complete(ChatMessage(id = "reply", role = ChatRole.ASSISTANT, text = "A long reply"))
            eventually { speech.spoken.isNotEmpty() }
            speech.beginPlayback()
            val stopsBeforeBargeIn = speech.stopSpeakingCalls.get()
            speech.updateDictation("Wait")

            eventually { speech.stopSpeakingCalls.get() > stopsBeforeBargeIn }
            voiceMode.awaitState { !it.speaking }
        } finally {
            voiceMode.close()
        }
    }

    private suspend fun VoiceMode.awaitState(predicate: (VoiceModeState) -> Boolean) {
        withTimeout(2_000) {
            while (!predicate(state.value)) delay(5)
        }
    }

    private suspend fun eventually(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) delay(5)
        }
    }

    private class FakeVoiceModeSpeech : VoiceModeSpeechAdapter {
        private val mutableDictation = MutableStateFlow<DictationState>(DictationState.Idle)
        private val mutablePlayback = MutableStateFlow<SpeechPlaybackState>(SpeechPlaybackState.Idle)
        override val dictation: StateFlow<DictationState> = mutableDictation
        override val playback: StateFlow<SpeechPlaybackState> = mutablePlayback
        val spoken = CopyOnWriteArrayList<String>()
        val startDictationCalls = AtomicInteger()
        val stopSpeakingCalls = AtomicInteger()

        override fun startDictation(finishOnEndpoint: Boolean, stopPlaybackFirst: Boolean) {
            startDictationCalls.incrementAndGet()
            if (stopPlaybackFirst) mutablePlayback.value = SpeechPlaybackState.Idle
            mutableDictation.value = DictationState.Listening("")
        }

        override fun clearDictation() {
            mutableDictation.value = DictationState.Idle
        }

        override fun cancelDictation() {
            mutableDictation.value = DictationState.Idle
        }

        override fun speak(text: String) {
            spoken += text
            mutableDictation.value = DictationState.Idle
            mutablePlayback.value = SpeechPlaybackState.Loading
        }

        override fun stopSpeaking() {
            stopSpeakingCalls.incrementAndGet()
            mutablePlayback.value = SpeechPlaybackState.Idle
        }

        fun finishDictation(text: String) {
            mutableDictation.value = DictationState.Finished(text)
        }

        fun beginPlayback() {
            mutablePlayback.value = SpeechPlaybackState.Speaking
        }

        fun finishPlayback() {
            mutablePlayback.value = SpeechPlaybackState.Idle
        }

        fun updateDictation(text: String) {
            mutableDictation.value = DictationState.Listening(text)
        }
    }

    private class FakeVoiceModeChat : VoiceModeChatAdapter {
        private val mutableState = MutableStateFlow<CodexChatState>(CodexChatState.Ready("test"))
        private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private val mutableCompleted = MutableStateFlow<List<ChatMessage>>(emptyList())
        override val id: String = "voice-test-chat"
        override val state: StateFlow<CodexChatState> = mutableState
        override val messages: StateFlow<List<ChatMessage>> = mutableMessages
        override val completedAssistantMessages: StateFlow<List<ChatMessage>> = mutableCompleted
        val sentPrompts = CopyOnWriteArrayList<String>()
        val interruptCalls = AtomicInteger()

        override fun send(text: String): Boolean {
            if (mutableState.value !is CodexChatState.Ready) return false
            sentPrompts += text
            mutableState.value = CodexChatState.Running("test")
            return true
        }

        override fun interrupt(): Boolean {
            interruptCalls.incrementAndGet()
            return true
        }

        fun setRunning() {
            mutableState.value = CodexChatState.Running("test")
        }

        fun setReady() {
            mutableState.value = CodexChatState.Ready("test")
        }

        fun complete(message: ChatMessage) {
            mutableMessages.value = mutableMessages.value + message
            mutableCompleted.value = mutableCompleted.value + message
            mutableState.value = CodexChatState.Ready("test")
        }

        fun republishCompleted(message: ChatMessage) {
            mutableCompleted.value = mutableCompleted.value + message
        }
    }
}
