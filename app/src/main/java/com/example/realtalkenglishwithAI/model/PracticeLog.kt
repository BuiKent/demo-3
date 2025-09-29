package com.example.realtalkenglishwithAI.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "practice_log_table")
data class PracticeLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

