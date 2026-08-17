package com.jarvis.ai.core

import kotlinx.coroutines.delay

/**
 * Moteur d'agent multi-étapes : voit l'écran -> décide UNE action -> l'exécute ->
 * attend que l'écran se stabilise -> recommence, jusqu'à "done" ou la limite d'étapes.
 */
object AgentRunner {

    private const val MAX_STEPS = 8
    private const val STEP_DELAY_MS = 1300L

    suspend fun run(goal: String): String {
        val history = ArrayList<String>()

        for (i in 0 until MAX_STEPS) {
            val screen = Jarvis.accessibility?.currentScreenText()?.take(2500)
                ?: Jarvis.memory.current()?.text.orEmpty()

            val step = Jarvis.brain.nextStep(goal, history, screen)

            if (step.say.isNotBlank()) Jarvis.speak(step.say)
            if (step.action == "done") return "terminé"
            if (step.action == "wait") {
                history.add("étape ${i + 1}: attente")
                delay(STEP_DELAY_MS)
                continue
            }

            val status = Jarvis.executor.perform(step.action, step.target)
            history.add("étape ${i + 1}: ${step.action} '${step.target}' -> $status")
            delay(STEP_DELAY_MS)
        }
        Jarvis.speak("Je me suis arrêté, la tâche prend trop d'étapes.")
        return "arrêt : trop d'étapes"
    }
}
