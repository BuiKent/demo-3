package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue // Added for SP unit
import android.view.* 
import android.widget.LinearLayout // Added for type hint
import android.widget.TextView 
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity 
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat 
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController 
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.FragmentStoryReadingBinding
import com.example.realtalkenglishwithAI.utils.PronunciationScorer
import com.example.realtalkenglishwithAI.viewmodel.ModelState
import com.example.realtalkenglishwithAI.viewmodel.VoskModelViewModel
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min // Added for levenshtein and render logic

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

    private var recognizer: Recognizer? = null
    private var targetSentenceForScoring: String = ""

    private var mediaPlayer: MediaPlayer? = null

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
            if (isAdded && _binding != null) { // Ensure fragment is added
                displayInitialStoryContent(it, binding.linearLayoutResultsContainer)
            }
        }
        Log.d(TAG, "Story Title: $currentStoryTitle, Story Content: $currentStoryContent")
        setupClickListeners()
        observeVoskModelStatus()
    }

    private fun displayInitialStoryContent(storyText: String, container: LinearLayout) {
        if (!isAdded || _binding == null) return
        container.removeAllViews()
        val sentences = storyText.split(Regex("(?<=[.!?;:])\\s+")).filter { it.isNotBlank() }
        for (sentence in sentences) {
            val tv = TextView(requireContext()).apply {
                text = sentence
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) 
                setTextColor(Color.BLACK) 
                setPadding(4, 8, 4, 8)
                setLineSpacing(0f, 1.2f)

            }
            container.addView(tv)
        }
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
            var typeface: Typeface? = ResourcesCompat.getFont(requireContext(), R.font.dancing_script)
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
            if (state == ModelState.READY && voskModelViewModel.voskModel != null) {
                if (recognizer == null) { 
                    try {
                        voskModelViewModel.voskModel?.let { model ->
                            recognizer = Recognizer(model, sampleRate.toFloat())
                            Log.i(TAG, "Vosk Recognizer initialized for StoryReadingFragment.")
                        } ?: run {
                            Log.e(TAG, "Vosk model is null when state is READY.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize Vosk Recognizer", e)
                        Toast.makeText(requireContext(), "Speech recognizer init failed.", Toast.LENGTH_SHORT).show()
                        recognizer = null 
                    }
                }
            } else { 
                if (recognizer != null) {
                    recognizer?.close()
                    recognizer = null
                    Log.i(TAG, "Vosk Recognizer released.")
                }
            }
            updateUIForVoskModelState(state)
        }
    }

    private fun updateUIForVoskModelState(state: ModelState) {
        if (!isAdded || _binding == null) return
        val modelIsReadyForUse = state == ModelState.READY && voskModelViewModel.voskModel != null && recognizer != null
        binding.buttonRecordStorySentence.isEnabled = modelIsReadyForUse
        binding.buttonRecordStorySentence.alpha = if (modelIsReadyForUse) 1.0f else 0.5f
        if (!modelIsReadyForUse && isRecording) {
            stopRecordingInternal(false) 
        }
    }

    private fun handleRecordAction() {
        if (!isAdded || _binding == null) return
        if (voskModelViewModel.modelState.value != ModelState.READY || voskModelViewModel.voskModel == null || recognizer == null) {
            Toast.makeText(requireContext(), "Speech engine not ready. Please wait.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), "No story text to record.", Toast.LENGTH_SHORT).show()
            return
        }
        pcmFile?.delete()
        wavFileForPlayback?.delete()
        binding.textViewSentenceScore.visibility = View.GONE
        // Clear previous results before starting new recording
        if (isAdded && _binding != null) {
             binding.linearLayoutResultsContainer.removeAllViews()
             // Optionally, re-display initial story if you want it to revert from colored to plain on new record start
             currentStoryContent?.let { displayInitialStoryContent(it, binding.linearLayoutResultsContainer) }
        }
        binding.buttonPlayUserSentenceRecording.isEnabled = false
        binding.buttonPlayUserSentenceRecording.alpha = 0.5f

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            bufferSize = max(minBufferSize, 4096)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
            fileOutputStream = FileOutputStream(pcmFile)
            audioRecord?.startRecording()
            isRecording = true
            updateRecordButtonUI()
            Toast.makeText(requireContext(), "Recording started...", Toast.LENGTH_SHORT).show()
            recordingThread = thread(start = true, name = "StoryAudioProducer") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val shortBuffer = ShortArray(bufferSize)
                while (isRecording) {
                    val readResult = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (readResult > 0) {
                        val byteArray = ByteArray(readResult * 2)
                        for (i in 0 until readResult) {
                            val v = shortBuffer[i].toInt()
                            byteArray[i * 2] = (v and 0xFF).toByte()
                            byteArray[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        try { fileOutputStream?.write(byteArray) } catch (e: IOException) { break }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording setup/start failed", e)
            Toast.makeText(requireContext(), "Recording setup failed.", Toast.LENGTH_SHORT).show()
            isRecording = false
            updateRecordButtonUI()
        }
    }

    private fun stopRecordingInternal(processAudio: Boolean) {
        if (!isRecording && recordingThread == null) return
        isRecording = false 
        updateRecordButtonUI()
        try { recordingThread?.join(500) } catch (e: InterruptedException) { Log.e(TAG, "Recording thread join interrupted", e) }
        recordingThread = null
        audioRecord?.stopSafely()
        audioRecord?.releaseSafely()
        audioRecord = null
        fileOutputStream?.flushSafely()
        fileOutputStream?.closeSafely()
        fileOutputStream = null
        if (processAudio && pcmFile?.exists() == true && pcmFile!!.length() > 0) {
            Toast.makeText(requireContext(), "Processing...", Toast.LENGTH_SHORT).show()
            startPostProcessingThread(pcmFile!!, targetSentenceForScoring)
        } else if (processAudio) {
            Toast.makeText(requireContext(), "Recording was empty or failed.", Toast.LENGTH_SHORT).show()
            binding.buttonPlayUserSentenceRecording.isEnabled = false
            binding.buttonPlayUserSentenceRecording.alpha = 0.5f
        }
    }

    private fun startPostProcessingThread(currentPcmFile: File, currentTargetStory: String) {
        val currentRecognizer = recognizer
        if (currentRecognizer == null || voskModelViewModel.modelState.value != ModelState.READY) {
            Toast.makeText(requireContext(), "Speech engine not ready for processing.", Toast.LENGTH_SHORT).show()
            return
        }
        thread(start = true, name = "StoryPostProcessThread") {
            var voskJsonResult: String? = null
            var wavCreatedSuccessfully = false
            try {
                FileInputStream(currentPcmFile).use { fis ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int 
                    currentRecognizer.reset() 
                    while (fis.read(buffer).also { bytesRead = it } > 0) {
                        currentRecognizer.acceptWaveForm(buffer, bytesRead)
                    }
                }
                voskJsonResult = currentRecognizer.finalResult
                wavFileForPlayback?.let {
                    try {
                        FileOutputStream(it).use { fosWav ->
                            fosWav.write(createWavHeader(currentPcmFile.length().toInt(), sampleRate, 1, 16))
                            FileInputStream(currentPcmFile).use { fisPcmForWav -> fisPcmForWav.copyTo(fosWav) }
                        }
                        wavCreatedSuccessfully = it.exists() && it.length() > 44
                    } catch (e: Exception) { Log.e(TAG, "Error creating WAV file", e) }
                }
            } catch (e: Exception) { Log.e(TAG, "Error in Vosk processing or WAV creation", e) }

            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                if (voskJsonResult != null) {
                    val scoringResult = PronunciationScorer.scoreSentenceQuick(voskJsonResult, currentTargetStory)
                    binding.textViewSentenceScore.text = "Overall Score: ${scoringResult.overallSentenceScore}"
                    binding.textViewSentenceScore.visibility = View.VISIBLE
                    renderPerSentenceFromOverall(currentTargetStory, scoringResult.evaluations, binding.linearLayoutResultsContainer)
                } else {
                     Toast.makeText(requireContext(), "Could not get speech result.", Toast.LENGTH_SHORT).show()
                     // Optionally, render the original story without scores or with error indication
                     currentStoryContent?.let { displayInitialStoryContent(it, binding.linearLayoutResultsContainer) }
                }
                binding.buttonPlayUserSentenceRecording.isEnabled = wavCreatedSuccessfully
                binding.buttonPlayUserSentenceRecording.alpha = if (wavCreatedSuccessfully) 1.0f else 0.5f
            }
        }
    }
    
    // Helper function: Levenshtein Distance (as used in PronunciationScorer)
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

    private fun normalizeToken(s: String): String = 
        s.lowercase().replace(Regex("[^a-z0-9']"), "")

    private fun findNextTokenPos(sentence: String, tokenToSearch: String, startIndex: Int): Int {
        if (tokenToSearch.isEmpty()) return -1
        val sentenceLower = sentence.lowercase()
        val tokenLower = tokenToSearch.lowercase() // Ensure token for search is also lowercased
        var idx = sentenceLower.indexOf(tokenLower, startIndex)
        while (idx >= 0) {
            val leftOk = idx == 0 || !sentenceLower[idx - 1].isLetterOrDigit()
            val rightPos = idx + tokenLower.length
            val rightOk = rightPos >= sentenceLower.length || !sentenceLower[rightPos].isLetterOrDigit()
            if (leftOk && rightOk) return idx
            idx = sentenceLower.indexOf(tokenLower, idx + 1)
        }
        return -1
    }

    private fun renderPerSentenceFromOverall(
        storyText: String,
        overallEvaluations: List<PronunciationScorer.SentenceWordEvaluation>,
        container: LinearLayout
    ) {
        if (!isAdded || _binding == null) return
        container.removeAllViews()
        val sentences = storyText.split(Regex("(?<=[.!?;:])\\s+")).filter { it.isNotBlank() }
        var evalIdx = 0 // Pointer for overallEvaluations

        for (sentenceText in sentences) {
            val spannable = SpannableString(sentenceText)
            val sentenceDisplayWords = sentenceText.split(Regex("\\s+")).filter { it.isNotBlank() } // Words as they appear in the sentence for display
            
            var currentWordPosInSentence = 0 // Cursor for finding word position in sentenceText
            var sentenceTotalScore = 0
            var sentenceWordCount = 0

            for (displayWord in sentenceDisplayWords) {
                val normalizedDisplayWord = normalizeToken(displayWord)
                if (normalizedDisplayWord.isEmpty()) continue

                val windowSize = 10 
                var bestMatchEvalIndex = -1
                var bestLevDistance = Int.MAX_VALUE
                
                val searchEndIndex = min(evalIdx + windowSize, overallEvaluations.size)

                for (k in evalIdx until searchEndIndex) {
                    val evalWord = overallEvaluations[k]
                    val normalizedEvalWord = normalizeToken(evalWord.targetWord)
                    if (normalizedEvalWord.isEmpty()) continue

                    val distance = levenshtein(normalizedDisplayWord, normalizedEvalWord)
                    if (distance < bestLevDistance) {
                        bestLevDistance = distance
                        bestMatchEvalIndex = k
                    }
                    if (bestLevDistance == 0) break // Perfect match found
                }

                var chosenScore = 0
                val maxAcceptableDistanceRatio = 0.6 

                if (bestMatchEvalIndex != -1) {
                    val matchedEvalWord = overallEvaluations[bestMatchEvalIndex]
                    val normalizedMatchedEvalWord = normalizeToken(matchedEvalWord.targetWord)
                    val currentDistanceRatio = if (max(normalizedDisplayWord.length, normalizedMatchedEvalWord.length) == 0) 0.0 
                                             else bestLevDistance.toDouble() / max(normalizedDisplayWord.length, normalizedMatchedEvalWord.length)

                    if (currentDistanceRatio <= maxAcceptableDistanceRatio) {
                        chosenScore = matchedEvalWord.score
                        evalIdx = bestMatchEvalIndex + 1 // Advance pointer
                    } else {
                        // No good match within ratio, treat as 0, don't advance evalIdx aggressively
                        // to allow next display word to match current/next eval item.
                    }
                } else {
                     // No match in window
                }

                sentenceTotalScore += chosenScore
                sentenceWordCount++

                // Find position of the original displayWord (with punctuation) to apply span
                val tokenActualPos = findNextTokenPos(sentenceText, displayWord, currentWordPosInSentence)
                if (tokenActualPos >= 0) {
                    val color = if (chosenScore >= 70) Color.GREEN else if (chosenScore > 0) Color.rgb(255,165,0) else Color.RED
                    try {
                        spannable.setSpan(
                            ForegroundColorSpan(color),
                            tokenActualPos,
                            (tokenActualPos + displayWord.length).coerceAtMost(sentenceText.length),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error applying span to '$displayWord' in '$sentenceText'", e)
                    }
                    currentWordPosInSentence = tokenActualPos + displayWord.length
                }
            }

            var avgSentenceScore = if (sentenceWordCount > 0) sentenceTotalScore / sentenceWordCount else 0
            
            val sentenceTv = TextView(requireContext()).apply {
                text = spannable
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) // Match initial display size
                setLineSpacing(0f, 1.2f)
                setLineSpacing(0f, 1.2f)
            }
            container.addView(sentenceTv)

            // Optional: add small score label for the sentence
            val scoreTv = TextView(requireContext()).apply {
                text = "Sentence Score: $avgSentenceScore"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.DKGRAY)
                setPadding(4, 0, 4, 16) // More bottom padding for separation
            }
            container.addView(scoreTv)
        }
    }

    private fun playUserRecording() {
        if (wavFileForPlayback?.exists() != true || wavFileForPlayback!!.length() == 0L) {
            Toast.makeText(requireContext(), "No recording available.", Toast.LENGTH_SHORT).show()
            return
        }
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(wavFileForPlayback!!.absolutePath)
                prepareAsync()
                setOnPreparedListener { mp -> mp.start() }
                setOnCompletionListener { mp -> 
                    mp.release()
                    mediaPlayer = null
                    Toast.makeText(context, "Playback finished.", Toast.LENGTH_SHORT).show()
                }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(context, "Error playing recording.", Toast.LENGTH_SHORT).show()
                    mediaPlayer?.release()
                    mediaPlayer = null
                    true
                }
                Toast.makeText(context, "Playing recording...", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Log.e(TAG, "MediaPlayer setup failed", e)
                Toast.makeText(context, "Cannot play recording.", Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun AudioRecord.stopSafely() { try { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop() } catch (e: IllegalStateException) { Log.e(TAG, "AudioRecord.stop() failed safely", e) } }
    private fun AudioRecord.releaseSafely() { try { release() } catch (e: Exception) { Log.e(TAG, "AudioRecord.release() failed safely", e) } }
    private fun FileOutputStream.flushSafely() { try { flush() } catch (e: IOException) { Log.e(TAG, "FileOutputStream.flush() failed safely", e) } }
    private fun FileOutputStream.closeSafely() { try { close() } catch (e: IOException) { Log.e(TAG, "FileOutputStream.close() failed safely", e) } }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isRecording) {
            stopRecordingInternal(false) 
        }
        recognizer?.close() 
        recognizer = null
        mediaPlayer?.release()
        mediaPlayer = null
        (activity as? AppCompatActivity)?.setSupportActionBar(null)
        _binding = null
        Log.d(TAG, "StoryReadingFragment onDestroyView")
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
