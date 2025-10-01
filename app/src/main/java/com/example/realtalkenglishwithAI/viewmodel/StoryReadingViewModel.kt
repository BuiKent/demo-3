package com.example.realtalkenglishwithAI.viewmodel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.realtalkenglishwithAI.model.Word
import com.example.realtalkenglishwithAI.model.WordStatus
import com.example.realtalkenglishwithAI.PronunciationScorer // Corrected import
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class StoryReadingViewModel : ViewModel() {

    private val _storyTitle = MutableStateFlow("")
    val storyTitle: StateFlow<String> = _storyTitle.asStateFlow()

    private val _fullStoryText = MutableStateFlow("")
    val fullStoryText: StateFlow<String> = _fullStoryText.asStateFlow()

    private val _displayWords = MutableStateFlow<List<Word>>(emptyList())
    val displayWords: StateFlow<List<Word>> = _displayWords.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlayingUserAudio = MutableStateFlow(false)
    val isPlayingUserAudio: StateFlow<Boolean> = _isPlayingUserAudio.asStateFlow()

    private val _voskModelState = MutableStateFlow(ModelState.IDLE)
    val voskModelState: StateFlow<ModelState> = _voskModelState.asStateFlow()

    private var _userRecordingPcmFile: File? = null

    private val _canPlayUserRecording = MutableStateFlow(false)
    val canPlayUserRecording: StateFlow<Boolean> = _canPlayUserRecording.asStateFlow()

    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null // For playback
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null

    private val sampleRate = 16000
    private var bufferSizeForRecording: Int = 0
    private var bufferSizeForPlayback: Int = 0

    private var storyProgressIndex = 0 // Keep track of the current word to be matched
    private var lastUiUpdateTimestamp = 0L
    private val uiUpdateThrottleMs = 180L // Throttle UI updates for partial results
    private val lookAheadWindowSize = 4 // How many words ahead to look for a match
    private val levenshteinDistanceThreshold = 1 // Tolerance for matching words

    fun initialize(context: Context, voskModelInstance: Model, title: String, content: String) {
        this.voskModel = voskModelInstance
        _storyTitle.value = title
        _fullStoryText.value = content
        _displayWords.value = buildDisplayWords(content)
        _userRecordingPcmFile = File(context.externalCacheDir, "story_reading_user_audio.pcm")
        _voskModelState.value = ModelState.READY
        resetStoryStateInternal() // Reset any previous state
    }

    private fun buildDisplayWords(fullText: String): List<Word> {
        val wordsList = mutableListOf<Word>()
        val regex = Regex("\\b([\\w'-]+)\\b") // Regex to find words
        regex.findAll(fullText).forEach { matchResult ->
            val originalWord = matchResult.value
            val normalized = normalizeWord(originalWord)
            if (normalized.isNotEmpty()) {
                val start = matchResult.range.first
                val end = matchResult.range.last + 1
                wordsList.add(
                    Word(
                        originalText = originalWord,
                        normalizedText = normalized,
                        status = WordStatus.UNREAD,
                        startIndexInFullText = start,
                        endIndexInFullText = end
                    )
                )
            }
        }
        return wordsList
    }

    private fun normalizeWord(word: String): String {
        return word.lowercase().replace(Regex("[^a-z0-9']"), "") // Normalize to lowercase and remove non-alphanumeric (except apostrophe)
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1].equals(s2[j - 1], ignoreCase = true)) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    fun toggleRecording(context: Context) {
        if (isRecording.value) {
            stopRecordingInternal()
        } else {
            startRecordingInternal(context)
        }
    }

    private fun startRecordingInternal(context: Context) {
        if (voskModel == null) {
            Log.e("StoryReadingVM", "Vosk model is not initialized.")
            _voskModelState.value = ModelState.ERROR
            return
        }
        if (isRecording.value) return
        stopPlaybackInternal() // Stop any ongoing playback before starting recording

        _userRecordingPcmFile?.delete() // Delete previous recording
        _canPlayUserRecording.value = false
        resetStoryStateInternal() // Reset word statuses to UNREAD

        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _isRecording.update { true }
                val minRecBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                bufferSizeForRecording = max(minRecBufferSize, 4096) // Use a reasonable buffer size

                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeForRecording * 2)
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("StoryReadingVM", "AudioRecord not initialized")
                    _isRecording.update { false }
                    return@launch
                }

                recognizer = Recognizer(voskModel, sampleRate.toFloat()) // Vosk model for speech recognition
                FileOutputStream(_userRecordingPcmFile).use { fos ->
                    audioRecord?.startRecording()
                    Log.d("StoryReadingVM", "Recording started...")
                    val audioBuffer = ByteArray(bufferSizeForRecording)
                    while (isActive && isRecording.value) { // Check coroutine and recording state
                        val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                        if (readResult > 0) {
                            fos.write(audioBuffer, 0, readResult) // Save to PCM file
                            if (recognizer?.acceptWaveForm(audioBuffer, readResult) == true) {
                                // Full sentence recognized (or significant pause)
                                processPartialResult(recognizer?.partialResult ?: "")
                            } else {
                                // Partial result available
                                processPartialResult(recognizer?.partialResult ?: "")
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e("StoryReadingVM", "Recording permission issue", e)
                _isRecording.update { false }
            } catch (e: Exception) {
                Log.e("StoryReadingVM", "Recording failed", e)
                _isRecording.update { false }
            } finally {
                _isRecording.update { false } // Ensure UI state is updated
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                val currentRecognizer = recognizer // Capture instance before nullifying
                recognizer = null // Release recognizer instance
                val finalVoskResult = currentRecognizer?.finalResult ?: ""
                currentRecognizer?.close()
                Log.d("StoryReadingVM", "Recording stopped, resources released. Final Vosk Result: $finalVoskResult")
                _userRecordingPcmFile?.let { file ->
                    if (file.exists() && file.length() > 0) {
                        reprocessPcmForFinalResult(file, finalVoskResult)
                        _canPlayUserRecording.update { true }
                    } else {
                         _canPlayUserRecording.update { false }
                    }
                }
            }
        }
    }

    private fun stopRecordingInternal() {
        if (!isRecording.value && recordingJob?.isActive != true) return
        _isRecording.value = false // Signal the recording loop to stop
        recordingJob?.cancel() // Cancel the coroutine job
        recordingJob = null
        // Cleanup (audioRecord.stop/release, recognizer.close) is handled in the finally block of recordingJob
    }

    private fun processPartialResult(voskPartialJson: String) {
        viewModelScope.launch(Dispatchers.Default) { // Use Default dispatcher for CPU-bound work
            val now = System.currentTimeMillis()
            if (now - lastUiUpdateTimestamp < uiUpdateThrottleMs && voskPartialJson.isNotEmpty()) {
                 return@launch // Throttle UI updates
            }
            if(voskPartialJson.isNotEmpty()) lastUiUpdateTimestamp = now

            try {
                val partialText = JSONObject(voskPartialJson).optString("partial", "")
                if (partialText.isBlank() && !_isRecording.value) { // Don't process empty partials unless recording stopped (to clear last partial)
                    return@launch
                }

                val currentDisplayWords = _displayWords.value.toMutableList()
                val transcribedWords = partialText.trim().split(Regex("\\s+"))
                    .map { normalizeWord(it) }
                    .filter { it.isNotBlank() }

                if (transcribedWords.isEmpty() && partialText.isNotBlank()) {
                     return@launch // No valid words to process
                }

                var tIdx = 0 // Index for transcribedWords
                // Find the first UNREAD or INCORRECT word in the story
                var sIdx = currentDisplayWords.indexOfFirst { it.status == WordStatus.UNREAD || it.status == WordStatus.INCORRECT }
                if (sIdx == -1 && currentDisplayWords.all { it.status == WordStatus.CORRECT }) {
                    sIdx = currentDisplayWords.size // All words are correct, nothing to match against further partials
                }
                 if (sIdx == -1) sIdx = 0 // Default to start if no such word (e.g. all UNREAD)

                var wordsChanged = false

                while (sIdx < currentDisplayWords.size && tIdx < transcribedWords.size) {
                    val storyWordInfo = currentDisplayWords[sIdx]
                    val transcriptWord = transcribedWords[tIdx]
                    val isMatch = storyWordInfo.normalizedText == transcriptWord || levenshtein(storyWordInfo.normalizedText, transcriptWord) <= levenshteinDistanceThreshold

                    if (isMatch) {
                        if (storyWordInfo.status != WordStatus.CORRECT) {
                           currentDisplayWords[sIdx] = storyWordInfo.copy(status = WordStatus.CORRECT)
                           wordsChanged = true
                        }
                        sIdx++
                        tIdx++
                    } else {
                        // Word mismatch, try to find the transcriptWord in a small look-ahead window
                        var foundAhead = false
                        for (k in (sIdx + 1) until min(sIdx + 1 + lookAheadWindowSize, currentDisplayWords.size)) {
                            val lookAheadStoryWord = currentDisplayWords[k]
                            if (lookAheadStoryWord.status != WordStatus.CORRECT) { // Only match non-green words
                                if (lookAheadStoryWord.normalizedText == transcriptWord || levenshtein(lookAheadStoryWord.normalizedText, transcriptWord) <= levenshteinDistanceThreshold) {
                                    // Mark skipped words as INCORRECT (optional, can be aggressive)
                                    // for (j in sIdx until k) {
                                    //     if (currentDisplayWords[j].status == WordStatus.UNREAD) {
                                    //         currentDisplayWords[j] = currentDisplayWords[j].copy(status = WordStatus.INCORRECT)
                                    //         wordsChanged = true
                                    //     }
                                    // }
                                    currentDisplayWords[k] = lookAheadStoryWord.copy(status = WordStatus.CORRECT)
                                    sIdx = k + 1
                                    tIdx++
                                    foundAhead = true
                                    wordsChanged = true
                                    break
                                }
                            }
                        }
                        if (!foundAhead) {
                            // If not found ahead, and the current story word is UNREAD, mark it as INCORRECT (optional, aggressive)
                            // if (storyWordInfo.status == WordStatus.UNREAD) {
                            //    currentDisplayWords[sIdx] = storyWordInfo.copy(status = WordStatus.INCORRECT)
                            //    wordsChanged = true
                            // }
                            // sIdx++ // Move to next story word if current doesn't match and not found ahead
                            tIdx++ // Assume current transcript word was a misrecognition or insertion, move to next transcript word
                        }
                    }
                }
                if (wordsChanged) {
                    _displayWords.update { currentDisplayWords.toList() } 
                }
            } catch (e: Exception) {
                Log.e("StoryReadingVM", "Error processing partial result: $voskPartialJson", e)
            }
        }
    }

    private fun reprocessPcmForFinalResult(pcmFile: File, initialFinalResultJson: String) {
        if (voskModel == null) return
        var finalJsonToProcess = initialFinalResultJson

        // Option: Always reprocess the entire file if initialFinalResultJson is often incomplete or inaccurate.
        // This can be computationally more expensive.
        // For now, we trust initialFinalResultJson if it's not blank, assuming recognizer.finalResult is decent.
        if (finalJsonToProcess.isBlank() && pcmFile.exists() && pcmFile.length() > 100) { // Reprocess if initial is blank and PCM exists
            Log.i("StoryReadingVM", "Initial final JSON is blank, reprocessing PCM file for final result.")
            viewModelScope.launch(Dispatchers.IO) {
                var reprocessedJson: String? = null
                try {
                    val tempRecognizer = Recognizer(voskModel, sampleRate.toFloat())
                    FileInputStream(pcmFile).use { fis ->
                        val buffer = ByteArray(4096)
                        var nbytes: Int
                        while (fis.read(buffer).also { nbytes = it } > 0 && isActive) {
                            tempRecognizer.acceptWaveForm(buffer, nbytes)
                        }
                    }
                    // Add a small amount of silence to help finalize the last words
                    val silenceDurationMs = 200 
                    val numSamplesSilence = (sampleRate * silenceDurationMs / 1000)
                    val silenceBytes = ByteArray(numSamplesSilence * 2) // 16-bit PCM
                    if (silenceBytes.isNotEmpty()) {
                        tempRecognizer.acceptWaveForm(silenceBytes, silenceBytes.size)
                    }
                    reprocessedJson = tempRecognizer.finalResult
                    tempRecognizer.close()
                } catch (e: Exception) {
                    Log.e("StoryReadingVM", "Error during final reprocessing of PCM", e)
                }
                reprocessedJson?.let { 
                    Log.d("StoryReadingVM", "Reprocessed final JSON: $it")
                    finalJsonToProcess = it 
                }
                processFinalResult(finalJsonToProcess) // Process the (potentially reprocessed) JSON
            }         
            return // Launching a new coroutine, so return from this synchronous part
        }
        
        // If not reprocessing, or if reprocessing was skipped, directly use the passed final JSON
        processFinalResult(finalJsonToProcess)
    }

    private fun processFinalResult(voskJsonResult: String) {
        viewModelScope.launch(Dispatchers.Default) {
            Log.d("StoryReadingVM", "Processing Final Vosk JSON: $voskJsonResult")
            if (voskJsonResult.isBlank()) {
                Log.w("StoryReadingVM", "Final JSON result is blank, cannot process final scores.")
                // Mark all UNREAD words as INCORRECT if no final result
                val updatedWords = _displayWords.value.map { word ->
                    if (word.status == WordStatus.UNREAD) word.copy(status = WordStatus.INCORRECT)
                    else word
                }
                _displayWords.update { updatedWords }
                return@launch
            }
            val scoringResult = PronunciationScorer.scoreSentenceQuick(voskJsonResult, _fullStoryText.value) 
            val currentWords = _displayWords.value.toMutableList()
            var wordsChanged = false

            // Corrected line for filtering based on recognizedWord
            val finalWordEvaluations = scoringResult.evaluations.filter { it.recognizedWord?.isNotBlank() == true } 
            var storyWordIdx = 0 // Current position in the story's words (_displayWords)

            for(evalWordInfo in finalWordEvaluations){ // evalWordInfo is from PronunciationScorer, based on recognized words
                // Corrected line to use recognizedWord and handle nullability
                val normEvalWord = normalizeWord(evalWordInfo.recognizedWord ?: "") 
                var matchedInStory = false
                // Try to find a match for normEvalWord starting from storyWordIdx
                for(k in storyWordIdx until currentWords.size){
                    val storyWord = currentWords[k]
                    // Skip words already marked CORRECT by partial results unless we want to refine them (e.g. from GREEN to RED based on score)
                    // For simplicity now, if it's CORRECT, we assume it's fine.
                    if(storyWord.status == WordStatus.CORRECT && normalizeWord(storyWord.originalText) == normEvalWord) {
                         if(k == storyWordIdx) storyWordIdx++ // Advance if current is already green and matches
                         matchedInStory = true // يعتبر مطابقاً إذا كان أخضر ويتطابق
                         break // ننتقل للكلمة التالية في التقييم
                    }
                    if(storyWord.status == WordStatus.CORRECT) { // If it's green but doesn't match, skip it in story and continue search
                        if(k == storyWordIdx) storyWordIdx++
                        continue
                    }

                    if(normEvalWord.isNotBlank() && (normalizeWord(storyWord.originalText) == normEvalWord || levenshtein(normalizeWord(storyWord.originalText), normEvalWord) <= levenshteinDistanceThreshold)){
                        // Corrected line to use a specific threshold (e.g., 70) for score
                        val newStatus = if (evalWordInfo.score >= 70) WordStatus.CORRECT else WordStatus.INCORRECT
                        if(storyWord.status != newStatus){
                            currentWords[k] = storyWord.copy(status = newStatus)
                            wordsChanged = true
                        }
                        storyWordIdx = k + 1 // Advance story index past this matched word
                        matchedInStory = true
                        break // Found a match for evalWord, move to next evalWord
                    }
                }
            }
            // Mark any remaining UNREAD story words (not matched by final transcript and not already CORRECT) as INCORRECT
            for(k in 0 until currentWords.size){ // Iterate all words to ensure unread ones are marked
                 if(currentWords[k].status == WordStatus.UNREAD){
                     currentWords[k] = currentWords[k].copy(status = WordStatus.INCORRECT)
                     wordsChanged = true
                 }
            }

            if (wordsChanged) {
                _displayWords.update { currentWords.toList() } 
            }
        }
    }

    fun togglePlayUserRecording() {
        if (isPlayingUserAudio.value) {
            stopPlaybackInternal()
        } else {
            playUserRecordingInternal()
        }
    }
    
    private fun playUserRecordingInternal() {
        val fileToPlay = _userRecordingPcmFile
        if (fileToPlay == null || !fileToPlay.exists() || fileToPlay.length() == 0L) {
            Log.w("StoryReadingVM", "User recording file not available or empty.")
            _canPlayUserRecording.value = false // Ensure this is accurate
            return
        }
        if (isRecording.value) {
            Log.w("StoryReadingVM", "Cannot play audio while recording is active.")
            return
        }
        stopPlaybackInternal() // Stop any previous playback just in case

        playbackJob = viewModelScope.launch(Dispatchers.IO) {
            _isPlayingUserAudio.update { true }
            Log.d("StoryReadingVM", "Starting playback for: ${fileToPlay.absolutePath}")

            try {
                val minPlayBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                bufferSizeForPlayback = max(minPlayBufferSize, 4096)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSizeForPlayback)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e("StoryReadingVM", "AudioTrack not initialized")
                    _isPlayingUserAudio.update { false }
                    return@launch
                }

                audioTrack?.play()
                FileInputStream(fileToPlay).use { fis ->
                    val buffer = ByteArray(bufferSizeForPlayback)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1 && isActive && isPlayingUserAudio.value) {
                        audioTrack?.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                Log.e("StoryReadingVM", "Playback failed", e)
            } finally {
                _isPlayingUserAudio.update { false }
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                Log.d("StoryReadingVM", "Playback stopped, resources released.")
            }
        }
    }

    private fun stopPlaybackInternal(){
        if (!isPlayingUserAudio.value && playbackJob?.isActive != true) return
        _isPlayingUserAudio.value = false // Signal the playback loop to stop
        playbackJob?.cancel() // Cancel the coroutine job
        playbackJob = null
        // AudioTrack cleanup (stop/release) is handled in the finally block of the playbackJob
    }

    fun resetStoryState() {
        stopRecordingInternal()
        stopPlaybackInternal()
        resetStoryStateInternal()
        _userRecordingPcmFile?.delete() // Delete the recording file
        _canPlayUserRecording.value = false // Disable playback button
    }

    private fun resetStoryStateInternal(){
        storyProgressIndex = 0 // Reset story progress index
        val resetWords = _displayWords.value.map { it.copy(status = WordStatus.UNREAD) }
        _displayWords.value = resetWords
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("StoryReadingVM", "onCleared called")
        audioRecord?.release()
        audioRecord = null
        recognizer?.close() // Ensure Vosk recognizer is closed
        recognizer = null
        audioTrack?.release()
        audioTrack = null
        recordingJob?.cancel()
        playbackJob?.cancel()
        // voskModel is managed by VoskModelViewModel and Application, not closed here
    }
}
