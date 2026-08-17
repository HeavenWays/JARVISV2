package com.jarvis.ai.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Voix NEURONALE gratuite (moteur "Read Aloud" de Microsoft Edge).
 * Rend une voix française masculine très naturelle, quasi-humaine (fr-FR-RemyMultilingualNeural,
 * dernière génération, chaleureuse et expressive), sans aucune clé. En cas d'échec
 * (réseau, blocage serveur), renvoie null → Jarvis bascule automatiquement sur la
 * voix synthétique du téléphone.
 *
 * Le résultat est un flux MP3 (audio-24khz-48kbitrate-mono-mp3).
 */
object NaturalVoice {

    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_TOKEN"
    private const val GEC_VERSION = "1-130.0.2849.68"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
    private const val ORIGIN = "chrome-extension://jdiccldimpahghalaidlgppnnldeekpp"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** Synthétise le texte en MP3 (ou null si indisponible). Suspend jusqu'à la fin. */
    suspend fun synthesize(text: String, voice: String): ByteArray? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        return try {
            suspendCancellableCoroutine<ByteArray?> { cont ->
                val url = BASE +
                    "&Sec-MS-GEC=${secMsGec()}" +
                    "&Sec-MS-GEC-Version=$GEC_VERSION" +
                    "&ConnectionId=${uuid()}"
                val request = Request.Builder()
                    .url(url)
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Origin", ORIGIN)
                    .header("User-Agent", UA)
                    .build()

                val audio = ByteArrayOutputStream()
                var resumed = false
                fun done(result: ByteArray?) {
                    if (resumed) return
                    resumed = true
                    if (cont.isActive) cont.resume(result)
                }

                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(configMessage())
                        webSocket.send(ssmlMessage(clean, voice))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Fin de la synthèse d'un "tour".
                        if (text.contains("Path:turn.end")) {
                            webSocket.close(1000, null)
                            done(if (audio.size() > 0) audio.toByteArray() else null)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // Trame binaire = [2 octets: longueur d'en-tête][en-tête][données audio].
                        val data = bytes.toByteArray()
                        if (data.size < 2) return
                        val headerLen =
                            ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        val start = 2 + headerLen
                        if (start in 0..data.size) audio.write(data, start, data.size - start)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        done(null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        done(if (audio.size() > 0) audio.toByteArray() else null)
                    }
                })
                cont.invokeOnCancellation { try { ws.cancel() } catch (_: Exception) {} }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Jeton anti-abus exigé par Microsoft (SHA-256 d'un temps Windows arrondi à 5 min + token). */
    private fun secMsGec(): String {
        var ticks = (System.currentTimeMillis() / 1000L) + 11644473600L // secondes depuis 1601
        ticks -= ticks % 300L                                           // arrondi à 5 minutes
        ticks *= 10_000_000L                                            // en intervalles de 100 ns
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun uuid(): String = UUID.randomUUID().toString().replace("-", "")

    private fun timestamp(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun configMessage(): String =
        "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

    private fun ssmlMessage(text: String, voice: String): String {
        // Rémy est déjà une voix masculine naturelle : on NE déforme PAS le pitch (ça la
        // rendrait robotique). Un débit à peine posé (-3%) donne un rendu plus humain et calme.
        val ssml =
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='fr-FR'>" +
                "<voice name='$voice'>" +
                "<prosody rate='-3%'>${escape(text)}</prosody>" +
                "</voice></speak>"
        return "X-RequestId:${uuid()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}\r\n" +
            "Path:ssml\r\n\r\n" + ssml
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
