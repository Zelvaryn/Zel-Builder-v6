package com.zelbuilder.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BuildEntity::class], version = 1, exportSchema = false)
abstract class ZelDatabase : RoomDatabase() {

    abstract fun buildDao(): BuildDao

    companion object {
        @Volatile
        private var instance: ZelDatabase? = null

        fun get(context: Context): ZelDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ZelDatabase::class.java,
                    "zel.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
