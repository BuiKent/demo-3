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
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.realtalkenglishwithAI.MyApplication
// Quan trọng: Import MainAppNavigation từ package navigation
import com.example.realtalkenglishwithAI.navigation.MainAppNavigation
import com.example.realtalkenglishwithAI.ui.theme.RealTalkEnglishWithAITheme
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
                MainAppNavigation() // Gọi hàm MainAppNavigation đã được import
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

// Hàm Preview này vẫn giữ nguyên vì nó cần gọi MainAppNavigation
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    RealTalkEnglishWithAITheme {
        MainAppNavigation()
    }
}
