package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
// import android.graphics.Color // No longer needed here
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer // Keep for nativeMediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
// import android.text.Spannable // No longer needed here
import android.text.SpannableString // Still needed for displayEvaluation if it constructs one for error cases
// import android.text.style.ForegroundColorSpan // No longer needed here
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
// import androidx.lifecycle.lifecycleScope // No longer directly used for coroutines here, but good to keep if other uses exist
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
// import com.google.gson.Gson // No longer needed here
// import kotlinx.coroutines.Dispatchers // Not used
// import kotlinx.coroutines.launch // Not used
// import kotlinx.coroutines.withContext // Not used
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FileInputStream
import kotlin.concurrent.thread
import kotlin.math.max
// import kotlin.math.min // No longer needed here

// Data classes PronunciationFeedback, InternalVoskWord, InternalVoskFullResult are removed

class PracticeFragment : Fragment() {

    private var _binding: FragmentPracticeBinding? = null
    private val binding get() = _binding!!

    private val practiceViewModel: PracticeViewModel by viewModels()
    private val voskModelViewModel: VoskModelViewModel by activityViewModels()
    private lateinit var savedWordsAdapter: SavedWordsAdapter

    // private val gson = Gson() // Removed
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var audioFilePath: String? = null
    private val sampleRate = 16000
    private var bufferSize: Int = 0

    private var recordingThread: Thread? = null
    private var pcmFile: File? = null
    private var fileOutputStream: FileOutputStream? = null

