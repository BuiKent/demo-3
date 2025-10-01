package com.example.realtalkenglishwithAI.model

enum class WordStatus {
    UNREAD,
    CORRECT,
    INCORRECT
}

data class Word(
    val originalText: String, // Word as it appears in the original story, with punctuation
    val normalizedText: String, // Word normalized for comparison (lowercase, no punctuation)
    var status: WordStatus = WordStatus.UNREAD,
    val startIndexInFullText: Int, // Start index in the original full story string
    val endIndexInFullText: Int    // End index in the original full story string
)
