package com.example.realtalkenglishwithai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    /* Other default text styles to override if needed
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    */
)

// Nếu bạn có font chữ tùy chỉnh (ví dụ: dancing_script.ttf),
// bạn sẽ cần khai báo FontFamily ở đây và tải nó từ thư mục res/font.
// Ví dụ:
// val DancingScriptFontFamily = FontFamily(
//     Font(R.font.dancing_script, FontWeight.Normal)
// )
// Sau đó bạn có thể sử dụng nó trong AppTypography:
// titleLarge = TextStyle(
//     fontFamily = DancingScriptFontFamily,
//     fontWeight = FontWeight.Normal,
//     fontSize = 30.sp // điều chỉnh kích thước cho phù hợp
// ),
