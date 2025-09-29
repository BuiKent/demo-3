package com.example.realtalkenglishwithAI.fragment

import android.content.Intent // Keep for other intents if any, or remove if not used elsewhere
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController // Added for Navigation
import androidx.recyclerview.widget.LinearLayoutManager
// import com.example.realtalkenglishwithAI.activity.StoryActivity // No longer navigating directly to StoryActivity from here
import com.example.realtalkenglishwithAI.R // Added for R.id access
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

        // Set OnClickListener for Reading Today CardView to navigate to StoryReadingFragment
        binding.cardViewReadingToday.setOnClickListener {
            // Sample story data. In a real app, you'd fetch this based on the selected story.
            val sampleStoryTitle = "Monday Story" // Added story title
            val sampleStoryContent = "Once upon a time, in a land far, far away, there lived a brave Coder. The Coder loved to write Kotlin and build amazing Android apps. One day, a mischievous Bug appeared, causing trouble in the Code Kingdom. The Coder, with skill and determination, set out on a quest to debug the system and restore peace."
            
            val bundle = Bundle().apply {
                putString("story_title", sampleStoryTitle) // Key matches argument in nav_graph.xml
                putString("story_content", sampleStoryContent) // Key matches argument in nav_graph.xml (renamed from story_text)
            }
            try {
                findNavController().navigate(R.id.action_homeFragment_to_storyReadingFragment, bundle)
            } catch (e: Exception) {
                // Log error or show a toast if navigation fails (e.g., action ID not found)
                android.util.Log.e("HomeFragment", "Navigation to StoryReadingFragment failed", e)
            }
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
