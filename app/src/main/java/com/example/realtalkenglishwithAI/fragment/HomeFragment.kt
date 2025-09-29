package com.example.realtalkenglishwithAI.fragment

import android.content.Intent // Added for Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.realtalkenglishwithAI.activity.StoryActivity // Added for StoryActivity
import com.example.realtalkenglishwithAI.adapter.RoleplayScenarioAdapter
import com.example.realtalkenglishwithAI.databinding.FragmentHomeBinding
import com.example.realtalkenglishwithAI.model.RoleplayScenario

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRoleplayRecyclerView()

        // Set OnClickListener for Reading Today CardView
        binding.cardViewReadingToday.setOnClickListener {
            val intent = Intent(requireActivity(), StoryActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupRoleplayRecyclerView() {
        val scenarios = listOf(
            RoleplayScenario("Greetings", "https://placehold.co/300x400/6200EE/FFFFFF?text=Hi!"),
            RoleplayScenario("At a Cafe", "https://placehold.co/300x400/03DAC5/000000?text=Cafe"),
            RoleplayScenario("At Home", "https://placehold.co/300x400/3700B3/FFFFFF?text=Home"),
            RoleplayScenario("Travel", "https://placehold.co/300x400/000000/FFFFFF?text=Travel")
        )

        val adapter = RoleplayScenarioAdapter(scenarios)
        binding.roleplayRecyclerView.adapter = adapter
        binding.roleplayRecyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
