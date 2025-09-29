package com.example.realtalkenglishwithAI.ui.util

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * ItemDecoration để thêm khoảng trắng cho các mục trong GridLayoutManager.
 * @param spanCount Số cột trong lưới.
 * @param spacing Khoảng cách giữa các mục (tính bằng pixel).
 * @param includeEdge Có bao gồm khoảng trắng ở các cạnh của RecyclerView hay không.
 */
class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int,
    private val includeEdge: Boolean
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view) // Vị trí của mục
        val column = position % spanCount // Cột của mục

        if (includeEdge) {
            outRect.left = spacing - column * spacing / spanCount
            outRect.right = (column + 1) * spacing / spanCount

            if (position < spanCount) { // Hàng trên cùng
                outRect.top = spacing
            }
            outRect.bottom = spacing // Khoảng trắng dưới cùng
        } else {
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) {
                outRect.top = spacing // Khoảng trắng trên cùng cho các hàng không phải hàng đầu tiên
            }
        }
    }
}
