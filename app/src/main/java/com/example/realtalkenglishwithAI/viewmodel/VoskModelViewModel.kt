package com.example.realtalkenglishwithAI.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import kotlinx.coroutines.*
import org.vosk.Model // Đảm bảo import đúng class Model của Vosk
import java.io.File
import java.io.IOException

// Định nghĩa ModelState enum class tại đây
enum class ModelState {
    IDLE,     // Trạng thái ban đầu, chưa có hành động gì
    LOADING,  // Đang trong quá trình tải hoặc khởi tạo model
    READY,    // Model đã sẵn sàng để sử dụng
    ERROR     // Có lỗi xảy ra trong quá trình tải hoặc khởi tạo model
}

class VoskModelViewModel(application: Application) : AndroidViewModel(application) {

    private val _modelState = MutableLiveData<ModelState>(ModelState.IDLE)
    val modelState: LiveData<ModelState> = _modelState

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private var _voskModelInstance: Model? = null
    val voskModel: Model?
        get() = if (_modelState.value == ModelState.READY) _voskModelInstance else null

    private val modelJob = SupervisorJob()
    private val modelScope = CoroutineScope(Dispatchers.IO + modelJob)

    // MainActivity sẽ gọi hàm này sau khi đảm bảo model đã được giải nén vào filesDir
    fun initModelAfterUnzip() {
        if (_modelState.value == ModelState.READY && _voskModelInstance != null) {
            Log.d("VoskModelViewModel", "Model already initialized and ready.")
            return
        }
        if (_modelState.value == ModelState.LOADING) {
            Log.d("VoskModelViewModel", "Model is already being loaded.")
            return
        }

        _modelState.postValue(ModelState.LOADING)
        _errorMessage.postValue(null)

        modelScope.launch {
            try {
                val appContext = getApplication<Application>().applicationContext
                // Đường dẫn đến thư mục model đã giải nén, ví dụ: "files/model"
                val modelPath = File(appContext.filesDir, "model")
                Log.d("VoskModelViewModel", "Attempting to load Vosk model from: ${modelPath.absolutePath}")

                // Kiểm tra sự tồn tại của các file/thư mục con quan trọng của model
                val checkFileAm = File(modelPath, "am")
                val checkFileConf = File(modelPath, "conf")

                if (!modelPath.exists() || !modelPath.isDirectory ||
                    !checkFileAm.exists() || !checkFileAm.isDirectory ||
                    !checkFileConf.exists() || !checkFileConf.isDirectory ||
                    modelPath.listFiles().isNullOrEmpty()) {
                    Log.e("VoskModelViewModel", "Model directory or essential sub-directories (am/conf) not found or empty at: ${modelPath.absolutePath}")
                    _voskModelInstance = null
                    _modelState.postValue(ModelState.ERROR)
                    _errorMessage.postValue("Model files are missing, corrupted, or not properly unzipped.")
                    return@launch
                }

                _voskModelInstance = Model(modelPath.absolutePath)
                _modelState.postValue(ModelState.READY)
                Log.i("VoskModelViewModel", "Vosk model loaded successfully into ViewModel and is READY.")

            } catch (e: UnsatisfiedLinkError) {
                Log.e("VoskModelViewModel", "UnsatisfiedLinkError loading Vosk model. Check JNA setup and native libs.", e)
                _voskModelInstance = null
                _modelState.postValue(ModelState.ERROR)
                _errorMessage.postValue("Error with speech recognition engine (native libraries).")
            } catch (e: Exception) { // Các lỗi khác như IOException
                Log.e("VoskModelViewModel", "Failed to load Vosk model into ViewModel", e)
                _voskModelInstance = null
                _modelState.postValue(ModelState.ERROR)
                _errorMessage.postValue("Failed to initialize speech model: ${e.localizedMessage}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        modelJob.cancel()
        _voskModelInstance?.close()
        _voskModelInstance = null
        Log.d("VoskModelViewModel", "VoskModelViewModel cleared, Vosk model instance released.")
    }
}
