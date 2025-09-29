package com.example.realtalkenglishwithAI.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.realtalkenglishwithAI.database.AppDatabase
import com.example.realtalkenglishwithAI.model.PracticeLog
import com.example.realtalkenglishwithAI.model.Progress
import com.example.realtalkenglishwithAI.repository.ProgressRepository
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ProgressViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ProgressRepository
    val userProgress: LiveData<Progress>

    private val _streak = MutableLiveData<Int>()
    val streak: LiveData<Int> = _streak

    init {
        val db = AppDatabase.getDatabase(application)
        repository = ProgressRepository(db.progressDao(), db.practiceLogDao())
        userProgress = repository.userProgress

        repository.allPracticeLogs.observeForever { logs ->
            calculateStreak(logs)
        }
    }

    private fun calculateStreak(logs: List<PracticeLog>) {
        if (logs.isEmpty()) {
            _streak.postValue(0)
            return
        }

        val uniqueDays = logs.map { getDayInMillis(it.timestamp) }.toSet()

        val today = getDayInMillis(System.currentTimeMillis())
        val yesterday = getDayInMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))

        var currentStreak: Int // Removed redundant initializer
        var currentDate: Long

        if (uniqueDays.contains(today)) {
            currentStreak = 1
            currentDate = today
        } else if (uniqueDays.contains(yesterday)) {
            currentStreak = 1
            currentDate = yesterday
        } else {
            _streak.postValue(0)
            return
        }

        while (true) {
            val previousDay = getDayInMillis(currentDate - TimeUnit.DAYS.toMillis(1))
            if (uniqueDays.contains(previousDay)) {
                currentStreak++
                currentDate = previousDay
            } else {
                break
            }
        }
        _streak.postValue(currentStreak)
    }

    private fun getDayInMillis(timestamp: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun updateProgress(completedLessons: Int) = viewModelScope.launch {
        val progress = Progress(id = 1, completedLessons = completedLessons)
        repository.updateProgress(progress)
    }
}
