package com.example.realtalkenglishwithAI.model.api

import com.google.gson.annotations.SerializedName

// Data classes for parsing Vosk's JSON result
data class VoskResult(
    @SerializedName("result")
    val result: List<WordResult>?,
    @SerializedName("text")
    val text: String
)

data class WordResult(
    @SerializedName("conf")
    val confidence: Double,
    @SerializedName("word")
    val word: String
)
