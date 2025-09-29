package com.example.realtalkenglishwithAI.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ProfileViewModel : ViewModel() {

    private val _selectedAvatarResId = MutableLiveData<Int>()
    val selectedAvatarResId: LiveData<Int> = _selectedAvatarResId

    fun selectAvatar(resId: Int) {
        _selectedAvatarResId.value = resId
    }
}
