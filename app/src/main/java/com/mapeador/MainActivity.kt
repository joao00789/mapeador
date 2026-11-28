package com.mapeador

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val TAG = "B13MainActivity"
private const val PREFS_NAME = "mapeador"
private const val SAVED_GAMES_KEY = "saved_games"

data class GameApp(
    val packageName: String,
    val label: String,
    val hasMapping: Boolean,
)

private enum class Page(val label: String, val icon: ImageVector) {
    GAMES("Jogos", Icons.Filled.Home),
    SETTINGS("Config.", Icons.Filled.Settings),
}

private sealed interface CatalogResult {
    data object Loading : CatalogResult
    data class Loaded(val apps: List<GameApp>) : CatalogResult
    data class Failed(val message: String) : CatalogResult
}

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

@androidx.compose.runtime.Composable
private fun MapeadorApp() {
    val context = LocalContext.current
    val resumeRevision = rememberResumeRevision()
    var pageName by rememberSaveable { mutableStateOf(Page.GAMES.name) }
    val page = Page.valueOf(pageName)
    var shizukuReady by remember {
        mutableStateOf(
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
        )
    }
    LaunchedEffect(resumeRevision) {
        shizukuReady = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuReady = grantResult == PackageManager.PERMISSION_GRANTED &&
                Shizuku.pingBinder()
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    B13MaterialTheme {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(B13Theme.Background),
        ) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = B13Theme.PanelDark,
            ) {
                Page.entries.forEach { item ->
                    NavigationRailItem(
                        selected = page == item,
                        onClick = { pageName = item.name },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Vertical + WindowInsetsSides.End,
                        ),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.b13_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Column {
                        Text(
                            "B13 MAUSE PRO",
                            color = B13Theme.TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (page == Page.GAMES) "Biblioteca" else "Configurações",
                            color = B13Theme.Muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    B13StatusIndicator(
                        label = if (shizukuReady) "Shizuku pronto" else "Shizuku necessário",
                        color = if (shizukuReady) B13Theme.Success else B13Theme.Error,
                    )
                }

                Spacer(Modifier.height(10.dp))
                when (page) {
                    Page.GAMES -> GamesPage(context, shizukuReady, resumeRevision)
                    Page.SETTINGS -> SettingsPage(context, shizukuReady, resumeRevision)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GamesPage(context: Context, shizukuReady: Boolean, resumeRevision: Int) {
    val appContext = remember(context) { context.applicationContext }
    val prefs = remember(appContext) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var savedPackages by rememberSaveable {
        mutableStateOf(prefs.getStringSet(SAVED_GAMES_KEY, emptySet()).orEmpty().toList())
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var catalogRevision by rememberSaveable { mutableIntStateOf(0) }
    val catalog by produceState<CatalogResult>(
        initialValue = CatalogResult.Loading,
        savedPackages,
        resumeRevision,
        catalogRevision,
    ) {
        value = withContext(Dispatchers.IO) {
            loadSavedGames(appContext, prefs, savedPackages)
        }
    }
    val games = (catalog as? CatalogResult.Loaded)?.apps.orEmpty()
    val filteredGames = remember(games, searchQuery) {
        games.filter { game ->
            game.label.contains(searchQuery, ignoreCase = true) ||
                game.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Meus jogos",
                    color = B13Theme.TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Escolha um app para iniciar sua sessão de mapeamento.",
                    color = B13Theme.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { showPicker = true },
                colors = ButtonDefaults.buttonColors(containerColor = B13Theme.Accent),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Adicionar app")
            }
        }

        B13SearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Buscar jogo ou app",
        )

        if (!shizukuReady) {
            Card(
                colors = CardDefaults.cardColors(containerColor = B13Theme.PanelDark),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Autorize o Shizuku em Config. para ativar o mapeamento durante o jogo.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = B13Theme.TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        when (val result = catalog) {
            CatalogResult.Loading -> LoadingCatalog()
            is CatalogResult.Failed -> B13EmptyState(
                title = "Não foi possível carregar a biblioteca",
                message = result.message,
                actionLabel = "Tentar novamente",
                onAction = { catalogRevision += 1 },
            )
            is CatalogResult.Loaded -> when {
                result.apps.isEmpty() -> B13EmptyState(
                    title = "Sua biblioteca está vazia",
                    message = "Adicione um app instalado para começar.",
                    actionLabel = "Adicionar app",
                    onAction = { showPicker = true },
                )
                filteredGames.isEmpty() -> B13EmptyState(
                    title = "Nenhum resultado",
                    message = "Tente outro nome ou pacote.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    state = rememberLazyGridState(),
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = filteredGames,
                        key = { it.packageName },
                        contentType = { "game-card" },
                    ) { game ->
                        GameCard(
                            game = game,
                            onLaunch = { launchGame(context, game.packageName) },
                            onRemove = {
                                savedPackages = savedPackages.filterNot { it == game.packageName }
                                prefs.edit()
                                    .putStringSet(SAVED_GAMES_KEY, savedPackages.toSet())
                                    .apply()
                            },
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        GamePickerDialog(
            context = appContext,
            savedPackages = savedPackages,
            onDismiss = { showPicker = false },
            onPick = { packageName ->
                savedPackages = (savedPackages + packageName).distinct()
                prefs.edit().putStringSet(SAVED_GAMES_KEY, savedPackages.toSet()).apply()
                showPicker = false
            },
        )
    }
}

@androidx.compose.runtime.Composable
private fun GameCard(
    game: GameApp,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onLaunch,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 142.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = B13Theme.Panel),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GameIcon(game.packageName, game.label, Modifier.size(48.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        game.label,
                        color = B13Theme.TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        game.packageName,
                        color = B13Theme.Muted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remover ${game.label}",
                        tint = B13Theme.Muted,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                B13StatusIndicator(
                    label = if (game.hasMapping) "Mapa atual salvo" else "Sem mapa salvo",
                    color = if (game.hasMapping) B13Theme.Success else B13Theme.Muted,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onLaunch) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Iniciar")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GamePickerDialog(
    context: Context,
    savedPackages: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    val catalog by produceState<CatalogResult>(
        initialValue = CatalogResult.Loading,
        savedPackages,
        retryToken,
    ) {
        value = withContext(Dispatchers.IO) {
            loadInstalledApps(context, savedPackages.toSet())
        }
    }
    val apps = (catalog as? CatalogResult.Loaded)?.apps.orEmpty()
    val filteredApps = remember(apps, query) {
        apps.filter { app ->
            app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 600.dp)
                .heightIn(max = 500.dp)
                .padding(8.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = B13Theme.Panel),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Adicionar app",
                            color = B13Theme.TextPrimary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Selecione um app instalado no dispositivo.",
                            color = B13Theme.Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Fechar") }
                }

                B13SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Pesquisar apps",
                )

                when (val result = catalog) {
                    CatalogResult.Loading -> LoadingCatalog()
                    is CatalogResult.Failed -> B13EmptyState(
                        title = "Não foi possível listar os apps",
                        message = result.message,
                        actionLabel = "Tentar novamente",
                        onAction = { retryToken += 1 },
                    )
                    is CatalogResult.Loaded -> when {
                        filteredApps.isEmpty() -> B13EmptyState(
                            title = "Nenhum app encontrado",
                            message = "Tente outro nome ou pacote.",
                        )
                        else -> LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 330.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(
                                items = filteredApps,
                                key = { it.packageName },
                                contentType = { "installed-app" },
                            ) { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { onPick(app.packageName) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    GameIcon(app.packageName, app.label, Modifier.size(42.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            app.label,
                                            color = B13Theme.TextPrimary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            app.packageName,
                                            color = B13Theme.Muted,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "Adicionar ${app.label}",
                                        tint = B13Theme.AccentSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GameIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        context,
        packageName,
    ) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(96, 96)
                    .asImageBitmap()
            } catch (error: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Ícone indisponível para $packageName", error)
                null
            } catch (error: SecurityException) {
                Log.e(TAG, "Sem permissão para carregar o ícone de $packageName", error)
                null
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(B13Theme.PanelDark),
        contentAlignment = Alignment.Center,
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap == null) {
            Icon(
                Icons.Filled.Home,
                contentDescription = "Ícone indisponível para $label",
                tint = B13Theme.Muted,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Image(
                bitmap = loadedBitmap,
                contentDescription = "Ícone de $label",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingsPage(context: Context, shizukuReady: Boolean, resumeRevision: Int) {
    val prefs = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var indicators by rememberSaveable {
        mutableStateOf(prefs.getBoolean("indicators", true))
    }
    var overlayGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    LaunchedEffect(resumeRevision) {
        overlayGranted = Settings.canDrawOverlays(context)
    }
    val emergencyKey = remember(prefs, resumeRevision) {
        Keymap.fromJsonString(prefs.getString("mapping", null)).emergencyKey
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = B13Theme.Panel),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Permissões",
                    color = B13Theme.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                SettingsRow(
                    "Shizuku",
                    if (shizukuReady) "Autorizado" else "Toque para autorizar",
                ) {
                    if (!Shizuku.pingBinder()) {
                        Toast.makeText(
                            context,
                            "Abra o app Shizuku e inicie o serviço primeiro.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Shizuku.requestPermission(1001)
                    }
                }
                SettingsRow(
                    "Sobrepor outros apps",
                    if (overlayGranted) "Concedida" else "Toque para conceder",
                ) {
                    if (!Settings.canDrawOverlays(context)) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = B13Theme.Panel),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Controles",
                    color = B13Theme.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                SettingSwitch("Mostrar indicadores", indicators) {
                    indicators = it
                    prefs.edit().putBoolean("indicators", it).apply()
                }
                SettingsRow("Tecla de emergência", emergencyKey)
                Text(
                    "A V1 prioriza a configuração e o uso do mapeamento com mouse.",
                    color = B13Theme.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingsRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val clickModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(clickModifier)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = B13Theme.TextPrimary, modifier = Modifier.weight(1f))
        Text(
            value,
            color = B13Theme.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@androidx.compose.runtime.Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = B13Theme.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@androidx.compose.runtime.Composable
private fun LoadingCatalog() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = B13Theme.Accent)
    }
}

@androidx.compose.runtime.Composable
private fun rememberResumeRevision(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return revision
}

private fun loadSavedGames(
    context: Context,
    prefs: android.content.SharedPreferences,
    packages: List<String>,
): CatalogResult {
    val hasMapping = Keymap.fromJsonString(prefs.getString("mapping", null))
        .controls
        .isNotEmpty()

    return try {
        val games = packages.mapNotNull { packageName ->
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                GameApp(
                    packageName = packageName,
                    label = context.packageManager.getApplicationLabel(info).toString(),
                    hasMapping = hasMapping,
                )
            } catch (error: PackageManager.NameNotFoundException) {
                Log.w(TAG, "App salvo não está mais instalado: $packageName", error)
                null
            }
        }
        CatalogResult.Loaded(games)
    } catch (error: SecurityException) {
        Log.e(TAG, "O Android bloqueou a leitura dos apps salvos", error)
        CatalogResult.Failed("O Android bloqueou a leitura dos apps salvos.")
    }
}

private fun loadInstalledApps(
    context: Context,
    savedPackages: Set<String>,
): CatalogResult {
    return try {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName || packageName in savedPackages) {
                    return@mapNotNull null
                }
                GameApp(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(context.packageManager).toString(),
                    hasMapping = false,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        CatalogResult.Loaded(apps)
    } catch (error: SecurityException) {
        Log.e(TAG, "O Android bloqueou a listagem de apps instalados", error)
        CatalogResult.Failed("Verifique a permissão de visibilidade de apps e tente novamente.")
    }
}

private fun launchGame(context: Context, packageName: String) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent == null) {
        Log.e(TAG, "Não há atividade de inicialização para $packageName")
        Toast.makeText(context, "Este app não pode ser iniciado.", Toast.LENGTH_LONG).show()
        return
    }

    try {
        context.startActivity(launchIntent)
    } catch (error: ActivityNotFoundException) {
        Log.e(TAG, "A atividade de $packageName não está disponível", error)
        Toast.makeText(context, "Não foi possível abrir este app.", Toast.LENGTH_LONG).show()
        return
    }

    val serviceIntent = Intent(context, FloatingBubbleService::class.java)
        .putExtra(FloatingBubbleService.EXTRA_PACKAGE_NAME, packageName)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    } catch (error: IllegalStateException) {
        Log.e(TAG, "Não foi possível iniciar o serviço de mapeamento", error)
        Toast.makeText(context, "Não foi possível iniciar o mapeamento.", Toast.LENGTH_LONG).show()
    } catch (error: SecurityException) {
        Log.e(TAG, "Permissão insuficiente para iniciar o serviço de mapeamento", error)
        Toast.makeText(context, "Verifique as permissões do B13 Mause Pro.", Toast.LENGTH_LONG).show()
    }
}
