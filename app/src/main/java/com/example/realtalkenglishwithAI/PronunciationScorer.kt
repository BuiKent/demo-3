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
    private val gson = Gson()

    // Existing data classes and analyze function (kept for other potential uses)
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
            Log.e(LOG_TAG_DETAILED, "Error in basic analyze function", e)
            return PronunciationResult(0, emptyList())
        }
    }

    // --- Start: Detailed Word Scoring ---

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
        val text: String, val result: List<InternalVoskWordForScorer>?
    )

    private fun levenshtein(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    private fun alignStrings(a: String, b: String): Triple<List<Char>, List<Char>, List<Char>> {
        val s1 = a.toCharArray()
        val s2 = b.toCharArray()
        val m = s1.size
        val n = s2.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        var i = m
        var j = n
        val aligned1 = ArrayList<Char>()
        val aligned2 = ArrayList<Char>()
        val ops = ArrayList<Char>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                if (dp[i][j] == dp[i - 1][j - 1] + cost) {
                    aligned1.add(s1[i - 1]); aligned2.add(s2[j - 1]); ops.add(if (cost == 0) 'M' else 'S'); i--; j--; continue
                }
            }
            if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                aligned1.add(s1[i - 1]); aligned2.add('-'); ops.add('D'); i--; continue
            }
            if (j > 0 && dp[i][j] == dp[i][j - 1] + 1) {
                aligned1.add('-'); aligned2.add(s2[j - 1]); ops.add('I'); j--; continue
            }
            if (i > 0) { aligned1.add(s1[i-1]); aligned2.add('-'); ops.add('D'); i--; continue }
            if (j > 0) { aligned1.add('-'); aligned2.add(s2[j-1]); ops.add('I'); j--; continue }
            break
        }
        aligned1.reverse(); aligned2.reverse(); ops.reverse()
        return Triple(aligned1, aligned2, ops)
    }

    private fun calculateFlexibleScore(target: String, recognized: String): Int {
        if (recognized.isBlank()) return -1
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
        val spannable = SpannableString(targetWordOriginal) // Use original for display length

        fun createErrorFeedback(defaultScore: Int = -1, recognized: String? = null, conf: Float? = null): DetailedPronunciationResult {
            if (targetWordOriginal.isNotEmpty()) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.RED),
                    0,
                    targetWordOriginal.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            Log.d(LOG_TAG_DETAILED, "createErrorFeedback: score=$defaultScore, target='$targetWordOriginal', recognized='$recognized'")
            return DetailedPronunciationResult(targetWordOriginal, recognized, defaultScore, spannable, conf)
        }

        if (targetNormalized.isBlank() || voskJson.isBlank()) {
            val score = if (targetNormalized.isBlank()) -1 else 0 // No target means error, no JSON means 0 for target
            Log.w(LOG_TAG_DETAILED, "Invalid inputs. targetBlank=${targetNormalized.isBlank()}, voskJsonBlank=${voskJson.isBlank()}")
            return createErrorFeedback(score)
        }

        return try {
            val voskFullResult = gson.fromJson(voskJson, InternalVoskFullResultForScorer::class.java)
            val recognizedTextFullRaw = voskFullResult.text ?: ""
            val recognizedTextNormalized = recognizedTextFullRaw.lowercase().trim()

            Log.d(LOG_TAG_DETAILED, "Target: '$targetWordOriginal' ('$targetNormalized'), Recognized full: '$recognizedTextNormalized'")

            if (recognizedTextNormalized.isBlank() && voskFullResult.result.isNullOrEmpty()) {
                Log.d(LOG_TAG_DETAILED, "Recognized text and result list are blank -> score 0")
                return createErrorFeedback(0)
            }

            val recognizedWords = voskFullResult.result?.map { it.word.lowercase().trim() } ?: emptyList()
            val recognizedConfs = voskFullResult.result?.map { it.conf } ?: emptyList()

            var bestMatchIndex = -1
            var bestDistance = Int.MAX_VALUE
            var bestMatchedWordFromList: String? = null

            if (recognizedWords.isNotEmpty()){
                for ((idx, rw) in recognizedWords.withIndex()) {
                    if (rw.isEmpty()) continue
                    val d = levenshtein(targetNormalized, rw)
                    if (d < bestDistance) {
                        bestDistance = d
                        bestMatchIndex = idx
                        bestMatchedWordFromList = rw
                    }
                    if (d == 0) break // Perfect match found
                }
            }

            var finalScore: Int
            var finalRecognizedSegment: String? = null
            var finalConfidence: Float? = null

            if (bestMatchIndex != -1 && bestMatchedWordFromList != null) {
                finalRecognizedSegment = bestMatchedWordFromList
                finalConfidence = recognizedConfs.getOrNull(bestMatchIndex)
                val baseScore = if (finalConfidence != null) {
                    (finalConfidence * 100).toInt()
                } else {
                    // Fallback if confidence is somehow null for a found word
                    val maxLen = maxOf(targetNormalized.length, finalRecognizedSegment.length)
                    if (maxLen == 0) 100 else ((1.0 - bestDistance.toDouble() / maxLen) * 100).toInt()
                }
                finalScore = baseScore.coerceIn(0, 100)

                // Apply penalty for imperfect matches (Levenshtein distance > 0)
                if (bestDistance > 0 && targetNormalized.isNotEmpty()) {
                    val penaltyRatio = bestDistance.toDouble() / targetNormalized.length
                    val penaltyAmount = (finalScore * penaltyRatio * 1.5).toInt() // Slightly higher penalty for mismatches
                    finalScore = (finalScore - penaltyAmount).coerceAtLeast(0)
                }
                 Log.d(LOG_TAG_DETAILED, "Matched word from list: '$finalRecognizedSegment' (conf: $finalConfidence, dist: $bestDistance) -> initial score: $finalScore")
            } else {
                // No specific word in Vosk result matched well, or result list was empty.
                // Fallback to comparing target with the full recognized text.
                finalRecognizedSegment = recognizedTextNormalized // Use the whole recognized string
                finalScore = calculateFlexibleScore(targetNormalized, recognizedTextNormalized)
                finalConfidence = null // No single word confidence applicable here
                Log.d(LOG_TAG_DETAILED, "No per-word match; fallback to full text. Recognized: '$finalRecognizedSegment', score: $finalScore")
                if (recognizedTextNormalized.isBlank() && targetNormalized.isNotEmpty()) {
                     // Explicitly set score to 0 if target is there but nothing was recognized
                    finalScore = 0
                }
            }

            val alignmentSource = finalRecognizedSegment ?: "" // Ensure not null for alignStrings

            try {
                // Use targetWordOriginal for spannable length, targetNormalized for alignment logic
                val (alignedTargetChars, _, ops) = alignStrings(targetNormalized, alignmentSource)
                var originalCharPointer = 0 // Pointer for targetWordOriginal
                val originalLower = targetWordOriginal.lowercase() // For char-by-char comparison against targetNormalized

                for (k in alignedTargetChars.indices) {
                    val charFromNormalizedTarget = alignedTargetChars[k]
                    val operation = ops[k]

                    if (charFromNormalizedTarget == '-') continue // Deletion in target, skip coloring on target
                    
                    // Find the corresponding character in targetWordOriginal
                    // This is tricky if targetNormalized had chars removed (e.g. punctuation) that were in targetWordOriginal
                    // For simplicity, we assume a direct mapping for now, or use originalCharPointer carefully.
                    // A more robust way would be to align targetWordOriginal with targetNormalized first.
                    // Current PracticeFragment logic iterates based on `origIndexPointer` and `originalLower`
                    
                    var actualOriginalIndex = -1
                    // Find charFromNormalizedTarget in originalLower starting from originalCharPointer
                    var searchIdx = originalCharPointer
                    while(searchIdx < originalLower.length){
                        if(originalLower[searchIdx] == charFromNormalizedTarget){
                            actualOriginalIndex = searchIdx
                            break
                        }
                        searchIdx++
                    }

                    if(actualOriginalIndex != -1 && actualOriginalIndex < targetWordOriginal.length){
                        val color = if (operation == 'M') Color.GREEN else Color.RED
                        spannable.setSpan(
                            ForegroundColorSpan(color),
                            actualOriginalIndex,
                            actualOriginalIndex + 1,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        originalCharPointer = actualOriginalIndex + 1
                    } else if (originalCharPointer < targetWordOriginal.length) {
                        // If char from normalized target not found (e.g. was punctuation in original)
                        // or if alignment produced a substitution for a char that's hard to map back,
                        // color this char in original red as a fallback for this position.
                        // This part needs to be robust.
                        // For now, let's assume if a char in normalized target isn't found, it's a mismatch for that *position*
                        // The PracticeFragment logic for coloring seems more robust here by advancing origIndexPointer.
                        // Re-implementing PracticeFragment's coloring logic more directly:
                        // The loop should be on `ops` and `alignedTargetChars`. `origIndexPointer` refers to `targetWordOriginal`.
                        // The coloring loop from PracticeFragment is more robust here. Let's adapt that.
                        // The provided alignStrings gives alignedTargetChars, alignedRecognizedChars, and ops.
                        // We need to iterate through these and map back to originalTargetWord's indices.
                    }
                }
                // --- Re-implementing coloring based on PracticeFragment's loop for robustness ---
                // Clear previous spans if any (though spannable is new here)
                val oldSpans = spannable.getSpans(0, spannable.length, Any::class.java)
                for(span in oldSpans) spannable.removeSpan(span)

                var currentOriginalIdx = 0
                for(k in ops.indices) {
                    val op = ops[k]
                    val charTarget = alignedTargetChars[k]
                    // val charRecognized = alignedRecognizedChars[k] // from alignStrings Triple's second element if needed

                    if (charTarget == '-') { // Insertion in recognized, relative to target; no char in target to color.
                        continue
                    }

                    // Find the character in the original display string
                    // This assumes targetNormalized (used for alignment) has a close relationship to targetWordOriginal
                    var displayCharIdx = -1
                    var searchPointer = currentOriginalIdx
                    val targetCharLower = charTarget.lowercaseChar()
                    while(searchPointer < targetWordOriginal.length) {
                        if (targetWordOriginal[searchPointer].lowercaseChar() == targetCharLower) {
                            displayCharIdx = searchPointer
                            break
                        }
                        searchPointer++
                    }

                    if (displayCharIdx != -1) {
                        val color = if (op == 'M') Color.GREEN else Color.RED
                        spannable.setSpan(ForegroundColorSpan(color), displayCharIdx, displayCharIdx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        currentOriginalIdx = displayCharIdx + 1
                    } else if (currentOriginalIdx < targetWordOriginal.length) {
                         // If char from aligned target not found in remaining original (e.g. original had more chars like punctuation)
                         // Color this char RED in original, and advance pointer. This is a best-effort.
                        spannable.setSpan(ForegroundColorSpan(Color.RED), currentOriginalIdx, currentOriginalIdx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        currentOriginalIdx++
                    }
                     if (currentOriginalIdx >= targetWordOriginal.length) break // All original chars processed
                }
                 // If any remaining part of original string wasn't covered by alignment (e.g. target longer than aligned part)
                if(currentOriginalIdx < targetWordOriginal.length){
                     spannable.setSpan(ForegroundColorSpan(Color.RED), currentOriginalIdx, targetWordOriginal.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }


            } catch (e: Exception) {
                Log.w(LOG_TAG_DETAILED, "Alignment coloring failed, fallback to coarse coloring for '$targetWordOriginal'", e)
                val wholeColor = if (finalScore >= 70) Color.GREEN else Color.RED
                if (targetWordOriginal.isNotEmpty()) {
                     // Clear previous spans before applying whole color
                    val oldSpans = spannable.getSpans(0, spannable.length, Any::class.java)
                    for(span in oldSpans) spannable.removeSpan(span)
                    spannable.setSpan(
                        ForegroundColorSpan(wholeColor),
                        0,
                        targetWordOriginal.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            return DetailedPronunciationResult(targetWordOriginal, finalRecognizedSegment, finalScore.coerceIn(0, 100), spannable, finalConfidence)

        } catch (e: Exception) {
            Log.e(LOG_TAG_DETAILED, "Error in scoreWordDetailed for target '$targetWordOriginal'", e)
            return createErrorFeedback() // Default error feedback
        }
    }
    // --- End: Detailed Word Scoring ---
}