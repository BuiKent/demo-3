package com.example.realtalkenglishwithAI.activity

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
// import androidx.work.OneTimeWorkRequestBuilder // Comment out or remove if not using WorkManager yet
// import androidx.work.WorkManager // Comment out or remove if not using WorkManager yet
// import androidx.work.workDataOf // Comment out or remove if not using WorkManager yet
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.ActivityMainBinding
import com.example.realtalkenglishwithAI.viewmodel.VoskModelViewModel
// import com.example.realtalkenglishwithAI.worker.UnzipModelWorker // Assuming you might create this later
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logTag = "MainActivityVosk"
    private val voskModelViewModel: VoskModelViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* isGranted: Boolean -> */
            // Handle permission result if needed in the future.
        }

    companion object {
        private const val PREFS_NAME = "VoskModelPrefs"
        private const val KEY_MODEL_UNZIPPED_SUCCESSFULLY = "model_unzipped_successfully_v1" // Added _v1 for versioning
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        askNotificationPermission()
        setupNavigation()
        prepareVoskModelAssets()

        voskModelViewModel.errorMessage.observe(this) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(this, "Model Initialization Error: $it", Toast.LENGTH_LONG).show()
                Log.e(logTag, "VoskModelViewModel error: $it")
                // voskModelViewModel.clearErrorMessage() // Consider adding this to ViewModel
            }
        }
    }

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)
    }

    private fun isModelUnzippedSuccessfully(): Boolean {
        return prefs.getBoolean(KEY_MODEL_UNZIPPED_SUCCESSFULLY, false)
    }

    private fun setModelUnzippedSuccessfully(success: Boolean) {
        prefs.edit().putBoolean(KEY_MODEL_UNZIPPED_SUCCESSFULLY, success).apply()
    }

    private fun prepareVoskModelAssets() {
        val modelDir = File(filesDir, "model")
        val modelAssetFileName = "vosk-model.zip"

        if (isModelUnzippedSuccessfully()) {
            Log.i(logTag, "Model previously unzipped (flagged in SharedPreferences). Triggering ViewModel to load/verify.")
            // Still good to verify physical presence in case of data clear or external deletion not reflected in prefs
            val amFile = File(modelDir, "am")
            val confFile = File(modelDir, "conf")
            if (modelDir.exists() && amFile.exists() && confFile.exists()) {
                voskModelViewModel.initModelAfterUnzip()
                return
            } else {
                Log.w(logTag, "SharedPreferences flag is true, but model files are missing. Will attempt to re-unzip.")
                setModelUnzippedSuccessfully(false) // Reset flag as files are missing
            }
        }

        // If not unzipped successfully via SharedPreferences, or if files were missing despite flag,
        // proceed with physical check and potential unzip.
        val amFile = File(modelDir, "am") // Re-declare for this scope if needed or use previous
        val confFile = File(modelDir, "conf")
        val areModelFilesPhysicallyPresentAndValid = modelDir.exists() && modelDir.isDirectory &&
                                                     amFile.exists() && amFile.isDirectory &&
                                                     confFile.exists() && confFile.isDirectory &&
                                                     (modelDir.listFiles()?.isNotEmpty() == true)

        if (!areModelFilesPhysicallyPresentAndValid) {
            Log.i(logTag, "Vosk model files at ${modelDir.absolutePath} are missing or incomplete. Attempting to unzip from assets: $modelAssetFileName")
            
            // --- Option 1: Using Coroutine (current approach, slightly modified) ---
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    unzipAssetToDirectory(applicationContext, modelAssetFileName, modelDir)
                    Log.i(logTag, "Successfully unzipped $modelAssetFileName. Setting SharedPreferences flag.")
                    setModelUnzippedSuccessfully(true)
                    withContext(Dispatchers.Main) {
                        voskModelViewModel.initModelAfterUnzip()
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Critical error during Vosk model unzipping ($modelAssetFileName): ${e.message}", e)
                    setModelUnzippedSuccessfully(false) // Ensure flag is false on error
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to prepare speech model assets: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            // --- End Option 1 ---

            // --- Option 2: Using WorkManager (recommended for more robust background work) ---
            // Log.i(logTag, "Enqueueing WorkManager to unzip Vosk model assets.")
            // val unzipWorkRequest = OneTimeWorkRequestBuilder<UnzipModelWorker>()
            //    .setInputData(workDataOf(
            //        UnzipModelWorker.KEY_ASSET_FILE_NAME to modelAssetFileName,
            //        UnzipModelWorker.KEY_TARGET_DIRECTORY to modelDir.absolutePath
            //    ))
            //    .build()
            // WorkManager.getInstance(applicationContext).enqueue(unzipWorkRequest)
            //
            // // You would then observe the WorkInfo from the ViewModel or another mechanism
            // // to know when to call voskModelViewModel.initModelAfterUnzip() and set SharedPreferences flag.
            // // For simplicity, this example doesn't include the full observation logic for WorkManager.
            // // For now, with WorkManager, initModelAfterUnzip might be called by the Worker on success,
            // // or MainActivity observes WorkInfo and calls it.
            // // The SharedPreferences flag would also be set by the Worker or by MainActivity on observing success.
            // --- End Option 2 ---

        } else {
            Log.i(logTag, "Vosk model files appear to be physically present at ${modelDir.absolutePath}. Setting SharedPreferences flag and triggering ViewModel.")
            setModelUnzippedSuccessfully(true) // Ensure flag is set if files are already there
            voskModelViewModel.initModelAfterUnzip()
        }
    }

    @Throws(IOException::class)
    private fun unzipAssetToDirectory(context: Context, assetFileName: String, targetDirectory: File) {
        Log.d(logTag, "Unzipping $assetFileName to ${targetDirectory.absolutePath}")

        if (targetDirectory.exists()) {
            Log.d(logTag, "Target directory '${targetDirectory.name}' exists. Deleting recursively before unzipping...")
            if (!targetDirectory.deleteRecursively()) {
                Log.w(logTag, "Failed to delete existing target directory: ${targetDirectory.absolutePath}. Proceeding with caution.")
            }
        }

        Log.d(logTag, "Creating target directory: ${targetDirectory.absolutePath}")
        if (!targetDirectory.mkdirs()) {
            if (!targetDirectory.isDirectory) {
                 throw IOException("Failed to create target directory '${targetDirectory.absolutePath}'. It might be an existing file or access issue.")
            }
            Log.d(logTag, "Target directory '${targetDirectory.name}' either already existed or was created by another process. Assuming okay.")
        }

        context.assets.open(assetFileName).use { inputStream ->
            ZipInputStream(inputStream.buffered()).use { zis ->
                var zipEntry = zis.nextEntry
                while (zipEntry != null) {
                    val newFile = File(targetDirectory, zipEntry.name)
                    if (!newFile.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator)) {
                        throw SecurityException("Zip entry '${zipEntry.name}' is trying to escape the target directory.")
                    }

                    if (zipEntry.isDirectory) {
                        if (!newFile.mkdirs() && !newFile.isDirectory) { 
                            throw IOException("Failed to create directory ${newFile.absolutePath}")
                        }
                    } else {
                        val parent = newFile.parentFile
                        if (parent != null && !parent.exists()) {
                            if (!parent.mkdirs() && !parent.isDirectory) {
                                throw IOException("Failed to create parent directory ${parent.absolutePath}")
                            }
                        }
                        FileOutputStream(newFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    zipEntry = zis.nextEntry
                }
            }
        }
        Log.d(logTag, "Successfully unzipped $assetFileName to ${targetDirectory.absolutePath}")
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
