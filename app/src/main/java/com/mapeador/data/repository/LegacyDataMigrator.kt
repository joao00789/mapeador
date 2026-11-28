package com.mapeador.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.mapeador.Control
import com.mapeador.data.local.AppDatabase
import com.mapeador.data.local.GameEntity
import com.mapeador.data.local.MappingControlEntity
import com.mapeador.data.local.MappingProfileEntity
import com.mapeador.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class LegacyDataMigrator(
    context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val installedAppRepository: InstalledAppRepository,
) {
    private val oldPreferences = context.applicationContext
        .getSharedPreferences("mapeador", Context.MODE_PRIVATE)

    suspend fun migrateIfNeeded(): MigrationReport = withContext(Dispatchers.IO) {
        if (settingsRepository.legacyMigrationCompleted.first()) {
            return@withContext MigrationReport()
        }

        val savedPackages = oldPreferences
            .getStringSet("saved_games", emptySet())
            .orEmpty()
            .toList()
            .filter(String::isNotBlank)
            .distinct()
        val legacyKeymap = oldPreferences.getString("mapping", null)
        val parseResult = try {
            parseControls(legacyKeymap) to null
        } catch (error: JSONException) {
            Log.e(TAG, "O mapa legado está inválido; o texto original será preservado", error)
            emptyList<JSONObject>() to error.message
        }
        val (controls, warning) = parseResult
        val assignGlobalMapToSingleGame = savedPackages.size == 1 && warning == null

        database.withTransaction {
            savedPackages.forEach { packageName ->
                val now = System.currentTimeMillis()
                val existingGame = database.gameDao().findByPackage(packageName)
                val label = installedAppRepository.getAppLabel(packageName)
                val game = existingGame ?: GameEntity(
                    id = UUID.randomUUID().toString(),
                    packageName = packageName,
                    displayName = label ?: packageName,
                    iconReference = packageName,
                    isInstalled = label != null,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ).also { database.gameDao().insert(it) }

                if (database.mappingProfileDao().getDefaultForGame(game.id) == null) {
                    val profileId = UUID.randomUUID().toString()
                    val profile = MappingProfileEntity(
                        id = profileId,
                        gameId = game.id,
                        profileName = "Padrão",
                        isDefault = true,
                        controlCount = if (assignGlobalMapToSingleGame) controls.size else 0,
                        referenceWidthPx = 0,
                        referenceHeightPx = 0,
                        orientation = "LANDSCAPE",
                        sensitivityX = 1f,
                        sensitivityY = 1f,
                        cursorSettingsJson = "{}",
                        legacyKeymapJson = if (assignGlobalMapToSingleGame) legacyKeymap else null,
                        createdAtMillis = now,
                        updatedAtMillis = now,
                    )
                    database.mappingProfileDao().insert(profile)
                    if (assignGlobalMapToSingleGame) {
                        database.mappingControlDao().upsertAll(
                            controls.mapIndexed { index, control ->
                                control.toEntity(profileId, index)
                            },
                        )
                    }
                }
            }
        }

        settingsRepository.completeLegacyMigration(
            showIndicators = oldPreferences.getBoolean("indicators", true),
            legacyKeymap = legacyKeymap,
        )
        MigrationReport(
            warning = warning ?: if (
                savedPackages.size > 1 && !legacyKeymap.isNullOrBlank()
            ) {
                "O mapa antigo era global e foi preservado. Escolha um app na tela Mapas para associá-lo."
            } else {
                null
            },
        )
    }

    private fun parseControls(legacyKeymap: String?): List<JSONObject> {
        if (legacyKeymap.isNullOrBlank()) return emptyList()

        val root = try {
            JSONObject(legacyKeymap)
        } catch (_: JSONException) {
            null
        }
        val controls = if (root == null) {
            JSONArray(legacyKeymap)
        } else {
            root.optJSONArray("controls")
                ?: throw JSONException("O mapa legado não contém uma lista de controles.")
        }

        return (0 until controls.length()).map { index ->
            controls.optJSONObject(index)
                ?: throw JSONException("O controle legado na posição $index não é um objeto.")
        }.also { items ->
            if (items.any { Control.fromJson(it) == null }) {
                throw JSONException("O mapa contém um tipo de controle desconhecido.")
            }
        }
    }

    private fun JSONObject.toEntity(profileId: String, index: Int) = MappingControlEntity(
        profileId = profileId,
        controlId = optString("id").ifBlank { "legacy-$index" },
        controlType = optString("type", "UNKNOWN"),
        normalizedX = optDouble("x", 0.5).toFloat(),
        normalizedY = optDouble("y", 0.5).toFloat(),
        width = optDouble("radius", 0.85).toFloat(),
        height = optDouble("radius", 0.85).toFloat(),
        mouseButton = optString("key", ""),
        sensitivityX = optDouble("sensitivityX", 1.0).toFloat(),
        sensitivityY = optDouble("sensitivityY", 1.0).toFloat(),
        enabled = true,
        payloadJson = toString(),
    )

    private companion object {
        const val TAG = "B13LegacyMigration"
    }
}

data class MigrationReport(val warning: String? = null)
