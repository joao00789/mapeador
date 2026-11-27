package com.mapeador

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku

data class GameApp(val packageName: String, val label: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        setContent { MapeadorApp() }
    }

}

private enum class Page(val label: String) { GAMES("Jogos"), SETTINGS("Config.") }

@Composable
private fun MapeadorApp() {
    val context = LocalContextProvider()
    var page by remember { mutableStateOf(Page.GAMES) }
    var shizukuReady by remember { mutableStateOf(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuReady = grantResult == PackageManager.PERMISSION_GRANTED
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = B13Theme.Background, surface = B13Theme.Panel, primary = B13Theme.Accent,
    )) {
        Scaffold(
            containerColor = B13Theme.Background,
            bottomBar = {
                NavigationBar(containerColor = B13Theme.PanelDark) {
                    Page.entries.forEach { p ->
                        NavigationBarItem(
                            selected = page == p,
                            onClick = { page = p },
                            icon = {},
                            label = { Text(p.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (page) {
                    Page.GAMES -> GamesPage(context, shizukuReady)
                    Page.SETTINGS -> SettingsPage(context, shizukuReady) { shizukuReady = it }
                }
            }
        }
    }
}

@Composable
private fun LocalContextProvider() = androidx.compose.ui.platform.LocalContext.current

@Composable
private fun GamesPage(context: android.content.Context, shizukuReady: Boolean) {
    val prefs = remember { context.getSharedPreferences("mapeador", android.content.Context.MODE_PRIVATE) }
    var savedPackages by remember {
        mutableStateOf(prefs.getStringSet("saved_games", emptySet())!!.toList())
    }
    var showPicker by remember { mutableStateOf(false) }
    val games = remember(savedPackages) {
        savedPackages.mapNotNull { pkg ->
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                GameApp(pkg, context.packageManager.getApplicationLabel(info).toString())
            } catch (_: Exception) { null }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (!shizukuReady) {
            Card(colors = CardDefaults.cardColors(containerColor = B13Theme.PanelDark), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Autorize o Shizuku na aba Configurações antes de iniciar um jogo.",
                    Modifier.padding(16.dp), color = B13Theme.TextPrimary,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Meus jogos", color = B13Theme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { showPicker = true }, colors = ButtonDefaults.buttonColors(containerColor = B13Theme.Accent)) {
                Text("Adicionar")
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(games) { game ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(B13Theme.CARD_CORNER_DP.dp))
                        .background(B13Theme.PanelDark)
                        .clickable { launchGame(context, game.packageName) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GameIcon(context, game.packageName)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(game.label, color = B13Theme.TextPrimary, fontWeight = FontWeight.Bold)
                        Text(game.packageName, color = B13Theme.Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        savedPackages = savedPackages - game.packageName
                        prefs.edit().putStringSet("saved_games", savedPackages.toSet()).apply()
                    }) { Text("Remover", color = B13Theme.Muted) }
                }
            }
            if (games.isEmpty()) {
                item {
                    Text(
                        "Nenhum jogo adicionado ainda. Toque em Adicionar para escolher um app instalado.",
                        color = B13Theme.Muted, modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }

    if (showPicker) {
        GamePickerDialog(
            context = context,
            onDismiss = { showPicker = false },
            onPick = { pkg ->
                savedPackages = (savedPackages + pkg).distinct()
                prefs.edit().putStringSet("saved_games", savedPackages.toSet()).apply()
                showPicker = false
            },
        )
    }
}

@Composable
private fun GameIcon(context: android.content.Context, packageName: String) {
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96) }.getOrNull()
    }
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(B13Theme.Panel)) {
        bitmap?.let {
            androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GamePickerDialog(context: android.content.Context, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val apps = remember {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = { Text("Escolha um jogo") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps) { (pkg, label) ->
                    Text(
                        label,
                        Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
    )
}

private fun launchGame(context: android.content.Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    context.startActivity(intent)
    val serviceIntent = Intent(context, FloatingBubbleService::class.java)
        .putExtra(FloatingBubbleService.EXTRA_PACKAGE_NAME, packageName)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
    else context.startService(serviceIntent)
}

@Composable
private fun SettingsPage(context: android.content.Context, shizukuReady: Boolean, onShizukuChanged: (Boolean) -> Unit) {
    val prefs = remember { context.getSharedPreferences("mapeador", android.content.Context.MODE_PRIVATE) }
    var keymap by remember { mutableStateOf(Keymap.fromJsonString(prefs.getString("mapping", null))) }
    var indicators by remember { mutableStateOf(prefs.getBoolean("indicators", true)) }
    val overlayGranted = Settings.canDrawOverlays(context)

    fun persist() {
        prefs.edit().putString("mapping", keymap.toJsonString()).apply()
        ShizukuBridge.updateKeymap(keymap.toJsonString())
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Permissões", color = B13Theme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SettingsRow("Shizuku", if (shizukuReady) "Autorizado" else "Toque para autorizar") {
            if (!Shizuku.pingBinder()) {
                android.widget.Toast.makeText(context, "Abra o app Shizuku e inicie o serviço primeiro", android.widget.Toast.LENGTH_LONG).show()
            } else {
                Shizuku.requestPermission(1001)
            }
        }
        SettingsRow("Sobrepor outros apps", if (overlayGranted) "Concedida" else "Toque para conceder") {
            if (!overlayGranted) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Mapeador", color = B13Theme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SettingSwitch("Mostrar indicadores dos controles", indicators) {
            indicators = it
            prefs.edit().putBoolean("indicators", it).apply()
        }
        SettingSwitch("Capturar teclado completo (chat etc.)", keymap.captureKeyboard) {
            keymap = keymap.copy(captureKeyboard = it)
            persist()
        }
        SettingsRow("Tecla de emergência (pausa tudo)", keymap.emergencyKey) {}

        Spacer(Modifier.height(24.dp))
        Text(
            "A tecla de emergência pausa e libera todos os toques instantaneamente — útil se algum controle travar durante o jogo.",
            color = B13Theme.Muted, fontSize = 13.sp,
        )
    }
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = B13Theme.TextPrimary)
        Text(value, color = B13Theme.Muted)
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = B13Theme.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
