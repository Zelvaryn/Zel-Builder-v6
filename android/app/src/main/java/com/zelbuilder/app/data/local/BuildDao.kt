package com.zelbuilder.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BuildDao {
    @Insert
    suspend fun insert(build: BuildEntity): Long

    @Query("SELECT * FROM builds ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BuildEntity>>

    @Query("DELETE FROM builds")
    suspend fun clear()
}
