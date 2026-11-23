package com.mapeador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

private val Ink = Color(0xFF10131B)
private val Panel = Color(0xFF1A1F2B)
private val Muted = Color(0xFF9AA4B2)
private val Accent = Color(0xFF77E4C1)

private enum class ComponentType(val title: String) {
    TOUCH("Toque"),
    SWIPE("Deslizar"),
    SCROLL("Scroll"),
    MACRO("Macro"),
    JOYSTICK("Joystick WASD"),
    FPS_VIEW("Visão FPS"),
    FREE_LOOK("Olhar livre"),
    MOUSE_PICK("Mouse Pick")
}

private data class MappedComponent(
    val id: String,
    val type: ComponentType,
    val key: String = "Toque",
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val mousePosition: String = "Centro",
    val cursorX: Float = 0.5f,
    val cursorY: Float = 0.5f,
    val preventViewFreeze: Boolean = true,
    val controllerAcceleration: Float = 0.5f,
    val sensitivityX: Float = 1f,
    val sensitivityY: Float = 1f
)

private enum class AppPage(val label: String) {
    HOME("Início"),
    GAMES("Jogos"),
    EDITOR("Editor"),
    SETTINGS("Ajustes")
}

private data class GameApp(val packageName: String, val label: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Ink
                ) {
                    MapeadorApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MapeadorApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember { context.getSharedPreferences("mapeador", Context.MODE_PRIVATE) }

    var page by remember { mutableStateOf(AppPage.HOME) }
    var games by remember { mutableStateOf(loadGames(context)) }
    var showGamePicker by remember { mutableStateOf(false) }
    var components by remember { mutableStateOf(loadComponents(preferences)) }
    var captureTarget by remember { mutableStateOf<String?>(null) }
    var fpsConfigTarget by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var shizukuState by remember { mutableStateOf(shizukuStatus()) }
    var typingEnabled by remember { mutableStateOf(preferences.getBoolean("typing", true)) }
    var indicatorsEnabled by remember { mutableStateOf(preferences.getBoolean("indicators", true)) }
    var indicatorOpacity by remember {
        mutableFloatStateOf(preferences.getFloat("opacity", 0.8f))
    }
    var sensitivityX by remember {
        mutableFloatStateOf(preferences.getFloat("sensitivity_x", 1f))
    }
    var sensitivityY by remember {
        mutableFloatStateOf(preferences.getFloat("sensitivity_y", 1f))
    }
    var indicatorColor by remember {
        mutableStateOf(preferences.getString("indicator_color", "Verde") ?: "Verde")
    }

    val wifiPing by produceState(initialValue = "—", context) {
        while (true) {
            value = withContext(Dispatchers.IO) { wifiPing(context) }
            delay(4_000)
        }
    }
    val batteryLevel = rememberBatteryLevel(context)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shizukuState = shizukuStatus()
                components = loadComponents(preferences)
                val pendingPackage = preferences.getString("pending_game", null)
                if (pendingPackage != null) {
                    if (Settings.canDrawOverlays(context)) {
                        preferences.edit().remove("pending_game").apply()
                        launchGameWithBubble(context, pendingPackage)
                    } else {
                        preferences.edit().remove("pending_game").apply()
                        Toast.makeText(context, "A permissão de janela flutuante não foi concedida.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1001) {
                shizukuState = if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    "Conectado e autorizado"
                } else {
                    "Permissão do Shizuku negada"
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        onDispose { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    }

    LaunchedEffect(components) {
        preferences.edit().putString("mapping", encodeComponents(components)).apply()
    }
    LaunchedEffect(typingEnabled) { preferences.edit().putBoolean("typing", typingEnabled).apply() }
    LaunchedEffect(indicatorsEnabled) {
        preferences.edit().putBoolean("indicators", indicatorsEnabled).apply()
    }
    LaunchedEffect(indicatorOpacity) {
        preferences.edit().putFloat("opacity", indicatorOpacity).apply()
    }
    LaunchedEffect(sensitivityX) {
        preferences.edit().putFloat("sensitivity_x", sensitivityX).apply()
    }
    LaunchedEffect(sensitivityY) {
        preferences.edit().putFloat("sensitivity_y", sensitivityY).apply()
    }
    LaunchedEffect(indicatorColor) {
        preferences.edit().putString("indicator_color", indicatorColor).apply()
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                AppPage.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { page = destination },
                        icon = { Text(destination.label.take(1), fontWeight = FontWeight.Bold) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp)
        ) {
            Header(page)
            when (page) {
                AppPage.HOME -> HomePage(
                    shizukuState = shizukuState,
                    batteryLevel = batteryLevel,
                    wifiPing = wifiPing,
                    onConnectShizuku = {
                        when {
                            !Shizuku.pingBinder() -> shizukuState = "Shizuku não está iniciado"
                            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                shizukuState = "Conectado e autorizado"
                            else -> Shizuku.requestPermission(1001)
                        }
                    },
                    onOpenEditor = { page = AppPage.EDITOR }
                )
                AppPage.GAMES -> GamesPage(
                    games = games,
                    onAddGame = { showGamePicker = true },
                    onRemoveGame = { game ->
                        games = games.filterNot { it.packageName == game.packageName }
                        saveGames(preferences, games)
                    },
                    onStartGame = { game ->
                        if (Settings.canDrawOverlays(context)) {
                            launchGameWithBubble(context, game.packageName)
                        } else {
                            preferences.edit().putString("pending_game", game.packageName).apply()
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                            Toast.makeText(
                                context,
                                "Permita a sobreposição e o jogo será iniciado ao voltar.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
                AppPage.EDITOR -> EditorPage(
                    components = components,
                    indicatorsEnabled = indicatorsEnabled,
                    indicatorOpacity = indicatorOpacity,
                    indicatorColor = indicatorColor,
                    onAdd = { type ->
                        components = components + MappedComponent(
                            id = java.util.UUID.randomUUID().toString(),
                            type = type,
                            key = if (type == ComponentType.JOYSTICK) "WASD" else "Toque",
                            x = 0.5f,
                            y = 0.5f
                        )
                    },
                    onMove = { id, deltaX, deltaY ->
                        components = components.map {
                            if (it.id == id) it.copy(
                                x = (it.x + deltaX).coerceIn(0.03f, 0.97f),
                                y = (it.y + deltaY).coerceIn(0.05f, 0.95f)
                            )
                            else it
                        }
                    },
                    onCapture = { captureTarget = it },
                    onConfigure = { fpsConfigTarget = it },
                    onRemove = { id -> components = components.filterNot { it.id == id } },
                    onImport = { importText = "" },
                    onShare = { shareMapping(context, components) },
                    onSave = {
                        preferences.edit().putString("mapping", encodeComponents(components)).apply()
                        Toast.makeText(context, "Mapeamento salvo", Toast.LENGTH_SHORT).show()
                    }
                )
                AppPage.SETTINGS -> SettingsPage(
                    typingEnabled = typingEnabled,
                    indicatorsEnabled = indicatorsEnabled,
                    indicatorOpacity = indicatorOpacity,
                    sensitivityX = sensitivityX,
                    sensitivityY = sensitivityY,
                    indicatorColor = indicatorColor,
                    onTypingChange = { typingEnabled = it },
                    onIndicatorsChange = { indicatorsEnabled = it },
                    onOpacityChange = { indicatorOpacity = it },
                    onSensitivityXChange = { sensitivityX = it },
                    onSensitivityYChange = { sensitivityY = it },
                    onColorChange = { indicatorColor = it }
                )
            }
        }
    }

    captureTarget?.let { targetId ->
        val focusRequester = remember(targetId) { FocusRequester() }
        LaunchedEffect(targetId) { focusRequester.requestFocus() }
        AlertDialog(
            onDismissRequest = { captureTarget = null },
            title = { Text("Vincular tecla") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                val keyName = event.key.toString().uppercase()
                                components = components.map {
                                    if (it.id == targetId) it.copy(key = keyName) else it
                                }
                                captureTarget = null
                                true
                            } else {
                                false
                            }
                        }
                        .pointerInteropFilter { event ->
                            val isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
                            val isButtonPress = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                                (event.actionMasked == MotionEvent.ACTION_DOWN && isMouse)
                            if (!isMouse || !isButtonPress) {
                                false
                            } else {
                                val button = if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
                                    event.actionButton
                                } else {
                                    event.buttonState
                                }
                                val buttonName = when {
                                    button and MotionEvent.BUTTON_PRIMARY != 0 -> "MOUSE_LEFT"
                                    button and MotionEvent.BUTTON_SECONDARY != 0 -> "MOUSE_RIGHT"
                                    button and MotionEvent.BUTTON_TERTIARY != 0 -> "MOUSE_MIDDLE"
                                    else -> "MOUSE_BUTTON"
                                }
                                components = components.map {
                                    if (it.id == targetId) it.copy(key = buttonName) else it
                                }
                                captureTarget = null
                                true
                            }
                        }
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Pressione a tecla ou o botão do mouse desejado")
                }
            },
            confirmButton = {
                TextButton(onClick = { captureTarget = null }) { Text("Cancelar") }
            }
        )
    }

    fpsConfigTarget?.let { targetId ->
        val component = components.firstOrNull { it.id == targetId }
        if (component == null) {
            fpsConfigTarget = null
        } else {
            FpsViewDialog(
                component = component,
                onDismiss = { fpsConfigTarget = null },
                onUpdate = { updated ->
                    components = components.map { if (it.id == updated.id) updated else it }
                },
                onChangeShortcut = {
                    fpsConfigTarget = null
                    captureTarget = targetId
                }
            )
        }
    }

    importText?.let { currentText ->
        AlertDialog(
            onDismissRequest = { importText = null; importError = null },
            title = { Text("Importar mapeamento") },
            text = {
                Column {
                    OutlinedTextField(
                        value = currentText,
                        onValueChange = { importText = it; importError = null },
                        label = { Text("Cole o JSON do mapeamento") },
                        minLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                    importError?.let { Text(it, color = Color(0xFFFF8B8B)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        components = decodeComponents(currentText)
                        importText = null
                        importError = null
                    } catch (error: JSONException) {
                        importError = "Arquivo inválido: ${error.message ?: "formato JSON incorreto"}"
                    } catch (error: IllegalArgumentException) {
                        importError = "Mapeamento inválido: ${error.message ?: "tipo desconhecido"}"
                    }
                }) { Text("Importar") }
            },
            dismissButton = {
                TextButton(onClick = { importText = null; importError = null }) { Text("Cancelar") }
            }
        )
    }

    if (showGamePicker) {
        GamePickerDialog(
            games = queryLaunchableGames(context),
            onDismiss = { showGamePicker = false },
            onSelect = { game ->
                if (games.none { it.packageName == game.packageName }) {
                    games = games + game
                    saveGames(preferences, games)
                }
                showGamePicker = false
            }
        )
    }
}

@Composable
private fun Header(page: AppPage) {
    Column(modifier = Modifier.padding(top = 20.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_nexoplay),
                contentDescription = "Logo NexoPlay",
                tint = Color.Unspecified,
                modifier = Modifier.size(28.dp)
            )
            Text(
                "NEXOPLAY",
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(page.label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomePage(
    shizukuState: String,
    batteryLevel: Int?,
    wifiPing: String,
    onConnectShizuku: () -> Unit,
    onOpenEditor: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Pronto para jogar?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Configure teclado e mouse para os seus jogos.",
                    color = Muted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                )
                Button(onClick = onOpenEditor, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir editor")
                }
            }
        }
        Text("STATUS DO DISPOSITIVO", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusCard("Bateria", batteryLevel?.let { "$it%" } ?: "—", Modifier.weight(1f))
            StatusCard("Ping Wi-Fi", wifiPing, Modifier.weight(1f))
        }
        StatusCard("FPS", "Leitura do jogo ainda não disponível", Modifier.fillMaxWidth())
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Ativação Shizuku", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(shizukuState, color = Muted, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
                OutlinedButton(onClick = onConnectShizuku) {
                    Text(if (shizukuState == "Conectado e autorizado") "Verificar conexão" else "Conectar")
                }
            }
        }
        Text(
            "Latência TCP pela conexão Wi-Fi até 1.1.1.1:443; não é o ping do servidor do jogo.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun StatusCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun GamesPage(
    games: List<GameApp>,
    onAddGame: () -> Unit,
    onRemoveGame: (GameApp) -> Unit,
    onStartGame: (GameApp) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Seus jogos", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Adicione jogos instalados. Iniciar abre o jogo e mostra o botão flutuante NexoPlay.",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
                )
                Button(onClick = onAddGame, modifier = Modifier.fillMaxWidth()) {
                    Text("Adicionar jogo")
                }
            }
        }
        if (games.isEmpty()) {
            Text(
                "Nenhum jogo adicionado. Toque em “Adicionar jogo” para escolher um app instalado.",
                color = Muted,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        games.forEach { game ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(game.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(game.packageName, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { onStartGame(game) }, modifier = Modifier.weight(1f)) {
                            Text("Iniciar")
                        }
                        OutlinedButton(onClick = { onRemoveGame(game) }) { Text("Remover") }
                    }
                }
            }
        }
        Text(
            "O Android pedirá permissão para exibir a bolha sobre outros apps. Depois de autorizar, o jogo é iniciado automaticamente.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun GamePickerDialog(
    games: List<GameApp>,
    onDismiss: () -> Unit,
    onSelect: (GameApp) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar jogo") },
        text = {
            if (games.isEmpty()) {
                Text("Não encontrei apps com tela de início neste dispositivo.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    games.forEach { game ->
                        TextButton(
                            onClick = { onSelect(game) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(game.label, color = Color.White)
                                Text(game.packageName, color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
private fun EditorPage(
    components: List<MappedComponent>,
    indicatorsEnabled: Boolean,
    indicatorOpacity: Float,
    indicatorColor: String,
    onAdd: (ComponentType) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onCapture: (String) -> Unit,
    onConfigure: (String) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Componentes", color = Muted, fontSize = 13.sp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ComponentType.entries.forEach { type ->
                FilterChip(
                    selected = false,
                    onClick = { onAdd(type) },
                    label = { Text("+ ${type.title}") }
                )
            }
        }
        MappingCanvas(
            components = components,
            indicatorsEnabled = indicatorsEnabled,
            indicatorOpacity = indicatorOpacity,
            indicatorColor = indicatorColor,
            onMove = onMove,
            onCapture = onCapture,
            onConfigure = onConfigure,
            onRemove = onRemove
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text("Salvar")
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Importar") }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Compartilhar") }
        }
        Text(
            "Arraste os controles para posicionar. Toque em um controle para vincular uma tecla; pressione e segure para removê-lo.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 18.dp)
        )
    }
}

@Composable
private fun MappingCanvas(
    components: List<MappedComponent>,
    indicatorsEnabled: Boolean,
    indicatorOpacity: Float,
    indicatorColor: String,
    onMove: (String, Float, Float) -> Unit,
    onCapture: (String) -> Unit,
    onConfigure: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(390.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF202A35))
            .border(1.dp, Color(0xFF384554), RoundedCornerShape(20.dp))
    ) {
        val canvasWidthPx = with(density) { maxWidth.toPx() }
        val canvasHeightPx = with(density) { 390.dp.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val spacing = 32.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(Color.White.copy(alpha = 0.035f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height))
                x += spacing
            }
            var y = 0f
            while (y < size.height) {
                drawLine(Color.White.copy(alpha = 0.035f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y))
                y += spacing
            }
        }
        if (components.isEmpty()) {
            Text(
                "Área de jogo\nAdicione um componente acima",
                color = Muted,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        components.forEach { component ->
            val color = if (indicatorsEnabled) {
                previewColor(indicatorColor).copy(alpha = indicatorOpacity)
            } else {
                Color(0xFF4A5360)
            }
            Surface(
                modifier = Modifier
                    .offset(
                        x = (component.x * maxWidth.value - 34f).dp,
                        y = (component.y * 390f - 26f).dp
                    )
                    .pointerInput(component.id) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onMove(
                                    component.id,
                                    dragAmount.x / canvasWidthPx,
                                    dragAmount.y / canvasHeightPx
                                )
                            }
                        )
                    }
                    .pointerInput(component.id) {
                        detectTapGestures(
                            onTap = {
                                if (component.type == ComponentType.FPS_VIEW) onConfigure(component.id)
                                else onCapture(component.id)
                            },
                            onLongPress = { onRemove(component.id) }
                        )
                    },
                color = color,
                contentColor = Ink,
                shape = CircleShape,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .size(width = if (component.type == ComponentType.JOYSTICK) 76.dp else 68.dp, height = 52.dp)
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(component.key, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (indicatorsEnabled) {
                        Text(component.type.title, fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun FpsViewDialog(
    component: MappedComponent,
    onDismiss: () -> Unit,
    onUpdate: (MappedComponent) -> Unit,
    onChangeShortcut: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurar visão FPS") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Atalho atual: ${component.key}")
                OutlinedButton(onClick = onChangeShortcut) { Text("Alterar tecla de atalho") }
                Text("Posição do mouse", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Centro", "Personalizado").forEach { position ->
                        FilterChip(
                            selected = component.mousePosition == position,
                            onClick = { onUpdate(component.copy(mousePosition = position)) },
                            label = { Text(position) }
                        )
                    }
                }
                if (component.mousePosition == "Personalizado") {
                    SettingSlider(
                        "Posição X",
                        component.cursorX,
                        "Horizontal",
                        { onUpdate(component.copy(cursorX = it)) },
                        0f..1f
                    )
                    SettingSlider(
                        "Posição Y",
                        component.cursorY,
                        "Vertical",
                        { onUpdate(component.copy(cursorY = it)) },
                        0f..1f
                    )
                }
                SettingSwitch(
                    title = "Free Fire: evitar visão presa",
                    description = "Ajuste local para ajudar a recuperar o movimento da visão.",
                    checked = component.preventViewFreeze,
                    onCheckedChange = { onUpdate(component.copy(preventViewFreeze = it)) }
                )
                SettingSlider(
                    "Aceleração do controlador",
                    component.controllerAcceleration,
                    "Menor = leve • Maior = pesado",
                    { onUpdate(component.copy(controllerAcceleration = it)) },
                    0f..1f
                )
                SettingSlider(
                    "Sensibilidade X",
                    component.sensitivityX,
                    "Horizontal",
                    { onUpdate(component.copy(sensitivityX = it)) }
                )
                SettingSlider(
                    "Sensibilidade Y",
                    component.sensitivityY,
                    "Vertical",
                    { onUpdate(component.copy(sensitivityY = it)) }
                )
                Text(
                    "A compatibilidade depende do Android e do jogo; esta opção não altera nem contorna proteções do jogo.",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Concluir") }
        }
    )
}

@Composable
private fun SettingsPage(
    typingEnabled: Boolean,
    indicatorsEnabled: Boolean,
    indicatorOpacity: Float,
    sensitivityX: Float,
    sensitivityY: Float,
    indicatorColor: String,
    onTypingChange: (Boolean) -> Unit,
    onIndicatorsChange: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onSensitivityXChange: (Float) -> Unit,
    onSensitivityYChange: (Float) -> Unit,
    onColorChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingSwitch(
            title = "Digitação do teclado",
            description = if (typingEnabled) "O teclado também pode digitar texto." else "Teclas reservadas para os controles do jogo.",
            checked = typingEnabled,
            onCheckedChange = onTypingChange
        )
        SettingSwitch(
            title = "Mostrar indicadores",
            description = "Exibe as teclas mapeadas sobre a tela.",
            checked = indicatorsEnabled,
            onCheckedChange = onIndicatorsChange
        )
        SettingSlider("Sensibilidade X", sensitivityX, "Horizontal", onSensitivityXChange)
        SettingSlider("Sensibilidade Y", sensitivityY, "Vertical", onSensitivityYChange)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text("Aparência dos indicadores", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Cor", color = Muted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Verde", "Azul", "Roxo").forEach { colorName ->
                        FilterChip(
                            selected = indicatorColor == colorName,
                            onClick = { onColorChange(colorName) },
                            label = { Text(colorName) }
                        )
                    }
                }
                Text("Transparência: ${(indicatorOpacity * 100).roundToInt()}%", color = Muted)
                Slider(value = indicatorOpacity, onValueChange = onOpacityChange, valueRange = 0.15f..1f)
                Text("Prévia", color = Muted)
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(previewColor(indicatorColor).copy(alpha = indicatorOpacity))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("W  •  Atirar", color = Ink, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "Ajustes de aceleração e suspensão do mouse ficam nas propriedades do componente Visão FPS.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(description, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    hint: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0.1f..2f
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${(value * 100).roundToInt()}%", color = Accent)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
            Text(hint, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun rememberBatteryLevel(context: Context): Int? {
    var level by remember {
        mutableStateOf(
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?.takeIf { it >= 0 }
        )
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val current = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                level = current.takeIf { it >= 0 }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return level
}

private fun wifiPing(context: Context): String {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return "Sem Wi-Fi"
    val capabilities = manager.getNetworkCapabilities(network) ?: return "Sem Wi-Fi"
    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Sem Wi-Fi"

    val start = System.nanoTime()
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("1.1.1.1", 443), 1_200)
        }
        "${((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)} ms"
    } catch (error: java.io.IOException) {
        Log.w("Mapeador", "Falha ao medir latência pela conexão Wi-Fi", error)
        "Indisponível"
    }
}

private fun shizukuStatus(): String = when {
    !Shizuku.pingBinder() -> "Shizuku não está iniciado"
    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ->
        "Conectado e autorizado"
    else -> "Aguardando autorização"
}

private fun queryLaunchableGames(context: Context): List<GameApp> {
    val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val packageManager = context.packageManager
    val activities = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.queryIntentActivities(
            launchIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
    }

    return activities.mapNotNull { info ->
        val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
        if (packageName == context.packageName) return@mapNotNull null
        GameApp(packageName, info.loadLabel(packageManager).toString())
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

private fun loadGames(context: Context): List<GameApp> {
    val savedPackages = try {
        val array = JSONArray(context.getSharedPreferences("mapeador", Context.MODE_PRIVATE)
            .getString("games", "[]"))
        List(array.length()) { index -> array.getString(index) }.toSet()
    } catch (error: JSONException) {
        Log.e("NexoPlay", "Lista de jogos salva está inválida", error)
        emptySet()
    }
    return queryLaunchableGames(context).filter { it.packageName in savedPackages }
}

private fun saveGames(preferences: android.content.SharedPreferences, games: List<GameApp>) {
    val packages = JSONArray().apply { games.forEach { put(it.packageName) } }
    preferences.edit().putString("games", packages.toString()).apply()
}

private fun launchGameWithBubble(context: Context, packageName: String) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent == null) {
        Toast.makeText(context, "Não foi possível abrir esse jogo.", Toast.LENGTH_LONG).show()
        return
    }

    try {
        val serviceIntent = Intent(context, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
        else context.startService(serviceIntent)

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    } catch (error: SecurityException) {
        Log.e("NexoPlay", "Não foi possível iniciar a bolha flutuante", error)
        Toast.makeText(context, "O Android não permitiu iniciar a bolha. Confira as permissões.", Toast.LENGTH_LONG).show()
    } catch (error: android.content.ActivityNotFoundException) {
        Log.e("NexoPlay", "A atividade inicial do jogo não está disponível", error)
        Toast.makeText(context, "Não foi possível abrir esse jogo.", Toast.LENGTH_LONG).show()
    }
}

private fun encodeComponents(components: List<MappedComponent>): String =
    JSONArray().apply {
        components.forEach { component ->
            put(
                JSONObject()
                    .put("id", component.id)
                    .put("type", component.type.name)
                    .put("key", component.key)
                    .put("x", component.x.toDouble())
                    .put("y", component.y.toDouble())
                    .put("mousePosition", component.mousePosition)
                    .put("cursorX", component.cursorX.toDouble())
                    .put("cursorY", component.cursorY.toDouble())
                    .put("preventViewFreeze", component.preventViewFreeze)
                    .put("controllerAcceleration", component.controllerAcceleration.toDouble())
                    .put("sensitivityX", component.sensitivityX.toDouble())
                    .put("sensitivityY", component.sensitivityY.toDouble())
            )
        }
    }.toString()

private fun decodeComponents(json: String): List<MappedComponent> {
    val array = JSONArray(json)
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        val typeName = item.getString("type")
        val type = ComponentType.entries.firstOrNull { it.name == typeName }
            ?: throw IllegalArgumentException("tipo '$typeName' desconhecido")
        MappedComponent(
            id = item.getString("id"),
            type = type,
            key = item.getString("key"),
            x = item.getDouble("x").toFloat().coerceIn(0f, 1f),
            y = item.getDouble("y").toFloat().coerceIn(0f, 1f),
            mousePosition = item.optString("mousePosition", "Centro"),
            cursorX = item.optDouble("cursorX", 0.5).toFloat().coerceIn(0f, 1f),
            cursorY = item.optDouble("cursorY", 0.5).toFloat().coerceIn(0f, 1f),
            preventViewFreeze = item.optBoolean("preventViewFreeze", true),
            controllerAcceleration = item.optDouble("controllerAcceleration", 0.5).toFloat().coerceIn(0f, 1f),
            sensitivityX = item.optDouble("sensitivityX", 1.0).toFloat().coerceIn(0.1f, 2f),
            sensitivityY = item.optDouble("sensitivityY", 1.0).toFloat().coerceIn(0.1f, 2f)
        )
    }
}

private fun loadComponents(preferences: android.content.SharedPreferences): List<MappedComponent> {
    val json = preferences.getString("mapping", null) ?: return emptyList()
    return try {
        decodeComponents(json)
    } catch (error: JSONException) {
        Log.e("Mapeador", "Mapeamento salvo está corrompido", error)
        emptyList()
    } catch (error: IllegalArgumentException) {
        Log.e("Mapeador", "Mapeamento salvo contém dados inválidos", error)
        emptyList()
    }
}

private fun shareMapping(context: Context, components: List<MappedComponent>) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TEXT, encodeComponents(components))
    }
    context.startActivity(Intent.createChooser(sendIntent, "Compartilhar mapeamento"))
}

private fun previewColor(name: String): Color = when (name) {
    "Azul" -> Color(0xFF77B9FF)
    "Roxo" -> Color(0xFFC19AFF)
    else -> Accent
}
