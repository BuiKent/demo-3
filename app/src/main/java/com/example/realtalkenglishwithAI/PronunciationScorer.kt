package com.example.realtalkenglishwithAI.utils

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import com.google.gson.Gson
import org.json.JSONObject
import java.util.ArrayList
import kotlin.math.max
import kotlin.math.min

object PronunciationScorer {

    private const val LOG_TAG_DETAILED = "PronunciationScorerDetailed"
    private const val LOG_TAG_SENTENCE = "PronunciationScorerSentence"
    private val gson = Gson()

    data class WordScore(val word: String, val confidence: Double)
    data class PronunciationResult(val overallScore: Int, val wordScores: List<WordScore>)

    fun analyze(voskResultJson: String): PronunciationResult {
        if (voskResultJson.isBlank()) return PronunciationResult(0, emptyList())
        try {
            val jsonObject = JSONObject(voskResultJson)
            if (!jsonObject.has("result")) return PronunciationResult(0, emptyList())
            val resultArray = jsonObject.getJSONArray("result")
            if (resultArray.length() == 0) return PronunciationResult(0, emptyList())
            var totalConfidence = 0.0
            val wordScoresList = mutableListOf<WordScore>()
            for (i in 0 until resultArray.length()) {
                val wordObject = resultArray.getJSONObject(i)
                wordScoresList.add(WordScore(wordObject.getString("word"), wordObject.getDouble("conf")))
                totalConfidence += wordObject.getDouble("conf")
            }
            return PronunciationResult((totalConfidence / resultArray.length() * 100).toInt(), wordScoresList)
        } catch (e: Exception) {
            Log.e(LOG_TAG_DETAILED, "Error in basic analyze function", e)
            return PronunciationResult(0, emptyList())
        }
    }

    data class DetailedPronunciationResult(
        val targetWordOriginal: String,
        val recognizedSegment: String?,
        val overallScore: Int,
        val coloredTargetDisplay: SpannableString,
        val confidenceOfRecognized: Float?
    )

    private data class InternalVoskWordForScorer(
        val word: String, val conf: Float, val start: Float, val end: Float
    )

    private data class InternalVoskFullResultForScorer(
        val text: String?, val result: List<InternalVoskWordForScorer>?
    )

