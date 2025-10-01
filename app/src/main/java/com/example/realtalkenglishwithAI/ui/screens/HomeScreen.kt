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
fun HomeScreen() {
    // Nội dung chi tiết của Home Screen sẽ được thêm vào sau
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Home Screen Content")
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    RealTalkEnglishWithAITheme {
        HomeScreen()
    }
}
