package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.findNavController
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.FragmentStoryReadingBinding
import com.example.realtalkenglishwithAI.utils.PronunciationScorer
import com.example.realtalkenglishwithAI.viewmodel.ModelState
import com.example.realtalkenglishwithAI.viewmodel.VoskModelViewModel
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class StoryReadingFragment : Fragment() {

    private var _binding: FragmentStoryReadingBinding? = null
    private val binding get() = _binding!!

    private val voskModelViewModel: VoskModelViewModel by activityViewModels()

    private var currentStoryContent: String? = null
    private var currentStoryTitle: String? = null

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var pcmFile: File? = null
    private var wavFileForPlayback: File? = null
    private var fileOutputStream: FileOutputStream? = null

    private val sampleRate = 16000
    private var bufferSize: Int = 0

    private var currentSessionRecognizer: Recognizer? = null // Changed from recognizer
    private var targetSentenceForScoring: String = ""

    private var exoPlayer: ExoPlayer? = null

    private var colorDefaultText: Int = Color.BLACK
    private var colorTemporaryHighlight: Int = Color.LTGRAY
    private var colorCorrectWord: Int = Color.GREEN
    private var colorIncorrectWord: Int = Color.RED

    private data class WordInfo(
        val originalText: String,
        val normalizedText: String,
        val sentenceIndex: Int,
        val wordIndexInSentence: Int,
        val startCharInSentence: Int,
        val endCharInSentence: Int,
        var currentSpan: ForegroundColorSpan? = null
    )

    private val sentenceTextViews = mutableListOf<TextView>()
    private val allWordInfosInStory = mutableListOf<WordInfo>()
    private var currentHighlightedWordIndex = -1

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                handleRecordAction()
            } else {
                Toast.makeText(requireContext(), "Record permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            currentStoryContent = it.getString(ARG_STORY_CONTENT)
            currentStoryTitle = it.getString(ARG_STORY_TITLE)
            targetSentenceForScoring = currentStoryContent ?: ""
        }
        val pcmPath = "${requireContext().externalCacheDir?.absolutePath}/story_sentence_audio.pcm"
        pcmFile = File(pcmPath)
        val wavPath = "${requireContext().externalCacheDir?.absolutePath}/story_sentence_audio_playback.wav"
        wavFileForPlayback = File(wavPath)

        setHasOptionsMenu(true)

        colorDefaultText = ContextCompat.getColor(requireContext(), android.R.color.black)
        colorTemporaryHighlight = ContextCompat.getColor(requireContext(), R.color.gray_500)
        colorCorrectWord = ContextCompat.getColor(requireContext(), R.color.blue_500)
        colorIncorrectWord = ContextCompat.getColor(requireContext(), R.color.red_500)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoryReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        currentStoryContent?.let {
            if (isAdded && _binding != null) {
                displayInitialStoryContent(it, binding.linearLayoutResultsContainer)
            }
        }
        setupClickListeners()
        observeVoskModelStatus()
        binding.textViewPartialTranscript.visibility = View.GONE
    }

    private fun displayInitialStoryContent(storyText: String, container: LinearLayout) {
        if (!isAdded || _binding == null) return
        container.removeAllViews()
        sentenceTextViews.clear()
        allWordInfosInStory.clear()
        currentHighlightedWordIndex = -1

        val sentences = storyText.split(Regex("(?<=[.!?;:])\\s+")).filter { it.isNotBlank() }
        sentences.forEachIndexed { sentenceIdx, sentenceStr ->
            val tv = TextView(requireContext()).apply {
                text = SpannableString(sentenceStr)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(colorDefaultText)
                setPadding(4, 8, 4, 8)
                setLineSpacing(0f, 1.2f)
            }
            container.addView(tv)
            sentenceTextViews.add(tv)

            var currentWordStartChar = 0
            val wordsInSentence = sentenceStr.split(Regex("\\s+")).filter { it.isNotBlank() }
            wordsInSentence.forEachIndexed { wordIdxInSent, word ->
                val normalizedWord = normalizeToken(word)
                if (normalizedWord.isNotEmpty()) {
                    val originalWordStart = sentenceStr.indexOf(word, currentWordStartChar)
                    if (originalWordStart != -1) {
                        val originalWordEnd = originalWordStart + word.length
                        allWordInfosInStory.add(
                            WordInfo(
                                originalText = word,
                                normalizedText = normalizedWord,
                                sentenceIndex = sentenceIdx,
                                wordIndexInSentence = wordIdxInSent,
                                startCharInSentence = originalWordStart,
                                endCharInSentence = originalWordEnd
                            )
                        )
                        currentWordStartChar = originalWordEnd
                    } else {
                        Log.w(TAG, "Word '$word' not found in sentence '$sentenceStr' from char $currentWordStartChar")
                    }
                }
            }
        }
    }

    private fun clearAllSpansFromTextViews() {
        if (!isAdded || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        sentenceTextViews.forEach { tv ->
            val spannable = tv.text as? SpannableString ?: SpannableString(tv.text)
            val spans = spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java)
            spans.forEach { spannable.removeSpan(it) }
            tv.text = spannable
            tv.setTextColor(colorDefaultText)
        }
        allWordInfosInStory.forEach { it.currentSpan = null }
    }

    private fun updateWordSpan(wordInfo: WordInfo, color: Int) {
        if (!isAdded || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) || wordInfo.sentenceIndex >= sentenceTextViews.size) return
        val tv = sentenceTextViews[wordInfo.sentenceIndex]
        val spannable = tv.text as? SpannableString ?: SpannableString(tv.text)

        wordInfo.currentSpan?.let { spannable.removeSpan(it) }

        val newSpan = ForegroundColorSpan(color)
        try {
            spannable.setSpan(
                newSpan,
                wordInfo.startCharInSentence,
                wordInfo.endCharInSentence.coerceAtMost(spannable.length),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            wordInfo.currentSpan = newSpan
            tv.text = spannable
        } catch (e: Exception) {
            Log.e(TAG, "Error applying span to '${wordInfo.originalText}' at [${wordInfo.startCharInSentence}-${wordInfo.endCharInSentence}]", e)
        }
    }

    private fun applyTemporaryHighlights(partialTranscript: String) {
        if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

        val partialWords = partialTranscript.split(Regex("\\s+")).map { normalizeToken(it) }.filter { it.isNotEmpty() }

        for (i in 0..currentHighlightedWordIndex) {
            if (i < allWordInfosInStory.size) {
                updateWordSpan(allWordInfosInStory[i], colorDefaultText)
            }
        }
        if (partialWords.isEmpty()) {
            currentHighlightedWordIndex = -1
            return
        }

        var storyWordIdx = 0
        var partialWordIdx = 0
        var lastSuccessfullyMatchedStoryWordGlobalIndex = -1

        while (storyWordIdx < allWordInfosInStory.size && partialWordIdx < partialWords.size) {
            val storyWordInfo = allWordInfosInStory[storyWordIdx]
            val partialWord = partialWords[partialWordIdx]

            if (storyWordInfo.normalizedText == partialWord) {
                updateWordSpan(storyWordInfo, colorTemporaryHighlight)
                lastSuccessfullyMatchedStoryWordGlobalIndex = storyWordIdx
                partialWordIdx++
            } else {
                break
            }
            storyWordIdx++
        }
        currentHighlightedWordIndex = lastSuccessfullyMatchedStoryWordGlobalIndex
    }

    private fun setupToolbar() {
        if (activity is AppCompatActivity) {
            (activity as AppCompatActivity).setSupportActionBar(binding.toolbarStoryReading)
        }
        val actionBar = (activity as? AppCompatActivity)?.supportActionBar
        actionBar?.title = currentStoryTitle ?: "Story"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setDisplayShowHomeEnabled(true)

        try {
            val typeface: Typeface? = ResourcesCompat.getFont(requireContext(), R.font.dancing_script)
            for (i in 0 until binding.toolbarStoryReading.childCount) {
                val childView = binding.toolbarStoryReading.getChildAt(i)
                if (childView is TextView) {
                    if (childView.text.toString().equals(actionBar?.title?.toString(), ignoreCase = true)) {
                        childView.typeface = typeface
                        childView.setTextColor(Color.parseColor("#333333"))
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load or apply custom font/color for toolbar title: ${R.font.dancing_script}", e)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.story_reading_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                findNavController().navigateUp()
                true
            }
            R.id.action_play_story_audio -> {
                Toast.makeText(requireContext(), "Play story audio clicked (Not implemented yet)", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupClickListeners() {
        binding.buttonRecordStorySentence.setOnClickListener {
            handleRecordAction()
        }
        binding.buttonPlayUserSentenceRecording.setOnClickListener {
            playUserRecording()
        }
    }

    private fun observeVoskModelStatus() {
        voskModelViewModel.modelState.observe(viewLifecycleOwner) { state ->
            Log.d(TAG, "VoskModelViewModel state changed: $state")
            // Removed recognizer initialization/closing logic from here
            updateUIForVoskModelState(state)
        }
    }

    private fun updateUIForVoskModelState(state: ModelState) {
        if (!isAdded || _binding == null) return
        // Check against model availability directly
        val modelIsReadyForUse = state == ModelState.READY && voskModelViewModel.voskModel != null
        binding.buttonRecordStorySentence.isEnabled = modelIsReadyForUse
        binding.buttonRecordStorySentence.alpha = if (modelIsReadyForUse) 1.0f else 0.5f
        if (!modelIsReadyForUse && isRecording) {
            stopRecordingInternal(false) // Stop recording if model becomes not ready
        }
    }

    private fun handleRecordAction() {
        if (!isAdded || _binding == null) return
        // Check model readiness directly
        if (voskModelViewModel.modelState.value != ModelState.READY || voskModelViewModel.voskModel == null) {
            Toast.makeText(requireContext(), "Speech engine not ready. Please wait or check model.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (isRecording) {
                stopRecordingInternal(true)
            } else {
                startRecordingInternal()
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecordingInternal() {
        if (targetSentenceForScoring.isBlank()) {
            Toast.makeText(requireContext(), "No story text to record.", Toast.LENGTH_SHORT).show(); return
        }

        val currentModel = voskModelViewModel.voskModel
        if (currentModel == null) {
            Toast.makeText(requireContext(), "Speech model not available. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            currentSessionRecognizer?.close() // Ensure any previous session's recognizer is closed
            currentSessionRecognizer = Recognizer(currentModel, sampleRate.toFloat())
            Log.i(TAG, "New Recognizer instance created for the session.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new Recognizer instance", e)
            Toast.makeText(requireContext(), "Failed to initialize speech recognizer for session.", Toast.LENGTH_LONG).show()
            currentSessionRecognizer = null
            return // Stop if recognizer creation fails
        }

        pcmFile?.delete()
        wavFileForPlayback?.delete()
        binding.textViewSentenceScore.visibility = View.GONE
        binding.textViewPartialTranscript.text = "Đang nghe..."
        binding.textViewPartialTranscript.visibility = View.VISIBLE

        clearAllSpansFromTextViews()
        currentHighlightedWordIndex = -1

        binding.buttonPlayUserSentenceRecording.isEnabled = false
        binding.buttonPlayUserSentenceRecording.alpha = 0.5f
        // No recognizer.reset() needed as it's a new instance

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            bufferSize = max(minBufferSize, 4096)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                Toast.makeText(requireContext(), "AudioRecord initialization failed.", Toast.LENGTH_SHORT).show()
                currentSessionRecognizer?.close() // Clean up newly created recognizer
                currentSessionRecognizer = null
                return
            }
            fileOutputStream = FileOutputStream(pcmFile)
            audioRecord?.startRecording()
            isRecording = true
            updateRecordButtonUI()
            Toast.makeText(requireContext(), "Recording started...", Toast.LENGTH_SHORT).show()

            val localRecognizerForThread = currentSessionRecognizer // Capture for thread safety

            recordingThread = thread(start = true, name = "StoryAudioProducer") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val audioBuffer = ByteArray(bufferSize)

                while (isRecording) {
                    val currentAudioRecord = audioRecord
                    // Use the local recognizer instance for this thread
                    if (currentAudioRecord == null || localRecognizerForThread == null || !isRecording) break

                    val readResult = currentAudioRecord.read(audioBuffer, 0, audioBuffer.size)
                    if (readResult > 0) {
                        try {
                            fileOutputStream?.write(audioBuffer, 0, readResult)
                            // Check if localRecognizerForThread is still valid (though it should be if loop continues based on isRecording)
                            if (localRecognizerForThread.acceptWaveForm(audioBuffer, readResult)) {
                                val partialJson = localRecognizerForThread.partialResult
                                val jsonObject = JSONObject(partialJson)
                                val partialText = jsonObject.optString("partial", "")
                                activity?.runOnUiThread {
                                    if (isAdded && _binding != null && isRecording) {
                                        binding.textViewPartialTranscript.text = partialText
                                        applyTemporaryHighlights(partialText)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in recording loop (writing/processing): ${e.message}", e)
                            break
                        }
                    } else if (readResult < 0) {
                        Log.e(TAG, "AudioRecord read error: $readResult")
                        activity?.runOnUiThread { stopRecordingInternal(false) } 
                        break
                    }
                }
                Log.d(TAG, "StoryAudioProducer thread finished.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording setup/start failed", e)
            Toast.makeText(requireContext(), "Recording setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            isRecording = false
            updateRecordButtonUI()
            currentSessionRecognizer?.close() // Clean up recognizer if setup failed
            currentSessionRecognizer = null
            activity?.runOnUiThread {
                if(isAdded && _binding != null) {
                    binding.textViewPartialTranscript.text = ""
                    binding.textViewPartialTranscript.visibility = View.GONE
                }
            }
        }
    }

    private fun stopRecordingInternal(processAudio: Boolean) {
        if (!isRecording && audioRecord == null && recordingThread == null) {
            Log.d(TAG, "stopRecordingInternal called but not in a recording state or already stopped.")
            return
        }
        Log.d(TAG, "stopRecordingInternal - Setting isRecording to false.")
        isRecording = false

        updateRecordButtonUI()

        Log.d(TAG, "stopRecordingInternal - Joining recordingThread.")
        try {
            recordingThread?.join(1000)
            if (recordingThread?.isAlive == true) {
                Log.w(TAG, "Recording thread did not finish in time, interrupting.")
                recordingThread?.interrupt()
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Recording thread join interrupted", e)
            Thread.currentThread().interrupt()
        }
        recordingThread = null
        Log.d(TAG, "stopRecordingInternal - RecordingThread joined/handled.")

        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try { it.stop() } catch (e: IllegalStateException) { Log.e(TAG, "AudioRecord.stop() failed", e) }
            }
            if (it.state == AudioRecord.STATE_INITIALIZED) {
                 try { it.release() } catch (e: Exception) { Log.e(TAG, "AudioRecord.release() failed", e) }
            }
        }
        audioRecord = null
        Log.d(TAG, "stopRecordingInternal - AudioRecord stopped and released.")

        try {
            fileOutputStream?.flush()
            fileOutputStream?.close()
        } catch (e: IOException) {
            Log.e(TAG, "FileOutputStream close/flush failed", e)
        }
        fileOutputStream = null
        Log.d(TAG, "stopRecordingInternal - FileOutputStream closed.")

        activity?.runOnUiThread {
            if(isAdded && _binding != null) {
                binding.textViewPartialTranscript.text = ""
                binding.textViewPartialTranscript.visibility = View.GONE
            }
        }

        val recognizerForProcessing = currentSessionRecognizer
        currentSessionRecognizer = null // Detach from class member, it will be closed in post-processing or GC'd

        if (processAudio && pcmFile?.exists() == true && pcmFile!!.length() > 44) {
            if (recognizerForProcessing != null) {
                Toast.makeText(requireContext(), "Processing final result...", Toast.LENGTH_SHORT).show()
                startPostProcessingThread(pcmFile!!, targetSentenceForScoring, recognizerForProcessing)
            } else {
                Log.e(TAG, "Recognizer was null when trying to start post processing after recording.")
                Toast.makeText(requireContext(), "Error: Recognizer not available for processing.", Toast.LENGTH_SHORT).show()
                binding.buttonPlayUserSentenceRecording.isEnabled = false
                binding.buttonPlayUserSentenceRecording.alpha = 0.5f
                clearAllSpansFromTextViews()
            }
        } else if (processAudio) {
            Toast.makeText(requireContext(), "Recording was empty or invalid.", Toast.LENGTH_SHORT).show()
            binding.buttonPlayUserSentenceRecording.isEnabled = false
            binding.buttonPlayUserSentenceRecording.alpha = 0.5f
            clearAllSpansFromTextViews()
            recognizerForProcessing?.close() // Close if not passed to post-processing and processAudio was true
        } else {
            clearAllSpansFromTextViews()
            recognizerForProcessing?.close() // Close if not processing audio at all
        }
        Log.d(TAG, "stopRecordingInternal - Finished.")
    }

    // Changed last parameter name and added finally block to close recognizer
    private fun startPostProcessingThread(currentPcmFile: File, fullStoryText: String, recognizerToProcess: Recognizer) {
        if (voskModelViewModel.modelState.value != ModelState.READY && voskModelViewModel.voskModel == null) {
            Toast.makeText(requireContext(), "Speech engine not ready (model state).", Toast.LENGTH_SHORT).show()
            try {
                 recognizerToProcess.close() // Close if model is not ready
            } catch (e: Exception) {
                Log.e(TAG, "Error closing recognizerToProcess in startPostProcessingThread when model not ready", e)
            }
            return
        }

        thread(start = true, name = "StoryFinalProcessThread") {
            var voskJsonResult: String? = null
            var wavCreatedSuccessfully = false
            try {
                voskJsonResult = recognizerToProcess.finalResult

                wavFileForPlayback?.let {
                    if (currentPcmFile.exists() && currentPcmFile.length() > 0) {
                        try {
                            FileOutputStream(it).use { fosWav ->
                                fosWav.write(createWavHeader(currentPcmFile.length().toInt(), sampleRate, 1, 16))
                                FileInputStream(currentPcmFile).use { fisPcmForWav -> fisPcmForWav.copyTo(fosWav) }
                            }
                            wavCreatedSuccessfully = it.exists() && it.length() > 44
                        } catch (e: Exception) { Log.e(TAG, "Error creating WAV file", e) }
                    } else {
                         Log.w(TAG, "PCM file for WAV is empty or not found.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Vosk finalResult or WAV creation within thread", e)
            } finally {
                try {
                    recognizerToProcess.close()
                    Log.i(TAG, "Recognizer instance closed in StoryFinalProcessThread.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing recognizerToProcess in StoryFinalProcessThread finally block", e)
                }
            }

            activity?.runOnUiThread {
                if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@runOnUiThread
                clearAllSpansFromTextViews()

                if (voskJsonResult != null) {
                    Log.d(TAG, "Vosk Final Result: $voskJsonResult")
                    val scoringResult = PronunciationScorer.scoreSentenceQuick(voskJsonResult, fullStoryText)
                    renderFinalWordColors(scoringResult.evaluations)
                } else {
                     Toast.makeText(requireContext(), "Could not get final speech result.", Toast.LENGTH_SHORT).show()
                     Log.e(TAG, "Vosk Final Result was null.")
                }
                binding.buttonPlayUserSentenceRecording.isEnabled = wavCreatedSuccessfully
                binding.buttonPlayUserSentenceRecording.alpha = if (wavCreatedSuccessfully) 1.0f else 0.5f
            }
        }
    }

    private fun renderFinalWordColors(evaluations: List<PronunciationScorer.SentenceWordEvaluation>) {
        if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

        var evalIdx = 0
        allWordInfosInStory.forEach { storyWordInfo ->
            var chosenScore = 0
            // var matched = false // matched variable was not used
            if (evalIdx < evaluations.size) {
                val evalWord = evaluations[evalIdx]
                if (storyWordInfo.normalizedText == normalizeToken(evalWord.targetWord) ||
                    levenshtein(storyWordInfo.normalizedText, normalizeToken(evalWord.targetWord)) <= 1) {
                    chosenScore = evalWord.score
                    evalIdx++
                    // matched = true
                } else {
                    if (evalIdx + 1 < evaluations.size) {
                        val nextEvalWord = evaluations[evalIdx + 1]
                        if (storyWordInfo.normalizedText == normalizeToken(nextEvalWord.targetWord) ||
                            levenshtein(storyWordInfo.normalizedText, normalizeToken(nextEvalWord.targetWord)) <= 1) {
                            chosenScore = nextEvalWord.score
                            evalIdx += 2
                            // matched = true
                        }
                    }
                }
            }
            val colorToApply = if (chosenScore > 0) colorCorrectWord else colorIncorrectWord
            updateWordSpan(storyWordInfo, colorToApply)
        }
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

    private fun normalizeToken(s: String): String = s.lowercase().replace(Regex("[^a-z0-9']"), "")

    private fun playUserRecording() {
        if (wavFileForPlayback?.exists() != true || wavFileForPlayback!!.length() == 0L) {
            Toast.makeText(requireContext(), "No recording available.", Toast.LENGTH_SHORT).show()
            return
        }

        exoPlayer?.release() // Release previous instance
        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            val mediaItem = MediaItem.fromUri(wavFileForPlayback!!.toURI().toString())
            setMediaItem(mediaItem)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        Toast.makeText(context, "Playback finished.", Toast.LENGTH_SHORT).show()
                        this@apply.release() // Release player on completion
                        exoPlayer = null
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error: ", error)
                    Toast.makeText(context, "Error playing recording.", Toast.LENGTH_SHORT).show()
                    this@apply.release() // Release player on error
                    exoPlayer = null
                }
            })
            prepare()
            playWhenReady = true
        }
        Toast.makeText(requireContext(), "Playing recording...", Toast.LENGTH_SHORT).show()
    }

    private fun updateRecordButtonUI() {
        if (!isAdded || _binding == null) return
        val icon = if (isRecording) R.drawable.ic_close else R.drawable.ic_mic
        binding.buttonRecordStorySentence.setImageResource(icon)
        binding.buttonRecordStorySentence.isActivated = isRecording
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val header = ByteArray(44)
        val totalDataLen = dataSize + 36L
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte(); header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate shr 8) and 0xff).toByte(); header[26] = ((sampleRate shr 16) and 0xff).toByte(); header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte(); header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte(); header[33] = ((blockAlign.toInt() shr 8) and 0xff).toByte()
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte(); header[41] = ((dataSize shr 8) and 0xff).toByte(); header[42] = ((dataSize shr 16) and 0xff).toByte(); header[43] = ((dataSize shr 24) and 0xff).toByte()
        return header
    }

    private fun FileOutputStream.flushSafely() { try { flush() } catch (e: IOException) { Log.e(TAG, "FileOutputStream.flush() failed safely", e) } }
    private fun FileOutputStream.closeSafely() { try { close() } catch (e: IOException) { Log.e(TAG, "FileOutputStream.close() failed safely", e) } }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView - Stopping recording if active.")
        if (isRecording) {
            stopRecordingInternal(false) // This will also nullify currentSessionRecognizer if it was active
        }
        // Ensure any lingering session recognizer is closed if not handled by stopRecordingInternal
        currentSessionRecognizer?.let {
            try {
                it.close()
                Log.i(TAG, "CurrentSessionRecognizer closed in onDestroyView.")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing currentSessionRecognizer in onDestroyView", e)
            }
            currentSessionRecognizer = null
        }

        Log.d(TAG, "onDestroyView - Releasing ExoPlayer.")
        exoPlayer?.release()
        exoPlayer = null

        (activity as? AppCompatActivity)?.setSupportActionBar(null)
        _binding = null
        Log.d(TAG, "StoryReadingFragment onDestroyView completed.")
    }

    companion object {
        private const val ARG_STORY_CONTENT = "story_content"
        private const val ARG_STORY_TITLE = "story_title"
        private const val TAG = "StoryReadingFragment"

        @JvmStatic
        fun newInstance(storyTitle: String, storyContent: String) = StoryReadingFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_STORY_TITLE, storyTitle)
                putString(ARG_STORY_CONTENT, storyContent)
            }
        }
    }
}
