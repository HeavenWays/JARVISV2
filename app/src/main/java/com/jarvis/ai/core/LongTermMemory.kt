package com.jarvis.ai.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MÉMOIRE À LONG TERME de Jarvis.
 *
 * Tout ce que l'utilisateur confie (identité, proches, préférences, habitudes, travail,
 * projets, objectifs, décisions, rendez-vous…) est enregistré ICI, dans un fichier JSON
 * sur le téléphone qui SURVIT aux redémarrages, aux fermetures de l'app et aux mises à jour.
 * Rien n'est jamais perdu.
 *
 * Deux sources l'alimentent :
 *  - CAPTURE AUTOMATIQUE : après chaque échange, le cerveau extrait les infos durables et
 *    les ajoute ici (voir Brain.learnFrom).
 *  - CAPTURE EXPLICITE : « souviens-toi que… », « retiens que… » → enregistrées mot pour mot.
 *
 * À chaque tour de conversation, ces souvenirs sont réinjectés dans le cerveau
 * (personaPrompt) pour que Jarvis « connaisse » réellement son interlocuteur.
 */
object LongTermMemory {

    data class Item(val text: String, val time: Long)

    private const val FILE = "jarvis_memory.json"
    private const val MAX_ITEMS = 500      // garde-fou (très largement suffisant)
    private const val MAX_IN_PROMPT = 70   // combien de souvenirs on réinjecte au cerveau

    private val items = ArrayList<Item>()
    private var file: File? = null
    @Volatile private var loaded = false

    /** À appeler une fois au démarrage (depuis Jarvis.init). */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        file = File(context.filesDir, FILE)
        load()
        loaded = true
    }

    @Synchronized
    fun all(): List<Item> = items.toList()

    @Synchronized
    fun count(): Int = items.size

    /** Ajoute un fait s'il est nouveau (anti-doublon souple). Renvoie true si ajouté. */
    @Synchronized
    fun add(fact: String): Boolean {
        val f = clean(fact) ?: return false
        val norm = normalize(f)
        if (norm.length < 3) return false
        for (it in items) {
            val n = normalize(it.text)
            // déjà connu, ou l'un contient l'autre (mise à jour d'un fait proche)
            if (n == norm || n.contains(norm) || norm.contains(n)) return false
        }
        items.add(Item(f, System.currentTimeMillis()))
        while (items.size > MAX_ITEMS) items.removeAt(0)
        save()
        return true
    }

    @Synchronized
    fun addAll(facts: List<String>): Int {
        var n = 0
        for (f in facts) if (add(f)) n++
        return n
    }

    /** Oublie les souvenirs qui contiennent [query]. Renvoie le nombre supprimé. */
    @Synchronized
    fun forget(query: String): Int {
        val q = normalize(query)
        if (q.isBlank()) return 0
        val before = items.size
        items.removeAll { normalize(it.text).contains(q) }
        val removed = before - items.size
        if (removed > 0) save()
        return removed
    }

    @Synchronized
    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        save()
    }

    /** Bloc à injecter dans le prompt système (vide si aucune mémoire). */
    @Synchronized
    fun promptBlock(): String {
        if (items.isEmpty()) return ""
        val slice = items.takeLast(MAX_IN_PROMPT)
        val sb = StringBuilder()
        for (it in slice) sb.append("- ").append(it.text).append('\n')
        return sb.toString().trimEnd()
    }

    // ---------- Détection de commandes de mémoire explicites ----------

    /** « souviens-toi que X », « retiens que X »… → renvoie X, ou null. */
    fun extractSaveRequest(text: String): String? {
        val lower = text.lowercase()
        val triggers = listOf(
            "souviens-toi que ", "souviens toi que ", "rappelle-toi que ", "rappelle toi que ",
            "retiens bien que ", "retiens que ", "n'oublie pas que ", "note bien que ", "note que ",
            "il faut que tu retiennes que ", "je veux que tu retiennes que ",
            "souviens-toi de ", "souviens toi de "
        )
        for (t in triggers) {
            val idx = lower.indexOf(t)
            if (idx >= 0) {
                val fact = text.substring(idx + t.length).trim()
                if (fact.length >= 2) return fact
            }
        }
        return null
    }

    /** « oublie tout », « efface ta mémoire »… */
    fun isForgetAll(text: String): Boolean {
        val l = normalize(text)
        return l.contains("oublie tout") || l.contains("efface tout") ||
            l.contains("efface ta memoire") || l.contains("vide ta memoire") ||
            l.contains("supprime ta memoire") || l.contains("efface ce que tu sais")
    }

    // ---------- interne ----------

    private fun clean(s: String): String? {
        var t = s.trim().trim('-', '•', '*', '"', '\'', '.', ' ')
        if (t.length < 3) return null
        if (t.length > 240) t = t.substring(0, 240).trim()
        // évite d'enregistrer une phrase qui n'est manifestement pas un fait
        return t
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            items.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t")
                if (t.isNotBlank()) items.add(Item(t, o.optLong("time", System.currentTimeMillis())))
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (it in items) arr.put(JSONObject().put("t", it.text).put("time", it.time))
            f.writeText(arr.toString())
        } catch (_: Exception) {}
    }
}
