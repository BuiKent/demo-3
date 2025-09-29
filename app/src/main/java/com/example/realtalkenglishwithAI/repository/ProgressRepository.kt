package com.example.realtalkenglishwithAI.repository

import androidx.lifecycle.LiveData
import com.example.realtalkenglishwithAI.database.PracticeLogDao
import com.example.realtalkenglishwithAI.database.ProgressDao
import com.example.realtalkenglishwithAI.model.PracticeLog
import com.example.realtalkenglishwithAI.model.Progress

class ProgressRepository(
    private val progressDao: ProgressDao,
    private val practiceLogDao: PracticeLogDao
) {

    val userProgress: LiveData<Progress> = progressDao.getUserProgress(1)
    val allPracticeLogs: LiveData<List<PracticeLog>> = practiceLogDao.getAllLogs()

    suspend fun updateProgress(progress: Progress) {
        progressDao.insertOrUpdate(progress)
    }

    suspend fun addPracticeLog() {
        practiceLogDao.insert(PracticeLog())
    }
}

