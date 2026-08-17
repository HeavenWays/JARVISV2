package com.jarvis.ai.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.ai.apps.AppScanner

/** Traduit une action (du routeur ou de l'agent) en action réelle sur le téléphone. */
class ActionExecutor(private val context: Context) {

    val appScanner = AppScanner(context)

    /** Parle puis exécute (utilisé par le routeur). */
    fun execute(result: AgentResult): String {
        Jarvis.speak(result.say)
        return perform(result.action, result.target)
    }

    /** Exécute une action SANS parler (utilisé par l'agent multi-étapes). */
    fun perform(action: String, target: String): String = when (action) {
        "open_app" -> openApp(target)
        "open_url" -> openUrl(target)
        "web_search" -> webSearch(target)
        "type_text" -> typeText(target)
        "click_text" -> clickText(target)
        "press_enter" -> pressEnter()
        "go_home" -> global("home")
        "go_back" -> global("back")
        "open_recents" -> global("recents")
        "scroll_down" -> scroll(true)
        "scroll_up" -> scroll(false)
        "wait" -> "attente"
        else -> "réponse vocale"
    }

    private fun openApp(name: String): String {
        val entry = appScanner.find(name) ?: return "app introuvable : $name"
        return if (appScanner.launch(entry)) "ouvert : ${entry.label}" else "échec ouverture : $name"
    }

    private fun openUrl(url: String): String {
        val fixed = if (url.startsWith("http", true)) url else "https://$url"
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(fixed))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "ouvert : $fixed"
        } catch (e: Exception) {
            "URL invalide : $url"
        }
    }

    private fun webSearch(query: String): String =
        openUrl("https://duckduckgo.com/?q=" + Uri.encode(query))

    private fun typeText(text: String): String {
        if (text.isBlank()) return "texte vide"
        val svc = Jarvis.accessibility ?: return "accessibilité désactivée"
        Jarvis.runOnMain { svc.typeText(text) }
        return "écrit : $text"
    }

    private fun clickText(text: String): String {
        if (text.isBlank()) return "cible vide"
        val svc = Jarvis.accessibility ?: return "accessibilité désactivée"
        Jarvis.runOnMain { svc.clickByText(text) }
        return "clic : $text"
    }

    private fun pressEnter(): String {
        val svc = Jarvis.accessibility ?: return "accessibilité désactivée"
        Jarvis.runOnMain { svc.pressEnter() }
        return "envoi (touche entrée)"
    }

    private fun global(which: String): String {
        val svc = Jarvis.accessibility ?: return "accessibilité désactivée"
        Jarvis.runOnMain { svc.doGlobal(which) }
        return "action système : $which"
    }

    private fun scroll(down: Boolean): String {
        val svc = Jarvis.accessibility ?: return "accessibilité désactivée"
        Jarvis.runOnMain { svc.doScroll(down) }
        return if (down) "défilement bas" else "défilement haut"
    }
}
