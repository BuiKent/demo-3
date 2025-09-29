package com.example.realtalkenglishwithAI.fragment

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.example.realtalkenglishwithAI.databinding.FragmentProgressBinding
import com.example.realtalkenglishwithAI.viewmodel.ProgressViewModel

class ProgressFragment : Fragment() {

    private var _binding: FragmentProgressBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProgressViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProgressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.userProgress.observe(viewLifecycleOwner) { progress ->
            // Use a placeholder for total lessons until it's implemented
            val totalLessons = 10 // Example total
            val completedCount = progress?.completedLessons ?: 0

            val percentage = if (totalLessons > 0) {
                (completedCount * 100 / totalLessons)
            } else {
                0
            }

            // Use the correct IDs from the layout file: progressBar and progressTextView
            binding.progressBar.progress = percentage
            binding.progressTextView.text = "$completedCount out of $totalLessons lessons completed"
        }

        // For demo purposes, we'll increment progress by 1 each time the screen is viewed.
        // In a real app, you would update this based on user actions.
        // The observer will automatically catch the initial value, so we can update it here.
        val currentProgress = viewModel.userProgress.value?.completedLessons ?: 0
        if (currentProgress < 10) { // Simple cap for demo
            viewModel.updateProgress(currentProgress + 1)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

