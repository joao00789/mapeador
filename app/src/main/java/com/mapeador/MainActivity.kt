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
import androidx.compose.foundation.layout.only
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapeador.data.repository.InstalledApp
import com.mapeador.diagnostics.MouseDiagnosticsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val TAG = "B13MainActivity"

data class GameApp(
    val packageName: String,
    val label: String,
    val hasMapping: Boolean,
    val isInstalled: Boolean = true,
)

private enum class Page(val label: String, val icon: ImageVector) {
    LIBRARY("Biblioteca", Icons.Filled.Home),
    MAPS("Mapas", Icons.Filled.Add),
    MOUSE("Mouse", Icons.Filled.PlayArrow),
    SETTINGS("Config.", Icons.Filled.Settings),
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

    override fun onStart() {
        super.onStart()
        (application as B13Application).mouseDiagnostics.start()
    }

    override fun onStop() {
        (application as B13Application).mouseDiagnostics.stop()
        super.onStop()
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        (application as B13Application).mouseDiagnostics.record(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        (application as B13Application).mouseDiagnostics.record(event)
        return super.dispatchTouchEvent(event)
    }
}

@androidx.compose.runtime.Composable
private fun MapeadorApp() {
    val context = LocalContext.current
    val application = context.applicationContext as B13Application
    val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(application))
    val resumeRevision = rememberResumeRevision()
    val games by viewModel.games.collectAsStateWithLifecycle()
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()
    val showIndicators by viewModel.showIndicators.collectAsStateWithLifecycle()
    val selectedGamePackage by viewModel.selectedGamePackage.collectAsStateWithLifecycle()
    val legacyKeymapBackup by viewModel.legacyKeymapBackup.collectAsStateWithLifecycle()
    val mouseState by viewModel.mouseState.collectAsStateWithLifecycle()
    val libraryError by viewModel.libraryError.collectAsStateWithLifecycle()
    val appsState by viewModel.appsState.collectAsStateWithLifecycle()
    var pageName by rememberSaveable { mutableStateOf(Page.LIBRARY.name) }
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
        viewModel.refreshLibrary()
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(B13Theme.Background),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
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
                            page.label,
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

