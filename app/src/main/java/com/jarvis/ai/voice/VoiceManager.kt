package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Les "oreilles" de Jarvis en mode MAINS-LIBRES : écoute en continu.
 * Pour chaque phrase entendue, `onHeard` est appelé — c'est le service qui décide
 * quoi en faire (ignorer si le mot « Jarvis » est absent, ou traiter la commande).
 * L'écoute se relance automatiquement tant que le mode ambiant est actif.
 */
class VoiceManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    var onHeard: ((String) -> Unit)? = null
    var onListeningChange: ((Boolean) -> Unit)? = null

    @Volatile private var ambient = false
    @Volatile private var paused = false
    @Volatile private var sessionActive = false
    @Volatile private var restartScheduled = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Démarre l'écoute continue. */
    fun startAmbient() {
        ambient = true
        paused = false
        listenCycle()
    }

    /** Arrête complètement l'écoute continue. */
    fun stopAmbient() {
        ambient = false
        cancelInternal()
    }

    /** Suspend l'écoute (ex : pendant que Jarvis parle ou agit) pour ne pas s'entendre lui-même. */
    fun pause() {
        paused = true
        cancelInternal()
    }

    /** Reprend l'écoute continue. */
    fun resume() {
        if (!ambient) return
        paused = false
        listenCycle()
    }

    private fun ensureRecognizer() {
        if (recognizer == null && available) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
            }
        }
    }

    private fun listenCycle() {
        if (!ambient || paused || sessionActive) return
        ensureRecognizer()
        val rec = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.FRENCH.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Fin de phrase détectée plus vite → Jarvis répond plus tôt (valeurs indicatives).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
        try {
            sessionActive = true
            onListeningChange?.invoke(true)
            rec.startListening(intent)
        } catch (e: Exception) {
            sessionActive = false
            scheduleRestart(700)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!ambient || paused || restartScheduled || sessionActive) return
        restartScheduled = true
        main.postDelayed({
            restartScheduled = false
            listenCycle()
        }, delayMs)
    }

    private fun cancelInternal() {
        sessionActive = false
        onListeningChange?.invoke(false)
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    fun destroy() {
        ambient = false
        sessionActive = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            sessionActive = false
            onListeningChange?.invoke(false)
            // Erreurs bénignes (silence, aucune correspondance, reconnaisseur occupé) : on relance vite.
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 700L
                SpeechRecognizer.ERROR_CLIENT -> 500L
                else -> 200L
            }
            scheduleRestart(delay)
        }

        override fun onResults(results: Bundle?) {
            sessionActive = false
            onListeningChange?.invoke(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) onHeard?.invoke(text)
            // Si `onHeard` a mis l'écoute en pause (commande en cours), le garde ci-dessous l'empêche.
            scheduleRestart(120)
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
