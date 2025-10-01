package com.example.realtalkenglishwithai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.realtalkenglishwithai.ui.theme.RealTalkEnglishWithAITheme

@Composable
fun PracticeScreen() {
    // Nội dung chi tiết của Practice Screen sẽ được thêm vào sau
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Practice Screen Content")
    }
}

@Preview(showBackground = true)
@Composable
fun PracticeScreenPreview() {
    RealTalkEnglishWithAITheme {
        PracticeScreen()
    }
}
