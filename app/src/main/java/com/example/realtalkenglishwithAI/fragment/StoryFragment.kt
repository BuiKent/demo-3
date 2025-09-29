package com.example.realtalkenglishwithAI.fragment

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.realtalkenglishwithAI.databinding.FragmentStoryBinding

class StoryFragment : Fragment() {

    private var _binding: FragmentStoryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        // TODO: Thêm logic cho việc ghi âm và phát lại ở đây
        binding.recordFab.setOnClickListener {
            // Xử lý khi nhấn nút ghi âm
        }

        binding.playFab.setOnClickListener {
            // Xử lý khi nhấn nút phát lại
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}