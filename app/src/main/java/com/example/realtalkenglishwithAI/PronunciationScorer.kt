package com.example.realtalkenglishwithAI.utils // Hoặc package bạn muốn

import org.json.JSONObject

// Dùng 'object' để tạo một singleton, dễ dàng gọi hàm mà không cần tạo instance
object PronunciationScorer {

    // Dùng data class để chứa kết quả phân tích chi tiết
    data class WordScore(val word: String, val confidence: Double)
    data class PronunciationResult(val overallScore: Int, val wordScores: List<WordScore>)

    fun analyze(voskResultJson: String): PronunciationResult {
        if (voskResultJson.isBlank()) {
            return PronunciationResult(0, emptyList())
        }

        try {
            val jsonObject = JSONObject(voskResultJson)
            if (!jsonObject.has("result")) {
                return PronunciationResult(0, emptyList())
            }

            val resultArray = jsonObject.getJSONArray("result")
            val wordCount = resultArray.length()
            if (wordCount == 0) {
                return PronunciationResult(0, emptyList())
            }

            var totalConfidence = 0.0
            val wordScores = mutableListOf<WordScore>()

            for (i in 0 until wordCount) {
                val wordObject = resultArray.getJSONObject(i)
                val word = wordObject.getString("word")
                val confidence = wordObject.getDouble("conf")

                totalConfidence += confidence
                wordScores.add(WordScore(word, confidence))
            }

            val averageConfidence = totalConfidence / wordCount
            val overallScore = (averageConfidence * 100).toInt()

            return PronunciationResult(overallScore, wordScores)

        } catch (e: Exception) {
            e.printStackTrace()
            return PronunciationResult(0, emptyList())
        }
    }
}