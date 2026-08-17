package com.jarvis.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.jarvis.ai.MainActivity
import com.jarvis.ai.R
import com.jarvis.ai.core.Jarvis
import com.jarvis.ai.core.Orchestrator
import com.jarvis.ai.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Service persistant : garde Jarvis actif, affiche la bulle flottante et écoute en
 * MAINS-LIBRES en continu. Une phrase n'est traitée QUE si elle contient « Jarvis » ;
 * sinon elle est ignorée. La commande est ensuite routée vers l'orchestrateur
 * (réponse / web / tâche multi-étapes, ex : envoyer un message sur WhatsApp/Discord).
 */
class JarvisForegroundService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var voice: VoiceManager
    private var bubble: View? = null
    private var bubbleIcon: ImageView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var processing = false
    @Volatile private var expectingFollowup = false

    // Mode conversation : après un premier « Jarvis », on enchaîne sans le redire pendant un moment.
    @Volatile private var conversationActive = false
    private val endConversation = Runnable { conversationActive = false }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        voice = VoiceManager(this).apply {
            onListeningChange = { listening ->
                main.post { bubbleIcon?.alpha = if (listening) 1f else 0.82f }
            }
            onHeard = { text -> handleHeard(text) }
        }

        // Quand Jarvis a fini de parler, on peut ré-écouter (sans s'entendre soi-même).
        Jarvis.onSpeechDone = { maybeResume() }

        startForeground(NOTIF_ID, buildNotification())
        addBubble()

        scope.launch(Dispatchers.IO) { Jarvis.executor.appScanner.scan() }

        // Écoute continue dès le démarrage
        voice.startAmbient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ---- Traitement d'une phrase entendue ----

    private fun handleHeard(text: String) {
        if (processing) return
        val lower = text.lowercase().trim()
        if (lower.isBlank()) { maybeResume(); return }

        // Mot d'arrêt : couper la parole et clore la conversation.
        if (isStopWord(lower)) {
            Jarvis.stopSpeaking()
            closeConversation()
            maybeResume()
            return
        }

        when {
            containsWake(lower) -> {
                touchConversation()
                val command = stripWake(text)
                if (command.length >= 2) startProcessing(command) else promptFollowup()
            }
            conversationActive || expectingFollowup -> {
                // On est déjà en pleine discussion → pas besoin de redire « Jarvis ».
                expectingFollowup = false
                touchConversation()
                startProcessing(text)
            }
            else -> {
                // Hors conversation et sans « Jarvis » → on ignore complètement.
            }
        }
    }

    /** Ouvre/prolonge la fenêtre de conversation (enchaînement sans mot-clé). */
    private fun touchConversation() {
        conversationActive = true
        main.removeCallbacks(endConversation)
        main.postDelayed(endConversation, CONVERSATION_WINDOW_MS)
    }

    private fun closeConversation() {
        conversationActive = false
        expectingFollowup = false
        main.removeCallbacks(endConversation)
    }

    private fun isStopWord(lower: String): Boolean =
        lower == "stop" || lower == "chut" || lower == "silence" ||
            lower.contains("tais-toi") || lower.contains("tais toi") ||
            lower == "arrête" || lower == "arrete"

    private fun promptFollowup() {
        expectingFollowup = true
        touchConversation()
        voice.pause()
        Jarvis.speak("Oui ?")
        // onSpeechDone → maybeResume() relance l'écoute pour capter la suite
    }

    private fun startProcessing(command: String) {
        processing = true
        expectingFollowup = false
        voice.pause()
        scope.launch {
            try {
                Orchestrator.process(command, allowVision = true)
            } finally {
                processing = false
                // On garde la conversation ouverte pour que l'utilisateur puisse répondre aussitôt.
                touchConversation()
                main.post { maybeResume() }
            }
        }
    }

    /** Reprend l'écoute seulement si Jarvis ne parle plus et ne traite plus rien. */
    private fun maybeResume() {
        if (!processing && !Jarvis.speaking) voice.resume()
    }

    // ---- Détection du mot « Jarvis » ----

    private fun containsWake(lower: String): Boolean = WAKE_VARIANTS.any { lower.contains(it) }

    private fun stripWake(text: String): String {
        var t = text
        for (w in WAKE_VARIANTS) {
            t = Regex("(?i)$w").replace(t, " ")
        }
        return t
            .replace(Regex("^[\\s,.:;!?-]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ---- Bulle flottante (appui = déclenchement manuel) ----

    private fun addBubble() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(62), dp(62), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(170)
        }

        val container = FrameLayout(this)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.jarvis_logo)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = dp(9)
            setPadding(pad, pad, pad, pad)
            alpha = 0.82f
        }
        bubbleIcon = icon
        container.addView(icon, FrameLayout.LayoutParams(dp(62), dp(62)))
        attachDrag(container, params)
        bubble = container
        try {
            windowManager.addView(container, params)
        } catch (_: Exception) {
        }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = initX + dx
                    params.y = initY + dy
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTap()
                    true
                }
                else -> false
            }
        }
    }

    /** Appui manuel : coupe la parole si Jarvis parle, sinon écoute la prochaine phrase. */
    private fun onBubbleTap() {
        if (processing) return
        if (Jarvis.speaking) {
            Jarvis.stopSpeaking()
            closeConversation()
            main.postDelayed({ maybeResume() }, 150)
            return
        }
        promptFollowup()
    }

    // ---- Notification ----

    private fun buildNotification(): Notification {
        val channelId = "jarvis_core"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Jarvis", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Assistant Jarvis actif" }
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis est à l'écoute")
            .setContentText("Dis « Jarvis » pour lancer la discussion — ensuite, parle-lui normalement.")
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Jarvis.onSpeechDone = null
        main.removeCallbacks(endConversation)
        try { Jarvis.stopSpeaking() } catch (_: Exception) {}
        try { bubble?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        voice.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val NOTIF_ID = 1001

        // Durée pendant laquelle on continue d'écouter sans redire « Jarvis » (mode conversation).
        private const val CONVERSATION_WINDOW_MS = 22_000L

        // Variantes courantes mal entendues par la reconnaissance vocale (ordre : plus long d'abord)
        private val WAKE_VARIANTS = listOf("jarvisse", "jarviss", "djarvis", "jarvis", "harvis", "jarvi")

        fun start(ctx: Context) {
            val i = Intent(ctx, JarvisForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, JarvisForegroundService::class.java))
        }
    }
}
