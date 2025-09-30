package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer // Keep for nativeMediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString // Still needed for displayEvaluation if it constructs one for error cases
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.adapter.SavedWordsAdapter
import com.example.realtalkenglishwithAI.databinding.FragmentPracticeBinding
import com.example.realtalkenglishwithAI.model.api.ApiResponseItem
import com.example.realtalkenglishwithAI.service.FloatingSearchService
import com.example.realtalkenglishwithAI.utils.PronunciationScorer // Import the scorer
import com.example.realtalkenglishwithAI.viewmodel.PracticeViewModel
import com.example.realtalkenglishwithAI.viewmodel.VoskModelViewModel
import com.example.realtalkenglishwithAI.viewmodel.ModelState
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FileInputStream
import kotlin.concurrent.thread
import kotlin.math.max

class PracticeFragment : Fragment() {

    private var _binding: FragmentPracticeBinding? = null
    private val binding get() = _binding!!

    private val practiceViewModel: PracticeViewModel by viewModels()
    private val voskModelViewModel: VoskModelViewModel by activityViewModels()
    private lateinit var savedWordsAdapter: SavedWordsAdapter

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var userRecordingPcmPath: String? = null // Renamed from audioFilePath, now always .pcm
    private val sampleRate = 16000
    private var bufferSizeForRecording: Int = 0 // Renamed from bufferSize

    private var recordingThread: Thread? = null
    private var pcmFile: File? = null // This will be the File object for userRecordingPcmPath
    private var fileOutputStream: FileOutputStream? = null

