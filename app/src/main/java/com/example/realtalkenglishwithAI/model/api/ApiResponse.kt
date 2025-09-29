package com.example.realtalkenglishwithAI.model.api

// Data classes to match the JSON structure from the Dictionary API
// The API returns a List<ApiResponseItem>

data class ApiResponseItem(
    val word: String?,
    val phonetic: String?,
    val phonetics: List<Phonetic>?,
    val meanings: List<Meaning>?,
    val sourceUrls: List<String>?
)

data class Phonetic(
    val text: String?,
    val audio: String?,
    val sourceUrl: String?
)

data class Meaning(
    val partOfSpeech: String?,
    val definitions: List<Definition>?,
    val synonyms: List<String>?,
    val antonyms: List<String>?
)

data class Definition(
    val definition: String?,
    val synonyms: List<String>?,
    val antonyms: List<String>?,
    val example: String?
)

