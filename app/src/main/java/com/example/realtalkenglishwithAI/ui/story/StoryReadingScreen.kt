package com.example.realtalkenglishwithAI.ui.story

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack // Updated import
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.realtalkenglishwithAI.model.WordStatus
import com.example.realtalkenglishwithAI.viewmodel.StoryReadingViewModel
import org.vosk.Model

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryReadingScreen(
    navController: NavController,
    storyTitleArg: String?,
    storyContentArg: String?,
    voskModel: Model?, // The loaded Vosk Model instance
    storyReadingViewModel: StoryReadingViewModel = viewModel()
) {
    val context = LocalContext.current

    // States from ViewModel
    val storyTitle by storyReadingViewModel.storyTitle.collectAsState()
    val displayWords by storyReadingViewModel.displayWords.collectAsState()
    val isRecording by storyReadingViewModel.isRecording.collectAsState()
    val isPlayingUserAudio by storyReadingViewModel.isPlayingUserAudio.collectAsState()
    val canPlayUserRecording by storyReadingViewModel.canPlayUserRecording.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                storyReadingViewModel.toggleRecording(context) // Pass context if needed by VM
            } else {
                // Handle permission denial, e.g., show a Snackbar
                // Consider showing a rationale before requesting if appropriate
            }
        }
    )

    LaunchedEffect(key1 = storyTitleArg, key2 = storyContentArg, key3 = voskModel) {
        if (voskModel != null && storyTitleArg != null && storyContentArg != null) {
            storyReadingViewModel.initialize(context, voskModel, storyTitleArg, storyContentArg)
        } else {
            if (voskModel == null) {
                // This case should be handled by StoryReadingHostFragment showing loading/error
                // If it still reaches here with voskModel null, it might indicate an issue in flow
                navController.popBackStack() // Fallback error handling
            }
        }
    }

    // Ensure playback stops when the composable is disposed or recording starts
    DisposableEffect(Unit) {
        onDispose {
            if(isPlayingUserAudio) storyReadingViewModel.togglePlayUserRecording() //Stops playback
        }
    }
    // Stop playback if user starts recording
    LaunchedEffect(isRecording) {
        if (isRecording && isPlayingUserAudio) {
            storyReadingViewModel.togglePlayUserRecording() // Stop playback
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(storyTitle) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") // Updated usage
                    }
                }
            )
        },
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Story Text Area
                Text(
                    text = buildAnnotatedString {
                        displayWords.forEach { word ->
                            val color = when (word.status) {
                                WordStatus.UNREAD -> Color.Gray
                                WordStatus.CORRECT -> Color(0xFF4CAF50) // Green
                                WordStatus.INCORRECT -> Color.Red
                            }
                            withStyle(style = SpanStyle(color = color, fontSize = 22.sp)) {
                                append(word.originalText)
                            }
                            append(" ") // Space between words
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    lineHeight = 34.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Record Button
                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = voskModel != null && !isPlayingUserAudio // Disable if playing user audio
                    ) {
                        Icon(
                            if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isRecording) "Stop Recording" else "Start Recording"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isRecording) "Stop" else "Record")
                    }

                    // Play User Recording Button
                    Button(
                        onClick = { storyReadingViewModel.togglePlayUserRecording() },
                        enabled = canPlayUserRecording && !isRecording // Disable if recording
                    ) {
                        Icon(
                           if(isPlayingUserAudio) Icons.Filled.Pause else Icons.Filled.PlayArrow, 
                           contentDescription = if(isPlayingUserAudio) "Pause User Recording" else "Play User Recording"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if(isPlayingUserAudio) "Pause" else "Play")
                    }
                    
                    // Reset Button
                    IconButton(
                        onClick = { storyReadingViewModel.resetStoryState() },
                        enabled = !isRecording && !isPlayingUserAudio // Disable if recording or playing
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset Story")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    )
}
