package com.zelbuilder.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "builds")
data class BuildEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val repo: String,
    val status: String,
    val apkPath: String,
    val createdAt: Long
)
