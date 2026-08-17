package com.jarvis.ai.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Recherche web gratuite (sans clé) via DuckDuckGo.
 * Renvoie des extraits texte que le cerveau transforme ensuite en réponse orale.
 */
object WebSearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSnippets(query: String): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()

        // 1) Réponse instantanée DuckDuckGo (JSON, fiable pour les faits/définitions)
        try {
            val url = "https://api.duckduckgo.com/?q=" + enc(query) +
                "&format=json&no_html=1&skip_disambig=1"
            val json = get(url)
            if (json.isNotBlank()) {
                val j = JSONObject(json)
                j.optString("AbstractText").takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
                j.optString("Answer").takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
                j.optString("Definition").takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
                val related = j.optJSONArray("RelatedTopics")
                if (related != null) {
                    var i = 0
                    while (i < related.length() && out.length < 1200) {
                        val o = related.optJSONObject(i)
                        o?.optString("Text")?.takeIf { it.isNotBlank() }?.let {
                            out.append("- ").append(it).append('\n')
                        }
                        i++
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2) Repli : extraits HTML "lite" si on n'a presque rien
        if (out.length < 160) {
            try {
                val html = get("https://lite.duckduckgo.com/lite/?q=" + enc(query))
                val snippets = Regex("<td[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .findAll(html)
                    .map { stripHtml(it.groupValues[1]) }
                    .filter { it.isNotBlank() }
                    .take(5)
                    .toList()
                snippets.forEach { out.append("- ").append(it).append('\n') }
            } catch (_: Exception) {
            }
        }

        out.toString().trim().take(1500)
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) JarvisAI")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            return resp.body?.string().orEmpty()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
