package com.jarvis.ai.apps

import android.content.Context
import android.content.Intent
import java.util.Locale

data class AppEntry(val label: String, val packageName: String)

/**
 * Scanne toutes les applications lançables du téléphone et permet
 * de retrouver + lancer la meilleure correspondance à partir d'un nom parlé.
 */
class AppScanner(private val context: Context) {

    @Volatile
    private var cache: List<AppEntry> = emptyList()

    fun scan(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val list = resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            AppEntry(label, pkg)
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        cache = list
        return list
    }

    fun apps(): List<AppEntry> = if (cache.isNotEmpty()) cache else scan()

    /** Recherche tolérante : exact -> commence par -> contient -> package. */
    fun find(query: String): AppEntry? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        val apps = apps()
        apps.firstOrNull { normalize(it.label) == q }?.let { return it }
        apps.firstOrNull { normalize(it.label).startsWith(q) }?.let { return it }
        apps.firstOrNull { normalize(it.label).contains(q) }?.let { return it }
        apps.firstOrNull { normalize(it.packageName).contains(q) }?.let { return it }
        return null
    }

    fun launch(entry: AppEntry): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(launch); true } catch (e: Exception) { false }
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
}
