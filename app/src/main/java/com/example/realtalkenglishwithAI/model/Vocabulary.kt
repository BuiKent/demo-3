package com.example.realtalkenglishwithAI.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary_table")
data class Vocabulary(
    @PrimaryKey
    val word: String,
    val ipa: String?,
    val meaning: String?,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    var isFavorite: Boolean = false
)

