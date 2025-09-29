package com.example.realtalkenglishwithAI.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.realtalkenglishwithAI.databinding.ActivityTextDisplayBinding

class TextDisplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextDisplayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra("TITLE")
        val content = intent.getStringExtra("CONTENT")

        binding.toolbar.title = title
        binding.contentTextView.text = content

        binding.toolbar.setNavigationOnClickListener {
            finish() // Closes this activity and returns to the previous one
        }
    }
}

