package com.example.realtalkenglishwithAI.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.example.realtalkenglishwithAI.MyApplication
import com.example.realtalkenglishwithAI.navigation.Screen
import com.example.realtalkenglishwithAI.navigation.bottomNavScreens
import com.example.realtalkenglishwithAI.ui.screens.HomeScreen
import com.example.realtalkenglishwithAI.ui.screens.PracticeScreen
import com.example.realtalkenglishwithAI.ui.screens.ProgressScreen
import com.example.realtalkenglishwithAI.ui.screens.ProfileScreen
import com.example.realtalkenglishwithAI.ui.theme.RealTalkEnglishWithAITheme // Đảm bảo theme này tồn tại
import com.example.realtalkenglishwithAI.viewmodel.ModelState
import com.example.realtalkenglishwithAI.viewmodel.VoskModelViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val logTag = "MainActivityVosk"
    private val voskModelViewModel: VoskModelViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* isGranted: Boolean -> */
            // Xử lý kết quả quyền nếu cần
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RealTalkEnglishWithAITheme { // Áp dụng theme của ứng dụng
                MainAppNavigation() // Đổi tên MainScreen thành MainAppNavigation cho rõ ràng
            }
        }

        askNotificationPermission()
        observeAndTriggerModelInitialization()

        voskModelViewModel.errorMessage.observe(this) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(this, "Model Init Error: $it", Toast.LENGTH_LONG).show()
                Log.e(logTag, "VoskModelViewModel error: $it")
            }
        }
    }

    private fun observeAndTriggerModelInitialization() {
        lifecycleScope.launch {
            (applicationContext as MyApplication).unpackingState.collect { state ->
                when (state) {
                    ModelState.READY -> {
                        Log.i(logTag, "MyApplication báo model đã unpack. Đang khởi tạo VoskModelViewModel.")
                        voskModelViewModel.initModelAfterUnzip()
                    }
                    ModelState.LOADING -> {
                        Log.i(logTag, "MyApplication đang unpack/xác minh Vosk model...")
                    }
                    ModelState.ERROR -> {
                        Log.e(logTag, "MyApplication báo lỗi khi unpack Vosk model.")
                        Toast.makeText(this@MainActivity, "Lỗi nghiêm trọng: Không thể chuẩn bị tài nguyên model giọng nói.", Toast.LENGTH_LONG).show()
                    }
                    ModelState.IDLE -> {
                        Log.d(logTag, "Trạng thái unpacking của MyApplication là IDLE.")
                    }
                     // Thêm trường hợp else hoặc null check nếu cần
                    else -> {
                        Log.d(logTag, "Trạng thái unpacking không xác định: $state")
                    }
                }
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // Scaffold là experimental trong M3
@Composable
fun MainAppNavigation() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavScreens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen(/* Có thể truyền navController nếu HomeScreen cần */) }
            composable(Screen.Practice.route) { PracticeScreen(/* ... */) }
            composable(Screen.Progress.route) { ProgressScreen(/* ... */) }
            composable(Screen.Profile.route) { ProfileScreen(/* ... */) }
            // Các composable route khác có thể được định nghĩa ở đây
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    RealTalkEnglishWithAITheme {
        MainAppNavigation()
    }
}
