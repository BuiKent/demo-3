package com.example.realtalkenglishwithAI.fragment

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.activityViewModels
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.activity.TextDisplayActivity
import com.example.realtalkenglishwithAI.databinding.FragmentProfileBinding
import com.example.realtalkenglishwithAI.ui.profile.AvatarPickerDialogFragment
import com.example.realtalkenglishwithAI.ui.profile.ProfileViewModel

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var sharedPreferences: SharedPreferences

    private val profileViewModel: ProfileViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        sharedPreferences = requireActivity().getSharedPreferences("user_profile", Context.MODE_PRIVATE)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadUserProfile()
        setupClickListeners()
        setupVersion()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật trạng thái công tắc mỗi khi người dùng quay lại màn hình này
        updateNotificationSwitchState()
    }

    private fun updateNotificationSwitchState() {
        // Kiểm tra trạng thái quyền thông báo thực tế từ hệ thống
        val areNotificationsEnabled = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        // Đặt trạng thái của công tắc cho đúng với thực tế
        // Gán trực tiếp isChecked sẽ không kích hoạt OnCheckedChangeListener, tránh vòng lặp vô hạn
        binding.notificationSwitch.isChecked = areNotificationsEnabled
    }

    private fun openNotificationSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        }
        startActivity(intent)
    }

    private fun loadUserProfile() {
        val name = sharedPreferences.getString("USER_NAME", "Kent")
        val age = sharedPreferences.getInt("USER_AGE", 25)
        val avatarResId = sharedPreferences.getInt("USER_AVATAR", R.drawable.ic_avatar_placeholder)

        binding.nameTextView.text = name
        binding.ageTextView.text = getString(R.string.age_format, age)
        binding.avatarImageView.setImageResource(avatarResId)
    }

    private fun setupVersion() {
        try {
            val context = requireContext()
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName
            binding.versionTextView.text = getString(R.string.version, versionName)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            binding.versionTextView.text = "Version N/A"
        }
    }

    private fun setupClickListeners() {
        binding.notificationSwitch.setOnClickListener {
            // Khi người dùng nhấn vào công tắc, mở màn hình cài đặt thông báo của hệ thống
            openNotificationSettings()
        }

        binding.editAvatarIcon.setOnClickListener {
            AvatarPickerDialogFragment().show(parentFragmentManager, "AvatarPicker")
        }

        binding.editNameButton.setOnClickListener {
            showEditDialog(
                title = getString(R.string.edit_name),
                hint = getString(R.string.enter_new_name),
                currentValue = binding.nameTextView.text.toString(),
                isNumeric = false
            ) { newName ->
                savePreference("USER_NAME", newName)
                binding.nameTextView.text = newName
            }
        }

        binding.editAgeButton.setOnClickListener {
            val currentAge = binding.ageTextView.text.toString().filter { it.isDigit() }
            showEditDialog(
                title = getString(R.string.edit_age),
                hint = getString(R.string.enter_new_age),
                currentValue = currentAge,
                isNumeric = true
            ) { newAge ->
                val ageInt = newAge.toIntOrNull() ?: 25
                savePreference("USER_AGE", ageInt)
                binding.ageTextView.text = getString(R.string.age_format, ageInt)
            }
        }

        binding.privacyPolicyButton.setOnClickListener {
            showTextPage(getString(R.string.privacy_policy), getString(R.string.lorem_ipsum))
        }

        binding.termsButton.setOnClickListener {
            showTextPage(getString(R.string.terms_of_use), getString(R.string.lorem_ipsum))
        }

        binding.supportButton.setOnClickListener {
            showTextPage(getString(R.string.support), getString(R.string.support_info))
        }

        binding.logoutButton.setOnClickListener {
            // Log out logic here
        }
    }

    private fun observeViewModel() {
        profileViewModel.selectedAvatarResId.observe(viewLifecycleOwner) { avatarResId ->
            binding.avatarImageView.setImageResource(avatarResId)
            savePreference("USER_AVATAR", avatarResId)
        }
    }

    private fun showTextPage(title: String, content: String) {
        val intent = Intent(requireContext(), TextDisplayActivity::class.java).apply {
            putExtra("TITLE", title)
            putExtra("CONTENT", content)
        }
        startActivity(intent)
    }

    private fun showEditDialog(
        title: String,
        hint: String,
        currentValue: String,
        isNumeric: Boolean,
        onSave: (String) -> Unit
    ) {
        val editText = EditText(requireContext()).apply {
            setText(currentValue)
            setHint(hint)
            if (isNumeric) {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(getString(R.string.save)) { dialog, _ ->
                val newValue = editText.text.toString()
                if (newValue.isNotBlank()) {
                    onSave(newValue)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    private fun savePreference(key: String, value: Any) {
        with(sharedPreferences.edit()) {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Long -> putLong(key, value)
            }
            apply()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
