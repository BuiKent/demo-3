package com.example.realtalkenglishwithAI.ui.story

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.realtalkenglishwithAI.viewmodel.ModelState // Import ModelState enum
import com.example.realtalkenglishwithAI.viewmodel.VoskModelViewModel

class StoryReadingHostFragment : Fragment() {

    private val voskModelViewModel: VoskModelViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme { // Replace with YourAppTheme if you have one
                    val modelState by voskModelViewModel.modelState.observeAsState()
                    val voskModel = voskModelViewModel.voskModel // Access the model instance directly

                    val storyTitle = arguments?.getString("story_title")
                    val storyContent = arguments?.getString("story_content")

                    Log.d("StoryReadingHost", "Observed ModelState: $modelState, VoskModel null: ${voskModel == null}")

                    when (modelState) {
                        ModelState.LOADING -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                                Text(" Loading Speech Model...")
                            }
                        }
                        ModelState.READY -> {
                            if (voskModel != null && storyTitle != null && storyContent != null) {
                                StoryReadingScreen(
                                    navController = findNavController(),
                                    storyTitleArg = storyTitle,
                                    storyContentArg = storyContent,
                                    voskModel = voskModel
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(if(voskModel == null) "Speech model not ready yet." else "Error: Story data missing.")
                                }
                            }
                        }
                        ModelState.ERROR -> {
                            val errorMessage = voskModelViewModel.errorMessage.value ?: "Failed to load speech model."
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Error: $errorMessage Please restart the app.")
                            }
                        }
                        ModelState.IDLE, null -> { // Null check for LiveData initial state
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Initializing speech model... Please wait.")
                                // VoskModelViewModel should be triggered by MainActivity to move from IDLE
                            }
                        }
                    }
                }
            }
        }
    }
}
