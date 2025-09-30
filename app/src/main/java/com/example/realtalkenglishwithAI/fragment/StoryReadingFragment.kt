package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import org.vosk.Model
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

    // REMOVED: private var currentSessionRecognizer: Recognizer? = null
    private var targetSentenceForScoring: String = ""

    private var currentAudioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

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

        setHasOptionsMenu(true)

        // Ensure context is available
        if (isAdded) {
            colorDefaultText = ContextCompat.getColor(requireContext(), android.R.color.black)
            colorTemporaryHighlight = ContextCompat.getColor(requireContext(), R.color.gray_500)
            colorCorrectWord = ContextCompat.getColor(requireContext(), R.color.blue_500)
            colorIncorrectWord = ContextCompat.getColor(requireContext(), R.color.red_500)
        } else {
            // Fallback colors if context not yet available, though ideally this shouldn't happen here
            colorDefaultText = Color.BLACK
            colorTemporaryHighlight = Color.LTGRAY
            colorCorrectWord = Color.GREEN
            colorIncorrectWord = Color.RED
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoryReadingBinding.inflate(inflater, container, false)
        // Re-initialize colors if they weren't in onCreate (e.g. due to context issues)
        if (colorDefaultText == Color.BLACK && context != null) { // Simple check
            colorDefaultText = ContextCompat.getColor(requireContext(), android.R.color.black)
            colorTemporaryHighlight = ContextCompat.getColor(requireContext(), R.color.gray_500)
            colorCorrectWord = ContextCompat.getColor(requireContext(), R.color.blue_500)
            colorIncorrectWord = ContextCompat.getColor(requireContext(), R.color.red_500)
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
                tv.text = spannable // Apply the modified spannable
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
                wordInfo.endCharInSentence.coerceAtMost(spannable.length), // Ensure end is within bounds
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            wordInfo.currentSpan = newSpan
            tv.text = spannable // Apply the modified spannable
        } catch (e: Exception) {
            Log.e(TAG, "Error applying span to \'${wordInfo.originalText}\' at [${wordInfo.startCharInSentence}-${wordInfo.endCharInSentence}] in sentence of length ${spannable.length}", e)
        }
    }


    private fun applyTemporaryHighlights(partialTranscript: String) {
        if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

        val partialWords = partialTranscript.split(Regex("\\s+")).map { normalizeToken(it) }.filter { it.isNotEmpty() }

        // Reset previously highlighted words that are no longer in the partial transcript
        for (i in 0..currentHighlightedWordIndex) {
            if (i < allWordInfosInStory.size) {
                 val wordInfo = allWordInfosInStory[i]
                 // Check if this word is still considered highlighted by the new partial or if it should be reset
                 var stillHighlighted = false
                 if (i < partialWords.size) { // Basic check, real logic is below
                      // The main logic below will re-highlight if necessary
                 }
                 if (!stillHighlighted) { // This part might be tricky; simpler to reset all then re-apply
                    // updateWordSpan(wordInfo, colorDefaultText)
                 }
            }
        }
         // Simpler: Clear all temporary highlights first, then re-apply based on current partial
        for(i in 0 until allWordInfosInStory.size){
             if(allWordInfosInStory[i].currentSpan?.foregroundColor == colorTemporaryHighlight || allWordInfosInStory[i].currentSpan?.foregroundColor == colorDefaultText ) { // only reset temp or default ones
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
                // If a word is already marked correct/incorrect, skip it in partial matching
                if (storyWordInfo.currentSpan?.foregroundColor == colorCorrectWord || storyWordInfo.currentSpan?.foregroundColor == colorIncorrectWord) {
                    storyWordIdx++
                    continue
                }
                break // Mismatch for a non-finalized word
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
            updateUIForVoskModelState(state)
        }
    }

    private fun updateUIForVoskModelState(state: ModelState) {
        if (!isAdded || _binding == null) return
        val modelIsReadyForUse = state == ModelState.READY && voskModelViewModel.voskModel != null
        binding.buttonRecordStorySentence.isEnabled = modelIsReadyForUse
        binding.buttonRecordStorySentence.alpha = if (modelIsReadyForUse) 1.0f else 0.5f
        if (!modelIsReadyForUse && isRecording) {
            stopRecordingInternal(false) // Stop recording if model becomes not ready
        }
    }

    private fun handleRecordAction() {
        if (!isAdded || _binding == null) return
        if (voskModelViewModel.modelState.value != ModelState.READY || voskModelViewModel.voskModel == null) {
            Toast.makeText(requireContext(), "Speech engine not ready. Please wait or check model.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (isRecording) {
                stopRecordingInternal(true) // Request to stop and process audio
            } else {
                startRecordingInternal()
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // MODIFIED: startRecordingInternal
    private fun startRecordingInternal() {
        if (!isAdded) return
        stopCurrentAudioTrackPlayback() // Stop any ongoing playback

        if (targetSentenceForScoring.isBlank()) {
            Toast.makeText(requireContext(), "No story text to record.", Toast.LENGTH_SHORT).show(); return
        }

        val voskModelInstance = voskModelViewModel.voskModel // Get model from ViewModel
        if (voskModelInstance == null) {
            Toast.makeText(requireContext(), "Speech model not available for producer.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "VoskModel is null when trying to start recording producer.")
            return
        }

        pcmFile?.delete()
        binding.textViewSentenceScore.visibility = View.GONE
        binding.textViewPartialTranscript.text = "Đang nghe..."
        binding.textViewPartialTranscript.visibility = View.VISIBLE

        clearAllSpansFromTextViews()
        currentHighlightedWordIndex = -1

        binding.buttonPlayUserSentenceRecording.isEnabled = false
        binding.buttonPlayUserSentenceRecording.alpha = 0.5f

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            bufferSizeForRecording = max(minBufferSize, 4096) // Ensure a reasonable buffer size
            
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                 Toast.makeText(requireContext(), "Audio permission missing for AudioRecord.", Toast.LENGTH_SHORT).show()
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
            fileOutputStream = FileOutputStream(pcmFile) // pcmFile should be valid here
            audioRecord?.startRecording()
            isRecording = true // Set before starting thread
            updateRecordButtonUI()
            Toast.makeText(requireContext(), "Recording started...", Toast.LENGTH_SHORT).show()

            recordingThread = thread(start = true, name = "StoryAudioProducer") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val audioBuffer = ByteArray(bufferSizeForRecording)
                var localProducerRecognizer: Recognizer? = null

                try {
                    // Create Recognizer owned by this thread
                    localProducerRecognizer = Recognizer(voskModelInstance, sampleRate.toFloat())
                    Log.i(TAG, "Producer thread created its own Recognizer.")

                    while (isRecording) { // isRecording is volatile
                        val currentAudioRecord = audioRecord // Fragment's audioRecord
                        if (currentAudioRecord == null || !isRecording) {
                            Log.d(TAG, "Producer: AudioRecord null or no longer recording, exiting loop.")
                            break
                        }

                        val readResult = try {
                            currentAudioRecord.read(audioBuffer, 0, audioBuffer.size)
                        } catch (e: Exception) {
                            Log.e(TAG, "Producer: AudioRecord.read failed, exiting loop.", e)
                            activity?.runOnUiThread {
                                if (isAdded) Toast.makeText(requireContext(), "Audio read error.", Toast.LENGTH_SHORT).show()
                            }
                            break
                        }

                        if (readResult > 0) {
                            try {
                                fileOutputStream?.write(audioBuffer, 0, readResult)
                            } catch (e: IOException) {
                                Log.e(TAG, "Producer: File write failed, exiting loop.", e)
                                activity?.runOnUiThread {
                                     if (isAdded) Toast.makeText(requireContext(), "File write error.", Toast.LENGTH_SHORT).show()
                                }
                                break
                            }

                            try {
                                val accepted = localProducerRecognizer.acceptWaveForm(audioBuffer, readResult)
                                if (accepted) {
                                    val partialJson = localProducerRecognizer.partialResult
                                    val partialText = JSONObject(partialJson).optString("partial", "")
                                    activity?.runOnUiThread {
                                        if (isAdded && _binding != null && isRecording) {
                                            binding.textViewPartialTranscript.text = partialText
                                            applyTemporaryHighlights(partialText)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Producer: Recognizer.acceptWaveForm or partialResult failed (caught), exiting loop: ${e.message}", e)
                                activity?.runOnUiThread {
                                    if (isAdded) Toast.makeText(requireContext(), "Speech processing error.", Toast.LENGTH_SHORT).show()
                                }
                                break
                            }
                        } else if (readResult < 0) {
                            Log.e(TAG, "Producer: AudioRecord read error code: $readResult, exiting loop.")
                             activity?.runOnUiThread {
                                if (isAdded) Toast.makeText(requireContext(), "Audio read error code: $readResult", Toast.LENGTH_SHORT).show()
                            }
                            break
                        }
                    } // End of while(isRecording)
                } catch (e: Exception) {
                    Log.e(TAG, "Producer: Error during recognizer creation or outer producer loop: ${e.message}", e)
                    activity?.runOnUiThread {
                        if (isAdded) Toast.makeText(requireContext(), "Recorder init error.", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    Log.d(TAG, "Producer thread: Entering finally block.")
                    localProducerRecognizer?.let {
                        try {
                            it.close()
                            Log.i(TAG, "Producer thread successfully closed its local Recognizer.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Producer thread: Failed to close its local Recognizer.", e)
                        }
                    }
                    Log.d(TAG, "StoryAudioProducer thread finished execution.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording setup/start failed", e)
            Toast.makeText(requireContext(), "Recording setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            isRecording = false // Ensure isRecording is reset
            updateRecordButtonUI()
            // Clean up resources if setup failed mid-way
            audioRecord?.release()
            audioRecord = null
            fileOutputStream?.closeSafely()
            fileOutputStream = null
            activity?.runOnUiThread {
                if(isAdded && _binding != null) {
                    binding.textViewPartialTranscript.text = ""
                    binding.textViewPartialTranscript.visibility = View.GONE
                }
            }
        }
    }

    // MODIFIED: stopRecordingInternal
    private fun stopRecordingInternal(processAudio: Boolean) {
        if (!isRecording && audioRecord == null && recordingThread == null && fileOutputStream == null) {
            Log.d(TAG, "stopRecordingInternal called but already stopped or not started properly.")
            // If isRecording is somehow true but other resources are null, still try to reset UI and state.
            if (isRecording) {
                isRecording = false
                updateRecordButtonUI()
                activity?.runOnUiThread {
                    if(isAdded && _binding != null) {
                        binding.textViewPartialTranscript.text = ""
                        binding.textViewPartialTranscript.visibility = View.GONE
                    }
                }
            }
            return
        }
        Log.d(TAG, "stopRecordingInternal - Setting isRecording to false. ProcessAudio: $processAudio")
        isRecording = false // Signal producer thread to stop

        updateRecordButtonUI() // Update UI immediately

        Log.d(TAG, "stopRecordingInternal - Joining recordingThread.")
        try {
            recordingThread?.join(1500) // Wait for producer thread to finish
            if (recordingThread?.isAlive == true) {
                Log.w(TAG, "Recording thread did not finish in time, interrupting.")
                recordingThread?.interrupt()
                recordingThread?.join(500) // Wait a bit more after interrupt
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Recording thread join interrupted", e)
            Thread.currentThread().interrupt() // Restore interrupted status
        }
        recordingThread = null
        Log.d(TAG, "stopRecordingInternal - RecordingThread joined/handled.")

        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) { Log.e(TAG, "AudioRecord stop/release failed", e) }
        }
        audioRecord = null
        Log.d(TAG, "stopRecordingInternal - AudioRecord stopped and released.")

        fileOutputStream?.flushSafely()
        fileOutputStream?.closeSafely()
        fileOutputStream = null
        Log.d(TAG, "stopRecordingInternal - FileOutputStream closed.")

        activity?.runOnUiThread {
            if(isAdded && _binding != null) {
                binding.textViewPartialTranscript.text = ""
                binding.textViewPartialTranscript.visibility = View.GONE
            }
        }

        // The producer thread's Recognizer is self-managed and closed in its finally block.
        // No need to handle a Fragment-level currentSessionRecognizer for the producer here.

        val pcmToProcess = pcmFile // Use a local copy of the File reference
        if (processAudio && pcmToProcess?.exists() == true && pcmToProcess.length() > 0) {
            if (isAdded) Toast.makeText(requireContext(), "Processing final result...", Toast.LENGTH_SHORT).show()
            startPostProcessingThreadUsingNewRecognizer(pcmToProcess, targetSentenceForScoring)
        } else if (processAudio) {
            if (isAdded) Toast.makeText(requireContext(), "Recording was empty or invalid.", Toast.LENGTH_SHORT).show()
            if(isAdded && _binding != null) {
                binding.buttonPlayUserSentenceRecording.isEnabled = false
                binding.buttonPlayUserSentenceRecording.alpha = 0.5f
            }
            clearAllSpansFromTextViews()
        } else {
            Log.d(TAG, "stopRecordingInternal: Not processing audio.")
            // Optionally clear spans if no processing is requested to reset UI state
            clearAllSpansFromTextViews()
        }
        Log.d(TAG, "stopRecordingInternal - Finished.")
    }


    // REMOVED: Old startPostProcessingThread(currentPcmFile: File, fullStoryText: String, recognizerToProcess: Recognizer)

    // ADDED: New post-processing function using its own Recognizer
    private fun startPostProcessingThreadUsingNewRecognizer(audioPcmFile: File, fullStoryText: String) {
        if (!isAdded) return
        val currentModel = voskModelViewModel.voskModel
        if (currentModel == null) {
            Log.e(TAG, "VoskModel is null for post processing. Cannot proceed.")
            activity?.runOnUiThread {
                if (isAdded) Toast.makeText(requireContext(), "Speech model unavailable for processing.", Toast.LENGTH_LONG).show()
                binding.buttonPlayUserSentenceRecording.isEnabled = false // Or based on file existence
                binding.buttonPlayUserSentenceRecording.alpha = 0.5f
                clearAllSpansFromTextViews()
            }
            return
        }

        thread(start = true, name = "StoryFinalProcessThread") {
            var voskJsonResult: String? = null
            var postProcessRecognizer: Recognizer? = null
            val pcmFilePlayable = audioPcmFile.exists() && audioPcmFile.length() > 0

            try {
                postProcessRecognizer = Recognizer(currentModel, sampleRate.toFloat())
                Log.i(TAG, "Post-process thread created its own Recognizer.")

                FileInputStream(audioPcmFile).use { fis ->
                    val buffer = ByteArray(4096) // Standard buffer size for feeding
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } > 0) {
                        try {
                            // Feed audio data in chunks. The recognizer internally buffers.
                           postProcessRecognizer.acceptWaveForm(buffer, bytesRead)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error feeding chunk to post-process recognizer", e)
                            // Depending on the error, you might want to break or continue
                            break // For simplicity, break on error
                        }
                    }
                }
                
                // Optionally feed a little silence (e.g., 300ms) to flush any remaining audio through the decoder
                // This can sometimes help get a more complete final result if the audio stream ended abruptly.
                // val silenceMs = 300
                // val silenceSamples = (sampleRate * silenceMs / 1000)
                // val silenceBytes = ByteArray(silenceSamples * 2) // 2 bytes per sample for PCM_16BIT
                // if (silenceBytes.isNotEmpty()) {
                //    postProcessRecognizer.acceptWaveForm(silenceBytes, silenceBytes.size)
                // }

                voskJsonResult = try {
                    postProcessRecognizer.finalResult // Get the final recognition result
                } catch (e: Exception) {
                    Log.e(TAG, "finalResult failed in post-process thread", e)
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Post-processing thread failed (e.g., Recognizer creation or file read)", e)
            } finally {
                postProcessRecognizer?.let {
                    try {
                        it.close()
                        Log.i(TAG, "Post-process thread successfully closed its Recognizer.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to close post-process recognizer", e)
                    }
                }
            }

            activity?.runOnUiThread {
                if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@runOnUiThread
                clearAllSpansFromTextViews() // Clear previous highlights before applying new ones

                if (voskJsonResult != null) {
                    Log.d(TAG, "Vosk Final Result (Post-Processed): $voskJsonResult")
                    val scoringResult = PronunciationScorer.scoreSentenceQuick(voskJsonResult, fullStoryText)
                    renderFinalWordColors(scoringResult.evaluations)
                } else {
                     if (isAdded) Toast.makeText(requireContext(), "Could not get final speech result.", Toast.LENGTH_SHORT).show()
                     Log.e(TAG, "Vosk Final Result was null after post-processing.")
                }
                if (isAdded && _binding != null) { // Check binding again
                    binding.buttonPlayUserSentenceRecording.isEnabled = pcmFilePlayable
                    binding.buttonPlayUserSentenceRecording.alpha = if (pcmFilePlayable) 1.0f else 0.5f
                }
            }
        }
    }


    private fun renderFinalWordColors(evaluations: List<PronunciationScorer.SentenceWordEvaluation>) {
        if (!isAdded || _binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return

        var evalIdx = 0
        allWordInfosInStory.forEach { storyWordInfo ->
            var chosenScore = 0 // Default to incorrect/not found
            if (evalIdx < evaluations.size) {
                val evalWord = evaluations[evalIdx]
                // Try to match current story word with current evaluation word
                if (storyWordInfo.normalizedText == normalizeToken(evalWord.targetWord) ||
                    levenshtein(storyWordInfo.normalizedText, normalizeToken(evalWord.targetWord)) <= 1) { // Allow minor variations
                    chosenScore = evalWord.score
                    evalIdx++ // Consume this evaluation
                } else {
                    // Heuristic: If current story word doesn't match, see if the NEXT evaluation word matches
                    // This can help skip over insertions by the user or misrecognized words.
                    if (evalIdx + 1 < evaluations.size) {
                        val nextEvalWord = evaluations[evalIdx + 1]
                        if (storyWordInfo.normalizedText == normalizeToken(nextEvalWord.targetWord) ||
                            levenshtein(storyWordInfo.normalizedText, normalizeToken(nextEvalWord.targetWord)) <= 1) {
                            // Match with next, assume current evalWord was an insertion/error by user
                            // chosenScore remains 0 for the current storyWordInfo (or handle as needed)
                            // We don't advance evalIdx here for storyWordInfo, but effectively skip evalWord[evalIdx]
                            // This part is tricky. A simpler approach might be to just take the score if it matches, otherwise 0.
                            // The current logic tries a limited lookahead.
                        }
                    }
                     // If no match, chosenScore remains 0, evalIdx does not advance for THIS story word based on THIS evalWord.
                     // The storyWord will be marked incorrect, and we'll try to match the *next* storyWord with the *current* evalWord.
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
        if (!isAdded) return
        stopCurrentAudioTrackPlayback() // Stop any previous playback

        val currentPcmFile = pcmFile
        if (currentPcmFile?.exists() != true || currentPcmFile.length() == 0L) {
            Toast.makeText(requireContext(), "No recording available to play.", Toast.LENGTH_SHORT).show()
            return
        }
        playPcmWithAudioTrack(currentPcmFile)
    }

    private fun stopCurrentAudioTrackPlayback(){
        playbackThread?.interrupt()
        try {
            playbackThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Interrupted while joining playback thread")
        }
        playbackThread = null

        currentAudioTrack?.let {
            try {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
                Log.d(TAG, "AudioTrack stopped and released.")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error stopping/releasing AudioTrack: ${e.message}")
            }
        }
        currentAudioTrack = null
    }

    private fun playPcmWithAudioTrack(audioPcmFile: File) {
        if (!isAdded) return
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
            Log.e(TAG, "AudioTrack.getMinBufferSize failed")
            Toast.makeText(requireContext(), "AudioTrack parameter error.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            currentAudioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                minBufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (currentAudioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized")
                Toast.makeText(requireContext(), "AudioTrack initialization failed.", Toast.LENGTH_SHORT).show()
                currentAudioTrack?.release() // Release if not initialized
                currentAudioTrack = null
                return
            }

            currentAudioTrack?.play()
            Toast.makeText(requireContext(), "Playing recording...", Toast.LENGTH_SHORT).show()
            binding.buttonPlayUserSentenceRecording.isEnabled = false // Disable play button during playback

            playbackThread = thread(start = true, name = "PcmPlaybackThread") {
                var bytesReadTotal = 0L
                try {
                    FileInputStream(audioPcmFile).use { fis ->
                        val buffer = ByteArray(minBufferSize)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1 && (currentAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING && !Thread.currentThread().isInterrupted)) {
                            if (bytesRead > 0) {
                                currentAudioTrack?.write(buffer, 0, bytesRead)
                                bytesReadTotal += bytesRead
                            }
                        }
                    }
                    if (Thread.currentThread().isInterrupted) {
                        Log.d(TAG, "PCM playback interrupted. Bytes written: $bytesReadTotal")
                    } else {
                        Log.d(TAG, "PCM playback finished. Bytes written: $bytesReadTotal")
                         activity?.runOnUiThread {
                            if(isAdded) Toast.makeText(requireContext(), "Playback finished.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "IOException during PCM playback", e)
                    activity?.runOnUiThread {
                        if(isAdded) Toast.makeText(requireContext(), "Error playing recording.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: IllegalStateException) { // Catch if AudioTrack is in a bad state (e.g., released)
                     Log.e(TAG, "IllegalStateException during PCM playback (AudioTrack might have been released)", e)
                     activity?.runOnUiThread {
                        if(isAdded) Toast.makeText(requireContext(), "Playback stopped abruptly.", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    activity?.runOnUiThread {
                        if(isAdded && _binding != null) { // Ensure binding is still valid
                           binding.buttonPlayUserSentenceRecording.isEnabled = true // Re-enable play button
                        }
                        // Ensure stopCurrentAudioTrackPlayback is called to clean up,
                        // but be careful not to call it if it's already being called from there.
                        // The current structure seems okay as stopCurrentAudioTrackPlayback sets currentAudioTrack to null.
                         if (currentAudioTrack != null) { // Only call if not already cleaned up
                            stopCurrentAudioTrackPlayback()
                         }
                    }
                }
            }
        } catch (e: Exception) { // Catch other exceptions during AudioTrack setup
            Log.e(TAG, "AudioTrack setup failed", e)
            Toast.makeText(requireContext(), "Audio playback setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            currentAudioTrack?.release()
            currentAudioTrack = null
        }
    }

    private fun updateRecordButtonUI() {
        if (!isAdded || _binding == null) return
        val icon = if (isRecording) R.drawable.ic_close else R.drawable.ic_mic
        binding.buttonRecordStorySentence.setImageResource(icon)
        // You might want to use a different visual state for "activated" e.g. selector drawable
        // binding.buttonRecordStorySentence.isActivated = isRecording
    }


    private fun FileOutputStream.flushSafely() { try { flush() } catch (e: IOException) { Log.e(TAG, "FileOutputStream.flush() failed safely", e) } }
    private fun FileOutputStream.closeSafely() { try { close() } catch (e: IOException) { Log.e(TAG, "FileOutputStream.close() failed safely", e) } }

    // MODIFIED: onDestroyView
    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView - Checking if recording is active.")

        if (isRecording) { // isRecording is volatile
            Log.d(TAG, "onDestroyView - Recording active, calling stopRecordingInternal(false).")
            // This will set isRecording = false, join the thread (which closes its own recognizer),
            // and release AudioRecord & FileOutputStream.
            stopRecordingInternal(false)
        } else {
            Log.d(TAG, "onDestroyView - Recording was not active.")
            // If not recording, but resources might still be held from a failed start, try to clean them.
            // This is more of a safeguard. Normal flow should clean these up.
            audioRecord?.let {
                try { if (it.state == AudioRecord.STATE_INITIALIZED) it.release() } catch (e: Exception) { Log.e(TAG, "onDestroyView: Error releasing audioRecord leftover.", e)}
                audioRecord = null
            }
            fileOutputStream?.closeSafely()
            fileOutputStream = null
        }

        // The producer's Recognizer is self-managed.
        // The Fragment-level currentSessionRecognizer variable has been removed.

        Log.d(TAG, "onDestroyView - Releasing AudioTrack (playback).")
        stopCurrentAudioTrackPlayback() // Handles playback resources

        // (activity as? AppCompatActivity)?.setSupportActionBar(null) // Toolbar is usually handled by NavController/Fragment lifecycle
        _binding = null // Crucial to prevent memory leaks
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
