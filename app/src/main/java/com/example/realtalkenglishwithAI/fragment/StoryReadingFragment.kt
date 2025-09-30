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

    private var recognizer: Recognizer? = null
    private var targetSentenceForScoring: String = "" // Should be the full story content

    private var mediaPlayer: MediaPlayer? = null

    // --- Colors ---
    private var colorDefaultText: Int = Color.BLACK
    private var colorTemporaryHighlight: Int = Color.LTGRAY
    private var colorCorrectWord: Int = Color.GREEN
    private var colorIncorrectWord: Int = Color.RED

    // --- Word and Sentence Tracking ---
    private data class WordInfo(
        val originalText: String,
        val normalizedText: String,
        val sentenceIndex: Int,
        val wordIndexInSentence: Int, // Index of this word within its sentence
        val startCharInSentence: Int, // Start char index of this word in its original sentence text
        val endCharInSentence: Int,   // End char index of this word in its original sentence text
        var currentSpan: ForegroundColorSpan? = null // To manage removing specific spans if needed
    )

    private val sentenceTextViews = mutableListOf<TextView>()
    private val allWordInfosInStory = mutableListOf<WordInfo>()
    private var currentHighlightedWordIndex = -1 // Tracks the last word highlighted by partial result


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

        // Initialize colors from resources
        colorDefaultText = ContextCompat.getColor(requireContext(), android.R.color.black) // Or your specific default text color
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
        Log.d(TAG, "Story Title: $currentStoryTitle, Story Content: $currentStoryContent")
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
        var globalWordCounter = 0
        sentences.forEachIndexed { sentenceIdx, sentenceStr ->
            val tv = TextView(requireContext()).apply {
                text = SpannableString(sentenceStr) // Use SpannableString from the start
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
                        currentWordStartChar = originalWordEnd // Important: Update for next search in sentence
                        globalWordCounter++
                    } else {
                        Log.w(TAG, "Word '$word' not found in sentence '$sentenceStr' starting from char $currentWordStartChar")
                    }
                }
            }
        }
    }

    private fun clearAllSpansFromTextViews() {
        if (!isAdded) return
        sentenceTextViews.forEach { tv ->
            val spannable = tv.text as? SpannableString ?: SpannableString(tv.text)
            val spans = spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java)
            spans.forEach { spannable.removeSpan(it) }
            tv.text = spannable // Re-assign to reflect span removal
            tv.setTextColor(colorDefaultText) // Ensure default color if spans are just removed
        }
        allWordInfosInStory.forEach { it.currentSpan = null }
    }

    private fun updateWordSpan(wordInfo: WordInfo, color: Int) {
        if (!isAdded || wordInfo.sentenceIndex >= sentenceTextViews.size) return
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
            Log.e(TAG, "Error applying span to '${wordInfo.originalText}' at [${wordInfo.startCharInSentence}-${wordInfo.endCharInSentence}] in sentence ${wordInfo.sentenceIndex}", e)
        }
    }

    private fun applyTemporaryHighlights(partialTranscript: String) {
        if (!isAdded || _binding == null) return

        val partialWords = partialTranscript.split(Regex("\\s+")).map { normalizeToken(it) }.filter { it.isNotEmpty() }
        
        // Clear previous temporary highlights before applying new ones
        for (i in 0..currentHighlightedWordIndex) {
            if (i < allWordInfosInStory.size) {
                updateWordSpan(allWordInfosInStory[i], colorDefaultText) // Reset to default
            }
        }
        // If partial is empty, reset all and return
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
                // If there's a mismatch, stop highlighting further for this partial result.
                // We only highlight a continuous sequence from the beginning of the story.
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
            if (state == ModelState.READY && voskModelViewModel.voskModel != null) {
                if (recognizer == null) {
                    try {
                        voskModelViewModel.voskModel?.let {
                            // val grammar = "[\"one two three four five six seven eight nine ten\", \"hello world\"]"
                            // recognizer = Recognizer(it, sampleRate.toFloat(), grammar)
                            recognizer = Recognizer(it, sampleRate.toFloat())
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
        binding.textViewPartialTranscript.text = "Đang nghe..."
        binding.textViewPartialTranscript.visibility = View.VISIBLE

        clearAllSpansFromTextViews() // Reset all words to default color
        currentHighlightedWordIndex = -1 // Reset highlight tracking
        
        binding.buttonPlayUserSentenceRecording.isEnabled = false
        binding.buttonPlayUserSentenceRecording.alpha = 0.5f
        recognizer?.reset() 

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
                val audioBuffer = ByteArray(bufferSize) 
                while (isRecording) {
                    val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readResult > 0) {
                        try {
                            fileOutputStream?.write(audioBuffer, 0, readResult)
                            if (recognizer?.acceptWaveForm(audioBuffer, readResult) == true) {
                                val partialJson = recognizer?.partialResult
                                try {
                                    val jsonObject = JSONObject(partialJson)
                                    val partialText = jsonObject.optString("partial", "")
                                    activity?.runOnUiThread {
                                        if (isAdded && _binding != null) {
                                            binding.textViewPartialTranscript.text = partialText
                                            applyTemporaryHighlights(partialText) 
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing partial JSON: $partialJson", e)
                                }
                            } 
                        } catch (e: IOException) {
                            Log.e(TAG, "Error writing to PCM or processing partial result", e)
                            break 
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording setup/start failed", e)
            Toast.makeText(requireContext(), "Recording setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            isRecording = false
            updateRecordButtonUI()
            if(isAdded && _binding != null) {
                binding.textViewPartialTranscript.text = ""
                binding.textViewPartialTranscript.visibility = View.GONE
            }
        }
    }

    private fun stopRecordingInternal(processAudio: Boolean) {
        if (!isRecording && audioRecord == null) return 
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
        
        activity?.runOnUiThread { 
            if(isAdded && _binding != null) {
                binding.textViewPartialTranscript.text = ""
                binding.textViewPartialTranscript.visibility = View.GONE
            }
        }

        if (processAudio && pcmFile?.exists() == true && pcmFile!!.length() > 0) {
            Toast.makeText(requireContext(), "Processing final result...", Toast.LENGTH_SHORT).show()
            startPostProcessingThread(pcmFile!!, targetSentenceForScoring)
        } else if (processAudio) {
            Toast.makeText(requireContext(), "Recording was empty or failed.", Toast.LENGTH_SHORT).show()
            binding.buttonPlayUserSentenceRecording.isEnabled = false
            binding.buttonPlayUserSentenceRecording.alpha = 0.5f
            clearAllSpansFromTextViews() 
        } else {
            clearAllSpansFromTextViews() 
        }
    }

    private fun startPostProcessingThread(currentPcmFile: File, fullStoryText: String) {
        val currentRecognizer = recognizer 
        if (currentRecognizer == null || voskModelViewModel.modelState.value != ModelState.READY) {
            Toast.makeText(requireContext(), "Speech engine not ready for final processing.", Toast.LENGTH_SHORT).show()
            return
        }
        thread(start = true, name = "StoryFinalProcessThread") {
            var voskJsonResult: String? = null
            var wavCreatedSuccessfully = false
            try {
                voskJsonResult = currentRecognizer.finalResult

                wavFileForPlayback?.let {
                    if (currentPcmFile.exists() && currentPcmFile.length() > 0) {
                        try {
                            FileOutputStream(it).use { fosWav ->
                                fosWav.write(createWavHeader(currentPcmFile.length().toInt(), sampleRate, 1, 16))
                                FileInputStream(currentPcmFile).use { fisPcmForWav -> fisPcmForWav.copyTo(fosWav) }
                            }
                            wavCreatedSuccessfully = it.exists() && it.length() > 44
                        } catch (e: Exception) { Log.e(TAG, "Error creating WAV file from PCM", e) }
                    } else {
                         Log.w(TAG, "PCM file for WAV creation is empty or does not exist.")
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Error in Vosk final processing or WAV creation", e) }

            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                 clearAllSpansFromTextViews() // Clear temporary highlights before applying final colors

                if (voskJsonResult != null) {
                    val scoringResult = PronunciationScorer.scoreSentenceQuick(voskJsonResult, fullStoryText)
                    renderFinalWordColors(scoringResult.evaluations) 
                } else {
                     Toast.makeText(requireContext(), "Could not get final speech result.", Toast.LENGTH_SHORT).show()
                }
                binding.buttonPlayUserSentenceRecording.isEnabled = wavCreatedSuccessfully
                binding.buttonPlayUserSentenceRecording.alpha = if (wavCreatedSuccessfully) 1.0f else 0.5f
            }
        }
    }
    
    private fun renderFinalWordColors(evaluations: List<PronunciationScorer.SentenceWordEvaluation>) {
        if (!isAdded || _binding == null ) return

        var evalIdx = 0
        allWordInfosInStory.forEach { storyWordInfo ->
            var chosenScore = 0 
            var matched = false
            // Attempt to match storyWordInfo with an evaluation word
            if (evalIdx < evaluations.size) {
                val evalWord = evaluations[evalIdx]
                if (storyWordInfo.normalizedText == normalizeToken(evalWord.targetWord)) {
                    chosenScore = evalWord.score
                    evalIdx++
                    matched = true
                } else {
                    // Simple lookahead for insertions/deletions if texts don't match
                    if (evalIdx + 1 < evaluations.size) {
                        val nextEvalWord = evaluations[evalIdx + 1]
                        if (storyWordInfo.normalizedText == normalizeToken(nextEvalWord.targetWord)) {
                            // Current evalWord might be an insertion, skip it and use next one
                            chosenScore = nextEvalWord.score
                            evalIdx += 2
                            matched = true
                        }
                    }
                }
            }
            // If not matched, it remains score 0 (incorrect)
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

    private fun normalizeToken(s: String): String = 
        s.lowercase().replace(Regex("[^a-z0-9']"), "")

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

    private fun AudioRecord.stopSafely() { try { if (state == AudioRecord.STATE_INITIALIZED && recordingState == AudioRecord.RECORDSTATE_RECORDING) stop() } catch (e: IllegalStateException) { Log.e(TAG, "AudioRecord.stop() failed safely", e) } }
    private fun AudioRecord.releaseSafely() { try { if (state == AudioRecord.STATE_INITIALIZED) release() } catch (e: Exception) { Log.e(TAG, "AudioRecord.release() failed safely", e) } }
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
