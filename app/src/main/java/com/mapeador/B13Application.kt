package com.mapeador

import android.app.Application
import com.mapeador.data.local.AppDatabase
import com.mapeador.data.preferences.SettingsRepository
import com.mapeador.data.repository.GameRepository
import com.mapeador.data.repository.InstalledAppRepository
import com.mapeador.data.repository.LegacyDataMigrator
import com.mapeador.diagnostics.MouseDiagnosticsController

class B13Application : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var installedAppRepository: InstalledAppRepository
        private set
    lateinit var gameRepository: GameRepository
        private set
    lateinit var legacyDataMigrator: LegacyDataMigrator
        private set
    lateinit var mouseDiagnostics: MouseDiagnosticsController
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
        settingsRepository = SettingsRepository(this)
        installedAppRepository = InstalledAppRepository(this)
        gameRepository = GameRepository(database, installedAppRepository)
        legacyDataMigrator = LegacyDataMigrator(
            context = this,
            database = database,
            settingsRepository = settingsRepository,
            installedAppRepository = installedAppRepository,
        )
        mouseDiagnostics = MouseDiagnosticsController(this)
    }
}
