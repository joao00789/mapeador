package com.mapeador.data.repository

import androidx.room.withTransaction
import com.mapeador.Keymap
import com.mapeador.data.local.AppDatabase
import com.mapeador.data.local.GameEntity
import com.mapeador.data.local.GameWithProfiles
import com.mapeador.data.local.MappingControlEntity
import com.mapeador.data.local.MappingProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class GameRepository(
    private val database: AppDatabase,
    private val installedAppRepository: InstalledAppRepository,
) {
    private val gameDao = database.gameDao()
    private val profileDao = database.mappingProfileDao()

    fun observeGames(): Flow<List<GameWithProfiles>> = gameDao.observeAllWithProfiles()

    suspend fun addGame(app: InstalledApp) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val existing = gameDao.findByPackage(app.packageName)
            val game = if (existing == null) {
                GameEntity(
                    id = UUID.randomUUID().toString(),
                    packageName = app.packageName,
                    displayName = app.label,
                    iconReference = app.packageName,
                    isInstalled = true,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ).also { gameDao.insert(it) }
            } else {
                existing.copy(
                    displayName = app.label,
                    iconReference = app.packageName,
                    isInstalled = true,
                    updatedAtMillis = now,
                ).also { updatedGame ->
                    gameDao.update(updatedGame)
                }
            }
            ensureDefaultProfile(game)
        }
    }

    suspend fun removeGame(packageName: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            gameDao.findByPackage(packageName)?.let(gameDao::delete)
        }
    }

    suspend fun loadKeymapJson(packageName: String): String? = withContext(Dispatchers.IO) {
        val game = gameDao.findByPackage(packageName) ?: return@withContext null
        profileDao.getDefaultForGame(game.id)?.legacyKeymapJson
    }

    suspend fun saveKeymap(packageName: String, keymap: Keymap) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val game = gameDao.findByPackage(packageName)
                ?: throw IllegalStateException("O app não está salvo na biblioteca.")
            val profile = profileDao.getDefaultForGame(game.id)
                ?: throw IllegalStateException("O perfil padrão do app não existe.")
            val now = System.currentTimeMillis()
            val controls = keymap.controls.map { control ->
                MappingControlEntity(
                    profileId = profile.id,
                    controlId = control.id,
                    controlType = control.type.wireName,
                    normalizedX = control.x,
                    normalizedY = control.y,
                    width = control.radius,
                    height = control.radius,
                    mouseButton = control.key,
                    sensitivityX = control.sensitivityX,
                    sensitivityY = control.sensitivityY,
                    enabled = true,
                    payloadJson = control.toJson().toString(),
                )
            }
            profileDao.update(
                profile.copy(
                    controlCount = controls.size,
                    legacyKeymapJson = keymap.toJsonString(),
                    updatedAtMillis = now,
                ),
            )
            database.mappingControlDao().deleteForProfile(profile.id)
            if (controls.isNotEmpty()) database.mappingControlDao().upsertAll(controls)
        }
    }

    suspend fun refreshInstallationStates() = withContext(Dispatchers.IO) {
        gameDao.getAll().forEach { game ->
            val currentLabel = installedAppRepository.getAppLabel(game.packageName)
            if (game.isInstalled != (currentLabel != null) ||
                (currentLabel != null && currentLabel != game.displayName)
            ) {
                gameDao.update(
                    game.copy(
                        displayName = currentLabel ?: game.displayName,
                        isInstalled = currentLabel != null,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun ensureDefaultProfile(game: GameEntity) {
        if (profileDao.getDefaultForGame(game.id) != null) return
        val now = System.currentTimeMillis()
        profileDao.insert(
            MappingProfileEntity(
                id = UUID.randomUUID().toString(),
                gameId = game.id,
                profileName = "Padrão",
                isDefault = true,
                controlCount = 0,
                referenceWidthPx = 0,
                referenceHeightPx = 0,
                orientation = "LANDSCAPE",
                sensitivityX = 1f,
                sensitivityY = 1f,
                cursorSettingsJson = "{}",
                legacyKeymapJson = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
    }
}
