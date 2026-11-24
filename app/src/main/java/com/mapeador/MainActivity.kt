package com.mapeador

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

private val Ink = Color(0xFF1A0709)
private val Panel = Color(0xFF9B1C27)
private val Muted = Color(0xFFF3CDD0)
private val Accent = Color(0xFFFF6B6B)
private val TextPrimary = Color(0xFFFFE9EA)
private val PanelDark = Color(0xFF3A0B10)

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
    val sensitivityY: Float = 1f,
    val joystickRadius: Float = 0.85f,
    val stopOnOpposite: Boolean = true
)

private enum class AppPage(val label: String) {
    GAMES("Iniciar jogo"),
    SETTINGS("Configurações")
}

private data class GameApp(
    val packageName: String,
    val label: String,
    val launcherActivity: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContent {
            MaterialTheme(
                colorScheme = androidx.compose.material3.darkColorScheme(
                    primary = Panel,
                    secondary = Color(0xFFE5484D),
                    background = Ink,
                    surface = Color(0xFF2B0C10),
                    onPrimary = Color.White,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
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

    var page by remember { mutableStateOf(AppPage.GAMES) }
    var games by remember { mutableStateOf(loadGames(context)) }
    var showGamePicker by remember { mutableStateOf(false) }
    var components by remember { mutableStateOf(loadComponents(preferences)) }
    var captureTarget by remember { mutableStateOf<String?>(null) }
    var fpsConfigTarget by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var shizukuState by remember { mutableStateOf(shizukuStatus()) }
    var indicatorsEnabled by remember { mutableStateOf(preferences.getBoolean("indicators", true)) }
    var indicatorOpacity by remember {
        mutableFloatStateOf(preferences.getFloat("opacity", 0.8f))
    }
    var indicatorColor by remember {
        mutableStateOf(preferences.getString("indicator_color2", "Branco") ?: "Branco")
    }

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
                    "Autorizado; iniciando serviço privilegiado…"
                } else {
                    "Permissão do Shizuku negada"
                }
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    ShizukuInputBridge.bind(context) { ready, message ->
                        shizukuState = if (ready) message else "Falha ao iniciar: $message"
                    }
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        onDispose { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    }

    LaunchedEffect(indicatorsEnabled) {
        preferences.edit().putBoolean("indicators", indicatorsEnabled).apply()
    }
    LaunchedEffect(indicatorOpacity) {
        preferences.edit().putFloat("opacity", indicatorOpacity).apply()
    }
    LaunchedEffect(indicatorColor) {
        preferences.edit().putString("indicator_color2", indicatorColor).apply()
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = { BottomTabs(page) { page = it } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp)
        ) {
            when (page) {
                AppPage.GAMES -> GamesPage(
                    games = games,
                    onAddGame = { showGamePicker = true },
                    onRemoveGame = { game ->
                        games = games.filterNot { it.packageName == game.packageName }
                        saveGames(preferences, games)
                    },
                    onStartGame = { game ->
                        if (!Shizuku.pingBinder()) {
                            Toast.makeText(context, "Inicie o Shizuku antes de iniciar o jogo.", Toast.LENGTH_LONG).show()
                        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(1001)
                            Toast.makeText(context, "Autorize o B13 MAUSE PRO no Shizuku e toque em Iniciar novamente.", Toast.LENGTH_LONG).show()
                        } else if (Settings.canDrawOverlays(context)) {
                            launchGameWithBubble(context, game.packageName, game.launcherActivity)
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
                AppPage.SETTINGS -> SettingsPage(
                    shizukuState = shizukuState,
                    indicatorsEnabled = indicatorsEnabled,
                    indicatorOpacity = indicatorOpacity,
                    indicatorColor = indicatorColor,
                    onConnectShizuku = {
                        when {
                            !Shizuku.pingBinder() -> shizukuState = "Shizuku não está iniciado"
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                                ShizukuInputBridge.bind(context) { ready, message ->
                                    shizukuState = if (ready) message else "Falha ao iniciar: $message"
                                }
                            else -> Shizuku.requestPermission(1001)
                        }
                    },
                    onIndicatorsChange = { indicatorsEnabled = it },
                    onOpacityChange = { indicatorOpacity = it },
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
                                val keyName = event.key.toString().uppercase().removePrefix("KEYCODE_")
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
                games = listOf(game) + games.filterNot { it.packageName == game.packageName }
                saveGames(preferences, games)
                Toast.makeText(context, "${game.label} adicionado à biblioteca.", Toast.LENGTH_SHORT).show()
                showGamePicker = false
            }
        )
    }
}

@Composable
private fun BottomTabs(page: AppPage, onSelect: (AppPage) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(52.dp).background(PanelDark)) {
        AppPage.entries.forEach { destination ->
            val selected = destination == page
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (selected) Color(0xFFD7263D) else Color(0xFF3A0B10))
                    .clickable { onSelect(destination) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    destination.label,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, connected: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (connected) Color(0xFF2E9E6B) else Color(0xFF6B1A21))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label: ${if (connected) "Conectado" else "Desconectado"}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun GamesPage(
    games: List<GameApp>,
    onAddGame: () -> Unit,
    onRemoveGame: (GameApp) -> Unit,
    onStartGame: (GameApp) -> Unit
) {
    val context = LocalContext.current
    var inputDevices by remember { mutableStateOf(readPhysicalInputDevices()) }
    DisposableEffect(context) {
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            private fun refresh() { inputDevices = readPhysicalInputDevices() }
            override fun onInputDeviceAdded(deviceId: Int) = refresh()
            override fun onInputDeviceRemoved(deviceId: Int) = refresh()
            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }
        inputManager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { inputManager.unregisterInputDeviceListener(listener) }
    }
    val gamesListState = rememberLazyListState()
    LaunchedEffect(games.firstOrNull()?.packageName) {
        if (games.isNotEmpty()) gamesListState.animateScrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 10.dp, bottom = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.b13_logo),
                contentDescription = "Logo B13",
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp)
            )
            Text(
                "B13 MAUSE PRO",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = 8.dp)
            )
            Button(
                onClick = onAddGame,
                modifier = Modifier.padding(start = 14.dp)
            ) { Text("＋ Adicionar jogo") }
            Box(Modifier.weight(1f))
            StatusPill("Mouse", inputDevices.second)
            Box(Modifier.width(8.dp))
            StatusPill("Teclado", inputDevices.first)
        }

        Text(
            "${games.size} ${if (games.size == 1) "jogo" else "jogos"} na biblioteca",
            color = TextPrimary.copy(alpha = 0.68f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )

        if (games.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nenhum jogo ainda", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Toque em Adicionar jogo e escolha um app instalado.",
                        color = TextPrimary.copy(alpha = 0.68f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = gamesListState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(games, key = { it.packageName }) { game ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            GameAppIcon(game, selected = false)
                            Column(Modifier.weight(1f)) {
                                Text(game.label, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    game.packageName,
                                    color = TextPrimary.copy(alpha = 0.62f),
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                            Button(onClick = { onStartGame(game) }) { Text("Iniciar") }
                            TextButton(onClick = { onRemoveGame(game) }) { Text("Remover") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionChip(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(subtitle, color = TextPrimary.copy(alpha = 0.62f), fontSize = 10.sp, maxLines = 1)
        }
    }
}

private fun readPhysicalInputDevices(): Pair<Boolean, Boolean> {
    var keyboard = false
    var mouse = false
    InputDevice.getDeviceIds().forEach { deviceId ->
        val device = InputDevice.getDevice(deviceId) ?: return@forEach
        if (device.isVirtual) return@forEach
        keyboard = keyboard || device.supportsSource(InputDevice.SOURCE_KEYBOARD)
        mouse = mouse || device.supportsSource(InputDevice.SOURCE_MOUSE)
    }
    return keyboard to mouse
}

@Composable
private fun GameAppIcon(game: GameApp, selected: Boolean) {
    val context = LocalContext.current
    val icon = remember(game.packageName) {
        try {
            context.packageManager.getApplicationIcon(game.packageName)
        } catch (_: Exception) {
            null
        }
    }
    if (icon == null) {
        Box(
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(
                if (selected) Color.White.copy(alpha = 0.18f) else Panel.copy(alpha = 0.12f)
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(game.label.take(1).uppercase(), color = if (selected) Color.White else Panel, fontSize = 24.sp)
        }
    } else {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { it.setImageDrawable(icon) },
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(16.dp))
        )
    }
}

@Composable
private fun GamePickerDialog(
    games: List<GameApp>,
    onDismiss: () -> Unit,
    onSelect: (GameApp) -> Unit
) {
    val context = LocalContext.current
    var availableGames by remember { mutableStateOf(games) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.fillMaxWidth(0.76f).fillMaxHeight(0.88f),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Adicionar jogo", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Escolha um app instalado para adicionar à biblioteca.", color = TextPrimary.copy(alpha = 0.65f), fontSize = 12.sp)
                        }
                        TextButton(onClick = { availableGames = queryLaunchableGames(context) }) {
                            Text("Atualizar")
                        }
                        TextButton(onClick = onDismiss) { Text("Fechar") }
                    }
                    if (availableGames.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "Nenhum app inicializável encontrado. Toque em Atualizar ou confira se o jogo está instalado neste perfil do Android.",
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(availableGames, key = { it.packageName }) { game ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                        .clickable { onSelect(game) }.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GameAppIcon(game, selected = false)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(game.label, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(game.packageName, color = TextPrimary.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1)
                                    }
                                    Text("Adicionar", color = Panel, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.width(176.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Adicionar controle", color = TextPrimary, fontWeight = FontWeight.Bold)
            listOf(ComponentType.TOUCH, ComponentType.SWIPE, ComponentType.MOUSE_PICK).forEach { type ->
                OutlinedButton(onClick = { onAdd(type) }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ ${type.title}", maxLines = 1)
                }
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Salvar") }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Importar") }
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Compartilhar") }
            Text("Arraste para mover. Toque para vincular; segure para remover.", color = TextPrimary.copy(alpha = 0.68f), fontSize = 11.sp)
        }
        MappingCanvas(
            modifier = Modifier.weight(1f).fillMaxSize(),
            components = components,
            indicatorsEnabled = indicatorsEnabled,
            indicatorOpacity = indicatorOpacity,
            indicatorColor = indicatorColor,
            onMove = onMove,
            onCapture = onCapture,
            onConfigure = onConfigure,
            onRemove = onRemove
        )
    }
}

@Composable
private fun MappingCanvas(
    modifier: Modifier = Modifier,
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
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF202A35))
            .border(1.dp, Color(0xFF384554), RoundedCornerShape(20.dp))
    ) {
        val canvasWidthPx = with(density) { maxWidth.toPx() }
        val canvasHeightPx = with(density) { maxHeight.toPx() }
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
            val componentSize = if (component.type == ComponentType.JOYSTICK) 82f else 68f
            Surface(
                modifier = Modifier
                    .offset(
                        x = (component.x * maxWidth.value - componentSize / 2f).dp,
                        y = (component.y * maxHeight.value - componentSize / 2f).dp
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
                contentColor = TextPrimary,
                shape = CircleShape,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .size(componentSize.dp)
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
                Text("Posição do mouse", color = TextPrimary.copy(alpha = 0.68f))
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
                    color = TextPrimary.copy(alpha = 0.68f),
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
    shizukuState: String,
    indicatorsEnabled: Boolean,
    indicatorOpacity: Float,
    indicatorColor: String,
    onConnectShizuku: () -> Unit,
    onIndicatorsChange: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onColorChange: (String) -> Unit
) {
    val context = LocalContext.current
    var showAssistant by remember { mutableStateOf(false) }
    val overlayGranted = Settings.canDrawOverlays(context)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Configurações", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(6.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                SettingsRow("Modo de Ativação", "Shizuku", onConnectShizuku)
                SettingsDivider()
                Text(
                    shizukuState,
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                SettingsDivider()
                SettingsRow("Assistente de ativação", "") { showAssistant = true }
                SettingsDivider()
                SettingsRow(
                    "Gerenciamento de permissões",
                    if (overlayGranted) "Sobreposição ativa" else "Sobreposição pendente"
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                }
            }
        }
        SettingSwitch(
            title = "Mostrar indicadores",
            description = "Exibe as teclas mapeadas sobre a tela.",
            checked = indicatorsEnabled,
            onCheckedChange = onIndicatorsChange
        )
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text("Aparência dos indicadores", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Cor", color = Muted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Branco", "Vermelho", "Verde", "Azul", "Roxo").forEach { colorName ->
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
                    Text("W  •  Atirar", color = Color(0xFF1A0709), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showAssistant) {
        AlertDialog(
            onDismissRequest = { showAssistant = false },
            title = { Text("Assistente de ativação") },
            text = {
                Text(
                    "1. Instale e abra o app Shizuku.\n" +
                        "2. Inicie o Shizuku por Depuração sem fio (Android 11+) ou por ADB.\n" +
                        "3. Volte aqui e toque em Modo de Ativação para autorizar o B13 MAUSE PRO.\n" +
                        "4. Permita a sobreposição em Gerenciamento de permissões.\n" +
                        "5. Em Iniciar jogo, adicione o jogo e toque em Iniciar jogo. A bolha aparece; toque nela para abrir o painel."
                )
            },
            confirmButton = { TextButton(onClick = { showAssistant = false }) { Text("Entendi") } }
        )
    }
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(if (value.isEmpty()) "›" else "$value ›", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))
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

private fun shizukuStatus(): String {
    if (!Shizuku.pingBinder()) return "Shizuku não está iniciado"
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "Aguardando autorização"
    return when (Shizuku.getUid()) {
        0 -> "Autorizado com identidade root"
        2000 -> "Autorizado com identidade ADB shell"
        else -> "Autorizado com UID ${Shizuku.getUid()}"
    }
}

private fun queryLaunchableGames(context: Context): List<GameApp> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launcherIntent, 0)
    }

    return activities.mapNotNull { resolveInfo ->
        val activity = resolveInfo.activityInfo ?: return@mapNotNull null
        val packageName = activity.packageName
        if (packageName == context.packageName) return@mapNotNull null
        GameApp(
            packageName = packageName,
            label = resolveInfo.loadLabel(packageManager).toString(),
            launcherActivity = activity.name
        )
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

private fun loadGames(context: Context): List<GameApp> {
    val savedGames = try {
        val array = JSONArray(context.getSharedPreferences("mapeador", Context.MODE_PRIVATE)
            .getString("games", "[]"))
        List(array.length()) { index ->
            when (val item = array.get(index)) {
                is String -> GameApp(item, item)
                is JSONObject -> GameApp(
                    packageName = item.getString("packageName"),
                    label = item.optString("label", item.getString("packageName")),
                    launcherActivity = item.optString("launcherActivity")
                )
                else -> throw JSONException("Jogo salvo no formato incorreto")
            }
        }
    } catch (error: JSONException) {
        Log.e("NexoPlay", "Lista de jogos salva está inválida", error)
        emptyList()
    }
    val discoveredGames = queryLaunchableGames(context).associateBy { it.packageName }
    return savedGames.distinctBy { it.packageName }.map { saved ->
        discoveredGames[saved.packageName] ?: saved
    }
}

private fun saveGames(preferences: android.content.SharedPreferences, games: List<GameApp>) {
    val savedGames = JSONArray().apply {
        games.forEach { game ->
            put(
                JSONObject()
                    .put("packageName", game.packageName)
                    .put("label", game.label)
                    .put("launcherActivity", game.launcherActivity)
            )
        }
    }
    preferences.edit().putString("games", savedGames.toString()).apply()
}

private fun launchGameWithBubble(
    context: Context,
    packageName: String,
    launcherActivity: String? = null
) {
    val resolvedActivity = launcherActivity?.takeIf { it.isNotBlank() }
        ?: queryLaunchableGames(context).firstOrNull { it.packageName == packageName }?.launcherActivity
    val launchIntent = resolvedActivity?.let { activity ->
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, activity))
    } ?: context.packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent == null) {
        Toast.makeText(context, "Não foi possível abrir esse jogo.", Toast.LENGTH_LONG).show()
        return
    }

    try {
        val mapping = context.getSharedPreferences("mapeador", Context.MODE_PRIVATE)
            .getString("mapping", "[]") ?: "[]"
        val serviceIntent = Intent(context, FloatingBubbleService::class.java)
            .putExtra(FloatingBubbleIntent.GAME_PACKAGE, packageName)
            .putExtra(FloatingBubbleIntent.MAPPING_JSON, mapping)
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
                    .put("radius", component.joystickRadius.toDouble())
                    .put("stopOnOpposite", component.stopOnOpposite)
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
            sensitivityY = item.optDouble("sensitivityY", 1.0).toFloat().coerceIn(0.1f, 2f),
            joystickRadius = item.optDouble("radius", 0.85).toFloat().coerceIn(0.35f, 1f),
            stopOnOpposite = item.optBoolean("stopOnOpposite", true)
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
    "Verde" -> Color(0xFF66E3A4)
    "Vermelho" -> Color(0xFFFF5A5F)
    else -> Color.White
}
