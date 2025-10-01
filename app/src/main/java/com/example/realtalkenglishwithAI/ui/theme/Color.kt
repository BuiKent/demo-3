package com.example.realtalkenglishwithai.ui.theme

import androidx.compose.ui.graphics.Color

// Default Material Design Colors (từ khi tạo Theme.kt ban đầu)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Các màu App ví dụ đã thêm trước đó
val AppColorPrimary = Color(0xFF006AF6) // Màu Xanh dương ví dụ, có thể khác với 'primary' của theme
val AppColorAccent = Color(0xFF00A0FF) // Màu Xanh nhạt ví dụ, có thể khác với 'accent/secondary' của theme

// Các màu được chuyển từ res/values/colors.xml
val XmlBlack = Color(0xFF000000)
val AppWhite = Color(0xFFFFFFFF) // Đây là màu 'white' trong colors.xml và cũng là 'AppWhite' đã có
val Red500 = Color(0xFFF44336)
val Gray500 = Color(0xFF757575)
val LightGrayBackground = Color(0xFFF5F5F5)
val Blue500 = Color(0xFF2196F3)
val LightBlue200 = Color(0xFF90CAF9)
val LightBlue200Transparent = Color(0xB290CAF9) // Alpha 70% cho #90CAF9 (ARGB)
val XmlColorPrimaryOriginal = Color(0xFF000000) // Đây là 'colorPrimary' trong colors.xml (màu đen)
val XmlColorAccentOriginal = Color(0xFFFF4081)  // Đây là 'colorAccent' trong colors.xml (màu hồng)
val LightBlueBorder = Color(0xFFE0E0E0)
val NavIconSelectedColor = Color(0xFF000000)   // Đây là 'nav_icon_selected' (màu đen)
val NavIconUnselectedColor = Color(0xFF757575) // Đây là 'nav_icon_unselected' (màu gray_500)

// Ghi chú:
// - colorPrimary (0xFF000000) từ colors.xml giờ là XmlColorPrimaryOriginal.
// - colorAccent (0xFFFF4081) từ colors.xml giờ là XmlColorAccentOriginal.
// Các tên này được chọn để tránh xung đột với các vai trò màu trong MaterialTheme (primary, secondary)
// và các màu AppColorPrimary/AppColorAccent đã định nghĩa trước đó.
// Bạn sẽ sử dụng các tên `val` cụ thể này khi cần chính xác các màu này.
// Đối với MaterialTheme, bạn sẽ gán màu cho các vai trò như 'primary', 'secondary' trong file Theme.kt.
