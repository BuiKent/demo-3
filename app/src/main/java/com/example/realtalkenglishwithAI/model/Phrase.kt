package com.example.realtalkenglishwithAI.model

data class Phrase(
    val id: Int,
    val englishText: String,
    val ipaText: String,
    val translationText: String,
    val audioUrl: String
)
