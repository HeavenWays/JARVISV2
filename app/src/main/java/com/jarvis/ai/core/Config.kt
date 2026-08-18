package com.jarvis.ai.core

import android.content.Context
import com.jarvis.ai.BuildConfig

/** Préférences persistantes : clé Groq + modèles (modifiables dans l'app). */
class Config(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    var groqKey: String
        // .trim() : évite qu'un espace/retour à la ligne parasite dans la clé (ex. venant du
        // secret CI) ne rende l'en-tête HTTP invalide → fausse "erreur de connexion".
        get() = (prefs.getString(KEY_GROQ, BuildConfig.GROQ_API_KEY) ?: BuildConfig.GROQ_API_KEY).trim()
        set(v) { prefs.edit().putString(KEY_GROQ, v.trim()).apply() }

    var textModel: String
        get() = prefs.getString(KEY_TEXT_MODEL, DEFAULT_TEXT_MODEL) ?: DEFAULT_TEXT_MODEL
        set(v) { prefs.edit().putString(KEY_TEXT_MODEL, v.trim()).apply() }

    var visionModel: String
        get() = prefs.getString(KEY_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(v) { prefs.edit().putString(KEY_VISION_MODEL, v.trim()).apply() }

    /** Prénom par lequel Jarvis s'adresse à l'utilisateur (vide = "Monsieur"). */
    var userName: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(v) { prefs.edit().putString(KEY_NAME, v.trim()).apply() }

    /** Voix neuronale humaine (en ligne). Si false → voix Android hors-ligne. */
    var naturalVoice: Boolean
        get() = prefs.getBoolean(KEY_NAT_VOICE, true)
        set(v) { prefs.edit().putBoolean(KEY_NAT_VOICE, v).apply() }

    /** Nom de la voix neuronale (ex : fr-FR-HenriNeural, fr-FR-RemyMultilingualNeural). */
    var voiceName: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(v) { prefs.edit().putString(KEY_VOICE, v.trim()).apply() }

    val hasKey: Boolean get() = groqKey.startsWith("gsk_")

    companion object {
        private const val KEY_GROQ = "groq_key"
        private const val KEY_TEXT_MODEL = "text_model"
        private const val KEY_VISION_MODEL = "vision_model"
        private const val KEY_NAME = "user_name"
        private const val KEY_NAT_VOICE = "natural_voice"
        private const val KEY_VOICE = "voice_name"

        // Modèles Groq gratuits (modifiables si Groq en change le nom un jour)
        const val DEFAULT_TEXT_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"

        // Voix masculine FR neuronale la plus proche d'un vrai humain (gratuite).
        // Rémy (multilingue, dernière génération) sonne beaucoup plus naturel et chaleureux
        // que Henri (qui « fait IA »). C'est la voix par défaut de Jarvis.
        const val DEFAULT_VOICE = "fr-FR-RemyMultilingualNeural"
    }
}
