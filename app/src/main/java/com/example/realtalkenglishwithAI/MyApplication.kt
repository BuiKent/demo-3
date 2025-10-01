package com.example.realtalkenglishwithAI

import android.app.Application
import android.util.Log
import com.example.realtalkenglishwithAI.viewmodel.ModelState // Assuming this enum is globally accessible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class MyApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // State for the model UNPACKING process
    private val _unpackingState = MutableStateFlow(ModelState.IDLE)
    val unpackingState: StateFlow<ModelState> = _unpackingState.asStateFlow()

    // Configuration for Vosk model
    private val VOSK_MODEL_ZIP_IN_ASSETS = "vosk-model.zip" // Name of your ZIP file in assets
    private val TARGET_MODEL_DIR_NAME_IN_INTERNAL_STORAGE = "model" // Target directory name in app's filesDir

    companion object {
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureVoskModelIsUnpacked()
    }

    private fun ensureVoskModelIsUnpacked() {
        applicationScope.launch {
            _unpackingState.value = ModelState.LOADING
            val targetModelDir = File(filesDir, TARGET_MODEL_DIR_NAME_IN_INTERNAL_STORAGE)

            try {
                if (isModelUnpackedAndValid(targetModelDir)) {
                    Log.i("MyApplication", "Vosk model already unpacked and valid in internal storage: ${targetModelDir.absolutePath}")
                    _unpackingState.value = ModelState.READY
                } else {
                    Log.i("MyApplication", "Vosk model not found or invalid. Unpacking from assets into ${targetModelDir.absolutePath}...")
                    // targetModelDir.mkdirs() // Ensure the target directory exists - already done by unpackZipFromAssets if needed
                    // Clean up target directory before unpacking to avoid issues with old files
                    if (targetModelDir.exists()) {
                        targetModelDir.deleteRecursively()
                    }
                    targetModelDir.mkdirs() // Re-create after delete

                    unpackZipFromAssets(VOSK_MODEL_ZIP_IN_ASSETS, targetModelDir)

                    if (isModelUnpackedAndValid(targetModelDir)) {
                        Log.i("MyApplication", "Vosk model unpacked successfully to ${targetModelDir.absolutePath}")
                        _unpackingState.value = ModelState.READY
                    } else {
                        Log.e("MyApplication", "Model still not valid after attempting to unpack. Path: ${targetModelDir.absolutePath}")
                        _unpackingState.value = ModelState.ERROR
                    }
                }
            } catch (e: Exception) {
                Log.e("MyApplication", "Error during Vosk model unpacking process", e)
                _unpackingState.value = ModelState.ERROR
            }
        }
    }

    private fun isModelUnpackedAndValid(modelPath: File): Boolean {
        if (!modelPath.exists() || !modelPath.isDirectory) {
            Log.d("MyApplication", "Model path ${modelPath.absolutePath} does not exist or not a directory.")
            return false
        }
        // Check for specific Vosk model files/subdirectories
        val amDir = File(modelPath, "am")
        val confDir = File(modelPath, "conf")
        // val mfccConf = File(modelPath, "mfcc.conf") // This file might be inside 'conf' or other specific structure based on your model zip
        // More robust check: check for a few key files/folders that Vosk model needs.
        // Example: model/am/final.mdl, model/conf/model.conf, model/graph/HCLG.fst etc.
        // For simplicity, we check for presence of 'am' and 'conf' directories as a basic validation.
        val res = amDir.exists() && amDir.isDirectory && confDir.exists() && confDir.isDirectory && (modelPath.listFiles()?.isNotEmpty() ?: false)
        if (!res) {
            Log.d("MyApplication", "Model validation failed for ${modelPath.absolutePath}: amDir exists=${amDir.exists()}, confDir exists=${confDir.exists()}, listFiles not empty=${modelPath.listFiles()?.isNotEmpty()}")
        }
        return res
    }

    @Throws(IOException::class)
    private fun unpackZipFromAssets(assetZipFileName: String, targetDirectory: File) {
        Log.d("MyApplication", "Starting to unpack $assetZipFileName to ${targetDirectory.absolutePath}")
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }
        assets.open(assetZipFileName).use { inputStream ->
            ZipInputStream(inputStream.buffered()).use { zipInputStream -> // Use buffered stream for efficiency
                var zipEntry = zipInputStream.nextEntry
                while (zipEntry != null) {
                    val newFile = File(targetDirectory, zipEntry.name)
                    // Security check to prevent Zip Slip vulnerability
                    if (!newFile.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator)) {
                        throw SecurityException("Zip entry is trying to escape target directory: ${zipEntry.name}")
                    }

                    if (zipEntry.isDirectory) {
                        if (!newFile.isDirectory && !newFile.mkdirs()) {
                            throw IOException("Failed to create directory ${newFile.absolutePath}")
                        }
                        Log.d("MyApplication", "Created directory: ${newFile.absolutePath}")
                    } else {
                        val parentDir = newFile.parentFile
                        if (parentDir != null && !parentDir.isDirectory && !parentDir.mkdirs()) {
                            throw IOException("Failed to create parent directory ${parentDir.absolutePath}")
                        }
                        FileOutputStream(newFile).use { fos ->
                            val buffer = ByteArray(8192) // Increased buffer size for potentially faster I/O
                            var len: Int
                            while (zipInputStream.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                        Log.d("MyApplication", "Unpacked file: ${newFile.absolutePath}")
                    }
                    zipInputStream.closeEntry()
                    zipEntry = zipInputStream.nextEntry
                }
            }
        }
        Log.d("MyApplication", "Finished unpacking $assetZipFileName")
    }
}
