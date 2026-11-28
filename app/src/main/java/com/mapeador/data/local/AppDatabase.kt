package com.mapeador.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        GameEntity::class,
        MappingProfileEntity::class,
        MappingControlEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun mappingProfileDao(): MappingProfileDao
    abstract fun mappingControlDao(): MappingControlDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "b13-mause-pro.db",
            ).build()
    }
}