    private fun levenshtein(s1: String, s2: String): Int {
        val s1Norm = s1.trim().replace(Regex("[^a-zA-Z0-9\\s]"), "").lowercase()
        val s2Norm = s2.trim().replace(Regex("[^a-zA-Z0-9\\s]"), "").lowercase()
        val m = s1Norm.length
        val n = s2Norm.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1Norm[i - 1] == s2Norm[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    private fun alignStrings(a: String, b: String): Triple<List<Char>, List<Char>, List<Char>> {
        val s1 = a.toCharArray(); val s2 = b.toCharArray(); val m = s1.size; val n = s2.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i; for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
        var i = m; var j = n
        val aligned1 = ArrayList<Char>(); val aligned2 = ArrayList<Char>(); val ops = ArrayList<Char>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + (if (s1[i - 1] == s2[j - 1]) 0 else 1)) {
                aligned1.add(s1[i - 1]); aligned2.add(s2[j - 1]); ops.add(if (s1[i-1] == s2[j-1]) 'M' else 'S'); i--; j--; continue
            } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                aligned1.add(s1[i - 1]); aligned2.add('-'); ops.add('D'); i--; continue
            } else if (j > 0 && dp[i][j] == dp[i][j - 1] + 1) {
                aligned1.add('-'); aligned2.add(s2[j - 1]); ops.add('I'); j--; continue
            } else break
        }
        aligned1.reverse(); aligned2.reverse(); ops.reverse()
        return Triple(aligned1, aligned2, ops)
    }

    private fun calculateFlexibleScore(target: String, recognized: String): Int {
        val t = target.lowercase().trim().replace("\\s+".toRegex(), " ")
        val r = recognized.lowercase().trim().replace("\\s+".toRegex(), " ")
        if (r.isBlank()) return if (t.isBlank()) 100 else 0
        if (t.isBlank() && r.isNotBlank()) return 0
        val distance = levenshtein(t, r)
        val maxLen = maxOf(t.length, r.length)
        if (maxLen == 0) return 100
        val score = ((1.0 - distance.toDouble() / maxLen) * 100).toInt()
        return score.coerceIn(0, 100)
    }

    fun scoreWordDetailed(voskJson: String, targetWordOriginal: String): DetailedPronunciationResult { 
        val targetNormalized = targetWordOriginal.lowercase().trim()
        val spannable = SpannableString(targetWordOriginal)
        fun createErrorFeedback(defaultScore: Int = -1, recognized: String? = null, conf: Float? = null): DetailedPronunciationResult {
            if (targetWordOriginal.isNotEmpty()) spannable.setSpan(ForegroundColorSpan(Color.RED), 0, targetWordOriginal.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return DetailedPronunciationResult(targetWordOriginal, recognized, defaultScore, spannable, conf)
        }
        if (targetNormalized.isBlank() || voskJson.isBlank()) return createErrorFeedback(if (targetNormalized.isBlank()) -1 else 0)
        try {
            val voskFullResult = gson.fromJson(voskJson, InternalVoskFullResultForScorer::class.java)
            val recognizedTextFullRaw = voskFullResult?.text ?: ""
            val recognizedTextNormalized = recognizedTextFullRaw.lowercase().trim()
            if (recognizedTextNormalized.isBlank() && voskFullResult?.result.isNullOrEmpty()) return createErrorFeedback(0)
            val recognizedWords = voskFullResult?.result?.map { it.word.lowercase().trim() } ?: emptyList()
            val recognizedConfs = voskFullResult?.result?.map { it.conf } ?: emptyList()
            var bestMatchIndex = -1; var bestDistance = Int.MAX_VALUE; var bestMatchedWordFromList: String? = null
            if (recognizedWords.isNotEmpty()){
                recognizedWords.forEachIndexed { idx, rw ->
                    if (rw.isEmpty()) return@forEachIndexed
                    val d = levenshtein(targetNormalized, rw)
                    if (d < bestDistance) { bestDistance = d; bestMatchIndex = idx; bestMatchedWordFromList = rw }
                    if (d == 0) return@forEachIndexed 
                }
            }
            var finalScore: Int; var finalRecognizedSegment: String?; var finalConfidence: Float?
            val currentBestMatchedWord = bestMatchedWordFromList
            if (bestMatchIndex != -1 && currentBestMatchedWord != null) {
                finalRecognizedSegment = currentBestMatchedWord
                finalConfidence = recognizedConfs.getOrNull(bestMatchIndex)
                val baseScore = finalConfidence?.let { (it * 100).toInt() } ?: ((1.0 - bestDistance.toDouble() / maxOf(targetNormalized.length, finalRecognizedSegment.length).coerceAtLeast(1)) * 100).toInt()
                finalScore = baseScore.coerceIn(0, 100)
                if (bestDistance > 0 && targetNormalized.isNotEmpty()) {
                    finalScore = (finalScore - (finalScore * (bestDistance.toDouble() / targetNormalized.length) * 1.5).toInt()).coerceAtLeast(0)
                }
            } else {
                finalRecognizedSegment = recognizedTextNormalized
                finalScore = calculateFlexibleScore(targetNormalized, recognizedTextNormalized)
                finalConfidence = null
                if (recognizedTextNormalized.isBlank() && targetNormalized.isNotEmpty()) finalScore = 0
            }
            val alignmentSource = finalRecognizedSegment ?: ""
            try {
                val (alignedTargetChars, _, ops) = alignStrings(targetNormalized, alignmentSource)
                val oldSpans = spannable.getSpans(0, spannable.length, Any::class.java); oldSpans.forEach { spannable.removeSpan(it) }
                var currentOriginalIdx = 0
                ops.forEachIndexed { k, op -> 
                    if (currentOriginalIdx >= targetWordOriginal.length) return@forEachIndexed
                    val charTarget = alignedTargetChars.getOrNull(k) ?: return@forEachIndexed
                    if (charTarget == '-') return@forEachIndexed
                    var displayCharIdx = -1; var searchPointer = currentOriginalIdx
                    while(searchPointer < targetWordOriginal.length) {
                        if (targetWordOriginal[searchPointer].lowercaseChar() == charTarget.lowercaseChar()) { displayCharIdx = searchPointer; break }
                        searchPointer++
                    }
                    if (displayCharIdx != -1) {
                        spannable.setSpan(ForegroundColorSpan(if (op == 'M') Color.GREEN else Color.RED), displayCharIdx, displayCharIdx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        currentOriginalIdx = displayCharIdx + 1
                    } else {
                        spannable.setSpan(ForegroundColorSpan(Color.RED), currentOriginalIdx, currentOriginalIdx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        currentOriginalIdx++
                    }
                }
                if(currentOriginalIdx < targetWordOriginal.length) spannable.setSpan(ForegroundColorSpan(Color.RED), currentOriginalIdx, targetWordOriginal.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (e: Exception) {
                Log.w(LOG_TAG_DETAILED, "Alignment coloring failed for '$targetWordOriginal'", e)
                val wholeColor = if (finalScore >= 70) Color.GREEN else Color.RED
                if (targetWordOriginal.isNotEmpty()) {
                    val oldSpans = spannable.getSpans(0, spannable.length, Any::class.java); oldSpans.forEach { spannable.removeSpan(it) }
                    spannable.setSpan(ForegroundColorSpan(wholeColor), 0, targetWordOriginal.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            return DetailedPronunciationResult(targetWordOriginal, finalRecognizedSegment, finalScore.coerceIn(0, 100), spannable, finalConfidence)
        } catch (e: Exception) {
            Log.e(LOG_TAG_DETAILED, "Error in scoreWordDetailed for '$targetWordOriginal'", e); return createErrorFeedback()
        }
    }

    data class SentenceWordEvaluation(
        val targetWord: String, 
        val recognizedWord: String?,
        val originalIndexInSentence: Int, 
        val score: Int, 
        val startTime: Float? = null, 
        val endTime: Float? = null 
    )

    data class SentenceOverallResult(
        val overallSentenceScore: Int, 
        val evaluations: List<SentenceWordEvaluation>,
        val fullRecognizedText: String? 
    )

    fun scoreSentenceQuick(voskJson: String, targetSentence: String): SentenceOverallResult {
        val targetWords = targetSentence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (voskJson.isBlank() || targetWords.isEmpty()) {
            val emptyEvals = targetWords.mapIndexed { index, word -> 
                SentenceWordEvaluation(word, null, index, 0)
            }
            val recognizedText = if (voskJson.isBlank()) null else try { gson.fromJson(voskJson, InternalVoskFullResultForScorer::class.java)?.text } catch (e: Exception) { null }
            return SentenceOverallResult(0, emptyEvals, recognizedText)
        }

        try {
            val voskFullResult = gson.fromJson(voskJson, InternalVoskFullResultForScorer::class.java)
            val recognizedWordDetails = voskFullResult?.result 
            val fullRecognizedTextFromVosk = voskFullResult?.text

            val evaluations = mutableListOf<SentenceWordEvaluation>()
            var totalScoreSum = 0
            var wordsScoredCount = 0

            if (recognizedWordDetails.isNullOrEmpty()) {
                Log.w(LOG_TAG_SENTENCE, "No detailed word results in Vosk JSON. Comparing full text.")
                val overallScoreBasedOnFullText = calculateFlexibleScore(targetSentence, fullRecognizedTextFromVosk ?: "")
                targetWords.forEachIndexed { index, targetWord ->
                    val wordScore = if (fullRecognizedTextFromVosk?.lowercase()?.let { recognizedLower -> recognizedLower.contains(targetWord.lowercase()) } == true) {
                        (overallScoreBasedOnFullText * 0.5).toInt()
                    } else {
                        0
                    }
                    evaluations.add(SentenceWordEvaluation(targetWord, null, index, wordScore.coerceIn(0,100)))
                    totalScoreSum += wordScore
                    wordsScoredCount++
                }
                val sentenceScore = if (wordsScoredCount > 0) (totalScoreSum / wordsScoredCount) else 0
                return SentenceOverallResult(sentenceScore.coerceIn(0,100), evaluations, fullRecognizedTextFromVosk)
            }

            var recWordIdx = 0
            for ((targetIdx, targetWord) in targetWords.withIndex()) {
                var bestMatchForTarget: InternalVoskWordForScorer? = null
                var bestLevDistance = Int.MAX_VALUE
                var matchedRecWordTempIdx = -1

                recognizedWordDetails.let { details ->
                    for (j in recWordIdx until min(recWordIdx + 5, details.size)) {
                        val voskWordDetail = details[j]
                        val currentLevDistance = levenshtein(targetWord, voskWordDetail.word)
                        if (currentLevDistance < bestLevDistance) {
                            bestLevDistance = currentLevDistance
                            bestMatchForTarget = voskWordDetail
                            matchedRecWordTempIdx = j
                        }
                        if (bestLevDistance == 0) break 
                    }
                }

                val currentBestMatch = bestMatchForTarget // Create stable val for smart casting
                if (currentBestMatch != null && bestLevDistance <= 2) { 
                    val score = (currentBestMatch.conf * 100).toInt() - (bestLevDistance * 10) 
                    evaluations.add(SentenceWordEvaluation(
                        targetWord = targetWord,
                        recognizedWord = currentBestMatch.word,
                        originalIndexInSentence = targetIdx,
                        score = score.coerceIn(0, 100),
                        startTime = currentBestMatch.start,
                        endTime = currentBestMatch.end
                    ))
                    totalScoreSum += score.coerceIn(0, 100)
                    wordsScoredCount++
                    recWordIdx = matchedRecWordTempIdx + 1 
                } else {
                    evaluations.add(SentenceWordEvaluation(targetWord, null, targetIdx, 0))
                    totalScoreSum += 0
                    wordsScoredCount++
                }
            }
            
            val overallAvgScore = if (wordsScoredCount > 0) (totalScoreSum / wordsScoredCount) else 0
            val fullSentenceComparisonScore = calculateFlexibleScore(targetSentence, fullRecognizedTextFromVosk ?: "")
            val finalOverallScore = ((overallAvgScore * 0.7) + (fullSentenceComparisonScore * 0.3)).toInt()

            return SentenceOverallResult(finalOverallScore.coerceIn(0,100), evaluations, fullRecognizedTextFromVosk)

        } catch (e: Exception) {
            Log.e(LOG_TAG_SENTENCE, "Error scoring sentence: '$targetSentence'", e)
            val errorEvals = targetWords.mapIndexed { index, word -> 
                SentenceWordEvaluation(word, null, index, 0)
            }
            val recognizedTextOnError = try { gson.fromJson(voskJson, InternalVoskFullResultForScorer::class.java)?.text } catch (e: Exception) { null }
            return SentenceOverallResult(0, errorEvals, recognizedTextOnError) 
        }
    }
}
