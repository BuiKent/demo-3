package com.example.realtalkenglishwithAI.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.realtalkenglishwithAI.model.Vocabulary

@Dao
interface VocabularyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(vocabulary: Vocabulary)

    @Update
    suspend fun update(vocabulary: Vocabulary)

    @Query("SELECT * FROM vocabulary_table WHERE word = :word LIMIT 1")
    suspend fun findWord(word: String): Vocabulary?

    @Query("SELECT * FROM vocabulary_table ORDER BY isFavorite DESC, timestamp DESC")
    fun getAllWords(): LiveData<List<Vocabulary>>
}

