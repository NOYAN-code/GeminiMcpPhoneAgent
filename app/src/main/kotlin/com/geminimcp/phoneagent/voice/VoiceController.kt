package com.geminimcp.phoneagent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

enum class VoiceState {
    IDLE,
    WAKE_LISTENING,
    COMMAND_LISTENING,
    CONFIRMATION_LISTENING,
    SPEAKING
}

class VoiceController(
    private val context: Context,
    private val onCommandCaptured: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private var wakeModeActive = false
    private var wakePhrase = "hey gemini"
    private var confirmationCallback: ((String) -> Unit)? = null
    private val pendingSpeechCallbacks = mutableMapOf<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.value = VoiceState.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        val cb = utteranceId?.let { pendingSpeechCallbacks.remove(it) }
                        mainHandler.post {
                            if (cb != null) {
                                cb()
                            } else if (wakeModeActive) {
                                startWakeLoop()
                            } else {
                                _state.value = VoiceState.IDLE
                            }
                        }
                    }

                    @Deprecated("Deprecated in Android SDK")
                    override fun onError(utteranceId: String?) {
                        onDone(utteranceId)
                    }
                })
            }
        }
    }

    fun speak(text: String, afterDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            afterDone?.invoke()
            return
        }
        mainHandler.post {
            stopRecognizerInternal()
            if (!ttsReady || tts == null) {
                afterDone?.invoke()
                return@post
            }
            val id = UUID.randomUUID().toString()
            if (afterDone != null) {
                pendingSpeechCallbacks[id] = afterDone
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun startCommandListening() {
        mainHandler.post {
            tts?.stop()
            confirmationCallback = null
            _partialTranscript.value = ""
            _state.value = VoiceState.COMMAND_LISTENING
            startRecognizerInternal(preferOffline = false)
        }
    }

    fun speakAndListenForConfirmation(prompt: String, onReply: (String) -> Unit) {
        confirmationCallback = onReply
        speak(prompt) {
            _partialTranscript.value = ""
            _state.value = VoiceState.CONFIRMATION_LISTENING
            startRecognizerInternal(preferOffline = false)
        }
    }

    fun setWakeWordEnabled(enabled: Boolean, phrase: String) {
        wakePhrase = phrase.trim().lowercase().ifEmpty { "hey gemini" }
        wakeModeActive = enabled
        mainHandler.post {
            if (enabled) {
                startWakeLoop()
            } else if (_state.value == VoiceState.WAKE_LISTENING) {
                stopRecognizerInternal()
                _state.value = VoiceState.IDLE
            }
        }
    }

    fun stopAll() {
        mainHandler.post {
            wakeModeActive = false
            confirmationCallback = null
            tts?.stop()
            stopRecognizerInternal()
            _state.value = VoiceState.IDLE
        }
    }

    fun release() {
        stopAll()
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
            tts?.shutdown()
            tts = null
        }
    }

    private fun startWakeLoop() {
        if (!wakeModeActive) return
        _state.value = VoiceState.WAKE_LISTENING
        startRecognizerInternal(preferOffline = true)
    }

    private fun startRecognizerInternal(preferOffline: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceState.IDLE
            return
        }
        stopRecognizerInternal()
        val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    _partialTranscript.value = partial
                }
            }

            override fun onResults(results: Bundle?) {
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                handleRecognitionText(spoken)
            }

            override fun onError(error: Int) {
                val current = _state.value
                if (current == VoiceState.WAKE_LISTENING && wakeModeActive) {
                    mainHandler.postDelayed({ startWakeLoop() }, 650L)
                } else if (current == VoiceState.CONFIRMATION_LISTENING && confirmationCallback != null) {
                    mainHandler.postDelayed({ startRecognizerInternal(false) }, 450L)
                } else {
                    _state.value = VoiceState.IDLE
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        sr.startListening(intent)
    }

    private fun handleRecognitionText(spoken: String) {
        val current = _state.value
        _partialTranscript.value = spoken

        when (current) {
            VoiceState.CONFIRMATION_LISTENING -> {
                val cb = confirmationCallback
                confirmationCallback = null
                _state.value = VoiceState.IDLE
                if (spoken.isNotBlank()) cb?.invoke(spoken)
            }

            VoiceState.COMMAND_LISTENING -> {
                _state.value = VoiceState.IDLE
                if (spoken.isNotBlank()) {
                    onCommandCaptured(spoken)
                } else if (wakeModeActive) {
                    startWakeLoop()
                }
            }

            VoiceState.WAKE_LISTENING -> {
                val lower = spoken.lowercase()
                if (lower.contains(wakePhrase)) {
                    val trailingCommand = lower.substringAfter(wakePhrase).trim()
                    if (trailingCommand.length >= 3) {
                        _state.value = VoiceState.IDLE
                        onCommandCaptured(trailingCommand)
                    } else {
                        speak("Listening") { startCommandListening() }
                    }
                } else if (wakeModeActive) {
                    mainHandler.postDelayed({ startWakeLoop() }, 300L)
                }
            }

            else -> Unit
        }
    }

    private fun stopRecognizerInternal() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
