package com.jarvis.ai.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.ai.service.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Point d'accès global au "système nerveux" de Jarvis : cerveau (Groq), mémoire d'écran,
 * exécuteur d'actions, et surtout la VOIX.
 *
 * La voix passe par une file séquentielle : chaque phrase est jouée l'une après l'autre
 * (voix neuronale humaine si dispo, sinon voix Android). Cela permet de PARLER EN FLUX :
 * le cerveau envoie les phrases au fur et à mesure qu'il les rédige → Jarvis commence à
 * répondre presque instantanément, comme un vrai interlocuteur.
 */
object Jarvis {
    lateinit var appContext: Context
        private set
    lateinit var config: Config
        private set
    lateinit var memory: ScreenMemory
        private set
    lateinit var brain: Brain
        private set
    lateinit var executor: ActionExecutor
        private set

    /** Renseigné par le service d'accessibilité quand il est actif. */
    @Volatile
    var accessibility: JarvisAccessibilityService? = null

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    /** Vrai tant que Jarvis a quelque chose à dire (utile au mode mains-libres). */
    @Volatile var speaking = false
        private set

    /** Appelé sur le thread principal quand Jarvis a TOUT fini de dire. */
    @Volatile var onSpeechDone: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private val utteranceConts = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val idGen = AtomicInteger(0)
    @Volatile private var currentPlayer: MediaPlayer? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        config = Config(appContext)
        memory = ScreenMemory()
        LongTermMemory.init(appContext)   // charge la mémoire long terme (survit aux MAJ)
        brain = Brain(config, memory)
        executor = ActionExecutor(appContext)
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finishUtterance(utteranceId, true) }
            @Deprecated("compat") override fun onError(utteranceId: String?) {
                finishUtterance(utteranceId, false)
            }
        })
        startWorker()
    }

    /** Boucle de lecture : consomme la file phrase par phrase, sans se chevaucher. */
    private fun startWorker() {
        speechScope.launch {
            for (text in queue) {
                try { playOne(text) } catch (_: Exception) {}
                if (pending.decrementAndGet() <= 0) {
                    pending.set(0)
                    speaking = false
                    onSpeechDone?.invoke()
                }
            }
        }
    }

    /** Ajoute une phrase à dire (peut être appelé plusieurs fois pour un flux). */
    fun speak(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        speaking = true
        // Compteur + file gérés sur le thread principal (comme le worker) → pas de course.
        main.post {
            speaking = true
            pending.incrementAndGet()
            queue.trySend(t)
        }
    }

    /** Coupe la parole immédiatement et vide la file (barge-in / "stop"). */
    fun stopSpeaking() {
        while (true) { if (queue.tryReceive().isFailure) break }
        pending.set(0)
        try { tts?.stop() } catch (_: Exception) {}
        main.post {
            try { currentPlayer?.stop() } catch (_: Exception) {}
            try { currentPlayer?.release() } catch (_: Exception) {}
            currentPlayer = null
        }
        utteranceConts.keys.toList().forEach { finishUtterance(it, false) }
        speaking = false
    }

    private suspend fun playOne(text: String) {
        if (config.naturalVoice) {
            val mp3 = try { NaturalVoice.synthesize(text, config.voiceName) } catch (_: Exception) { null }
            if (mp3 != null && mp3.size > 800) {
                val ok = try { playMp3(mp3) } catch (_: Exception) { false }
                if (ok) return
            }
        }
        androidSpeak(text)
    }

    private suspend fun playMp3(bytes: ByteArray): Boolean = withContext(Dispatchers.Main) {
        val file = File(appContext.cacheDir, "jarvis_tts_${idGen.incrementAndGet()}.mp3")
        try { file.writeBytes(bytes) } catch (_: Exception) { return@withContext false }
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            val mp = MediaPlayer()
            currentPlayer = mp
            var resumed = false
            fun finish(result: Boolean) {
                if (resumed) return
                resumed = true
                try { mp.release() } catch (_: Exception) {}
                if (currentPlayer === mp) currentPlayer = null
                if (cont.isActive) cont.resume(result)
            }
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> finish(false); true }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
            } catch (e: Exception) { finish(false) }
            cont.invokeOnCancellation { finish(false) }
        }
        try { file.delete() } catch (_: Exception) {}
        ok
    }

    private suspend fun androidSpeak(text: String): Unit = suspendCancellableCoroutine<Unit> { cont ->
        val id = "u" + idGen.incrementAndGet()
        utteranceConts[id] = { if (cont.isActive) cont.resume(Unit) }
        main.post {
            val engine = tts
            if (ttsReady && engine != null) {
                val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (r != TextToSpeech.SUCCESS) finishUtterance(id, false)
            } else {
                finishUtterance(id, false)
            }
        }
        cont.invokeOnCancellation { utteranceConts.remove(id) }
    }

    private fun finishUtterance(id: String?, ok: Boolean) {
        val cb = if (id != null) utteranceConts.remove(id) else null
        cb?.invoke(ok)
    }

    /**
     * Voix Android de secours : on choisit la meilleure voix française masculine
     * disponible, SANS déformer le pitch (déformer une voix neuronale la rend robotique).
     */
    private fun configureVoice() {
        val engine = tts ?: return
        try { engine.language = Locale.FRENCH } catch (_: Exception) {}
        try {
            val best = engine.voices
                ?.filter { it.locale.language == "fr" }
                ?.maxByOrNull {
                    maleScore(it.name) * 1000 + it.quality - if (it.isNetworkConnectionRequired) 1 else 0
                }
            if (best != null) engine.voice = best
        } catch (_: Exception) {}
        try { engine.setPitch(0.96f) } catch (_: Exception) {}
        try { engine.setSpeechRate(1.0f) } catch (_: Exception) {}
    }

    private fun maleScore(name: String?): Int {
        val n = name?.lowercase() ?: return 0
        return when {
            n.contains("female") || n.contains("-vlf") || n.contains("-fed") -> -5
            n.contains("male") -> 5
            n.contains("-frd") || n.contains("-frb") || n.contains("-fra") -> 2
            else -> 0
        }
    }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
