package com.jarvis.ai.core

import java.util.ArrayDeque

data class Snapshot(val time: Long, val app: String, val text: String)

/**
 * Mémoire d'écran roulante : conserve ce qui a été affiché sur ~15 minutes.
 * Permet de répondre à "qu'est-ce que j'ai vu il y a 5 minutes ?".
 */
class ScreenMemory {

    private val deque = ArrayDeque<Snapshot>()
    private val maxItems = 140

    @Synchronized
    fun add(app: String, text: String) {
        if (text.isBlank()) return
        val last = deque.peekLast()
        if (last != null && last.app == app && similar(last.text, text)) return
        deque.addLast(Snapshot(System.currentTimeMillis(), app, text.take(1600)))
        while (deque.size > maxItems) deque.pollFirst()
    }

    @Synchronized
    fun current(): Snapshot? = deque.peekLast()

    @Synchronized
    fun recentDigest(maxEntries: Int = 6): String {
        if (deque.isEmpty()) return "(aucune mémoire d'écran pour l'instant)"
        val now = System.currentTimeMillis()
        val items = deque.toList().takeLast(maxEntries).reversed()
        return items.joinToString("\n---\n") { s ->
            val ago = (now - s.time) / 1000
            val mn = ago / 60
            val sec = ago % 60
            val whenLabel = if (mn > 0) "il y a ${mn} min ${sec}s" else "il y a ${sec}s"
            "[$whenLabel · ${s.app}]\n${s.text.take(500)}"
        }
    }

    @Synchronized
    fun clear() = deque.clear()

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        return a.take(140) == b.take(140)
    }
}
