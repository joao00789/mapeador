package com.mapeador.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    indices = [Index(value = ["packageName"], unique = true)],
)
data class GameEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val displayName: String,
    val iconReference: String,
    val isInstalled: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "mapping_profiles",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("gameId"),
        Index(value = ["gameId", "profileName"], unique = true),
    ],
)
data class MappingProfileEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val profileName: String,
    val isDefault: Boolean,
    val controlCount: Int,
    val referenceWidthPx: Int,
    val referenceHeightPx: Int,
    val orientation: String,
    val sensitivityX: Float,
    val sensitivityY: Float,
    val cursorSettingsJson: String,
    val legacyKeymapJson: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "mapping_controls",
    primaryKeys = ["profileId", "controlId"],
    foreignKeys = [
        ForeignKey(
            entity = MappingProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class MappingControlEntity(
    val profileId: String,
    val controlId: String,
    val controlType: String,
    val normalizedX: Float,
    val normalizedY: Float,
    val width: Float,
    val height: Float,
    val mouseButton: String,
    val sensitivityX: Float,
    val sensitivityY: Float,
    val enabled: Boolean,
    val payloadJson: String,
)
