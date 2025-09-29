package com.example.realtalkenglishwithAI.ui.profile

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.ui.adapter.AvatarAdapter

class AvatarPickerDialogFragment : DialogFragment() {

    private val viewModel: ProfileViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_avatar_picker, container, false)
    }

    override fun onStart() {
        super.onStart()
        // Mở rộng dialog ra toàn bộ chiều rộng
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val avatarRecyclerView: RecyclerView = view.findViewById(R.id.avatarRecyclerView)
        val closeDialogButton: ImageButton = view.findViewById(R.id.closeDialogButton)
        val spanCount = 3

        // --- THÊM SỰ KIỆN CLICK CHO NÚT ĐÓNG ---
        closeDialogButton.setOnClickListener {
            dismiss()
        }

        // --- TÍNH TOÁN KÍCH THƯỚC AVATAR ĐỘNG ---
        val screenWidth = resources.displayMetrics.widthPixels
        val dialogHorizontalPadding = (32 * resources.displayMetrics.density).toInt() // 16dp mỗi bên
        val itemSpacing = (8 * resources.displayMetrics.density).toInt()
        val totalSpacing = itemSpacing * (spanCount - 1)
        val availableWidth = screenWidth - dialogHorizontalPadding - totalSpacing
        val itemSize = availableWidth / spanCount

        // TODO: Hãy chắc chắn rằng bạn đã thay thế bằng tên tệp ảnh .webp chính xác của mình
        val avatars = listOf(
            R.drawable.avatar_1,
            R.drawable.avatar_2,
            R.drawable.avatar_3,
            R.drawable.avatar_4,
            R.drawable.avatar_5,
            R.drawable.avatar_6,
            R.drawable.avatar_7,
            R.drawable.avatar_8
        )

        val avatarAdapter = AvatarAdapter(avatars, itemSize) { selectedAvatarResId ->
            viewModel.selectAvatar(selectedAvatarResId)
            dismiss()
        }

        avatarRecyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)

        if (avatarRecyclerView.itemDecorationCount > 0) {
            avatarRecyclerView.removeItemDecorationAt(0)
        }
        avatarRecyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                val column = position % spanCount

                if (column < spanCount - 1) {
                    outRect.right = itemSpacing
                }
                if (position >= spanCount) {
                    outRect.top = itemSpacing
                }
            }
        })

        avatarRecyclerView.adapter = avatarAdapter
    }
}
