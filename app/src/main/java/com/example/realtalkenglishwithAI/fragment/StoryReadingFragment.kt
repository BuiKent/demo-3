package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
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
    private var fileOutputStream: FileOutputStream? = null

    private val sampleRate = 16000
    private var bufferSizeForRecording: Int = 0

    private var targetSentenceForScoring: String = ""

    private var currentAudioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    // Colors for text spans
    private var colorDefaultText: Int = Color.BLACK // Fallback
    private var colorTemporaryHighlight: Int = Color.LTGRAY // Fallback
    private var colorCorrectWord: Int = Color.GREEN // Fallback
    private var colorIncorrectWord: Int = Color.RED // Fallback

    // Colors for FABs - to be initialized in onViewCreated or onCreate
    private var fabWhiteColor: Int = Color.WHITE
    private var fabGrayColor: Int = Color.GRAY
    private var fabBlackColor: Int = Color.BLACK
    private var fabRedColor: Int = Color.RED


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
        
        var defaultTitle = "Test Scroll Story"
        var defaultContent = "The old house stood on a hill overlooking the village. It had been empty for years, and the locals whispered stories about strange noises and flickering lights. One night, a group of curious teenagers decided to explore it. Armed with flashlights, they crept through the creaking front door. Cobwebs hung like ancient curtains, and dust lay thick on every surface. A grand staircase dominated the hallway, its wooden steps groaning under their weight. As they ventured deeper, a sudden gust of wind slammed a distant door shut, making them jump. A faint melody, like a forgotten lullaby, seemed to drift from an upstairs room. Heartbeats quickened, and nervous glances were exchanged. Was it just the wind, or was there truly something else within those decaying walls? They cautiously ascended the stairs, drawn by an irresistible curiosity. The air grew colder with each step. Reaching the landing, they noticed a single door slightly ajar at the end of the corridor. Light spilled from the crack, pulsating softly. Taking a deep breath, the bravest of the group pushed it open. This addition should make the text long enough to properly test the scrolling feature and ensure the control buttons remain fixed at the bottom of the screen. Let's add a few more sentences to be absolutely sure. The room beyond was surprisingly ordinary, albeit dusty. An old rocking chair sat in one corner, gently swaying as if recently vacated. A small, leather-bound diary lay open on a nearby table, its pages filled with elegant, faded script. The teenagers gathered around, their earlier fear now replaced by intrigue. What secrets did this forgotten place hold?"

        arguments?.let {
            currentStoryContent = it.getString(ARG_STORY_CONTENT, defaultContent)
            currentStoryTitle = it.getString(ARG_STORY_TITLE, defaultTitle)
        }

        // --- TEMPORARY OVERRIDE FOR SCROLL TESTING --- 
        currentStoryTitle = defaultTitle
        currentStoryContent = defaultContent
        // --- END TEMPORARY OVERRIDE --- 

        targetSentenceForScoring = currentStoryContent ?: ""

        val pcmPath = "${requireContext().externalCacheDir?.absolutePath}/story_sentence_audio.pcm"
        pcmFile = File(pcmPath)
        setHasOptionsMenu(true)

        context?.let { ctx ->
            colorDefaultText = ContextCompat.getColor(ctx, android.R.color.black)
            colorTemporaryHighlight = ContextCompat.getColor(ctx, R.color.gray_500)
            colorCorrectWord = ContextCompat.getColor(ctx, R.color.blue_500)
            colorIncorrectWord = ContextCompat.getColor(ctx, R.color.red_500)

            fabWhiteColor = ContextCompat.getColor(ctx, android.R.color.white)
            fabGrayColor = ContextCompat.getColor(ctx, R.color.gray_500)
            fabBlackColor = ContextCompat.getColor(ctx, android.R.color.black)
            fabRedColor = ContextCompat.getColor(ctx, R.color.red_500)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoryReadingBinding.inflate(inflater, container, false)
        if (context != null && colorDefaultText == Color.BLACK) { 
            colorDefaultText = ContextCompat.getColor(requireContext(), android.R.color.black)
            colorTemporaryHighlight = ContextCompat.getColor(requireContext(), R.color.gray_500)
            colorCorrectWord = ContextCompat.getColor(requireContext(), R.color.blue_500)
            colorIncorrectWord = ContextCompat.getColor(requireContext(), R.color.red_500)

            fabWhiteColor = ContextCompat.getColor(requireContext(), android.R.color.white)
            fabGrayColor = ContextCompat.getColor(requireContext(), R.color.gray_500)
            fabBlackColor = ContextCompat.getColor(requireContext(), android.R.color.black)
            fabRedColor = ContextCompat.getColor(requireContext(), R.color.red_500)
        }
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
        if (isAdded && _binding != null) {
             binding.textViewPartialTranscript.visibility = View.GONE
        }
        val modelReady = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
        val canPlayPcm = pcmFile?.exists() == true && pcmFile!!.length() > 0L
        updateFabStates(modelReady, canPlayPcm)
    }

    private fun updateFabStates(modelReady: Boolean, canPlayPcm: Boolean) {
        if (!isAdded || _binding == null) return

        binding.buttonRecordStorySentence.apply {
            setImageResource(R.drawable.ic_mic) // Always ensure mic icon
            if (isRecording) {
                backgroundTintList = ColorStateList.valueOf(fabRedColor)
                imageTintList = ColorStateList.valueOf(fabWhiteColor)
                isEnabled = true
                alpha = 1.0f
            } else {
                isEnabled = modelReady
                alpha = if (modelReady) 1.0f else 0.5f
                if (modelReady) {
                    backgroundTintList = ColorStateList.valueOf(fabWhiteColor)
                    imageTintList = ColorStateList.valueOf(fabBlackColor)
                } else {
                    backgroundTintList = ColorStateList.valueOf(fabWhiteColor)
                    imageTintList = ColorStateList.valueOf(fabGrayColor)
                }
            }
        }

        binding.buttonPlayUserSentenceRecording.apply {
            isEnabled = !isRecording && canPlayPcm
            alpha = if (isEnabled) 1.0f else 0.5f
            if (isEnabled) {
                backgroundTintList = ColorStateList.valueOf(fabWhiteColor)
                imageTintList = ColorStateList.valueOf(fabBlackColor)
            } else {
                backgroundTintList = ColorStateList.valueOf(fabWhiteColor)
                imageTintList = ColorStateList.valueOf(fabGrayColor)
            }
        }
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
                        Log.w(TAG, "Word \'$word\' not found in sentence \'$sentenceStr\' from char $currentWordStartChar")
                    }
                }
            }
        }
    }

    private fun clearAllSpansFromTextViews() {
        if (!isAdded || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        try {
            sentenceTextViews.forEach { tv ->
                val spannable = tv.text as? SpannableString ?: SpannableString(tv.text)
                val spans = spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java)
                spans.forEach { spannable.removeSpan(it) }
                tv.text = spannable
                tv.setTextColor(colorDefaultText)
            }
            allWordInfosInStory.forEach { it.currentSpan = null }
        } catch (e: Exception) {
            Log.e(TAG, "Error in clearAllSpansFromTextViews", e)
        }
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
            Log.e(TAG, "Error applying span to \'${wordInfo.originalText}\' at [${wordInfo.startCharInSentence}-${wordInfo.endCharInSentence}] in sentence of length ${spannable.length}", e)
        }
    }

    private fun applyTemporaryHighlights(partialTranscript: String) {
        if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

        val partialWords = partialTranscript.split(Regex("\\s+")).map { normalizeToken(it) }.filter { it.isNotEmpty() }

        for(i in 0 until allWordInfosInStory.size){
             if(allWordInfosInStory[i].currentSpan?.foregroundColor == colorTemporaryHighlight || allWordInfosInStory[i].currentSpan?.foregroundColor == colorDefaultText ) { 
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
                if (storyWordInfo.currentSpan?.foregroundColor != colorCorrectWord && storyWordInfo.currentSpan?.foregroundColor != colorIncorrectWord) {
                     updateWordSpan(storyWordInfo, colorTemporaryHighlight)
                }
                lastSuccessfullyMatchedStoryWordGlobalIndex = storyWordIdx
                partialWordIdx++
            } else {
                if (storyWordInfo.currentSpan?.foregroundColor == colorCorrectWord || storyWordInfo.currentSpan?.foregroundColor == colorIncorrectWord) {
                    storyWordIdx++
                    continue
                }
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
            val typeface: Typeface? = if (isAdded) ResourcesCompat.getFont(requireContext(), R.font.dancing_script) else null
            if (typeface != null) {
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
                if (isAdded) findNavController().navigateUp()
                true
            }
            R.id.action_play_story_audio -> {
                if (isAdded) Toast.makeText(requireContext(), "Play story audio clicked (Not implemented yet)", Toast.LENGTH_SHORT).show()
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
        if (!isAdded) return
        voskModelViewModel.modelState.observe(viewLifecycleOwner) { state ->
            Log.d(TAG, "VoskModelViewModel state changed: $state")
            val modelIsReady = state == ModelState.READY && voskModelViewModel.voskModel != null
            val canPlay = pcmFile?.exists() == true && pcmFile!!.length() > 0L
            updateFabStates(modelIsReady, canPlay)
            
            if (!modelIsReady && isRecording) {
                stopRecordingInternal(false) // Stop recording if model becomes unavailable
            }
        }
    }

    private fun handleRecordAction() {
        if (!isAdded || _binding == null) return
        val modelReady = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
        if (!modelReady) {
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
        if (!isAdded || _binding == null) return
        stopCurrentAudioTrackPlayback()

        if (targetSentenceForScoring.isBlank()) {
            Toast.makeText(requireContext(), "No story text to record.", Toast.LENGTH_SHORT).show(); return
        }

        val voskModelInstance = voskModelViewModel.voskModel
        if (voskModelInstance == null) {
            Toast.makeText(requireContext(), "Speech model not available.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "VoskModel is null when trying to start recording.")
            return
        }

        pcmFile?.delete()
        binding.textViewSentenceScore.visibility = View.GONE
        clearAllSpansFromTextViews()
        currentHighlightedWordIndex = -1

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            bufferSizeForRecording = max(minBufferSize, 4096)
            
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                 Toast.makeText(requireContext(), "Audio permission missing.", Toast.LENGTH_SHORT).show()
                 return
            }
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeForRecording * 2)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                Toast.makeText(requireContext(), "AudioRecord initialization failed.", Toast.LENGTH_SHORT).show()
                audioRecord?.release()
                audioRecord = null
                return
            }
            fileOutputStream = FileOutputStream(pcmFile)
            audioRecord?.startRecording()
            isRecording = true
            Toast.makeText(requireContext(), "Recording started...", Toast.LENGTH_SHORT).show()
            updateFabStates(true, false) // Model is ready, not playing PCM

            recordingThread = thread(start = true, name = "StoryAudioProducer") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val audioBuffer = ByteArray(bufferSizeForRecording)
                var localProducerRecognizer: Recognizer? = null

                try {
                    localProducerRecognizer = Recognizer(voskModelInstance, sampleRate.toFloat())
                    while (isRecording) {
                        val currentAudioRecord = audioRecord
                        if (currentAudioRecord == null || !isRecording) break
                        val readResult = try { currentAudioRecord.read(audioBuffer, 0, audioBuffer.size) } catch (e: Exception) { Log.e(TAG, "Producer: AudioRecord.read failed", e); break }
                        if (readResult > 0) {
                            try { fileOutputStream?.write(audioBuffer, 0, readResult) } catch (e: IOException) { Log.e(TAG, "Producer: File write failed", e); break }
                            try {
                                if (localProducerRecognizer.acceptWaveForm(audioBuffer, readResult)) {
                                    val partialJson = localProducerRecognizer.partialResult
                                    val partialText = JSONObject(partialJson).optString("partial", "")
                                    activity?.runOnUiThread { if (isAdded && _binding != null && isRecording) { applyTemporaryHighlights(partialText) } }
                                }
                            } catch (e: Exception) { Log.e(TAG, "Producer: Recognizer.acceptWaveForm/partialResult failed", e); break }
                        } else if (readResult < 0) { Log.e(TAG, "Producer: AudioRecord read error: $readResult"); break }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Producer: Error in producer loop or recognizer init", e)
                } finally {
                    localProducerRecognizer?.close()
                    Log.d(TAG, "StoryAudioProducer thread finished.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording setup/start failed", e)
            Toast.makeText(requireContext(), "Recording setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            isRecording = false
            audioRecord?.release(); audioRecord = null
            fileOutputStream?.closeSafely(); fileOutputStream = null
            val modelRdy = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
            updateFabStates(modelRdy, false)
        }
    }

    private fun stopRecordingInternal(processAudio: Boolean) {
        if (!isRecording && audioRecord == null && recordingThread == null && fileOutputStream == null) {
            Log.d(TAG, "stopRecordingInternal called but already stopped or not started properly.")
            if (isRecording) isRecording = false // Ensure isRecording is false
            val modelRdy = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
            val canPly = pcmFile?.exists() == true && pcmFile!!.length() > 0L
            updateFabStates(modelRdy, canPly)
            return
        }
        isRecording = false
        val modelReady = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
        updateFabStates(modelReady, pcmFile?.exists() == true && pcmFile!!.length() > 0L) // Update immediately after isRecording changes

        try {
            recordingThread?.join(1500)
            if (recordingThread?.isAlive == true) { recordingThread?.interrupt(); recordingThread?.join(500) }
        } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        recordingThread = null

        audioRecord?.let { try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop(); it.release() } catch (e: Exception) { Log.e(TAG, "AudioRecord stop/release failed", e) } }
        audioRecord = null

        fileOutputStream?.flushSafely()
        fileOutputStream?.closeSafely()
        fileOutputStream = null

        val pcmToProcess = pcmFile
        val pcmFilePlayable = pcmToProcess?.exists() == true && pcmToProcess.length() > 0

        if (processAudio && pcmFilePlayable) {
            if (isAdded) Toast.makeText(requireContext(), "Processing final result...", Toast.LENGTH_SHORT).show()
            startPostProcessingThreadUsingNewRecognizer(pcmToProcess!!, targetSentenceForScoring) // pcmToProcess is not null here
        } else {
            if (processAudio) { // but pcmFilePlayable was false
                if (isAdded) Toast.makeText(requireContext(), "Recording was empty or invalid.", Toast.LENGTH_SHORT).show()
            }
            clearAllSpansFromTextViews()
            activity?.runOnUiThread { 
                 val modelRdyNow = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
                 updateFabStates(modelRdyNow, false)
            }
        }
    }

    private fun startPostProcessingThreadUsingNewRecognizer(audioPcmFile: File, fullStoryText: String) {
        if (!isAdded) return
        val currentModel = voskModelViewModel.voskModel
        if (currentModel == null) {
            Log.e(TAG, "VoskModel is null for post processing.")
            activity?.runOnUiThread {
                if (isAdded) Toast.makeText(requireContext(), "Speech model unavailable for processing.", Toast.LENGTH_LONG).show()
                val modelRdy = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
                updateFabStates(modelRdy, false)
                clearAllSpansFromTextViews()
            }
            return
        }

        thread(start = true, name = "StoryFinalProcessThread") {
            var voskJsonResult: String? = null
            var postProcessRecognizer: Recognizer? = null
            val currentPcmPlayable = audioPcmFile.exists() && audioPcmFile.length() > 0

            try {
                postProcessRecognizer = Recognizer(currentModel, sampleRate.toFloat())
                FileInputStream(audioPcmFile).use { fis ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } > 0) {
                        if (!postProcessRecognizer.acceptWaveForm(buffer, bytesRead)) break 
                    }
                }
                voskJsonResult = postProcessRecognizer.finalResult
            } catch (e: Exception) {
                Log.e(TAG, "Post-processing thread failed", e)
            } finally {
                postProcessRecognizer?.close()
            }

            activity?.runOnUiThread {
                if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@runOnUiThread
                clearAllSpansFromTextViews()
                val modelRdy = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null

                if (voskJsonResult != null) {
                    Log.d(TAG, "Vosk Final Result (Post-Processed): $voskJsonResult")
                    val scoringResult = PronunciationScorer.scoreSentenceQuick(voskJsonResult, fullStoryText)
                    renderFinalWordColors(scoringResult.evaluations)
                } else {
                     if (isAdded) Toast.makeText(requireContext(), "Could not get final speech result.", Toast.LENGTH_SHORT).show()
                }
                updateFabStates(modelRdy, currentPcmPlayable)
            }
        }
    }

    private fun renderFinalWordColors(evaluations: List<PronunciationScorer.SentenceWordEvaluation>) {
        if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

        var evalIdx = 0
        allWordInfosInStory.forEach { storyWordInfo ->
            var chosenScore = 0
            if (evalIdx < evaluations.size) {
                val evalWord = evaluations[evalIdx]
                if (storyWordInfo.normalizedText == normalizeToken(evalWord.targetWord) ||
                    levenshtein(storyWordInfo.normalizedText, normalizeToken(evalWord.targetWord)) <= 1) {
                    chosenScore = evalWord.score
                    evalIdx++
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

    private fun normalizeToken(s: String): String = s.lowercase().replace(Regex("[^a-z0-9\']"), "")

    private fun playUserRecording() {
        if (!isAdded || _binding == null) return
        stopCurrentAudioTrackPlayback() 

        val currentPcmFile = pcmFile
        if (currentPcmFile?.exists() != true || currentPcmFile.length() == 0L) {
            Toast.makeText(requireContext(), "No recording available to play.", Toast.LENGTH_SHORT).show()
            return
        }
        playPcmWithAudioTrack(currentPcmFile)
    }

    private fun stopCurrentAudioTrackPlayback(){
        playbackThread?.interrupt()
        try { playbackThread?.join(500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        playbackThread = null

        currentAudioTrack?.let {
            try { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop(); it.release() }
            catch (e: IllegalStateException) { Log.e(TAG, "Error stopping/releasing AudioTrack: ${e.message}") }
        }
        currentAudioTrack = null
    }

    private fun playPcmWithAudioTrack(audioPcmFile: File) {
        if (!isAdded || _binding == null) return
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize <= 0) { Log.e(TAG, "AudioTrack.getMinBufferSize failed: $minBufferSize"); return }

        try {
            currentAudioTrack = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                minBufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (currentAudioTrack?.state != AudioTrack.STATE_INITIALIZED) { Log.e(TAG, "AudioTrack not initialized"); currentAudioTrack?.release(); currentAudioTrack = null; return }

            currentAudioTrack?.play()
            Toast.makeText(requireContext(), "Playing recording...", Toast.LENGTH_SHORT).show()
            
            // Update FAB state for playback
            val modelRdy = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
            // Temporarily disable play button during playback by setting canPlayPcm to false in updateFabStates
            updateFabStates(modelRdy, false) // isRecording is false here
            binding.buttonPlayUserSentenceRecording.alpha = 0.5f // Explicitly dim if needed

            playbackThread = thread(start = true, name = "PcmPlaybackThread") {
                try {
                    FileInputStream(audioPcmFile).use { fis ->
                        val buffer = ByteArray(minBufferSize)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1 && currentAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING && !Thread.currentThread().isInterrupted) {
                            if (bytesRead > 0) currentAudioTrack?.write(buffer, 0, bytesRead)
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "Exception during PCM playback", e) }
                finally {
                    activity?.runOnUiThread {
                        if (isAdded) Toast.makeText(requireContext(), "Playback finished.", Toast.LENGTH_SHORT).show()
                        stopCurrentAudioTrackPlayback() 
                        val finalModelRdy = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
                        val canPly = pcmFile?.exists() == true && pcmFile!!.length() > 0L
                        updateFabStates(finalModelRdy, canPly)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack setup failed", e)
            currentAudioTrack?.release(); currentAudioTrack = null
             val modelRdy = voskModelViewModel.modelState.value == ModelState.READY && voskModelViewModel.voskModel != null
             val canPly = pcmFile?.exists() == true && pcmFile!!.length() > 0L
             updateFabStates(modelRdy, canPly)
        }
    }

    private fun FileOutputStream.flushSafely() { try { flush() } catch (e: IOException) { Log.e(TAG, "FileOutputStream.flush() failed safely", e) } }
    private fun FileOutputStream.closeSafely() { try { close() } catch (e: IOException) { Log.e(TAG, "FileOutputStream.close() failed safely", e) } }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isRecording) stopRecordingInternal(false)
        else {
            audioRecord?.release(); audioRecord = null
            fileOutputStream?.closeSafely(); fileOutputStream = null
        }
        stopCurrentAudioTrackPlayback()
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
