package com.example.realtalkenglishwithAI.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.realtalkenglishwithAI.model.Progress

@Dao
interface ProgressDao {
    // Sửa lại hàm này để chấp nhận một tham số userId
    @Query("SELECT * FROM progress_table WHERE id = :userId")
    fun getUserProgress(userId: Int): LiveData<Progress>

    // Hàm này để cập nhật hoặc thêm mới một đối tượng Progress
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(progress: Progress)
}