                Spacer(Modifier.height(8.dp))
                when (val startup = startupState) {
                    StartupState.Loading -> LoadingCatalog()
                    is StartupState.Failed -> B13EmptyState(
                        title = "Não foi possível iniciar",
                        message = startup.message,
                        actionLabel = "Tentar novamente",
                        onAction = viewModel::retryInitialization,
                    )
                    is StartupState.Ready -> {
                        if (startup.warning != null) {
                            Text(
                                startup.warning,
                                color = B13Theme.Error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        when (page) {
                            Page.LIBRARY -> GamesPage(
                                context = context,
                                viewModel = viewModel,
                                games = games,
                                appsState = appsState,
                                shizukuReady = shizukuReady,
                            )
                            Page.MAPS -> MapsPage(
                                games = games,
                                selectedGamePackage = selectedGamePackage,
                                legacyKeymapBackup = legacyKeymapBackup,
                                onSelect = viewModel::selectGame,
                                onImportLegacy = viewModel::importLegacyKeymap,
                            )
                            Page.MOUSE -> MouseDiagnosticsPage(mouseState)
                            Page.SETTINGS -> SettingsPage(
                                context = context,
                                shizukuReady = shizukuReady,
                                resumeRevision = resumeRevision,
                                indicators = showIndicators,
                                onIndicatorsChange = viewModel::setShowIndicators,
                                legacyKeymap = legacyKeymapBackup,
                            )
                        }
                    }
                }
            }

            NavigationBar(
                modifier = Modifier.height(58.dp),
                containerColor = B13Theme.PanelDark,
            ) {
                Page.entries.forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { pageName = item.name },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, maxLines = 1) },
                    )
                }
            }
        }

        if (libraryError != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissLibraryError,
                title = { Text("Não foi possível concluir") },
                text = { Text(libraryError.orEmpty()) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissLibraryError) { Text("Entendi") }
                },
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun GamesPage(
    context: Context,
    viewModel: MainViewModel,
    games: List<LibraryGame>,
    appsState: AppsLoadState,
    shizukuReady: Boolean,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val savedApps = remember(games) {
        games.map { game ->
            GameApp(
                packageName = game.packageName,
                label = game.label,
                hasMapping = game.hasMapping,
                isInstalled = game.isInstalled,
            )
        }
    }
    LaunchedEffect(showPicker) {
        if (showPicker) viewModel.loadInstalledApps()
    }

    val filteredGames = remember(games, searchQuery) {
        savedApps.filter { game ->
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
            Text(
                "Shizuku ainda não autorizado. A biblioteca continua disponível.",
                color = B13Theme.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when {
                savedApps.isEmpty() -> B13EmptyState(
                    title = "Sua biblioteca está vazia",
                    message = "Adicione um app instalado para criar o primeiro perfil.",
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
                            onLaunch = {
                                viewModel.selectGame(game.packageName)
                                launchGame(context, game.packageName)
                            },
                            onRemove = { viewModel.removeGame(game.packageName) },
                        )
                    }
                }
        }
    }

    if (showPicker) {
        GamePickerDialog(
            appsState = appsState,
            savedPackages = savedApps.map(GameApp::packageName).toSet(),
            onDismiss = { showPicker = false },
            onRetry = viewModel::loadInstalledApps,
            onPick = { app ->
                viewModel.addGame(app)
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
        enabled = game.isInstalled,
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
                    label = when {
                        !game.isInstalled -> "App não instalado"
                        game.hasMapping -> "Mapa salvo"
                        else -> "Sem mapa salvo"
                    },
                    color = if (game.isInstalled && game.hasMapping) {
                        B13Theme.Success
                    } else {
                        B13Theme.Muted
                    },
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onLaunch, enabled = game.isInstalled) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (game.isInstalled) "Iniciar" else "Indisponível")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GamePickerDialog(
    appsState: AppsLoadState,
    savedPackages: Set<String>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPick: (InstalledApp) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val apps = (appsState as? AppsLoadState.Loaded)?.apps.orEmpty()
        .filterNot { it.packageName in savedPackages }
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

                when (val result = appsState) {
                    AppsLoadState.Idle, AppsLoadState.Loading -> LoadingCatalog()
                    is AppsLoadState.Failed -> B13EmptyState(
                        title = "Não foi possível listar os apps",
                        message = result.message,
                        actionLabel = "Tentar novamente",
                        onAction = onRetry,
                    )
                    is AppsLoadState.Loaded -> when {
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
                                        .clickable { onPick(app) }
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
private fun MapsPage(
    games: List<LibraryGame>,
    selectedGamePackage: String?,
    legacyKeymapBackup: String?,
    onSelect: (String?) -> Unit,
    onImportLegacy: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Mapas por app",
            color = B13Theme.TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Cada app mantém seu próprio perfil salvo no dispositivo.",
            color = B13Theme.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (games.isEmpty()) {
            B13EmptyState(
                title = "Nenhum perfil ainda",
                message = "Adicione um app na Biblioteca para criar seu perfil.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(games, key = LibraryGame::id) { game ->
                    Card(
                        onClick = { onSelect(game.packageName) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (game.packageName == selectedGamePackage) {
                                B13Theme.PanelDark
                            } else {
                                B13Theme.Panel
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            GameIcon(game.packageName, game.label, Modifier.size(42.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val profileLabel = if (game.profileCount == 1) "perfil" else "perfis"
                                val mappingLabel = if (game.hasMapping) {
                                    "mapeamento existente"
                                } else {
                                    "sem controles mapeados"
                                }
                                Text(
                                    game.label,
                                    color = B13Theme.TextPrimary,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "${game.profileCount} $profileLabel • $mappingLabel",
                                    color = B13Theme.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (game.packageName == selectedGamePackage) {
                                B13StatusIndicator("Selecionado", B13Theme.Success)
                            }
                            if (!legacyKeymapBackup.isNullOrBlank() && !game.hasMapping) {
                                TextButton(onClick = { onImportLegacy(game.packageName) }) {
                                    Text("Importar mapa antigo")
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
private fun MouseDiagnosticsPage(state: MouseDiagnosticsState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Diagnóstico do mouse",
                    color = B13Theme.TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Observação passiva: esta tela não injeta nem altera eventos.",
                    color = B13Theme.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            B13StatusIndicator(
                if (state.isListening) "Monitorando" else "Pausado",
                if (state.isListening) B13Theme.Success else B13Theme.Muted,
            )
        }

        Text(
            "${state.devices.size} mouse(s) detectado(s) • ${state.totalEvents} evento(s)",
            color = B13Theme.TextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.devices.isEmpty()) {
            B13EmptyState(
                title = "Nenhum mouse reconhecido",
                message = "Conecte um mouse e aguarde a atualização do Android.",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.65f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.devices, key = { it.id }) { device ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = B13Theme.Panel),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Text(device.name, color = B13Theme.TextPrimary)
                            Text(
                                "${if (device.relativeMotion) "Movimento relativo" else "Movimento absoluto"}" +
                                    " • ${if (device.buttons) "botões" else "sem botões"}",
                                color = B13Theme.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Text(
            "Eventos recentes",
            color = B13Theme.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.events.isEmpty()) {
            Text(
                "Aguardando movimento, botões ou roda do mouse.",
                color = B13Theme.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.events, key = { "${it.timeMillis}-${it.action}" }) { event ->
                    Text(
                        "${event.deviceName}: ${event.action} • Δ ${event.deltaX}, ${event.deltaY}" +
                            " • roda ${event.scrollX}, ${event.scrollY}" +
                            " • botões ${event.buttonState}" +
                            if (event.coalescedEvents > 1) " (${event.coalescedEvents} movimentos agrupados)" else "",
                        color = B13Theme.TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingsPage(
    context: Context,
    shizukuReady: Boolean,
    resumeRevision: Int,
    indicators: Boolean,
    onIndicatorsChange: (Boolean) -> Unit,
    legacyKeymap: String?,
) {
    var overlayGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    LaunchedEffect(resumeRevision) {
        overlayGranted = Settings.canDrawOverlays(context)
    }
    val emergencyKey = remember(legacyKeymap, resumeRevision) {
        Keymap.fromJsonString(legacyKeymap).emergencyKey
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
                    onIndicatorsChange(it)
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
