package com.example.realtalkenglishwithAI.repository

import androidx.lifecycle.LiveData
import com.example.realtalkenglishwithAI.database.VocabularyDao
import com.example.realtalkenglishwithAI.model.Vocabulary

class VocabularyRepository(private val vocabularyDao: VocabularyDao) {

    val allWords: LiveData<List<Vocabulary>> = vocabularyDao.getAllWords()

    suspend fun findWord(word: String): Vocabulary? {
        return vocabularyDao.findWord(word)
    }

    suspend fun insertOrUpdate(vocabulary: Vocabulary) {
        vocabularyDao.insertOrUpdate(vocabulary)
    }

    suspend fun update(vocabulary: Vocabulary) {
        vocabularyDao.update(vocabulary)
    }
}