    private var pulsatingAnimator: AnimatorSet? = null
    private var nativeMediaPlayer: MediaPlayer? = null
    // private var exoPlayer: ExoPlayer? = null // Removed ExoPlayer for user recording
    private var currentAudioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                handleRecordAction()
            } else {
                Toast.makeText(requireContext(), "Record permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(requireContext())) {
            requireActivity().startService(Intent(requireContext(), FloatingSearchService::class.java))
        } else {
            Toast.makeText(requireContext(), "Overlay permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userRecordingPcmPath = "${requireContext().externalCacheDir?.absolutePath}/user_recording.pcm"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPracticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupClickListeners()
        setupPracticeObservers()
        observeVoskModelStatus()

        updateUIForModelState(voskModelViewModel.modelState.value ?: ModelState.IDLE)
        val currentPcmFile = userRecordingPcmPath?.let { File(it) }
        if (currentPcmFile != null && currentPcmFile.exists() && currentPcmFile.length() > 0) {
            binding.playUserButton.isEnabled = true
            binding.playUserButton.alpha = 1.0f
        } else {
            binding.playUserButton.isEnabled = false
            binding.playUserButton.alpha = 0.5f
        }
        updatePlayUserButtonUI()
    }

    private fun observeVoskModelStatus() {
        voskModelViewModel.modelState.observe(viewLifecycleOwner) { state ->
            Log.d("PracticeFragment", "VoskModelViewModel state changed: $state")
            updateUIForModelState(state)
        }

        voskModelViewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (isAdded && error != null) {
                Log.e("PracticeFragment", "Received error from VoskModelViewModel: $error")
            }
        }
    }

    private fun updateUIForModelState(state: ModelState) {
        if (_binding == null || !isAdded) return
        val modelIsReadyForUse = state == ModelState.READY && voskModelViewModel.voskModel != null
        when (state) {
            ModelState.IDLE, ModelState.LOADING -> {
                binding.recordButton.isEnabled = false
                binding.recordButton.alpha = 0.5f
            }
            ModelState.READY -> {
                binding.recordButton.isEnabled = modelIsReadyForUse
                binding.recordButton.alpha = if (modelIsReadyForUse) 1.0f else 0.5f
                if (!modelIsReadyForUse) {
                    Log.w("PracticeFragment", "Model state is READY but voskModel from ViewModel is null!")
                }
            }
            ModelState.ERROR -> {
                binding.recordButton.isEnabled = false
                binding.recordButton.alpha = 0.5f
            }
        }
    }

    private fun setupRecyclerView() {
        savedWordsAdapter = SavedWordsAdapter(
            onItemClick = { vocabulary ->
                binding.wordInputEditText.setText(vocabulary.word)
                searchWord()
                binding.scrollView.post {
                    binding.scrollView.smoothScrollTo(0, 0)
                }
            },
            onFavoriteClick = { vocabulary ->
                practiceViewModel.toggleFavorite(vocabulary)
            }
        )
        binding.savedWordsRecyclerView.apply {
            adapter = savedWordsAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun hideKeyboard() {
        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view?.windowToken, 0)
    }

    private fun searchWord() {
        val word = binding.wordInputEditText.text.toString()
        if (word.isNotBlank()) {
            practiceViewModel.searchWord(word)
            hideKeyboard()
            userRecordingPcmPath?.let { File(it).delete() } // Delete old PCM file
            this.pcmFile = null // Clear the file reference

            if (isAdded && _binding != null) {
                binding.playUserButton.isEnabled = false
                binding.playUserButton.alpha = 0.5f
                updatePlayUserButtonUI()
                binding.scoreCircleTextView.visibility = View.GONE
            }
        }
    }

    private fun displayEvaluation(result: PronunciationScorer.DetailedPronunciationResult) {
        if (_binding == null || !isAdded) return
        binding.scoreCircleTextView.visibility = View.VISIBLE
        binding.wordTextView.text = result.coloredTargetDisplay

        if (result.overallScore == -1) {
            binding.scoreCircleTextView.text = "N/A"
            binding.scoreCircleTextView.setBackgroundResource(R.drawable.bg_score_circle_bad)
        } else {
            val displayScore = max(40, result.overallScore)
            binding.scoreCircleTextView.text = getString(R.string.score_display_format, displayScore)
            binding.scoreCircleTextView.setBackgroundResource(
                if (result.overallScore >= 80) R.drawable.bg_score_circle_good else R.drawable.bg_score_circle_bad
            )
        }
        practiceViewModel.logPracticeSession()
    }

    private fun generatePronunciationTip(word: String): String? {
        return when (word.lowercase()) {
            "hello" -> "Focus on the 'o' sound at the end. It should be long, like in the word 'go'."
            "apple" -> "The 'a' sound is short, like in 'cat'. Don't forget to pronounce the 'l' sound at the end."
            "school" -> "The 'sch' combination makes a 'sk' sound. The 'oo' is a long sound, like in 'moon'."
            "technology" -> "The stress is on the second syllable: tech-NO-lo-gy."
            "environment" -> "Pay attention to the 'n' sound before the 'm'. It's en-VI-ron-ment."
            else -> null
        }
    }

    private fun setupPracticeObservers() {
        practiceViewModel.searchResult.observe(viewLifecycleOwner) { result: ApiResponseItem? ->
            if (_binding == null || !isAdded || result == null) return@observe
            binding.resultCardView.visibility = View.VISIBLE
            binding.notFoundTextView.visibility = View.GONE
            binding.wordTextView.text = result.word?.replaceFirstChar { char -> char.uppercase() }
            binding.ipaTextView.text = result.phonetics?.find { !it.text.isNullOrEmpty() }?.text ?: "N/A"
            binding.meaningTextView.text = result.meanings?.firstOrNull()?.definitions?.firstOrNull()?.definition ?: "No definition found."
            binding.scoreCircleTextView.visibility = View.GONE

            val currentPcmFileToCheck = userRecordingPcmPath?.let { File(it) }
            val pcmReady = currentPcmFileToCheck != null && currentPcmFileToCheck.exists() && currentPcmFileToCheck.length() > 0
            binding.playUserButton.isEnabled = pcmReady
            binding.playUserButton.alpha = if (pcmReady) 1.0f else 0.5f
            updatePlayUserButtonUI()

            val tip = generatePronunciationTip(result.word ?: "")
            binding.pronunciationTipSection.visibility = if (tip != null) View.VISIBLE else View.GONE
            binding.tipContentTextView.text = tip
        }
        practiceViewModel.wordNotFound.observe(viewLifecycleOwner) { notFound: Boolean ->
            if (_binding == null || !isAdded) return@observe
            if (notFound) {
                binding.resultCardView.visibility = View.GONE
                binding.notFoundTextView.visibility = View.VISIBLE
            }
        }
        practiceViewModel.isLoading.observe(viewLifecycleOwner) { isLoading: Boolean ->
            if (_binding == null || !isAdded) return@observe
            binding.progressBar.isVisible = isLoading
        }
        practiceViewModel.nativeAudioUrl.observe(viewLifecycleOwner) { url: String? ->
            if (_binding == null || !isAdded) return@observe
            binding.playNativeButton.isEnabled = !url.isNullOrEmpty()
            binding.playNativeButton.alpha = if (url.isNullOrEmpty()) 0.5f else 1.0f
        }
        practiceViewModel.allSavedWords.observe(viewLifecycleOwner) { words ->
            if (_binding == null || !isAdded) return@observe
            savedWordsAdapter.submitList(words)
        }
        practiceViewModel.searchedWordIsFavorite.observe(viewLifecycleOwner) { isFavorite ->
            if (_binding == null || !isAdded) return@observe
            val starIcon = if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            binding.favoriteResultButton.setImageResource(starIcon)
        }
    }

    private fun setupClickListeners() {
        binding.screenSearchButton.setOnClickListener {
            if (!isAdded) return@setOnClickListener
            if (Settings.canDrawOverlays(requireContext())) {
                val serviceIntent = Intent(requireContext(), FloatingSearchService::class.java)
                requireActivity().stopService(serviceIntent) 
                requireActivity().startService(serviceIntent)
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${requireActivity().packageName}".toUri())
                overlayPermissionLauncher.launch(intent)
            }
        }
        binding.wordInputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchWord()
                true
            } else false
        }
        binding.clearSearchButton.setOnClickListener {
            binding.wordInputEditText.setText("")
        }
        binding.wordInputEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (_binding == null || !isAdded) return
                binding.clearSearchButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        binding.favoriteResultButton.setOnClickListener {
            if (!isAdded || _binding == null) return@setOnClickListener
            val word = binding.wordTextView.text.toString()
            if (word.isNotEmpty()) {
                practiceViewModel.allSavedWords.value?.find { it.word == word.lowercase() }?.let {
                    practiceViewModel.toggleFavorite(it)
                }
            }
        }
        binding.recordButton.setOnClickListener { handleRecordAction() }
        binding.playNativeButton.setOnClickListener {
            if (!isAdded || _binding == null) return@setOnClickListener
            practiceViewModel.nativeAudioUrl.value?.let { playNativeAudio(it) }
        }
        binding.playUserButton.setOnClickListener {
            playUserRecording()
        }
    }

    private fun playNativeAudio(url: String) {
        if (!isAdded || _binding == null) return
        nativeMediaPlayer?.release()
        nativeMediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { mp -> mp.start() }
                setOnCompletionListener { mp -> mp.release(); nativeMediaPlayer = null }
                setOnErrorListener { mp, what, extra ->
                    Log.e("PracticeFragment", "Native MediaPlayer error: what=$what, extra=$extra")
                    mp.release(); nativeMediaPlayer = null; true
                }
            } catch (e: Exception) {
                Log.e("PracticeFragment", "playNativeAudio failed", e)
                release(); nativeMediaPlayer = null
            }
        }
    }

    private fun handleRecordAction() {
        if (!isAdded || _binding == null) return
        when (voskModelViewModel.modelState.value) {
            ModelState.READY -> {
                if (voskModelViewModel.voskModel != null) {
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        toggleRecording()
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    Toast.makeText(requireContext(), "Speech recognition engine not ready.", Toast.LENGTH_SHORT).show()
                }
            }
            ModelState.LOADING -> Toast.makeText(requireContext(), "Speech model is loading...", Toast.LENGTH_SHORT).show()
            ModelState.ERROR -> Toast.makeText(requireContext(), "Speech model error.", Toast.LENGTH_LONG).show()
            ModelState.IDLE -> Toast.makeText(requireContext(), "Speech model not initialized.", Toast.LENGTH_SHORT).show()
            null -> Toast.makeText(requireContext(), "Speech model status unknown.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        stopCurrentAudioTrackPlayback() // Stop any ongoing playback

        if (voskModelViewModel.voskModel == null) {
            Toast.makeText(requireContext(), "Cannot start: model not ready.", Toast.LENGTH_SHORT).show(); return
        }
        if (_binding == null || !isAdded) return
        val originalTargetWordForDisplay = binding.wordTextView.text.toString()
        if (originalTargetWordForDisplay.isBlank()) {
            Toast.makeText(requireContext(), "No word to practice.", Toast.LENGTH_SHORT).show(); return
        }

        userRecordingPcmPath?.let { File(it).delete() } // Delete previous PCM file

        activity?.runOnUiThread {
            if (isAdded && _binding != null) {
                binding.playUserButton.isEnabled = false
                binding.playUserButton.alpha = 0.5f
                updatePlayUserButtonUI()
                binding.scoreCircleTextView.visibility = View.GONE
                binding.wordTextView.text = originalTargetWordForDisplay
            }
        }

        val pcmPathString = userRecordingPcmPath
        if (pcmPathString == null) {
            Toast.makeText(requireContext(), "Recording setup error (path).", Toast.LENGTH_SHORT).show(); return
        }
        this.pcmFile = File(pcmPathString)

        try {
            this.fileOutputStream = FileOutputStream(this.pcmFile)
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBufferSize <= 0) {
                Log.e("PracticeFragment", "Invalid minBufferSize: $minBufferSize")
                this.fileOutputStream?.close(); this.fileOutputStream = null; this.pcmFile = null; return
            }
            bufferSizeForRecording = max(minBufferSize, 4096)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeForRecording * 2)
        } catch (e: Exception) {
            Log.e("PracticeFragment", "Failed to initialize AudioRecord/FileOutputStream", e)
            isRecording = false; updateRecordButtonUI(); stopPulsatingAnimation()
            this.fileOutputStream?.closeSafely(); this.fileOutputStream = null; this.pcmFile = null
            audioRecord?.release(); audioRecord = null; return
        }

        try {
            audioRecord?.startRecording()
            isRecording = true
            updateRecordButtonUI(); startPulsatingAnimation()

            recordingThread = thread(start = true, name = "AudioProducerThread") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                // Using ByteArray directly as Vosk and AudioTrack can handle it.
                val audioBuffer = ByteArray(bufferSizeForRecording) 
                while (isRecording) {
                    val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readResult > 0) {
                        try { this.fileOutputStream?.write(audioBuffer, 0, readResult) }
                        catch (e: IOException) { Log.e("PracticeFragment", "FileOutputStream.write() error", e); break }
                    } else if (readResult < 0) {
                        Log.e("PracticeFragment", "Error reading audio data: $readResult"); break
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Log.e("PracticeFragment", "AudioRecord.startRecording() failed.", e)
            isRecording = false; updateRecordButtonUI(); stopPulsatingAnimation()
            this.fileOutputStream?.closeSafely(); this.fileOutputStream = null; this.pcmFile = null
            audioRecord?.release(); audioRecord = null
        }
    }

    private fun stopRecording() {
        if (!isRecording && recordingThread == null && audioRecord == null) return
        isRecording = false
        if (isAdded && _binding != null) { updateRecordButtonUI(); stopPulsatingAnimation() }

        try { audioRecord?.stop() } catch (e: IllegalStateException) { Log.e("PracticeFragment", "Error stopping AudioRecord", e) }
        finally { try { audioRecord?.release() } catch (e: Exception) { Log.e("PracticeFragment", "Error releasing AudioRecord", e) }; audioRecord = null }

        try { fileOutputStream?.flush(); fileOutputStream?.closeSafely() } 
        catch (e: IOException) { Log.e("PracticeFragment", "Error closing FileOutputStream (already handled by closeSafely?)", e) }
        fileOutputStream = null

        val currentPcmFileForProcessing = this.pcmFile
        if (currentPcmFileForProcessing == null) {
            Toast.makeText(requireContext(), "Recording data not found.", Toast.LENGTH_SHORT).show(); return
        }
        val currentTargetWord = if (isAdded && _binding != null) binding.wordTextView.text.toString() else ""
        if (currentTargetWord.isBlank()) {
            Log.w("PracticeFragment", "Target word is blank, skipping post-processing."); return
        }
        val currentVoskModel = voskModelViewModel.voskModel
        if (currentVoskModel == null) {
            Log.e("PracticeFragment", "Vosk model null for PostProcessThread."); return
        }

        thread(start = true, name = "PostProcessThread") {
            var finalResultJson: String? = null
            val targetForThread = currentTargetWord
            var pcmFileIsGoodForPlayback = false

            try {
                if (!currentPcmFileForProcessing.exists() || currentPcmFileForProcessing.length() == 0L) {
                    Log.e("PracticeFragment", "PCM file missing/empty for Vosk: ${currentPcmFileForProcessing.absolutePath}")
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            Toast.makeText(requireContext(), "Ghi âm thất bại.", Toast.LENGTH_SHORT).show()
                            val errorFeedback = PronunciationScorer.scoreWordDetailed("", targetForThread)
                            displayEvaluation(errorFeedback)
                            binding.playUserButton.isEnabled = false; binding.playUserButton.alpha = 0.5f
                            updatePlayUserButtonUI()
                        }
                    }
                    return@thread
                }

                FileInputStream(currentPcmFileForProcessing).use { fis ->
                    val recognizer = Recognizer(currentVoskModel, sampleRate.toFloat())
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } > 0) {
                        recognizer.acceptWaveForm(buffer, bytesRead)
                    }
                    val silenceDurationMs = 300
                    val numSamplesSilence = (sampleRate * silenceDurationMs / 1000)
                    val silenceBytes = ByteArray(numSamplesSilence * 2) // For 16-bit PCM
                    if (silenceBytes.isNotEmpty()) recognizer.acceptWaveForm(silenceBytes, silenceBytes.size)
                    finalResultJson = recognizer.finalResult
                    recognizer.close()
                }
                Log.d("PracticeFragment", "PostProcess Vosk JSON: $finalResultJson")

                // PCM file is kept, not converted to WAV and deleted.
                // We just confirm it's still good for playback.
                pcmFileIsGoodForPlayback = currentPcmFileForProcessing.exists() && currentPcmFileForProcessing.length() > 0

                val feedback = PronunciationScorer.scoreWordDetailed(finalResultJson ?: "{\"text\":\"\"}", targetForThread)
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        displayEvaluation(feedback)
                        binding.playUserButton.isEnabled = pcmFileIsGoodForPlayback
                        binding.playUserButton.alpha = if (pcmFileIsGoodForPlayback) 1.0f else 0.5f
                        updatePlayUserButtonUI()
                    }
                }
            } catch (e: Exception) {
                Log.e("PracticeFragment", "Error in PostProcessThread", e)
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        Toast.makeText(requireContext(), "Error processing audio.", Toast.LENGTH_SHORT).show()
                        val errorFeedback = PronunciationScorer.scoreWordDetailed("", targetForThread)
                        displayEvaluation(errorFeedback)
                        binding.playUserButton.isEnabled = false; binding.playUserButton.alpha = 0.5f
                        updatePlayUserButtonUI()
                    }
                }
            }
        }
    }

    private fun playUserRecording() {
        stopCurrentAudioTrackPlayback() // Stop any previous playback

        val currentPcmFileToPlay = this.pcmFile
        if (currentPcmFileToPlay?.exists() != true || currentPcmFileToPlay.length() == 0L) {
            Toast.makeText(requireContext(), "No recording available to play.", Toast.LENGTH_SHORT).show()
            if(isAdded && _binding != null) {
                 binding.playUserButton.isEnabled = false; binding.playUserButton.alpha = 0.5f
                 updatePlayUserButtonUI()
            }
            return
        }
        playPcmWithAudioTrack(currentPcmFileToPlay)
    }

    private fun stopCurrentAudioTrackPlayback(){
        playbackThread?.interrupt()
        try {
            playbackThread?.join(500) 
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e("PracticeFragment", "Interrupted while joining playback thread")
        }
        playbackThread = null

        currentAudioTrack?.let {
            try {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
                Log.d("PracticeFragment", "AudioTrack stopped and released.")
            } catch (e: IllegalStateException) {
                Log.e("PracticeFragment", "Error stopping/releasing AudioTrack: ${e.message}")
            }
        }
        currentAudioTrack = null
        // Ensure UI is updated after stopping
        activity?.runOnUiThread { if(isAdded && _binding != null) updatePlayUserButtonUI() }
    }

    private fun playPcmWithAudioTrack(audioPcmFile: File) {
        val minPlayBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minPlayBufferSize == AudioTrack.ERROR_BAD_VALUE || minPlayBufferSize == AudioTrack.ERROR) {
            Log.e("PracticeFragment", "AudioTrack.getMinBufferSize failed for playback")
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
                minPlayBufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (currentAudioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("PracticeFragment", "AudioTrack not initialized for playback")
                Toast.makeText(requireContext(), "AudioTrack initialization failed.", Toast.LENGTH_SHORT).show()
                currentAudioTrack = null
                return
            }

            currentAudioTrack?.play()
            Toast.makeText(requireContext(), "Playing recording...", Toast.LENGTH_SHORT).show()
            if(isAdded && _binding != null) updatePlayUserButtonUI() // Update button to show stop icon

            playbackThread = thread(start = true, name = "PcmPlaybackThreadPractice") {
                var bytesReadTotal = 0L
                try {
                    FileInputStream(audioPcmFile).use { fis ->
                        val buffer = ByteArray(minPlayBufferSize)
                        var bytesRead: Int // Corrected: Added Int type
                        // Check currentAudioTrack?.playState to allow interruption
                        while (fis.read(buffer).also { bytesRead = it } != -1 && currentAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            if (bytesRead > 0) {
                                currentAudioTrack?.write(buffer, 0, bytesRead)
                                bytesReadTotal += bytesRead
                            }
                        }
                    }
                    Log.d("PracticeFragment", "PCM playback finished. Bytes written: $bytesReadTotal")
                    activity?.runOnUiThread {
                         if(isAdded && _binding != null && currentAudioTrack != null && currentAudioTrack?.playState != AudioTrack.PLAYSTATE_STOPPED) { // Avoid Toast if stopped manually
                            Toast.makeText(requireContext(), "Playback finished.", Toast.LENGTH_SHORT).show()
                         }
                    }
                } catch (e: IOException) {
                    Log.e("PracticeFragment", "IOException during PCM playback", e)
                    activity?.runOnUiThread { if(isAdded) Toast.makeText(requireContext(), "Error playing recording.", Toast.LENGTH_SHORT).show() }
                } catch (e: IllegalStateException) {
                     Log.e("PracticeFragment", "IllegalStateException during PCM playback", e)
                     activity?.runOnUiThread { if(isAdded) Toast.makeText(requireContext(), "Playback stopped abruptly.", Toast.LENGTH_SHORT).show() }
                } finally {
                    activity?.runOnUiThread { //This ensures UI update and cleanup happens on main thread
                        stopCurrentAudioTrackPlayback() // This will also call updatePlayUserButtonUI()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PracticeFragment", "AudioTrack playback setup failed", e)
            Toast.makeText(requireContext(), "Audio playback setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            currentAudioTrack = null
            if(isAdded && _binding != null) updatePlayUserButtonUI()
        }
    }

    private fun startPulsatingAnimation() {
        if (!isAdded || _binding == null) return
        binding.pulsatingView.visibility = View.VISIBLE
        pulsatingAnimator?.cancel()
        pulsatingAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.pulsatingView, View.SCALE_X, 1f, 1.8f),
                ObjectAnimator.ofFloat(binding.pulsatingView, View.SCALE_Y, 1f, 1.8f),
                ObjectAnimator.ofFloat(binding.pulsatingView, View.ALPHA, 0.6f, 0f)
            )
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (isRecording && isAdded && _binding != null) animation.start()
                }
            })
            start()
        }
    }

    private fun stopPulsatingAnimation() {
        if (_binding == null || !isAdded) return
        pulsatingAnimator?.cancel()
        binding.pulsatingView.visibility = View.GONE
        binding.pulsatingView.alpha = 1f; binding.pulsatingView.scaleX = 1f; binding.pulsatingView.scaleY = 1f
    }

    private fun updateRecordButtonUI() {
        if (_binding == null || !isAdded) return
        binding.recordButton.setImageResource(if (isRecording) R.drawable.ic_close else R.drawable.ic_mic)
    }

    private fun updatePlayUserButtonUI() {
        if (_binding == null || !isAdded) return
        val isPlaying = currentAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
        binding.playUserButton.setImageResource(if (isPlaying) R.drawable.ic_stop_audio else R.drawable.ic_speaker_wave) // Changed to ic_stop_audio
        // Enable if not playing AND pcmFile is valid
        val pcmFileIsAvailable = this.pcmFile?.exists() == true && this.pcmFile!!.length() > 0
        binding.playUserButton.isEnabled = !isPlaying || pcmFileIsAvailable // Allow stopping if playing, allow playing if file available & not playing
        binding.playUserButton.alpha = if(binding.playUserButton.isEnabled) 1.0f else 0.5f
    }

    // Removed addWavHeader and createWavHeader functions
    private fun FileOutputStream.closeSafely() { try { close() } catch (e: IOException) { Log.e("PracticeFragment", "FileOutputStream.close() failed safely", e) } }


    override fun onStop() {
        super.onStop()
        if (isRecording) { Log.d("PracticeFragment", "onStop: stopping recording."); stopRecording() }
        nativeMediaPlayer?.release(); nativeMediaPlayer = null
        stopCurrentAudioTrackPlayback()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulsatingAnimator?.cancel(); pulsatingAnimator = null
        stopCurrentAudioTrackPlayback()
        _binding = null
        Log.d("PracticeFragment", "onDestroyView: _binding is null.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PracticeFragment", "onDestroy: Vosk model managed by ViewModel.")
    }
}
