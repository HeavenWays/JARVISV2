package com.jarvis.ai.wake

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Détection hors-ligne du mot d'activation « Jarvis » via Vosk.
 *
 * Nécessite un modèle vocal FR dans `app/src/main/assets/model-fr/`
 * (télécharge "vosk-model-small-fr" et place son contenu dans ce dossier).
 * Si le modèle est absent, le manager se désactive silencieusement
 * (la bulle "appui pour parler" continue de fonctionner).
 */
class WakeWordManager(
    private val context: Context,
    private val onWake: () -> Unit
) {
    private var model: Model? = null
    private var speechService: SpeechService? = null

    @Volatile private var armed = true
    @Volatile var available = false
        private set

    fun start() {
        if (model != null) {
            beginListening()
            return
        }
        try {
            StorageService.unpack(
                context, "model-fr", "vosk-model",
                { m ->
                    model = m
                    available = true
                    beginListening()
                },
                { _ ->
                    // Modèle absent/illisible : mode mains-libres désactivé.
                    available = false
                }
            )
        } catch (e: Exception) {
            available = false
        }
    }

    private fun beginListening() {
        val m = model ?: return
        try {
            val recognizer = Recognizer(m, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            armed = true
            speechService?.startListening(listener)
        } catch (e: Exception) {
            available = false
        }
    }

    /** Libère le micro pendant la capture d'une commande. */
    fun pauseListening() {
        try { speechService?.stop() } catch (_: Exception) {}
        speechService = null
    }

    /** Reprend l'écoute du mot d'activation après une commande. */
    fun resumeListening() {
        if (model == null) return
        if (speechService == null) beginListening() else armed = true
    }

    fun stop() {
        try { speechService?.stop() } catch (_: Exception) {}
        speechService = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        available = false
    }

    private fun matches(hypothesis: String): Boolean {
        val text = try {
            JSONObject(hypothesis).optString("partial").ifBlank {
                JSONObject(hypothesis).optString("text")
            }
        } catch (e: Exception) {
            hypothesis
        }
        return text.contains("jarvis", ignoreCase = true) ||
            text.contains("jarviss", ignoreCase = true) ||
            text.contains("jarvi", ignoreCase = true)
    }

    private fun fire() {
        if (!armed) return
        armed = false
        onWake()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (hypothesis != null && matches(hypothesis)) fire()
        }
        override fun onResult(hypothesis: String?) {
            if (hypothesis != null && matches(hypothesis)) fire()
        }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {}
        override fun onTimeout() {}
    }
}
