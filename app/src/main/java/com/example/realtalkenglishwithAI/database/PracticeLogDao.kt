package com.example.realtalkenglishwithAI.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.realtalkenglishwithAI.model.PracticeLog

@Dao
interface PracticeLogDao {
    @Insert
    suspend fun insert(log: PracticeLog)

    @Query("SELECT * FROM practice_log_table ORDER BY timestamp DESC")
    fun getAllLogs(): LiveData<List<PracticeLog>>
}
