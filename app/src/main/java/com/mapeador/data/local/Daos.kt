package com.mapeador.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Transaction
    @Query("SELECT * FROM games ORDER BY displayName COLLATE NOCASE")
    fun observeAllWithProfiles(): Flow<List<GameWithProfiles>>

    @Query("SELECT * FROM games WHERE packageName = :packageName LIMIT 1")
    suspend fun findByPackage(packageName: String): GameEntity?

    @Query("SELECT * FROM games ORDER BY displayName COLLATE NOCASE")
    suspend fun getAll(): List<GameEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(game: GameEntity): Long

    @Update
    suspend fun update(game: GameEntity)

    @Delete
    suspend fun delete(game: GameEntity)
}

@Dao
interface MappingProfileDao {
    @Query("SELECT * FROM mapping_profiles WHERE gameId = :gameId ORDER BY isDefault DESC, profileName COLLATE NOCASE")
    fun observeForGame(gameId: String): Flow<List<MappingProfileEntity>>

    @Query("SELECT * FROM mapping_profiles WHERE gameId = :gameId ORDER BY isDefault DESC, profileName COLLATE NOCASE")
    suspend fun getForGame(gameId: String): List<MappingProfileEntity>

    @Query("SELECT * FROM mapping_profiles WHERE gameId = :gameId AND isDefault = 1 LIMIT 1")
    suspend fun getDefaultForGame(gameId: String): MappingProfileEntity?

    @Transaction
    @Query("SELECT * FROM mapping_profiles WHERE id = :profileId LIMIT 1")
    suspend fun getWithControls(profileId: String): ProfileWithControls?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: MappingProfileEntity): Long

    @Update
    suspend fun update(profile: MappingProfileEntity)

    @Delete
    suspend fun delete(profile: MappingProfileEntity)
}

@Dao
interface MappingControlDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(controls: List<MappingControlEntity>)

    @Query("DELETE FROM mapping_controls WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)
}