    private var pulsatingAnimator: AnimatorSet? = null
    private var nativeMediaPlayer: MediaPlayer? = null
    private var exoPlayer: ExoPlayer? = null

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
        audioFilePath = "${requireContext().externalCacheDir?.absolutePath}/user_recording.wav"
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
        val audioFile = audioFilePath?.let { File(it) }
        if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
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
            audioFilePath?.let { wavPath ->
                File(wavPath).delete()
                File(wavPath.replace(".wav", ".pcm")).delete()
            }
            if (isAdded && _binding != null) {
                binding.playUserButton.isEnabled = false
                binding.playUserButton.alpha = 0.5f
                updatePlayUserButtonUI()
                binding.scoreCircleTextView.visibility = View.GONE
            }
        }
    }

    // Updated to take DetailedPronunciationResult
    private fun displayEvaluation(result: PronunciationScorer.DetailedPronunciationResult) {
        if (_binding == null || !isAdded) return
        binding.scoreCircleTextView.visibility = View.VISIBLE
        binding.wordTextView.text = result.coloredTargetDisplay // Use the colored SpannableString

        if (result.overallScore == -1) {
            binding.scoreCircleTextView.text = "N/A"
            binding.scoreCircleTextView.setBackgroundResource(R.drawable.bg_score_circle_bad)
        } else {
            val displayScore = max(40, result.overallScore) // Keep the logic to display min 40 for actual scores
            binding.scoreCircleTextView.text = getString(R.string.score_display_format, displayScore)
            if (result.overallScore >= 80) {
                binding.scoreCircleTextView.setBackgroundResource(R.drawable.bg_score_circle_good)
            } else {
                binding.scoreCircleTextView.setBackgroundResource(R.drawable.bg_score_circle_bad)
            }
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
            binding.wordTextView.text = result.word?.replaceFirstChar { char -> char.uppercase() } // Initial display before scoring
            binding.ipaTextView.text = result.phonetics?.find { !it.text.isNullOrEmpty() }?.text ?: "N/A"
            binding.meaningTextView.text = result.meanings?.firstOrNull()?.definitions?.firstOrNull()?.definition ?: "No definition found."
            binding.scoreCircleTextView.visibility = View.GONE
            val audioFile = audioFilePath?.let { File(it) }
            binding.playUserButton.isEnabled = audioFile != null && audioFile.exists() && audioFile.length() > 0
            binding.playUserButton.alpha = if (binding.playUserButton.isEnabled) 1.0f else 0.5f
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
                requireActivity().stopService(serviceIntent) // Stop first to avoid multiple instances
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
            val word = binding.wordTextView.text.toString() // This might be SpannableString now
            if (word.isNotEmpty()) {
                // Ensure we get the plain string for comparison
                practiceViewModel.allSavedWords.value?.find { it.word == word.toString().lowercase() }?.let {
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
            } catch (e: Exception) { // Catch broader exceptions
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
        if (voskModelViewModel.voskModel == null) {
            Toast.makeText(requireContext(), "Cannot start: model not ready.", Toast.LENGTH_SHORT).show(); return
        }
        if (_binding == null || !isAdded) return
        val originalTargetWordForDisplay = binding.wordTextView.text.toString() // This will be the word from API or user input
        if (originalTargetWordForDisplay.isBlank()) {
            Toast.makeText(requireContext(), "No word to practice.", Toast.LENGTH_SHORT).show(); return
        }

        audioFilePath?.let { wavPath ->
            File(wavPath).delete()
            File(wavPath.replace(".wav", ".pcm")).delete()
        }
        activity?.runOnUiThread {
            if (isAdded && _binding != null) {
                binding.playUserButton.isEnabled = false
                binding.playUserButton.alpha = 0.5f
                updatePlayUserButtonUI()
                binding.scoreCircleTextView.visibility = View.GONE
                // Reset wordTextView to plain text before new recording if it was Spannable
                // This ensures target word for PronunciationScorer is the plain, original word.
                binding.wordTextView.text = originalTargetWordForDisplay 
            }
        }

        val pcmFilePathString = audioFilePath?.replace(".wav", ".pcm")
        if (pcmFilePathString == null) {
            Toast.makeText(requireContext(), "Recording setup error.", Toast.LENGTH_SHORT).show(); return
        }
        this.pcmFile = File(pcmFilePathString)

        try {
            this.fileOutputStream = FileOutputStream(this.pcmFile)
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBufferSize <= 0) { // More robust check
                Log.e("PracticeFragment", "Invalid minBufferSize: $minBufferSize")
                this.fileOutputStream?.close(); this.fileOutputStream = null; this.pcmFile = null; return
            }
            bufferSize = max(minBufferSize, 4096)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
        } catch (e: Exception) {
            Log.e("PracticeFragment", "Failed to initialize AudioRecord/FileOutputStream", e)
            isRecording = false; updateRecordButtonUI(); stopPulsatingAnimation()
            this.fileOutputStream?.close(); this.fileOutputStream = null; this.pcmFile = null
            audioRecord?.release(); audioRecord = null; return
        }

        try {
            audioRecord?.startRecording()
            isRecording = true
            updateRecordButtonUI(); startPulsatingAnimation()

            recordingThread = thread(start = true, name = "AudioProducerThread") {
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
                        try { this.fileOutputStream?.write(byteArray) }
                        catch (e: IOException) { Log.e("PracticeFragment", "FileOutputStream.write() error", e); break }
                    } else if (readResult < 0) {
                        Log.e("PracticeFragment", "Error reading audio data: $readResult"); break
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Log.e("PracticeFragment", "AudioRecord.startRecording() failed.", e)
            isRecording = false; updateRecordButtonUI(); stopPulsatingAnimation()
            this.fileOutputStream?.close(); this.fileOutputStream = null; this.pcmFile = null
            audioRecord?.release(); audioRecord = null
        }
    }

    private fun stopRecording() {
        if (!isRecording && recordingThread == null) return
        isRecording = false
        if (isAdded && _binding != null) { updateRecordButtonUI(); stopPulsatingAnimation() }

        try { audioRecord?.stop() } catch (e: IllegalStateException) { Log.e("PracticeFragment", "Error stopping AudioRecord", e) }
        finally { try { audioRecord?.release() } catch (e: Exception) { Log.e("PracticeFragment", "Error releasing AudioRecord", e) }; audioRecord = null }

        try { fileOutputStream?.flush(); fileOutputStream?.close() } 
        catch (e: IOException) { Log.e("PracticeFragment", "Error closing FileOutputStream", e) }
        fileOutputStream = null

        val currentPcmFile = this.pcmFile
        if (currentPcmFile == null) {
            Toast.makeText(requireContext(), "Recording data not found.", Toast.LENGTH_SHORT).show(); return
        }
        // Use the text from wordTextView as the target, ensuring it's the plain word before scoring
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
            val targetForThread = currentTargetWord // Use the plain string

            try {
                if (!currentPcmFile.exists() || currentPcmFile.length() == 0L) {
                    Log.e("PracticeFragment", "PCM file missing/empty: ${currentPcmFile.absolutePath}")
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            Toast.makeText(requireContext(), "Ghi âm thất bại.", Toast.LENGTH_SHORT).show()
                            // Call PronunciationScorer even for error to get default colored SpannableString
                            val errorFeedback = PronunciationScorer.scoreWordDetailed("", targetForThread)
                            displayEvaluation(errorFeedback)
                        }
                    }
                    return@thread
                }

                FileInputStream(currentPcmFile).use { fis ->
                    val recognizer = Recognizer(currentVoskModel, sampleRate.toFloat())
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } > 0) {
                        recognizer.acceptWaveForm(buffer, bytesRead)
                    }
                    val silenceDurationMs = 300
                    val numSamplesSilence = (sampleRate * silenceDurationMs / 1000)
                    val silenceBytes = ByteArray(numSamplesSilence * 2)
                    if (silenceBytes.isNotEmpty()) recognizer.acceptWaveForm(silenceBytes, silenceBytes.size)
                    finalResultJson = recognizer.finalResult
                    recognizer.close()
                }
                Log.d("PracticeFragment", "PostProcess Vosk JSON: $finalResultJson")

                var wavFileSuccessfullyCreated = false
                audioFilePath?.let { File(it) }?.also { wavFile ->
                    if (currentPcmFile.exists()) {
                        try {
                            val dataSizeForWavHeader = currentPcmFile.length().toInt()
                            FileOutputStream(wavFile).use { fosWav ->
                                fosWav.write(createWavHeader(dataSizeForWavHeader))
                                FileInputStream(currentPcmFile).use { fisPcm -> fisPcm.copyTo(fosWav) }
                            }
                            wavFileSuccessfullyCreated = wavFile.exists() && wavFile.length() > 44
                            if (wavFileSuccessfullyCreated) currentPcmFile.delete()
                        } catch (e: Exception) { Log.e("PracticeFragment", "Error creating/deleting WAV/PCM", e) }
                    }
                }

                val feedback = PronunciationScorer.scoreWordDetailed(finalResultJson ?: "{\"text\":\"\"}", targetForThread)
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        displayEvaluation(feedback)
                        binding.playUserButton.isEnabled = wavFileSuccessfullyCreated
                        binding.playUserButton.alpha = if (wavFileSuccessfullyCreated) 1.0f else 0.5f
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
                    }
                }
            }
        }
    }

    private fun playUserRecording() {
        if (!isAdded || _binding == null) return
        val audioFile = audioFilePath?.let { File(it) }
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
            Toast.makeText(requireContext(), "No recording available.", Toast.LENGTH_SHORT).show()
            binding.playUserButton.isEnabled = false; binding.playUserButton.alpha = 0.5f; return
        }
        if (exoPlayer?.isPlaying == true) {
            stopUserPlayback()
        } else {
            exoPlayer?.release()
            exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
                setMediaItem(MediaItem.fromUri(audioFile.toUri()))
                prepare(); playWhenReady = true
                binding.playUserButton.isEnabled = false; updatePlayUserButtonUI()
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!isAdded || _binding == null) return
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) stopUserPlayback()
                        else binding.playUserButton.isEnabled = playbackState == Player.STATE_READY
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PracticeFragment", "ExoPlayer error: $error"); stopUserPlayback()
                    }
                })
            }
        }
    }

    private fun stopUserPlayback() {
        exoPlayer?.stop(); exoPlayer?.release(); exoPlayer = null
        if (_binding == null || !isAdded) return
        activity?.runOnUiThread {
            if (isAdded && _binding != null) {
                val audioFile = audioFilePath?.let { File(it) }
                val canPlay = audioFile != null && audioFile.exists() && audioFile.length() > 0L
                binding.playUserButton.isEnabled = canPlay
                binding.playUserButton.alpha = if(canPlay) 1.0f else 0.5f
                updatePlayUserButtonUI()
            }
        }
    }

    // levenshtein, alignStrings, processVoskResult, calculateFlexibleScore are REMOVED

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
        if (_binding == null) return
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
        val isPlaying = exoPlayer?.isPlaying == true && exoPlayer?.playbackState != Player.STATE_ENDED && exoPlayer?.playbackState != Player.STATE_IDLE
        binding.playUserButton.setImageResource(if (isPlaying) R.drawable.ic_close else R.drawable.ic_speaker_wave)
    }

    @Throws(IOException::class)
    private fun addWavHeader(pcmFile: File, wavFile: File) { // This function is effectively replaced by direct WAV creation in PostProcess
        // Kept for reference if direct PCM to WAV streaming needs this logic separately
        if (!pcmFile.exists()) { Log.e("PracticeFragment", "PCM not found for WAV: ${pcmFile.absolutePath}"); return }
        val pcmData = pcmFile.readBytes()
        if (pcmData.isEmpty()) { Log.w("PracticeFragment", "PCM empty: ${pcmFile.absolutePath}"); wavFile.delete(); return }
        FileOutputStream(wavFile).use { it.write(createWavHeader(pcmData.size)); it.write(pcmData) }
        // Log.d("PracticeFragment", "WAV header added: ${wavFile.absolutePath}") // PCM deletion is now in PostProcess
    }

    private fun createWavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44); val totalDataLen = dataSize + 36; val channels = 1; val byteRate = sampleRate * channels * (16 / 8)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte(); header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte(); header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte(); header[33] = 0; header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte(); header[41] = (dataSize shr 8 and 0xff).toByte(); header[42] = (dataSize shr 16 and 0xff).toByte(); header[43] = (dataSize shr 24 and 0xff).toByte()
        return header
    }

    override fun onStop() {
        super.onStop()
        if (isRecording) { Log.d("PracticeFragment", "onStop: stopping recording."); stopRecording() }
        nativeMediaPlayer?.release(); nativeMediaPlayer = null
        stopUserPlayback()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulsatingAnimator?.cancel(); pulsatingAnimator = null
        stopUserPlayback()
        _binding = null
        Log.d("PracticeFragment", "onDestroyView: _binding is null.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PracticeFragment", "onDestroy: Vosk model managed by ViewModel.")
    }
}
