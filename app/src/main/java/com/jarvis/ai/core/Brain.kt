package com.jarvis.ai.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Décision simple : ce que Jarvis dit + une action. */
data class AgentResult(
    val say: String,
    val action: String = "answer",
    val target: String = ""
)

/** Une étape du mode agent multi-étapes. */
data class AgentStep(
    val say: String,
    val action: String,
    val target: String
)

/** Résultat d'un tour de conversation : le texte prononcé + une éventuelle action. */
data class ConverseResult(
    val text: String,
    val action: String,
    val target: String
)

private data class ChatOut(val ok: Boolean, val content: String)

/**
 * Le cerveau : parle à Groq (Llama).
 *  - converse()    : CONVERSATION naturelle en flux (personnalité + mémoire du dialogue),
 *                    avec éventuellement une action à exécuter à la fin.
 *  - seeAndReply() : répond à une question visuelle (capture d'écran), avec la même personnalité.
 *  - nextStep()    : mode agent — décide UNE prochaine action vu l'écran + l'historique.
 *  - webAnswer()   : synthétise une réponse orale à partir d'extraits web.
 *  - handle()      : ancien routeur JSON (conservé, encore utilisé en secours).
 */
class Brain(private val config: Config, private val memory: ScreenMemory) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // Portée de fond pour l'apprentissage (mémoire long terme) sans ralentir la parole.
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Mémoire roulante du dialogue (paires rôle/contenu), pour une vraie conversation suivie.
    private val history = ArrayDeque<Pair<String, String>>()
    private val maxHistory = 16

    @Synchronized private fun remember(role: String, content: String) {
        val c = content.trim()
        if (c.isEmpty()) return
        history.addLast(role to c.take(500))
        while (history.size > maxHistory) history.removeFirst()
    }

    @Synchronized private fun historyMessages(): List<Pair<String, String>> = history.toList()

    fun clearConversation() {
        synchronized(this) { history.clear() }
    }

    // ---------- CONVERSATION en flux ----------

    /**
     * Répond à [userText] en parlant comme un humain. [onSentence] est appelé au fil de l'eau
     * (dès qu'une phrase complète est prête) pour que Jarvis commence à parler tout de suite.
     * Renvoie le texte complet dit + l'action à exécuter (ou "chat" s'il s'agit juste de discuter).
     */
    suspend fun converse(
        userText: String,
        screenHint: String?,
        onSentence: (String) -> Unit
    ): ConverseResult = withContext(Dispatchers.IO) {
        if (!config.hasKey) {
            val m = "Il me manque ma clé pour réfléchir. Ajoute ta clé Groq dans les réglages, et je serai à toi."
            onSentence(m)
            return@withContext ConverseResult(m, "chat", "")
        }

        val messages = JSONArray().put(msg("system", personaPrompt()))
        if (!screenHint.isNullOrBlank()) {
            messages.put(
                msg("system", "[Ce qui est actuellement à l'écran — à n'évoquer que si c'est vraiment pertinent]\n" + screenHint.take(1200))
            )
        }
        for ((role, content) in historyMessages()) messages.put(msg(role, content))
        messages.put(msg("user", userText))

        val sb = StringBuilder()
        var spokenIdx = 0

        fun speakable(): String {
            val full = sb.toString()
            val cut = full.indexOf("[[")
            return if (cut >= 0) full.substring(0, cut) else full
        }

        fun flush(finalFlush: Boolean) {
            val speakable = speakable()
            if (finalFlush) {
                if (spokenIdx < speakable.length) {
                    val rest = speakable.substring(spokenIdx).trim()
                    if (rest.isNotBlank()) onSentence(rest)
                    spokenIdx = speakable.length
                }
                return
            }
            while (true) {
                val end = sentenceEnd(speakable, spokenIdx) ?: break
                val s = speakable.substring(spokenIdx, end).trim()
                spokenIdx = end
                if (s.isNotBlank()) onSentence(s)
            }
        }

        val ok = streamCall(body(config.textModel, messages, stream = true, maxTokens = 420, temperature = 0.6)) { delta ->
            sb.append(delta)
            flush(false)
        }
        if (!ok && sb.isBlank()) {
            // On remonte le VRAI message d'erreur (ex : "Erreur du serveur IA (code 400)…" quand
            // le modèle n'existe plus) au lieu de tout masquer en "problème de connexion".
            val out = call(body(config.textModel, messages, stream = false, maxTokens = 420, temperature = 0.6))
            sb.append(out.content)
        }
        flush(true)

        val full = sb.toString()
        val cut = full.indexOf("[[")
        val spoken = (if (cut >= 0) full.substring(0, cut) else full).trim()
        val (action, target) = parseAction(full)

        remember("user", userText)
        remember("assistant", if (spoken.isBlank()) full.trim().take(300) else spoken)

        // Apprentissage : on retient en tâche de fond ce qui est durable (sans retarder la parole).
        learnFrom(userText, if (spoken.isBlank()) full.trim() else spoken)

        ConverseResult(spoken, action, target)
    }

    // ---------- Vision (avec personnalité) ----------

    suspend fun seeAndReply(userText: String, imageBase64: String): String =
        withContext(Dispatchers.IO) {
            if (!config.hasKey) return@withContext "Il me manque ma clé pour regarder l'écran."
            val content = JSONArray()
                .put(
                    JSONObject().put("type", "text").put(
                        "text",
                        "Regarde cette capture de l'écran et réponds à voix haute, en une à trois " +
                            "phrases naturelles, sans liste ni mise en forme.\n[DEMANDE]\n$userText"
                    )
                )
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
                )
            val messages = JSONArray()
                .put(msg("system", personaPrompt()))
                .put(JSONObject().put("role", "user").put("content", content))

            val out = call(body(config.visionModel, messages, stream = false, maxTokens = 400))
            val answer = if (out.ok) out.content.trim() else "Je n'ai pas réussi à analyser l'écran."
            remember("user", userText)
            remember("assistant", answer.take(300))
            learnFrom(userText, answer)
            answer
        }

    // ---------- Mode agent (étape par étape) ----------

    suspend fun nextStep(goal: String, history: List<String>, screen: String): AgentStep =
        withContext(Dispatchers.IO) {
            if (!config.hasKey) return@withContext AgentStep("Aucune clé configurée.", "done", "")
            val hist = if (history.isEmpty()) "(aucune action pour l'instant)"
            else history.joinToString("\n")

            val userContent =
                "[OBJECTIF]\n$goal\n\n" +
                    "[ACTIONS DÉJÀ FAITES]\n$hist\n\n" +
                    "[ÉCRAN ACTUEL]\n${screen.take(2500)}\n\n" +
                    "Donne UNIQUEMENT la prochaine étape."

            val messages = JSONArray()
                .put(msg("system", STEP_PROMPT))
                .put(msg("user", userContent))

            val out = call(body(config.textModel, messages))
            parseStep(if (out.ok) out.content else "{\"action\":\"done\",\"say\":\"\"}")
        }

    // ---------- Réponse à partir du web ----------

    suspend fun webAnswer(query: String): AgentResult = withContext(Dispatchers.IO) {
        if (!config.hasKey) return@withContext AgentResult("Aucune clé Groq configurée.")
        val snippets = WebSearch.fetchSnippets(query)
        if (snippets.isBlank()) {
            return@withContext AgentResult(
                "Je n'ai rien trouvé de fiable là-dessus sur le web.", "answer", ""
            )
        }
        val messages = JSONArray()
            .put(
                msg(
                    "system",
                    "Tu es Jarvis. À partir des extraits web fournis, réponds en français à la " +
                        "question, en 1 à 3 phrases claires et naturelles, prononçables à voix haute. " +
                        "Pas de liste ni de mise en forme. " +
                        "Si l'information n'est pas dans les extraits, dis-le franchement."
                )
            )
            .put(msg("user", "[EXTRAITS WEB]\n$snippets\n\n[QUESTION]\n$query"))

        val out = call(body(config.textModel, messages))
        if (out.ok) AgentResult(out.content.trim().take(600), "answer", "")
        else AgentResult(out.content)
    }

    // ---------- Ancien routeur (secours) ----------

    suspend fun handle(userText: String): AgentResult = withContext(Dispatchers.IO) {
        if (!config.hasKey) {
            return@withContext AgentResult("Aucune clé Groq configurée. Ajoute ta clé dans les réglages de Jarvis.")
        }
        val screen = memory.current()?.text ?: "(écran non lu)"
        val messages = JSONArray()
            .put(msg("system", ROUTER_PROMPT))
            .put(msg("user", "[ÉCRAN]\n${screen.take(2000)}\n\n[DEMANDE]\n$userText"))
        val out = call(body(config.textModel, messages))
        if (out.ok) parse(out.content) else AgentResult(out.content)
    }

    // ---------- Réseau ----------

    /** Appel classique (réponse complète en une fois). */
    private fun call(body: JSONObject): ChatOut {
        return try {
            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer ${config.groqKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ChatOut(
                        false,
                        "Erreur du serveur IA (code ${resp.code}). Vérifie ta clé Groq ou le nom du modèle."
                    )
                }
                val content = JSONObject(raw)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                ChatOut(true, content)
            }
        } catch (e: Exception) {
            ChatOut(false, "Je n'ai pas pu joindre le serveur IA. Vérifie ta connexion internet.")
        }
    }

    /** Appel en FLUX (SSE) : [onDelta] reçoit chaque fragment de texte au fur et à mesure. */
    private fun streamCall(body: JSONObject, onDelta: (String) -> Unit): Boolean {
        return try {
            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer ${config.groqKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val source = resp.body?.source() ?: return false
                var any = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    try {
                        val delta = JSONObject(data)
                            .getJSONArray("choices").getJSONObject(0)
                            .optJSONObject("delta")?.optString("content").orEmpty()
                        if (delta.isNotEmpty()) { any = true; onDelta(delta) }
                    } catch (_: Exception) {}
                }
                any
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun body(
        model: String,
        messages: JSONArray,
        stream: Boolean = false,
        maxTokens: Int = 600,
        temperature: Double = 0.4
    ): JSONObject {
        val o = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("messages", messages)
        if (stream) o.put("stream", true)
        return o
    }

    private fun msg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    // ---------- Découpage & extraction ----------

    /** Fin de phrase (pour parler en flux) : renvoie l'index juste après la phrase, ou null. */
    private fun sentenceEnd(s: String, from: Int): Int? {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\n') {
                if (i + 1 - from >= 2) return i + 1
            } else if ((c == '.' || c == '!' || c == '?' || c == '…') &&
                i + 1 < s.length && s[i + 1].isWhitespace()
            ) {
                if (i + 1 - from >= 4) {
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    return j
                }
            }
            i++
        }
        return null
    }

    private fun parseAction(full: String): Pair<String, String> {
        val idx = full.indexOf("[[ACTION]]")
        if (idx < 0) return "chat" to ""
        return try {
            val after = full.substring(idx + "[[ACTION]]".length)
            val o = JSONObject(extractJson(after))
            val a = o.optString("action", "chat").ifBlank { "chat" }
            a to o.optString("target", "")
        } catch (e: Exception) {
            "chat" to ""
        }
    }

    private fun parse(content: String): AgentResult {
        return try {
            val o = JSONObject(extractJson(content))
            val say = o.optString("say", "").ifBlank { o.optString("answer", "") }
            AgentResult(
                say = say.ifBlank { "D'accord." },
                action = o.optString("action", "answer").ifBlank { "answer" },
                target = o.optString("target", "")
            )
        } catch (e: Exception) {
            AgentResult(content.trim().take(400))
        }
    }

    private fun parseStep(content: String): AgentStep {
        return try {
            val o = JSONObject(extractJson(content))
            AgentStep(
                say = o.optString("say", ""),
                action = o.optString("action", "done").ifBlank { "done" },
                target = o.optString("target", "")
            )
        } catch (e: Exception) {
            AgentStep("", "done", "")
        }
    }

    private fun extractJson(content: String): String {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        return if (start >= 0 && end > start) content.substring(start, end + 1) else content
    }

    // ---------- Apprentissage (mémoire long terme) ----------

    /**
     * En tâche de fond, demande au modèle d'extraire les informations DURABLES de l'échange
     * et les enregistre dans la mémoire long terme. Ne bloque jamais la parole : la réponse
     * est déjà prononcée quand ceci s'exécute.
     */
    private fun learnFrom(userText: String, assistantText: String) {
        if (!config.hasKey) return
        val u = userText.trim()
        if (u.length < 3) return
        bgScope.launch {
            try {
                val content =
                    "[MESSAGE DE L'UTILISATEUR]\n$u\n\n" +
                        "[RÉPONSE DE JARVIS]\n${assistantText.trim().take(600)}\n\n" +
                        "Extrais les faits durables à retenir sur l'utilisateur."
                val messages = JSONArray()
                    .put(msg("system", MEMORY_PROMPT))
                    .put(msg("user", content))
                val out = call(body(config.textModel, messages, stream = false, maxTokens = 220, temperature = 0.0))
                if (out.ok) {
                    val facts = parseFacts(out.content)
                    if (facts.isNotEmpty()) LongTermMemory.addAll(facts)
                }
            } catch (_: Exception) {}
        }
    }

    /** Lit un tableau JSON de phrases-souvenirs renvoyé par le modèle. */
    private fun parseFacts(content: String): List<String> {
        return try {
            val start = content.indexOf('[')
            val end = content.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val arr = JSONArray(content.substring(start, end + 1))
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.length in 3..240) out.add(s)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- Personnalité ----------

    private fun personaPrompt(): String {
        val name = config.userName.trim()
        val who = if (name.isEmpty()) "ton interlocuteur" else name
        val addr = if (name.isEmpty()) "Monsieur" else name
        val mem = LongTermMemory.promptBlock()
        val memBlock = if (mem.isBlank()) "" else """

Ta MÉMOIRE sur $who (ce que tu sais déjà de lui — c'est vrai et durable) :
$mem
Sers-t'en naturellement quand c'est utile pour être plus juste et plus personnel.
Ne récite jamais cette liste d'un bloc ; ne dis pas « d'après ma mémoire ». Tu le sais, c'est tout.
"""
        return """
Tu es Jarvis, l'assistant personnel de $who — pas un chatbot, mais un véritable interlocuteur.
Tu as la classe, le calme et l'humour discret du majordome de Tony Stark dans Iron Man.
Tu parles À VOIX HAUTE, en français naturel, fluide et humain, comme au téléphone.

Style de parole :
- Phrases courtes et vivantes, ton chaleureux et posé, jamais mécanique.
- Emploie l'oral : « d'accord », « bien sûr », « voyons voir », « je m'en occupe », « tout à fait ».
- JAMAIS de listes, de puces, de tirets, d'astérisques, d'emojis ni de mise en forme : tout est lu à voix haute.
- Ne récite pas de menus d'options du type « je peux faire ceci, cela ». Réagis comme dans une vraie conversation.
- Ne te répète pas. Sois bref quand c'est simple ; développe seulement si on te le demande.
- Adresse-toi à lui par « $addr » de temps en temps, pas à chaque phrase.
- Tu te souviens de ce qui vient d'être dit (l'historique est fourni). Reste cohérent, enchaîne naturellement.
- Si tu ne sais pas, dis-le simplement, sans inventer.

Tu peux AGIR sur le téléphone. UNIQUEMENT si on te demande de FAIRE quelque chose concrètement
(ouvrir une application, envoyer un message, aller sur un site, chercher une info à jour, naviguer),
termine ta réponse par UNE seule ligne, tout à la fin, exactement à ce format :
[[ACTION]]{"action":"...","target":"..."}
Actions possibles : open_app, open_url, web_search, web_answer, task, click_text, go_home, go_back, scroll_down, scroll_up.
- Envoyer un message, écrire, ou enchaîner plusieurs étapes dans une app → action "task", target = l'objectif complet en une phrase.
- Info à jour (actualité, météo, sport, « c'est quoi X », « qui est X ») → action "web_answer", target = la question.
- Ouvrir une application → action "open_app", target = le nom de l'app.
Avant cette ligne, dis d'abord une phrase naturelle et brève (« Bien sûr, je m'en occupe. », « Voilà, j'ouvre ça. »).
Ne mets JAMAIS la ligne d'action si on veut juste discuter. Ne prononce jamais le mot « action » ni le JSON à voix haute.
        """.trim() + memBlock
    }

    companion object {
        private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        val MEMORY_PROMPT: String = """
Tu es le module de MÉMOIRE de Jarvis. À partir de l'échange fourni, tu extrais UNIQUEMENT
les informations DURABLES et utiles à long terme sur l'utilisateur :
son identité (prénom, âge, ville…), ses proches (famille, amis, collègues), son travail,
ses études, ses préférences et ses goûts, ses habitudes, ses projets, ses objectifs,
ses décisions, ses rendez-vous et dates importantes, et tout ce qu'il demande de retenir.

Règles strictes :
- N'invente RIEN. Ne déduis rien d'incertain.
- Ignore le bavardage, les questions générales de culture, les demandes ponctuelles
  (« quelle heure est-il », « ouvre YouTube »), et tout ce qui n'apprend rien sur LUI.
- Chaque fait doit être une phrase courte, en français, à la 3e personne, AUTONOME
  (compréhensible seule, sans le contexte).
- Ne répète pas un fait déjà évident ou trivial.

Réponds UNIQUEMENT par un tableau JSON de chaînes. Si rien n'est à retenir, réponds : []
Exemple : ["Il s'appelle Karim.", "Il déteste être appelé avant 9h.", "Il développe une app nommée CallTranslate.", "Sa sœur s'appelle Lina."]
        """.trim()

        val ROUTER_PROMPT: String = """
Tu es Jarvis. Réponds UNIQUEMENT par un objet JSON : {"say":"...","action":"...","target":"..."}.
Actions : answer, web_answer, web_search, open_url, open_app, task, go_home, go_back, scroll_down, scroll_up, click_text.
Réponds en français, bref.
        """.trim()

        val STEP_PROMPT: String = """
Tu es le module d'exécution de Jarvis. Tu réalises un OBJECTIF sur un téléphone Android,
UNE étape à la fois, en regardant l'écran actuel.

Réponds UNIQUEMENT par un objet JSON :
{"say": "<courte narration optionnelle>", "action": "<action>", "target": "<cible>"}

Actions disponibles :
- "open_app"   : ouvrir une app. target = nom (ex "WhatsApp", "Discord", "Messages").
- "click_text" : appuyer sur un élément visible à l'écran. target = son texte exact (ou une partie, ou l'intitulé d'une icône comme "Rechercher", "Nouvelle discussion", "Envoyer").
- "type_text"  : écrire dans le champ actuellement sélectionné. target = le texte à écrire.
- "press_enter": valider/envoyer via la touche entrée du clavier. target vide.
- "scroll_down" / "scroll_up" : faire défiler. target vide.
- "go_back" / "go_home" : navigation. target vide.
- "open_url"   : ouvrir une URL. target = URL.
- "wait"       : attendre que l'écran se charge. target vide.
- "done"       : l'objectif est atteint (ou impossible). Mets une phrase de conclusion dans "say".

Règles :
- Une seule action par réponse, la plus logique vu l'écran actuel.
- Envoyer un message (recette générale) :
  1) open_app (WhatsApp / Discord / Messages…)
  2) wait
  3) click_text sur l'icône/champ "Rechercher" (ou la loupe)
  4) type_text = le nom du contact ou du salon
  5) click_text sur le contact/salon qui apparaît dans les résultats
  6) wait
  7) click_text sur le champ "Message" / "Envoyer un message" (zone de saisie en bas)
  8) type_text = le contenu du message
  9) click_text sur l'icône d'envoi ("Envoyer"/"Send"/flèche) ; si tu ne la vois pas, "press_enter".
- Discord : le champ de saisie s'appelle souvent "Message @… " ; l'envoi se fait avec "press_enter".
- Si l'écran ne montre pas encore ce qu'il faut, utilise "wait".
- Ne réécris jamais deux fois le même message. Quand le message est parti, action "done".
        """.trim()
    }
}
