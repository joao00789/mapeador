package com.mapeador

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mapeador.data.repository.GameRepository
import com.mapeador.Keymap
import com.mapeador.data.repository.InstalledApp
import com.mapeador.data.repository.InstalledAppRepository
import com.mapeador.data.repository.LegacyDataMigrator
import com.mapeador.data.preferences.SettingsRepository
import com.mapeador.diagnostics.MouseDiagnosticsController
import com.mapeador.diagnostics.MouseDiagnosticsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

data class LibraryGame(
    val id: String,
    val packageName: String,
    val label: String,
    val isInstalled: Boolean,
    val profileCount: Int,
    val hasMapping: Boolean,
)

sealed interface StartupState {
    data object Loading : StartupState
    data class Ready(val warning: String? = null) : StartupState
    data class Failed(val message: String) : StartupState
}

sealed interface AppsLoadState {
    data object Idle : AppsLoadState
    data object Loading : AppsLoadState
    data class Loaded(val apps: List<InstalledApp>) : AppsLoadState
    data class Failed(val message: String) : AppsLoadState
}

class MainViewModel(
    private val gameRepository: GameRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val settingsRepository: SettingsRepository,
    private val legacyDataMigrator: LegacyDataMigrator,
    private val mouseDiagnostics: MouseDiagnosticsController,
) : ViewModel() {
    private val mutableStartupState = MutableStateFlow<StartupState>(StartupState.Loading)
    val startupState: StateFlow<StartupState> = mutableStartupState.asStateFlow()

    val games: StateFlow<List<LibraryGame>> = gameRepository.observeGames()
        .map { games ->
            games.map { item ->
                LibraryGame(
                    id = item.game.id,
                    packageName = item.game.packageName,
                    label = item.game.displayName,
                    isInstalled = item.game.isInstalled,
                    profileCount = item.profiles.size,
                    hasMapping = item.profiles.any { it.controlCount > 0 },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val showIndicators: StateFlow<Boolean> = settingsRepository.showIndicators
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val selectedGamePackage: StateFlow<String?> = settingsRepository.selectedGamePackage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val legacyKeymapBackup: StateFlow<String?> = settingsRepository.legacyKeymapBackup
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val mouseState: StateFlow<MouseDiagnosticsState> = mouseDiagnostics.state

    private val mutableAppsState = MutableStateFlow<AppsLoadState>(AppsLoadState.Idle)
    val appsState: StateFlow<AppsLoadState> = mutableAppsState.asStateFlow()

    init {
        initialize()
    }

    fun retryInitialization() = initialize()

    fun loadInstalledApps() {
        mutableAppsState.value = AppsLoadState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mutableAppsState.value = AppsLoadState.Loaded(installedAppRepository.loadLauncherApps())
            } catch (error: SecurityException) {
                Log.e(TAG, "O Android bloqueou a listagem de apps instalados", error)
                mutableAppsState.value = AppsLoadState.Failed(
                    "O Android bloqueou a listagem de apps. Verifique a visibilidade de apps e tente novamente.",
                )
            }
        }
    }

    fun addGame(app: InstalledApp) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gameRepository.addGame(app)
            } catch (error: SQLiteException) {
                Log.e(TAG, "Não foi possível salvar ${app.packageName} na biblioteca", error)
                mutableLibraryError.value = "Não foi possível salvar este app: ${error.message.orEmpty()}"
            }
        }
    }

    fun removeGame(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gameRepository.removeGame(packageName)
            } catch (error: SQLiteException) {
                Log.e(TAG, "Não foi possível remover $packageName da biblioteca", error)
                mutableLibraryError.value = "Não foi possível remover este app: ${error.message.orEmpty()}"
            }
        }
    }

    private val mutableLibraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = mutableLibraryError.asStateFlow()

    fun dismissLibraryError() {
        mutableLibraryError.value = null
    }

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gameRepository.refreshInstallationStates()
            } catch (error: SQLiteException) {
                Log.e(TAG, "Não foi possível atualizar o estado dos apps salvos", error)
                mutableLibraryError.value = "Não foi possível atualizar a biblioteca: ${error.message.orEmpty()}"
            } catch (error: SecurityException) {
                Log.e(TAG, "O Android bloqueou a verificação de apps salvos", error)
                mutableLibraryError.value = "O Android bloqueou a verificação de apps salvos."
            }
        }
    }

    fun setShowIndicators(show: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setShowIndicators(show)
            } catch (error: IOException) {
                Log.e(TAG, "Não foi possível salvar a preferência de indicadores", error)
                mutableLibraryError.value = "Não foi possível salvar a configuração."
            }
        }
    }

    fun selectGame(packageName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setSelectedGamePackage(packageName)
            } catch (error: IOException) {
                Log.e(TAG, "Não foi possível salvar a seleção de jogo", error)
                mutableLibraryError.value = "Não foi possível salvar a seleção do app."
            }

            fun importLegacyKeymap(packageName: String) {
                val serialized = legacyKeymapBackup.value
                if (serialized.isNullOrBlank()) {
                    mutableLibraryError.value = "Não há um mapa legado disponível para importar."
                    return
                }
                val legacyKeymap = try {
                    Keymap.fromJsonStringStrict(serialized)
                } catch (error: JSONException) {
                    Log.e(TAG, "O mapa legado não pode ser importado porque está inválido", error)
                    mutableLibraryError.value = "O mapa legado está inválido. O texto original continua preservado."
                    return
                }
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        gameRepository.saveKeymap(packageName, legacyKeymap)
                        val current = mutableStartupState.value
                        if (current is StartupState.Ready) {
                            mutableStartupState.value = current.copy(warning = null)
                        }
                    } catch (error: SQLiteException) {
                        Log.e(TAG, "Não foi possível importar o mapa legado", error)
                        mutableLibraryError.value = "Não foi possível importar o mapa: ${error.message.orEmpty()}"
                    } catch (error: IllegalStateException) {
                        Log.e(TAG, "O app não possui perfil para receber o mapa legado", error)
                        mutableLibraryError.value = error.message
                            ?: "O perfil selecionado não está disponível."
                    }
                }
            }
        }
    }

    fun startMouseDiagnostics() = mouseDiagnostics.start()

    fun stopMouseDiagnostics() = mouseDiagnostics.stop()

    fun recordMotionEvent(event: android.view.MotionEvent) = mouseDiagnostics.record(event)

    private fun initialize() {
        mutableStartupState.value = StartupState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = legacyDataMigrator.migrateIfNeeded()
                mutableStartupState.value = StartupState.Ready(report.warning)
            } catch (error: IOException) {
                Log.e(TAG, "Falha de armazenamento durante a migração de dados", error)
                mutableStartupState.value = StartupState.Failed(
                    "Não foi possível migrar as configurações antigas: ${error.message.orEmpty()}",
                )
            } catch (error: SQLiteException) {
                Log.e(TAG, "Falha no banco durante a migração de dados", error)
                mutableStartupState.value = StartupState.Failed(
                    "Não foi possível abrir o banco de dados: ${error.message.orEmpty()}",
                )
            } catch (error: SecurityException) {
                Log.e(TAG, "O Android bloqueou a migração das configurações", error)
                mutableStartupState.value = StartupState.Failed(
                    "O Android bloqueou a leitura das configurações antigas.",
                )
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "As configurações antigas não puderam ser convertidas", error)
                mutableStartupState.value = StartupState.Failed(
                    "As configurações antigas não puderam ser convertidas: ${error.message.orEmpty()}",
                )
            }
        }
    }

    private companion object {
        const val TAG = "B13ViewModel"
    }
}

class MainViewModelFactory(private val application: B13Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(MainViewModel::class.java)) {
            throw IllegalArgumentException("ViewModel não suportado: ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(
            gameRepository = application.gameRepository,
            installedAppRepository = application.installedAppRepository,
            settingsRepository = application.settingsRepository,
            legacyDataMigrator = application.legacyDataMigrator,
            mouseDiagnostics = application.mouseDiagnostics,
        ) as T
    }
}
