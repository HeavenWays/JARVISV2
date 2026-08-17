package com.jarvis.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.ai.core.Jarvis
import com.jarvis.ai.core.LongTermMemory
import com.jarvis.ai.core.Orchestrator
import com.jarvis.ai.core.UpdateInfo
import com.jarvis.ai.core.Updater
import com.jarvis.ai.service.JarvisAccessibilityService
import com.jarvis.ai.service.JarvisForegroundService
import com.jarvis.ai.ui.JarvisBg
import com.jarvis.ai.ui.JarvisBlue
import com.jarvis.ai.ui.JarvisCyan
import com.jarvis.ai.ui.JarvisGreen
import com.jarvis.ai.ui.JarvisMuted
import com.jarvis.ai.ui.JarvisRed
import com.jarvis.ai.ui.JarvisSurface
import com.jarvis.ai.ui.JarvisText
import com.jarvis.ai.ui.JarvisTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = JarvisBg) {
                    HomeScreen()
                }
            }
        }
    }
}

/* ----------------------------- Permissions ----------------------------- */

private fun overlayGranted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

private fun micGranted(ctx: Context): Boolean =
    ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun notifGranted(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun accessibilityGranted(ctx: Context): Boolean {
    val expected = ctx.packageName + "/" + JarvisAccessibilityService::class.java.name
    val enabled = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

/* ------------------------------- UI ------------------------------------ */

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }

    // Rafraîchit l'état des permissions au retour dans l'app
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    // Recalcule à chaque tick
    val overlayOk = remember(refresh) { overlayGranted(ctx) }
    val accessOk = remember(refresh) { accessibilityGranted(ctx) }
    val micOk = remember(refresh) { micGranted(ctx) }
    val notifOk = remember(refresh) { notifGranted(ctx) }
    val keyOk = remember(refresh) { Jarvis.config.hasKey }

    val allReady = overlayOk && accessOk && micOk && keyOk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        JarvisOrb(active = running && allReady)
        Spacer(Modifier.height(14.dp))
        Text(
            "JARVIS",
            color = JarvisText,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 8.sp
        )
        Text(
            if (running && allReady) "En ligne · à l'écoute"
            else if (allReady) "Prêt — appuie sur Activer"
            else "Configuration requise",
            color = if (allReady) JarvisCyan else JarvisMuted,
            fontSize = 13.sp,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(24.dp))

        // Bouton principal
        Button(
            onClick = {
                if (running) {
                    JarvisForegroundService.stop(ctx)
                    running = false
                } else {
                    JarvisForegroundService.start(ctx)
                    running = true
                }
            },
            enabled = allReady,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) JarvisRed else JarvisCyan,
                contentColor = JarvisBg,
                disabledContainerColor = JarvisSurface,
                disabledContentColor = JarvisMuted
            )
        ) {
            Text(
                if (running) "Arrêter Jarvis" else "Activer Jarvis",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(20.dp))
        UpdateBanner()

        Spacer(Modifier.height(24.dp))

        SectionTitle("Configuration")
        Spacer(Modifier.height(10.dp))

        StatusCard(
            title = "Clé IA (Groq)",
            subtitle = if (keyOk) "Clé détectée · gratuit" else "Aucune clé — voir réglages",
            ok = keyOk
        )
        StatusCard(
            title = "Superposition à l'écran",
            subtitle = "Affiche la bulle Jarvis par-dessus les apps",
            ok = overlayOk,
            action = if (!overlayOk) "Autoriser" else null,
            onAction = {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName)
                )
                ctx.startActivity(i)
            }
        )
        StatusCard(
            title = "Accessibilité (voir & agir)",
            subtitle = "Lit l'écran et exécute les actions",
            ok = accessOk,
            action = if (!accessOk) "Activer" else null,
            onAction = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
        StatusCard(
            title = "Microphone",
            subtitle = "Pour t'entendre parler",
            ok = micOk,
            action = if (!micOk) "Autoriser" else null,
            onAction = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
        )
        StatusCard(
            title = "Notifications",
            subtitle = "Garde Jarvis actif en arrière-plan",
            ok = notifOk,
            action = if (!notifOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Autoriser" else null,
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )

        Spacer(Modifier.height(20.dp))
        SettingsCard(onSaved = { refresh++ })

        Spacer(Modifier.height(20.dp))
        SectionTitle("Mémoire")
        Spacer(Modifier.height(10.dp))
        MemoryCard()

        Spacer(Modifier.height(20.dp))
        SectionTitle("Tester une commande")
        Spacer(Modifier.height(10.dp))
        TestConsole(onSend = { text, setResult ->
            scope.launch {
                setResult("…")
                val summary = Orchestrator.process(text, allowVision = false)
                setResult(summary)
            }
        })

        Spacer(Modifier.height(28.dp))
        Text(
            "Astuce : dis « Jarvis » pour lancer la discussion, puis parle-lui normalement —\n" +
                "pas besoin de répéter son nom à chaque phrase, comme une vraie conversation.\n" +
                "Il RETIENT ce que tu lui confies : « souviens-toi que je bosse le soir », et il s'en souviendra toujours.\n" +
                "Dis « stop » ou touche la bulle pour l'interrompre.\n" +
                "« Jarvis, ouvre YouTube », « envoie un message à Paul sur WhatsApp », « que vois-tu à l'écran ? »",
            color = JarvisMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = JarvisMuted,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var status by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(-1) }

    // Vérifie automatiquement s'il existe une version plus récente au lancement de l'app
    LaunchedEffect(Unit) {
        checking = true
        info = Updater.check()
        checking = false
    }

    val i = info
    val highlight = i?.available == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) JarvisBlue else JarvisSurface
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Mises à jour",
                    color = if (highlight) Color.White else JarvisText,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            checking = true; status = ""; progress = -1
                            info = Updater.check()
                            checking = false
                        }
                    },
                    enabled = !checking
                ) {
                    Text(
                        if (checking) "Vérification…" else "Vérifier",
                        color = if (highlight) Color.White else JarvisCyan
                    )
                }
            }

            val msg = when {
                checking && i == null -> "Vérification de la dernière version…"
                i != null -> i.message
                else -> ""
            }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(msg, color = if (highlight) Color.White else JarvisMuted, fontSize = 13.sp)
            }

            if (highlight && i != null) {
                if (progress in 0..99) {
                    Spacer(Modifier.height(8.dp))
                    Text("Téléchargement… $progress%", color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        scope.launch {
                            progress = 0
                            status = Updater.downloadAndInstall(ctx, i.apkUrl) { p -> progress = p }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White, contentColor = JarvisBlue
                    )
                ) { Text("Installer la mise à jour", fontWeight = FontWeight.Bold) }
            }

            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = if (highlight) Color.White else JarvisText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    subtitle: String,
    ok: Boolean,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (ok) JarvisGreen else JarvisRed)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = JarvisText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = JarvisMuted, fontSize = 12.sp)
            }
            if (action != null) {
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp)
                ) { Text(action, color = JarvisCyan, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun MemoryCard() {
    var expanded by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val items = remember(tick, expanded) { LongTermMemory.all() }
    val count = items.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Mémoire de Jarvis", color = JarvisText, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (count == 0) "Aucun souvenir pour l'instant"
                        else "$count souvenir${if (count > 1) "s" else ""} enregistré${if (count > 1) "s" else ""} · gardés à vie",
                        color = JarvisMuted, fontSize = 12.sp
                    )
                }
                TextButton(onClick = { tick++; expanded = !expanded }) {
                    Text(if (expanded) "Masquer" else "Voir", color = JarvisCyan)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (items.isEmpty()) {
                    Text(
                        "Dis-lui « souviens-toi que… », ou parle-lui simplement : " +
                            "il retient tout seul ce qui compte, et ne l'oublie jamais.",
                        color = JarvisMuted, fontSize = 12.sp, lineHeight = 17.sp
                    )
                } else {
                    items.takeLast(40).asReversed().forEach {
                        Text(
                            "•  ${it.text}",
                            color = JarvisText, fontSize = 13.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { LongTermMemory.clear(); tick++ },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Tout effacer", color = JarvisRed) }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(onSaved: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(Jarvis.config.userName) }
    var naturalVoice by remember { mutableStateOf(Jarvis.config.naturalVoice) }
    var key by remember { mutableStateOf(Jarvis.config.groqKey) }
    var textModel by remember { mutableStateOf(Jarvis.config.textModel) }
    var visionModel by remember { mutableStateOf(Jarvis.config.visionModel) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Personnalité & voix", color = JarvisText, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Fermer" else "Ouvrir", color = JarvisCyan)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Field("Ton prénom (comment Jarvis t'appelle)", name) { name = it }

                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Voix naturelle (humaine)", color = JarvisText, fontSize = 14.sp)
                        Text(
                            "Voix neuronale en ligne. Décoche pour la voix du téléphone (hors-ligne).",
                            color = JarvisMuted, fontSize = 11.sp, lineHeight = 15.sp
                        )
                    }
                    Switch(checked = naturalVoice, onCheckedChange = { naturalVoice = it })
                }

                Spacer(Modifier.height(6.dp))
                Field("Clé Groq (gsk_...)", key) { key = it }
                Field("Modèle texte", textModel) { textModel = it }
                Field("Modèle vision", visionModel) { visionModel = it }

                Spacer(Modifier.height(10.dp))
                Row {
                    Button(
                        onClick = {
                            Jarvis.config.userName = name
                            Jarvis.config.naturalVoice = naturalVoice
                            Jarvis.config.groqKey = key
                            Jarvis.config.textModel = textModel
                            Jarvis.config.visionModel = visionModel
                            onSaved()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisBlue, contentColor = Color.White
                        )
                    ) { Text("Enregistrer") }

                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = {
                            Jarvis.config.userName = name
                            Jarvis.config.naturalVoice = naturalVoice
                            val hi = if (name.isBlank()) "Bonjour Monsieur, je suis Jarvis, à votre service."
                            else "Bonjour $name, je suis Jarvis, à votre service."
                            Jarvis.speak(hi)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Tester la voix", color = JarvisCyan) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = JarvisMuted) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    )
}

@Composable
private fun TestConsole(onSend: (String, (String) -> Unit) -> Unit) {
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface)
    ) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Écris une commande à tester", color = JarvisMuted) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { if (input.isNotBlank()) onSend(input) { r -> result = r } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisCyan, contentColor = JarvisBg
                )
            ) { Text("Envoyer à Jarvis", fontWeight = FontWeight.Bold) }
            if (result.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(result, color = JarvisText, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun JarvisOrb(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "orb")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "angle"
    )
    val angle2 by transition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "angle2"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1700), repeatMode = RepeatMode.Reverse),
        label = "pulse"
    )

    val core = if (active) JarvisCyan else JarvisMuted

    androidx.compose.foundation.Canvas(modifier = Modifier.size(190.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val rMax = size.minDimension / 2f

        // Halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(core.copy(alpha = 0.22f), Color.Transparent),
                center = c, radius = rMax
            ),
            radius = rMax, center = c
        )
        // Noyau
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(core.copy(alpha = 0.9f), core.copy(alpha = 0.05f)),
                center = c, radius = rMax * 0.34f * pulse
            ),
            radius = rMax * 0.34f * pulse, center = c
        )
        // Anneaux tournants
        rotate(angle, pivot = c) {
            drawArc(
                color = core.copy(alpha = 0.9f),
                startAngle = 0f, sweepAngle = 110f, useCenter = false,
                topLeft = Offset(c.x - rMax * 0.72f, c.y - rMax * 0.72f),
                size = androidx.compose.ui.geometry.Size(rMax * 1.44f, rMax * 1.44f),
                style = Stroke(width = 6f, cap = StrokeCap.Round)
            )
        }
        rotate(angle2, pivot = c) {
            drawArc(
                color = JarvisBlue.copy(alpha = 0.8f),
                startAngle = 180f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(c.x - rMax * 0.9f, c.y - rMax * 0.9f),
                size = androidx.compose.ui.geometry.Size(rMax * 1.8f, rMax * 1.8f),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
        }
        drawCircle(
            color = core.copy(alpha = 0.5f),
            radius = rMax * 0.55f, center = c,
            style = Stroke(width = 1.5f)
        )
    }
}
