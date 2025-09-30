package com.example.realtalkenglishwithAI.activity

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.ActivityStoryBinding // Import ViewBinding
import java.io.IOException

class StoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoryBinding // Declare binding variable
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFilePath: String = ""
    private var isRecording = false

    private val RECORD_AUDIO_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoryBinding.inflate(layoutInflater) // Initialize binding
        setContentView(binding.root)

        // Setup Toolbar
        setSupportActionBar(binding.toolbarStory) // Use binding for toolbar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false) // Hide default title if using custom TextView

        // Handle Toolbar navigation click (back button)
        binding.toolbarStory.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed() // Or finish()
        }

        // Initialize audio file path
        audioFilePath = "${externalCacheDir?.absolutePath}/story_audio_recorded.3gp"

        // Set initial state for play button
        binding.buttonPlayStoryRecording.isEnabled = false
        binding.buttonPlayStoryRecording.alpha = 0.5f // Make it visually disabled

        binding.buttonRecordStory.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                if (checkPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            }
        }

        binding.buttonPlayStoryRecording.setOnClickListener {
            playAudio()
        }
    }

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, "Permission Denied to record audio", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(audioFilePath)
                    prepare()
                    start()
                }
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(audioFilePath)
                    prepare()
                    start()
                }
            }
            isRecording = true
            binding.buttonRecordStory.setImageResource(R.drawable.ic_close) // Change to stop icon
            binding.buttonPlayStoryRecording.isEnabled = false
            binding.buttonPlayStoryRecording.alpha = 0.5f
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e("StoryActivity", "startRecording failed: ${e.message}")
            Toast.makeText(this, "Recording failed to start", Toast.LENGTH_SHORT).show()
        } catch (e: IllegalStateException) {
            Log.e("StoryActivity", "startRecording failed (IllegalStateException): ${e.message}")
            Toast.makeText(this, "Recording failed to start", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: RuntimeException) { // Catch RuntimeException from stop() if called too quickly
            Log.w("StoryActivity", "RuntimeException on MediaRecorder.stop: ${e.message}")
            // Optionally delete the potentially corrupt file
            // File(audioFilePath).delete()
        } finally {
            mediaRecorder = null
            isRecording = false
            binding.buttonRecordStory.setImageResource(R.drawable.ic_mic) // Change back to mic icon
            binding.buttonPlayStoryRecording.isEnabled = true
            binding.buttonPlayStoryRecording.alpha = 1.0f
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playAudio() {
        if (binding.buttonPlayStoryRecording.isEnabled && audioFilePath.isNotEmpty()) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(audioFilePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        Toast.makeText(this@StoryActivity, "Playback finished", Toast.LENGTH_SHORT).show()
                        it.release()
                        mediaPlayer = null
                        // Optionally update UI for play button (e.g., change icon to play)
                    }
                }
                Toast.makeText(this, "Playing audio", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Log.e("StoryActivity", "playAudio failed (IOException): ${e.message}")
                Toast.makeText(this, "Could not play audio", Toast.LENGTH_SHORT).show()
            } catch (e: IllegalStateException) {
                Log.e("StoryActivity", "playAudio failed (IllegalStateException): ${e.message}")
                Toast.makeText(this, "Could not play audio", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No recording available to play", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed() // Or finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
