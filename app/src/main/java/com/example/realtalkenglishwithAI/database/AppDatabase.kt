package com.example.realtalkenglishwithAI.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.realtalkenglishwithAI.model.PracticeLog
import com.example.realtalkenglishwithAI.model.Progress
import com.example.realtalkenglishwithAI.model.Vocabulary

@Database(entities = [Progress::class, Vocabulary::class, PracticeLog::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun progressDao(): ProgressDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun practiceLogDao(): PracticeLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "realtalk_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

