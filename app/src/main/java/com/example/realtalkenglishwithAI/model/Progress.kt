package com.example.realtalkenglishwithAI.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "progress_table")
data class Progress(
    @PrimaryKey
    val id: Int = 1, // Giả định chỉ có một người dùng
    val completedLessons: Int
)

