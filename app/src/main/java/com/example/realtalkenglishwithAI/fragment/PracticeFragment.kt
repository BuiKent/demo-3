package com.example.realtalkenglishwithAI.fragment

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer // Keep for nativeMediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
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
import androidx.lifecycle.lifecycleScope
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
import com.example.realtalkenglishwithAI.viewmodel.PracticeViewModel
import com.example.realtalkenglishwithAI.viewmodel.VoskModelViewModel
import com.example.realtalkenglishwithAI.viewmodel.ModelState
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Recognizer // Keep Recognizer import
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FileInputStream // Thêm nếu chưa có
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

// --- START: Định nghĩa các data class mới và hằng số ---
private data class PronunciationFeedback(
    val score: Int, // Từ 0-100, -1 nếu lỗi/không nhận dạng được
    val coloredTarget: SpannableString
)

// Data classes để parse JSON chi tiết từ Vosk (đặt tên Internal để tránh xung đột)
private data class InternalVoskWord(
    val word: String,
    val conf: Float, // Confidence score from Vosk (0.0 to 1.0)
    val start: Float,
    val end: Float
)

private data class InternalVoskFullResult(
    val text: String, // Full recognized text
    val result: List<InternalVoskWord>? // List of individual recognized words with details
)

// --- END: Định nghĩa các data class mới và hằng số ---

class PracticeFragment : Fragment() {

    private var _binding: FragmentPracticeBinding? = null
    private val binding get() = _binding!!

    private val practiceViewModel: PracticeViewModel by viewModels()
    private val voskModelViewModel: VoskModelViewModel by activityViewModels()
    private lateinit var savedWordsAdapter: SavedWordsAdapter

