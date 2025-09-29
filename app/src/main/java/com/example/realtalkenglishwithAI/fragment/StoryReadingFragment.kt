package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.* // Added for Menu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity // Added for Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController // Added for Toolbar back navigation
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

class StoryReadingFragment : Fragment() {

    private var _binding: FragmentStoryReadingBinding? = null
    private val binding get() = _binding!!

    private val voskModelViewModel: VoskModelViewModel by activityViewModels()

    private var currentStoryContent: String? = null
    private var currentStoryTitle: String? = null

    // Audio recording variables
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

        setHasOptionsMenu(true) // Indicate that this fragment has an options menu for the toolbar
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

        setupToolbar() // Call Toolbar setup

        currentStoryContent?.let {
            binding.textViewStoryContent.text = it
        }
        Log.d(TAG, "Story Title: $currentStoryTitle, Story Content: $currentStoryContent")

        setupClickListeners()
        observeVoskModelStatus()
    }

    private fun setupToolbar() {
        if (activity is AppCompatActivity) {
            (activity as AppCompatActivity).setSupportActionBar(binding.toolbarStoryReading)
        }
        (activity as? AppCompatActivity)?.supportActionBar?.title = currentStoryTitle ?: "Story" // Set title
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true) // Show back button
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayShowHomeEnabled(true) // Ensure back button is shown
        // TODO: Apply Script font to toolbar title if available
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.story_reading_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { // Handle back button press
                findNavController().navigateUp()
                true
            }
            R.id.action_play_story_audio -> {
                Toast.makeText(requireContext(), "Play story audio clicked (Not implemented yet)", Toast.LENGTH_SHORT).show()
                // TODO: Implement actual audio playback of the story if needed
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
                            Log.e(TAG, "Vosk model is null when state is READY and model was checked (should not happen).")
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
                    Log.i(TAG, "Vosk Recognizer released due to model state change (not READY or model became null).")
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
        
        if (!modelIsReadyForUse) {
            if (isRecording) {
                stopRecordingInternal(false) 
            }
        }
    }

    private fun handleRecordAction() {
        if (!isAdded || _binding == null) return

        if (voskModelViewModel.modelState.value != ModelState.READY || voskModelViewModel.voskModel == null || recognizer == null) {
            Toast.makeText(requireContext(), "Speech engine not ready. Please wait.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Record action when Vosk model or recognizer not ready.")
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
        binding.textViewStoryContent.text = currentStoryContent 
        binding.buttonPlayUserSentenceRecording.isEnabled = false
        binding.buttonPlayUserSentenceRecording.alpha = 0.5f

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "Invalid minBufferSize: $minBufferSize")
                Toast.makeText(requireContext(), "Audio recording setup error.", Toast.LENGTH_SHORT).show()
                return
            }
            bufferSize = max(minBufferSize, 4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, 
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            fileOutputStream = FileOutputStream(pcmFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord or FileOutputStream", e)
            Toast.makeText(requireContext(), "Recording setup failed.", Toast.LENGTH_SHORT).show()
            isRecording = false
            updateRecordButtonUI()
            return
        }

        try {
            audioRecord?.startRecording()
            isRecording = true
            updateRecordButtonUI()
            Toast.makeText(requireContext(), "Recording started...", Toast.LENGTH_SHORT).show()

            recordingThread = thread(start = true, name = "StoryAudioProducer") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val shortBuffer = ShortArray(bufferSize)
                Log.d(TAG, "AudioProducerThread started for story. Writing to: ${pcmFile?.absolutePath}")
                while (isRecording) {
                    val readResult = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (readResult > 0) {
                        val byteArray = ByteArray(readResult * 2) 
                        for (i in 0 until readResult) {
                            val v = shortBuffer[i].toInt()
                            byteArray[i * 2] = (v and 0xFF).toByte()
                            byteArray[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        try {
                            fileOutputStream?.write(byteArray)
                        } catch (e: IOException) {
                            Log.e(TAG, "FileOutputStream.write() error in StoryAudioProducer", e)
                            activity?.runOnUiThread {
                                Toast.makeText(requireContext(), "Error writing audio data.", Toast.LENGTH_SHORT).show()
                            }
                            break
                        }
                    }
                }
                Log.d(TAG, "AudioProducerThread finished for story.")
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord.startRecording() failed for story.", e)
            Toast.makeText(requireContext(), "Failed to start recording.", Toast.LENGTH_SHORT).show()
            isRecording = false
            updateRecordButtonUI()
            fileOutputStream?.closeSafely()
            audioRecord?.releaseSafely()
        }
    }

    private fun stopRecordingInternal(processAudio: Boolean) {
        if (!isRecording && recordingThread == null) {
            Log.d(TAG, "stopRecordingInternal called but not actually recording.")
            return
        }
        isRecording = false 
        updateRecordButtonUI()

        recordingThread?.join(500) 
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
            Log.w(TAG, "PCM file for story is missing or empty. Cannot process.")
            Toast.makeText(requireContext(), "Recording was empty or failed.", Toast.LENGTH_SHORT).show()
            binding.buttonPlayUserSentenceRecording.isEnabled = false
            binding.buttonPlayUserSentenceRecording.alpha = 0.5f
        }
    }

    private fun startPostProcessingThread(currentPcmFile: File, currentTargetSentence: String) {
        val currentRecognizer = recognizer
        if (currentRecognizer == null || voskModelViewModel.modelState.value != ModelState.READY) {
            Log.e(TAG, "Vosk recognizer not available for post-processing.")
            Toast.makeText(requireContext(), "Speech engine not ready for processing.", Toast.LENGTH_SHORT).show()
            binding.buttonPlayUserSentenceRecording.isEnabled = false
            binding.buttonPlayUserSentenceRecording.alpha = 0.5f
            return
        }

        thread(start = true, name = "StoryPostProcessThread") {
            var voskJsonResult: String? = null
            var wavCreatedSuccessfully = false
            try {
                Log.d(TAG, "StoryPostProcess: Reading PCM from ${currentPcmFile.absolutePath}")
                FileInputStream(currentPcmFile).use { fis ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int 
                    currentRecognizer.reset() 
                    while (fis.read(buffer).also { bytesRead = it } > 0) {
                        if (currentRecognizer.acceptWaveForm(buffer, bytesRead)) {
                        }
                    }
                }
                voskJsonResult = currentRecognizer.finalResult
                Log.d(TAG, "StoryPostProcess Vosk JSON: $voskJsonResult")

                wavFileForPlayback?.let { wavFile ->
                    try {
                        val dataSize = currentPcmFile.length().toInt()
                        FileOutputStream(wavFile).use { fosWav ->
                            fosWav.write(createWavHeader(dataSize, sampleRate, 1, 16))
                            FileInputStream(currentPcmFile).use { fisPcmForWav -> fisPcmForWav.copyTo(fosWav) }
                        }
                        wavCreatedSuccessfully = wavFile.exists() && wavFile.length() > 44
                        Log.d(TAG, "Story WAV file created: $wavCreatedSuccessfully at ${wavFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating story WAV file", e)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in StoryPostProcessThread", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Error processing audio.", Toast.LENGTH_SHORT).show()
                }
                activity?.runOnUiThread {
                    binding.buttonPlayUserSentenceRecording.isEnabled = false
                    binding.buttonPlayUserSentenceRecording.alpha = 0.5f
                }
                return@thread
            }

            activity?.runOnUiThread {
                if (voskJsonResult != null) {
                    val scoringResult = PronunciationScorer.scoreSentenceQuick(voskJsonResult, currentTargetSentence)
                    displaySentenceEvaluation(scoringResult, currentTargetSentence)
                } else {
                     Toast.makeText(requireContext(), "Could not get speech result.", Toast.LENGTH_SHORT).show()
                     val errorEval = PronunciationScorer.scoreSentenceQuick("", currentTargetSentence)
                     displaySentenceEvaluation(errorEval, currentTargetSentence)
                }
                binding.buttonPlayUserSentenceRecording.isEnabled = wavCreatedSuccessfully
                binding.buttonPlayUserSentenceRecording.alpha = if (wavCreatedSuccessfully) 1.0f else 0.5f
            }
        }
    }

    private fun displaySentenceEvaluation(result: PronunciationScorer.SentenceOverallResult, originalSentence: String) {
        if (!isAdded || _binding == null) return

        binding.textViewSentenceScore.visibility = View.VISIBLE
        binding.textViewSentenceScore.text = "Score: ${result.overallSentenceScore}"

        val spannable = SpannableString(originalSentence)
        var currentWordStartIndex = 0

        for (evaluation in result.evaluations) {
            val targetWord = evaluation.targetWord
            val wordStartInSpannable = originalSentence.indexOf(targetWord, currentWordStartIndex, ignoreCase = true)

            if (wordStartInSpannable != -1) {
                val wordEndInSpannable = wordStartInSpannable + targetWord.length
                val color = if (evaluation.score >= 60) Color.GREEN 
                            else if (evaluation.score > 0) Color.rgb(255,165,0) // Orange
                            else Color.RED
                try {
                    spannable.setSpan(
                        ForegroundColorSpan(color),
                        wordStartInSpannable,
                        wordEndInSpannable,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } catch (e: IndexOutOfBoundsException) {
                     Log.e(TAG, "Error applying span: word='$targetWord', start=$wordStartInSpannable, end=$wordEndInSpannable, sentenceLen=${originalSentence.length}", e)
                }
                currentWordStartIndex = wordEndInSpannable
            } else {
                Log.w(TAG, "Could not find word '$targetWord' in sentence for highlighting starting from index $currentWordStartIndex")
                currentWordStartIndex += targetWord.length 
            }
        }
        binding.textViewStoryContent.text = spannable
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
                Log.e(TAG, "MediaPlayer setup failed for story recording", e)
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
        val totalFileSize = dataSize + 44L - 8L 
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalFileSize and 0xff).toByte(); header[5] = ((totalFileSize shr 8) and 0xff).toByte(); header[6] = ((totalFileSize shr 16) and 0xff).toByte(); header[7] = ((totalFileSize shr 24) and 0xff).toByte()
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
        // Ensure the support action bar is cleared to avoid issues if activity is reused by other fragments
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
