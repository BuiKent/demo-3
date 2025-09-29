package com.example.realtalkenglishwithAI.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.realtalkenglishwithAI.database.AppDatabase
import com.example.realtalkenglishwithAI.model.Vocabulary
import com.example.realtalkenglishwithAI.model.api.ApiResponseItem
import com.example.realtalkenglishwithAI.model.api.Phonetic
import com.example.realtalkenglishwithAI.network.RetrofitClient
import com.example.realtalkenglishwithAI.repository.ProgressRepository
import com.example.realtalkenglishwithAI.repository.VocabularyRepository
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

class PracticeViewModel(application: Application) : AndroidViewModel(application) {

    private val vocabularyRepository: VocabularyRepository
    private val progressRepository: ProgressRepository
    val allSavedWords: LiveData<List<Vocabulary>>

    private val _searchResult = MutableLiveData<ApiResponseItem?>()
    val searchResult: LiveData<ApiResponseItem?> = _searchResult

    private val _wordNotFound = MutableLiveData<Boolean>()
    val wordNotFound: LiveData<Boolean> = _wordNotFound

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _nativeAudioUrl = MutableLiveData<String?>()
    val nativeAudioUrl: LiveData<String?> = _nativeAudioUrl

    private val _searchedWordIsFavorite = MutableLiveData<Boolean>()
    val searchedWordIsFavorite: LiveData<Boolean> = _searchedWordIsFavorite

    init {
        val db = AppDatabase.getDatabase(application)
        vocabularyRepository = VocabularyRepository(db.vocabularyDao())
        progressRepository = ProgressRepository(db.progressDao(), db.practiceLogDao())
        allSavedWords = vocabularyRepository.allWords
    }

    fun searchWord(word: String) {
        if (word.isBlank()) {
            _wordNotFound.postValue(true)
            return
        }
        _isLoading.postValue(true)
        viewModelScope.launch {
            try {
                val response = RetrofitClient.apiService.getWordDefinition(word.trim().lowercase())
                if (response.isSuccessful && response.body() != null && response.body()!!.isNotEmpty()) {
                    val firstResult = response.body()!![0]
                    _searchResult.postValue(firstResult)
                    _wordNotFound.postValue(false)
                    val audioUrl = firstResult.phonetics?.find { phonetic: Phonetic -> !phonetic.audio.isNullOrEmpty() }?.audio
                    _nativeAudioUrl.postValue(audioUrl)

                    saveSearchedWord(firstResult)
                } else {
                    _searchResult.postValue(null)
                    _wordNotFound.postValue(true)
                    _nativeAudioUrl.postValue(null)
                }
            } catch (e: HttpException) {
                Log.e("PracticeViewModel", "HTTP error: ${e.message}")
                _searchResult.postValue(null)
                _wordNotFound.postValue(true)
                _nativeAudioUrl.postValue(null)
            } catch (e: IOException) {
                Log.e("PracticeViewModel", "Network error: ${e.message}")
                _searchResult.postValue(null)
                _wordNotFound.postValue(true)
                _nativeAudioUrl.postValue(null)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private suspend fun saveSearchedWord(apiResult: ApiResponseItem) {
        val word = apiResult.word?.trim()?.lowercase() ?: return

        val existingWord = vocabularyRepository.findWord(word)

        val ipa = apiResult.phonetics?.find { !it.text.isNullOrEmpty() }?.text
        val meaning = apiResult.meanings?.firstOrNull()?.definitions?.firstOrNull()?.definition

        val vocabulary = Vocabulary(
            word = word,
            ipa = ipa,
            meaning = meaning,
            isFavorite = existingWord?.isFavorite ?: false
        )
        vocabularyRepository.insertOrUpdate(vocabulary)
        _searchedWordIsFavorite.postValue(vocabulary.isFavorite)
    }

    fun toggleFavorite(vocabulary: Vocabulary) = viewModelScope.launch {
        vocabulary.isFavorite = !vocabulary.isFavorite
        vocabularyRepository.update(vocabulary)
        if (_searchResult.value?.word?.lowercase() == vocabulary.word) {
            _searchedWordIsFavorite.postValue(vocabulary.isFavorite)
        }
    }

    fun logPracticeSession() = viewModelScope.launch {
        progressRepository.addPracticeLog()
    }
}

