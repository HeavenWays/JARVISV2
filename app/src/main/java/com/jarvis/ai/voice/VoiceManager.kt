package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    @Volatile private var earconsMuted = false

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
            muteEarcons()            // coupe le "bip" système joué au démarrage de l'écoute
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
        unmuteEarcons()              // rend le son (ex : quand Jarvis va parler) — voir muteEarcons()
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    fun destroy() {
        ambient = false
        sessionActive = false
        unmuteEarcons()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    /**
     * Coupe le bip que le moteur de reconnaissance vocale joue au début (et à la fin) de
     * chaque écoute. Comme l'écoute se relance en continu, ce bip reviendrait sans cesse.
     * On garde les flux coupés pendant TOUTE la phase d'écoute, et on les rétablit dès que
     * Jarvis parle ou qu'on arrête d'écouter (pause/stopAmbient/destroy → cancelInternal).
     * Ainsi la voix de Jarvis reste audible et le bip disparaît.
     */
    private fun muteEarcons() {
        val am = audio ?: return
        if (earconsMuted) return
        earconsMuted = true
        for (stream in intArrayOf(
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION
        )) {
            try { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) {}
        }
    }

    private fun unmuteEarcons() {
        val am = audio ?: return
        if (!earconsMuted) return
        earconsMuted = false
        for (stream in intArrayOf(
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION
        )) {
            try { am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
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
