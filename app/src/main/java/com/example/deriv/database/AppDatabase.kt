package com.example.deriv.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TickEntity::class, SettingsEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tickDao(): TickDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "deriv_protrader_db"
                    ).fallbackToDestructiveMigration().build()
                } catch (e: Throwable) {
                    android.util.Log.e("AppDatabase", "SQLite file DB creation failed, falling back to in-memory DB to prevent crash: ${e.message}", e)
                    Room.inMemoryDatabaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java
                    ).fallbackToDestructiveMigration().build()
                }
                INSTANCE = db
                db
            }
        }
    }
}
