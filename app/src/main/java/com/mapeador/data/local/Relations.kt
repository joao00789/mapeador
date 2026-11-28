package com.mapeador.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class GameWithProfiles(
    @Embedded val game: GameEntity,
    @Relation(parentColumn = "id", entityColumn = "gameId")
    val profiles: List<MappingProfileEntity>,
)

data class ProfileWithControls(
    @Embedded val profile: MappingProfileEntity,
    @Relation(parentColumn = "id", entityColumn = "profileId")
    val controls: List<MappingControlEntity>,
)