    private val gson = Gson()
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
        updatePlayUserButtonUI() // Initial UI state for ExoPlayer button
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
                Log.d("PracticeFragment", "UI Update: Model IDLE or LOADING. Record button disabled.")
            }
            ModelState.READY -> {
                binding.recordButton.isEnabled = modelIsReadyForUse
                binding.recordButton.alpha = if (modelIsReadyForUse) 1.0f else 0.5f
                Log.d("PracticeFragment", "UI Update: Model READY. Record button enabled: $modelIsReadyForUse")
                if (!modelIsReadyForUse) {
                    Log.w("PracticeFragment", "Model state is READY but voskModel from ViewModel is null!")
                }
            }
            ModelState.ERROR -> {
                binding.recordButton.isEnabled = false
                binding.recordButton.alpha = 0.5f
                Log.d("PracticeFragment", "UI Update: Model ERROR. Record button disabled.")
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

            // Xóa cả file .wav và .pcm cũ
            audioFilePath?.let { wavPath ->
                Log.d("PracticeFragment", "Attempting to delete old recording files on new word search.")
                try {
                    File(wavPath).delete() // Xóa file .wav cũ
                    File(wavPath.replace(".wav", ".pcm")).delete() // Xóa file .pcm cũ
                    Log.d("PracticeFragment", "Old recording files (WAV & PCM) deleted or checked on new search.")
                } catch (e: SecurityException) {
                    Log.e("PracticeFragment", "SecurityException while deleting old recording files on new search.", e)
                }
            }
            if (isAdded && _binding != null) {
                binding.playUserButton.isEnabled = false
                binding.playUserButton.alpha = 0.5f
                updatePlayUserButtonUI()
            }
            binding.scoreCircleTextView.visibility = View.GONE // Ẩn điểm khi tìm từ mới
        }
    }

    private fun displayEvaluation(score: Int) {
        if (_binding == null || !isAdded) return
        binding.scoreCircleTextView.visibility = View.VISIBLE
        if (score == -1) {
            binding.scoreCircleTextView.text = "N/A"
            binding.scoreCircleTextView.setBackgroundResource(R.drawable.bg_score_circle_bad)
        } else {
            val displayScore = max(40, score)
            binding.scoreCircleTextView.text = getString(R.string.score_display_format, displayScore)
            if (score >= 80) {
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
            binding.wordTextView.text = result.word?.replaceFirstChar { char -> char.uppercase() }
            binding.ipaTextView.text = result.phonetics?.find { !it.text.isNullOrEmpty() }?.text ?: "N/A"
            binding.meaningTextView.text = result.meanings?.firstOrNull()?.definitions?.firstOrNull()?.definition ?: "No definition found."
            binding.scoreCircleTextView.visibility = View.GONE

            val audioFile = audioFilePath?.let { File(it) }
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                binding.playUserButton.isEnabled = true
                binding.playUserButton.alpha = 1.0f
            } else {
                binding.playUserButton.isEnabled = false
                binding.playUserButton.alpha = 0.5f
            }

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
                setOnPreparedListener { mp ->
                    mp.start()
                }
                setOnCompletionListener { mp ->
                    mp.release()
                    nativeMediaPlayer = null
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e("PracticeFragment", "Native MediaPlayer error: what=$what, extra=$extra")
                    mp.release()
                    nativeMediaPlayer = null
                    true
                }
            } catch (e: IOException) {
                Log.e("PracticeFragment", "playNativeAudio failed to setDataSource", e)
                release()
                nativeMediaPlayer = null
            } catch (e: IllegalStateException) {
                Log.e("PracticeFragment", "playNativeAudio failed with IllegalStateException", e)
                release()
                nativeMediaPlayer = null
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
                    Toast.makeText(requireContext(), "Speech recognition engine is not yet available.", Toast.LENGTH_SHORT).show()
                    Log.w("PracticeFragment", "Record action when ModelState is READY but voskModel from ViewModel is null.")
                }
            }
            ModelState.LOADING -> {
                Toast.makeText(requireContext(), "Speech model is loading. Please wait.", Toast.LENGTH_SHORT).show()
            }
            ModelState.ERROR -> {
                Toast.makeText(requireContext(), "Speech model error. Please try restarting the app.", Toast.LENGTH_LONG).show()
            }
            ModelState.IDLE -> {
                Toast.makeText(requireContext(), "Speech model is not yet initialized.", Toast.LENGTH_SHORT).show()
            }
            null -> {
                Toast.makeText(requireContext(), "Speech model status unknown.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val sharedVoskModel = voskModelViewModel.voskModel
        if (sharedVoskModel == null) {
            Log.e("PracticeFragment", "startRecording called but Vosk model is null.")
            Toast.makeText(requireContext(), "Cannot start recording: model not ready.", Toast.LENGTH_SHORT).show()
            return
        }
        if (_binding == null || !isAdded) return

        val originalTargetWordForDisplay = binding.wordTextView.text.toString()
        if (originalTargetWordForDisplay.isBlank()) {
            Toast.makeText(requireContext(), "No word selected to practice.", Toast.LENGTH_SHORT).show()
            return
        }

        // --- Xóa file ghi âm cũ ---
        audioFilePath?.let { wavPath ->
            try {
                File(wavPath).delete()
                File(wavPath.replace(".wav", ".pcm")).delete()
                Log.d("PracticeFragment", "Old WAV and PCM files deleted before new recording.")
            } catch (e: SecurityException) {
                Log.e("PracticeFragment", "SecurityException while deleting old recording files for new recording.", e)
            }
        }
        // Reset UI cho nút play và điểm
        activity?.runOnUiThread {
            if (isAdded && _binding != null) {
                binding.playUserButton.isEnabled = false
                binding.playUserButton.alpha = 0.5f
                updatePlayUserButtonUI()
                binding.scoreCircleTextView.visibility = View.GONE // Ẩn điểm khi bắt đầu ghi âm mới
            }
        }

        // Khởi tạo pcmFile và fileOutputStream
        val pcmFilePathString = audioFilePath?.replace(".wav", ".pcm")
        if (pcmFilePathString == null) {
            Log.e("PracticeFragment", "Cannot determine PCM file path from audioFilePath.")
            Toast.makeText(requireContext(), "Recording setup error.", Toast.LENGTH_SHORT).show()
            return
        }
        this.pcmFile = File(pcmFilePathString)

        try {
            this.fileOutputStream = FileOutputStream(this.pcmFile) // Gán cho biến thành viên

            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
                Log.e("PracticeFragment", "Invalid minBufferSize: $minBufferSize")
                this.fileOutputStream?.close() // Đóng stream nếu AudioRecord lỗi
                this.fileOutputStream = null
                this.pcmFile = null
                return
            }
            bufferSize = max(minBufferSize, 4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2 // Sử dụng buffer lớn hơn một chút cho AudioRecord
            )
        } catch (e: IOException) {
            Log.e("PracticeFragment", "Failed to create FileOutputStream for PCM", e)
            isRecording = false // Đảm bảo isRecording đúng trạng thái
            updateRecordButtonUI()
            stopPulsatingAnimation()
            this.fileOutputStream = null // Đảm bảo dọn dẹp
            this.pcmFile = null
            return
        } catch (e: Exception) { // Bắt các lỗi khác khi khởi tạo AudioRecord
            Log.e("PracticeFragment", "Failed to initialize AudioRecord", e)
            isRecording = false
            updateRecordButtonUI()
            stopPulsatingAnimation()
            this.fileOutputStream?.close() // Đóng stream nếu đã mở
            this.fileOutputStream = null
            this.pcmFile = null
            audioRecord?.release()
            audioRecord = null
            return
        }

        try {
            audioRecord?.startRecording()
            isRecording = true // Đặt isRecording = true CHỈ KHI MỌI THỨ SẴN SÀNG
            updateRecordButtonUI()
            startPulsatingAnimation()

            recordingThread = thread(start = true, name = "AudioProducerThread") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val shortBuffer = ShortArray(bufferSize) // bufferSize đã được tính toán

                Log.d("PracticeFragment", "AudioProducerThread started. Writing to: ${this.pcmFile?.absolutePath}")

                while (isRecording) { // Vòng lặp chính để ghi âm
                    val readResult = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (readResult > 0) {
                        val byteArray = ByteArray(readResult * 2) // *2 for 16-bit
                        for (i in 0 until readResult) {
                            val v = shortBuffer[i].toInt()
                            byteArray[i * 2] = (v and 0xFF).toByte()
                            byteArray[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        try {
                            this.fileOutputStream?.write(byteArray)
                        } catch (e: IOException) {
                            Log.e("PracticeFragment", "IOException during FileOutputStream.write() in AudioProducerThread", e)
                            // Cân nhắc dừng ghi âm nếu lỗi ghi file nghiêm trọng
                            // isRecording = false // Ví dụ
                            break // Thoát vòng lặp
                        }
                    } else if (readResult < 0) {
                        Log.e("PracticeFragment", "Error reading audio data: $readResult")
                        break // Thoát vòng lặp nếu có lỗi đọc từ AudioRecord
                    }
                }
                Log.d("PracticeFragment", "AudioProducerThread finished loop. isRecording: $isRecording")
                // Việc flush và close fileOutputStream sẽ được thực hiện trong stopRecording()
                // Việc stop và release audioRecord cũng sẽ được thực hiện trong stopRecording()
            }
        } catch (e: IllegalStateException) {
            Log.e("PracticeFragment", "AudioRecord.startRecording() failed.", e)
            isRecording = false
            updateRecordButtonUI()
            stopPulsatingAnimation()
            this.fileOutputStream?.close() // Dọn dẹp
            this.fileOutputStream = null
            this.pcmFile = null
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun stopRecording() {
        if (!isRecording && recordingThread == null) { // Kiểm tra cả recordingThread để tránh gọi nhầm khi chưa start
            Log.d("PracticeFragment", "stopRecording called but not actually recording or producer not started.")
            return
        }

        Log.d("PracticeFragment", "stopRecording initiated. Current isRecording: $isRecording")
        isRecording = false // Đặt isRecording = false NGAY LẬP TỨC để producer thread dừng

        // Chờ producer thread tự kết thúc một cách tự nhiên sau khi isRecording = false
        // Không cần join() một cách bắt buộc ở đây nữa vì producer sẽ thoát vòng lặp while
        // và việc đóng file/audioRecord sẽ xử lý các tài nguyên.

        // Cập nhật UI ngay
        if (isAdded && _binding != null) {
            updateRecordButtonUI()
            stopPulsatingAnimation()
        }

        // Đóng AudioRecord và FileOutputStream (quan trọng: thực hiện sau khi isRecording=false)
        try {
            audioRecord?.stop() // Gọi stop trước release
            Log.d("PracticeFragment", "AudioRecord stopped.")
        } catch (e: IllegalStateException) {
            Log.e("PracticeFragment", "Error stopping AudioRecord, possibly already stopped or not initialized.", e)
        } finally {
            try {
                audioRecord?.release()
                Log.d("PracticeFragment", "AudioRecord released.")
            } catch (e: Exception) {
                Log.e("PracticeFragment", "Error releasing AudioRecord", e)
            }
            audioRecord = null
        }

        try {
            fileOutputStream?.flush()
            fileOutputStream?.close()
            Log.d("PracticeFragment", "FileOutputStream flushed and closed. PCM file path: ${this.pcmFile?.absolutePath}")
        } catch (e: IOException) {
            Log.e("PracticeFragment", "Error flushing/closing FileOutputStream", e)
        }
        fileOutputStream = null

        // Phải đảm bảo pcmFile (biến thành viên) đã được ghi và đóng trước khi PostProcessThread dùng
        val currentPcmFile = this.pcmFile // Tạo bản sao cục bộ để thread dùng, tránh race condition nếu this.pcmFile thay đổi
        if (currentPcmFile == null) {
            Log.e("PracticeFragment", "PCM file (member) is null before starting PostProcessThread.")
            Toast.makeText(requireContext(), "Recording data not found.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentTargetWord = if (isAdded && _binding != null) binding.wordTextView.text.toString() else ""
        if (currentTargetWord.isBlank()){
            Log.w("PracticeFragment", "Target word is blank, skipping post-processing.")
            // Có thể reset UI ở đây nếu cần
            return
        }

        val currentVoskModel = voskModelViewModel.voskModel
        if (currentVoskModel == null) {
            Log.e("PracticeFragment", "Vosk model is null when starting PostProcessThread.")
            // Có thể hiển thị Toast cho người dùng
            return
        }

        // Hiển thị processing indicator (nếu có)
        // activity?.runOnUiThread { if (isAdded && _binding!=null) binding.voskProcessingIndicator.visibility = View.VISIBLE }

        thread(start = true, name = "PostProcessThread") {
            var finalResultJson: String? = null
            var pcmDataForVosk: ByteArray? = null
            val targetForThread = currentTargetWord // Sử dụng bản sao cục bộ

            try {
                if (!currentPcmFile.exists() || currentPcmFile.length() == 0L) {
                    Log.e("PracticeFragment", "PCM file missing or empty for post-processing: ${currentPcmFile.absolutePath}")
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            Toast.makeText(requireContext(), "Ghi âm thất bại hoặc rỗng.", Toast.LENGTH_SHORT).show()
                            // Ẩn processing indicator nếu có
                            // binding.voskProcessingIndicator.visibility = View.GONE
                            // Reset điểm số
                            processVoskResult("", targetForThread).let { errorFeedback ->
                                displayEvaluation(errorFeedback.score)
                                binding.wordTextView.text = errorFeedback.coloredTarget
                            }
                        }
                    }
                    return@thread
                }

                Log.d("PracticeFragment", "PostProcess: Reading PCM data for Vosk from: ${currentPcmFile.absolutePath}")
                try {
                    // Sử dụng FileInputStream để đọc từng chunk cho Vosk
                    val recognizer = Recognizer(currentVoskModel, sampleRate.toFloat())
                    FileInputStream(currentPcmFile).use { fis ->
                        val buffer = ByteArray(4096) // Buffer size for reading chunks
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } > 0) {
                            recognizer.acceptWaveForm(buffer, bytesRead)
                        }
                    }
                    // Feed thêm silence vào recognizer
                    val silenceDurationMs = 300
                    val numSamplesSilence = (sampleRate * silenceDurationMs / 1000)
                    val silenceBytes = ByteArray(numSamplesSilence * 2) // *2 vì 16-bit
                    if (silenceBytes.isNotEmpty()) {
                        Log.d("PracticeFragment", "PostProcess: Feeding ${silenceDurationMs}ms of silence to Vosk.")
                        recognizer.acceptWaveForm(silenceBytes, silenceBytes.size)
                    }
                    finalResultJson = recognizer.finalResult
                    recognizer.close()
                } catch (e: IOException) {
                    Log.e("PracticeFragment", "PostProcess: IOException during Vosk processing (reading PCM or feeding recognizer)", e)
                    activity?.runOnUiThread { /* Toast lỗi, ẩn indicator */ }
                    return@thread
                } catch (e: Exception) { // Bắt lỗi chung từ Vosk
                    Log.e("PracticeFragment", "PostProcess: Exception during Vosk recognition", e)
                    activity?.runOnUiThread { /* Toast lỗi, ẩn indicator */ }
                    return@thread
                }

                Log.d("PracticeFragment", "PostProcess: Final Vosk JSON: $finalResultJson")

                // Tạo file WAV (cho việc phát lại sau này) - Streaming I/O
                val wavFile = audioFilePath?.let { File(it) } // audioFilePath là đường dẫn .wav
                var wavFileSuccessfullyCreated = false
                if (wavFile != null && currentPcmFile.exists()) { // Đảm bảo pcm nguồn vẫn còn (nếu addWavHeader không xóa)
                    Log.d("PracticeFragment", "PostProcess: Creating WAV file: ${wavFile.absolutePath} from PCM: ${currentPcmFile.absolutePath}")
                    try {
                        // Đọc lại pcmData cho việc tạo WAV header, hoặc truyền dataSize
                        val dataSizeForWavHeader = currentPcmFile.length().toInt()

                        FileOutputStream(wavFile).use { fos -> // Stream để ghi WAV
                            val header = createWavHeader(dataSizeForWavHeader) // Hàm của bạn
                            fos.write(header)

                            FileInputStream(currentPcmFile).use { fisPcmForWav -> // Stream để đọc PCM
                                val bufferWav = ByteArray(4096)
                                var bytesReadWav: Int
                                while (fisPcmForWav.read(bufferWav).also { bytesReadWav = it } > 0) {
                                    fos.write(bufferWav, 0, bytesReadWav)
                                }
                            }
                        }
                        wavFileSuccessfullyCreated = wavFile.exists() && wavFile.length() > (44) // Header size
                        Log.d("PracticeFragment", "PostProcess: WAV file created successfully: $wavFileSuccessfullyCreated")
                        // --- THÊM HOẶC BỎ COMMENT VÀ SỬA ĐOẠN NÀY ---
                        if (wavFileSuccessfullyCreated && currentPcmFile.exists()) {
                            if (!currentPcmFile.delete()) {
                                Log.w("PracticeFragment", "PostProcess: Failed to delete PCM file: ${currentPcmFile.name}")
                            } else {
                                Log.d("PracticeFragment", "PostProcess: PCM file deleted successfully: ${currentPcmFile.name}")
                            }
                        }
                        // --- KẾT THÚC ĐOẠN THÊM/SỬA ---
                    } catch (e: Exception) {
                        Log.e("PracticeFragment", "PostProcess: Error creating WAV file", e)
                    }
                } else {
                    Log.w("PracticeFragment", "PostProcess: WAV file object or PCM source for WAV is null/missing.")
                }

                // Cập nhật UI với kết quả chấm điểm
                val feedback = processVoskResult(finalResultJson ?: "{\"text\":\"\"}", targetForThread)
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        displayEvaluation(feedback.score)
                        binding.wordTextView.text = feedback.coloredTarget
                        binding.playUserButton.isEnabled = wavFileSuccessfullyCreated
                        binding.playUserButton.alpha = if (wavFileSuccessfullyCreated) 1.0f else 0.5f
                        updatePlayUserButtonUI() // Cập nhật icon nút play
                        // Ẩn processing indicator
                        // binding.voskProcessingIndicator.visibility = View.GONE
                    }
                }

            } catch (e: Exception) { // Lỗi chung của PostProcessThread
                Log.e("PracticeFragment", "Outer error in PostProcessThread", e)
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        Toast.makeText(requireContext(), "Error processing audio.", Toast.LENGTH_SHORT).show()
                        // Ẩn processing indicator
                        // binding.voskProcessingIndicator.visibility = View.GONE
                        // Reset điểm
                        processVoskResult("", targetForThread).let { errorFeedback ->
                            displayEvaluation(errorFeedback.score)
                            binding.wordTextView.text = errorFeedback.coloredTarget
                        }
                        binding.playUserButton.isEnabled = false
                        binding.playUserButton.alpha = 0.5f
                    }
                }
            } finally {
                Log.d("PracticeFragment", "PostProcessThread finished. Vosk JSON was: $finalResultJson")
            }
        }
    }

    private fun playUserRecording() {
        if (!isAdded || _binding == null) return
        val audioFile = audioFilePath?.let { File(it) } ?: run {
            Toast.makeText(requireContext(), "Audio file path is invalid.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Toast.makeText(requireContext(), "No recording available to play.", Toast.LENGTH_SHORT).show()
            binding.playUserButton.isEnabled = false
            binding.playUserButton.alpha = 0.5f
            return
        }

        if (exoPlayer?.isPlaying == true) {
            stopUserPlayback()
        } else {
            exoPlayer?.release()
            exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
                val mediaItem = MediaItem.fromUri(audioFile.toUri())
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true

                binding.playUserButton.isEnabled = false
                updatePlayUserButtonUI()

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!isAdded || _binding == null) return
                        when (playbackState) {
                            Player.STATE_ENDED, Player.STATE_IDLE -> {
                                stopUserPlayback()
                            }
                            Player.STATE_READY -> {
                                binding.playUserButton.isEnabled = true
                            }
                            Player.STATE_BUFFERING -> {
                                binding.playUserButton.isEnabled = false
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!isAdded || _binding == null) return
                        Log.e("PracticeFragment", "ExoPlayer error: $error")
                        Toast.makeText(requireContext(), "Error playing recording.", Toast.LENGTH_SHORT).show()
                        stopUserPlayback()
                    }
                })
            }
        }
    }

    private fun stopUserPlayback() {
        if (_binding == null) {
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            return
        }
        activity?.runOnUiThread {
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            if (isAdded && _binding != null) {
                val audioFile = audioFilePath?.let { File(it) }
                val canPlay = audioFile != null && audioFile.exists() && audioFile.length() > 0L
                binding.playUserButton.isEnabled = canPlay
                binding.playUserButton.alpha = if(canPlay) 1.0f else 0.5f
                updatePlayUserButtonUI()
            }
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
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
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
                    aligned1.add(s1[i - 1])
                    aligned2.add(s2[j - 1])
                    ops.add(if (cost == 0) 'M' else 'S')
                    i--; j--; continue
                }
            }
            if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                aligned1.add(s1[i - 1])
                aligned2.add('-')
                ops.add('D')
                i--; continue
            }
            if (j > 0 && dp[i][j] == dp[i][j - 1] + 1) {
                aligned1.add('-')
                aligned2.add(s2[j - 1])
                ops.add('I')
                j--; continue
            }
            if (i > 0) { aligned1.add(s1[i - 1]); aligned2.add('-'); ops.add('D'); i--; continue }
            if (j > 0) { aligned1.add('-'); aligned2.add(s2[j - 1]); ops.add('I'); j--; continue }
        }
        aligned1.reverse(); aligned2.reverse(); ops.reverse()
        return Triple(aligned1, aligned2, ops)
    }

    private fun processVoskResult(
        voskJson: String,
        originalTargetWordForDisplay: String
    ): PronunciationFeedback {
        val logTag = "ScoreLogicV3"
        val targetNormalized = originalTargetWordForDisplay.lowercase().trim()
        val spannable = SpannableString(originalTargetWordForDisplay)

        fun createErrorFeedback(defaultScore: Int = -1): PronunciationFeedback {
            if (originalTargetWordForDisplay.isNotEmpty()) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.RED),
                    0,
                    originalTargetWordForDisplay.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            Log.d(logTag, "createErrorFeedback: score=$defaultScore target='$originalTargetWordForDisplay'")
            return PronunciationFeedback(defaultScore, spannable)
        }

        if (targetNormalized.isBlank() || voskJson.isBlank()) {
            val score = if (targetNormalized.isBlank()) -1 else 0
            Log.w(logTag, "Invalid inputs. targetBlank=${targetNormalized.isBlank()}, voskJsonBlank=${voskJson.isBlank()}")
            return createErrorFeedback(score)
        }

        return try {
            val voskFullResult = gson.fromJson(voskJson, InternalVoskFullResult::class.java)
            val recognizedTextFullRaw = voskFullResult.text ?: ""
            val recognizedTextNormalized = recognizedTextFullRaw.lowercase().trim()

            Log.d(logTag, "recognizedTextNormalized='$recognizedTextNormalized'")

            if (recognizedTextNormalized.isBlank()) {
                Log.d(logTag, "recognized text blank -> score 0")
                return createErrorFeedback(0)
            }

            val recognizedWords = voskFullResult.result?.map { it.word.lowercase().trim() } ?: emptyList()
            val recognizedConfs = voskFullResult.result?.map { it.conf } ?: emptyList()

            var bestIndex = -1
            var bestDist = Int.MAX_VALUE
            for ((idx, rw) in recognizedWords.withIndex()) {
                if (rw.isEmpty()) continue
                val d = levenshtein(targetNormalized, rw)
                if (d < bestDist) {
                    bestDist = d
                    bestIndex = idx
                }
                if (d == 0) break
            }

            var finalScore = -1
            var matchedWord: String? = null
            var matchedConf: Float?

            if (bestIndex >= 0) {
                matchedWord = recognizedWords[bestIndex]
                matchedConf = recognizedConfs.getOrNull(bestIndex)
                finalScore = if (matchedConf != null) {
                    (matchedConf * 100).toInt().coerceIn(0, 100)
                } else {
                    val maxLen = maxOf(targetNormalized.length, matchedWord.length)
                    val sim = if (maxLen == 0) 1.0 else (1.0 - bestDist.toDouble() / maxLen)
                    (sim * 100).toInt().coerceIn(0, 100)
                }

                if (bestDist > 0 && targetNormalized.isNotEmpty()) {
                    val penaltyRatio = bestDist.toDouble() / targetNormalized.length
                    val penaltyAmount = (finalScore * penaltyRatio).toInt()
                    finalScore = (finalScore - penaltyAmount).coerceAtLeast(0)
                }
                Log.d(logTag, "Matched word='$matchedWord' conf=$matchedConf dist=$bestDist score=$finalScore")
            } else {
                finalScore = calculateFlexibleScore(targetNormalized, recognizedTextNormalized)
                Log.d(logTag, "No per-word match; fallback score=$finalScore")
            }

            val alignmentSource = matchedWord ?: recognizedTextNormalized
            try {
                val (alignedT, _, ops) = alignStrings(targetNormalized, alignmentSource)

                var origIndexPointer = 0
                val originalLower = originalTargetWordForDisplay.lowercase()
                for (k in alignedT.indices) {
                    val chT = alignedT[k]
                    val op = ops[k]
                    if (chT == '-') {
                        continue
                    }
                    var matchedOrigIdx = -1
                    var searchIdx = origIndexPointer
                    while (searchIdx < originalLower.length) {
                        if (originalLower[searchIdx] == chT) {
                            matchedOrigIdx = searchIdx
                            break
                        }
                        searchIdx++
                    }
                    if (matchedOrigIdx == -1) {
                        if (origIndexPointer < originalLower.length) matchedOrigIdx = origIndexPointer else continue
                    }
                    val color = if (op == 'M') Color.GREEN else Color.RED
                    if (matchedOrigIdx < originalTargetWordForDisplay.length) {
                        spannable.setSpan(
                            ForegroundColorSpan(color),
                            matchedOrigIdx,
                            matchedOrigIdx + 1,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    origIndexPointer = matchedOrigIdx + 1
                }
            } catch (e: Exception) {
                Log.w(logTag, "Alignment coloring failed, fallback to coarse coloring", e)
                val wholeColor = if (finalScore >= 70) Color.GREEN else Color.RED
                if (originalTargetWordForDisplay.isNotEmpty()) {
                    spannable.setSpan(
                        ForegroundColorSpan(wholeColor),
                        0,
                        originalTargetWordForDisplay.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            PronunciationFeedback(finalScore.coerceIn(0, 100), spannable)
        } catch (e: Exception) {
            Log.e(logTag, "Error in processVoskResult", e)
            createErrorFeedback()
        }
    }

    private fun calculateFlexibleScore(target: String, recognized: String): Int {
        if (recognized.isBlank()) {
            return -1
        }
        val t = target.lowercase().trim().replace("\\s+".toRegex(), " ")
        val r = recognized.lowercase().trim().replace("\\s+".toRegex(), " ")

        if (r.isBlank()) {
            return if (t.isBlank()) 100 else -1
        }
        if (t.isBlank() && r.isNotBlank()){
            return 0
        }
        val distance = levenshtein(t, r)
        val maxLen = maxOf(t.length, r.length)

        if (maxLen == 0) {
            return 100
        }
        val score = ((1.0 - distance.toDouble() / maxLen) * 100).toInt()
        return score.coerceIn(0, 100)
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
        if (_binding == null) return
        pulsatingAnimator?.cancel()
        binding.pulsatingView.visibility = View.GONE
        binding.pulsatingView.alpha = 1f
        binding.pulsatingView.scaleX = 1f
        binding.pulsatingView.scaleY = 1f
    }

    private fun updateRecordButtonUI() {
        if (_binding == null || !isAdded) return
        val icon = if (isRecording) R.drawable.ic_close else R.drawable.ic_mic
        binding.recordButton.setImageResource(icon)
    }

    private fun updatePlayUserButtonUI() {
        if (_binding == null || !isAdded) return
        val isPlaying = exoPlayer?.isPlaying == true && exoPlayer?.playbackState != Player.STATE_ENDED && exoPlayer?.playbackState != Player.STATE_IDLE
        val icon = if (isPlaying) R.drawable.ic_close else R.drawable.ic_speaker_wave
        binding.playUserButton.setImageResource(icon)
    }

    @Throws(IOException::class)
    private fun addWavHeader(pcmFile: File, wavFile: File) {
        if (!pcmFile.exists()) {
            Log.e("PracticeFragment", "PCM file does not exist for WAV header: ${pcmFile.absolutePath}")
            return
        }
        val pcmData = pcmFile.readBytes()
        if (pcmData.isEmpty()) {
            Log.w("PracticeFragment", "PCM data is empty for: ${pcmFile.absolutePath}. Cannot create valid WAV.")
            if (wavFile.exists()) wavFile.delete()
            return
        }
        val wavHeader = createWavHeader(pcmData.size)
        FileOutputStream(wavFile).use {
            it.write(wavHeader)
            it.write(pcmData)
        }
        Log.d("PracticeFragment", "WAV header added to: ${wavFile.absolutePath}")
        if (!pcmFile.delete()) {
            Log.w("PracticeFragment", "Failed to delete PCM file: ${pcmFile.absolutePath}")
        }
    }

    private fun createWavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        val totalDataLen = dataSize + 36
        val channels = 1
        val byteRate = sampleRate * channels * (16 / 8)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte(); header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte(); header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte(); header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte(); header[41] = (dataSize shr 8 and 0xff).toByte(); header[42] = (dataSize shr 16 and 0xff).toByte(); header[43] = (dataSize shr 24 and 0xff).toByte()
        return header
    }

    override fun onStop() {
        super.onStop()
        if (isRecording) {
            Log.d("PracticeFragment", "onStop called while recording, stopping recording.")
            stopRecording()
        }
        nativeMediaPlayer?.release()
        nativeMediaPlayer = null
        stopUserPlayback()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulsatingAnimator?.cancel()
        pulsatingAnimator = null
        stopUserPlayback()
        _binding = null
        Log.d("PracticeFragment", "onDestroyView called, _binding is now null.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PracticeFragment", "onDestroy called. Shared Vosk model is managed by ViewModel.")
    }
}
