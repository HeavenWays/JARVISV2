package com.jarvis.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.jarvis.ai.core.Jarvis
import java.io.ByteArrayOutputStream

/**
 * Les "yeux" et les "mains" de Jarvis :
 *  - lit en continu le texte de l'écran (-> ScreenMemory)
 *  - exécute des gestes (clic, défilement, navigation système)
 *  - capture l'écran pour l'analyse visuelle multimodale
 */
class JarvisAccessibilityService : AccessibilityService() {

    private var lastRead = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Jarvis.accessibility = this
    }

    override fun onDestroy() {
        if (Jarvis.accessibility === this) Jarvis.accessibility = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = System.currentTimeMillis()
        if (now - lastRead < 700) return
        lastRead = now
        try {
            val root = rootInActiveWindow ?: return
            val app = root.packageName?.toString() ?: "?"
            val sb = StringBuilder()
            collect(root, sb, 0)
            Jarvis.memory.add(app, sb.toString().trim())
        } catch (_: Exception) {
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 40) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrBlank()) sb.append(t).append('\n')
        else if (!d.isNullOrBlank()) sb.append(d).append('\n')
        for (i in 0 until node.childCount) collect(node.getChild(i), sb, depth + 1)
    }

    /** Texte courant de l'écran, lu à la demande (utilisé par l'agent). */
    fun currentScreenText(): String {
        return try {
            val root = rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            collect(root, sb, 0)
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    // ---- Actions ----

    fun doGlobal(which: String) {
        val action = when (which) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        try { performGlobalAction(action) } catch (_: Exception) {}
    }

    fun clickByText(text: String) {
        try {
            val root = rootInActiveWindow ?: return
            // 1) Correspondance par texte visible
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (clickSelfOrParent(n)) return
                }
            }
            // 2) Correspondance par description de contenu (icônes : "Envoyer", etc.)
            val byDesc = findByContentDescription(root, text.lowercase())
            if (byDesc != null && clickSelfOrParent(byDesc)) return
            // 3) Repli : premier résultat texte
            nodes?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
        }
    }

    private fun clickSelfOrParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable) {
                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            cur = cur.parent
            hops++
        }
        return false
    }

    private fun findByContentDescription(
        node: AccessibilityNodeInfo?,
        needle: String,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val d = node.contentDescription?.toString()?.lowercase()
        if (!d.isNullOrBlank() && d.contains(needle)) return node
        for (i in 0 until node.childCount) {
            val found = findByContentDescription(node.getChild(i), needle, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** Écrit du texte dans le champ actuellement sélectionné (ou le premier éditable). */
    fun typeText(text: String) {
        try {
            val root = rootInActiveWindow ?: return
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: firstEditable(root)
                ?: return
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            // Certaines messageries (WhatsApp, Discord…) refusent SET_TEXT → repli par presse-papiers.
            if (!ok) pasteViaClipboard(target, text)
        } catch (_: Exception) {
        }
    }

    private fun pasteViaClipboard(target: AccessibilityNodeInfo, text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("jarvis", text))
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
        }
    }

    /** Valide/envoie via la touche « Entrée » de l'éditeur (utile pour envoyer un message). */
    fun pressEnter() {
        try {
            val root = rootInActiveWindow ?: return
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        } catch (_: Exception) {
        }
    }

    private fun firstEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = firstEditable(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun doScroll(down: Boolean) {
        try {
            val m = resources.displayMetrics
            val x = m.widthPixels / 2f
            val startY = if (down) m.heightPixels * 0.72f else m.heightPixels * 0.28f
            val endY = if (down) m.heightPixels * 0.28f else m.heightPixels * 0.72f
            val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
        }
    }

    /** Capture l'écran et renvoie une image JPEG en base64 (API 30+). */
    fun takeScreenshotBase64(onDone: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDone(null); return
        }
        captureR(onDone)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureR(onDone: (String?) -> Unit) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val buffer = result.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val soft = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                            bmp?.recycle()
                            if (soft == null) { onDone(null); return }
                            val scaled = scaleDown(soft, 1024)
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                            if (scaled !== soft) scaled.recycle()
                            soft.recycle()
                            onDone(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                        } catch (e: Exception) {
                            onDone(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) { onDone(null) }
                }
            )
        } catch (e: Exception) {
            onDone(null)
        }
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val m = maxOf(w, h)
        if (m <= maxDim) return src
        val r = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(src, (w * r).toInt(), (h * r).toInt(), true)
    }
}
