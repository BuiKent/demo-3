package com.example.realtalkenglishwithAI.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.realtalkenglishwithAI.R

class AvatarAdapter(
    private val avatarList: List<Int>,
    private val itemSize: Int, // Thêm tham số để nhận kích thước item
    private val onAvatarSelected: (Int) -> Unit
) : RecyclerView.Adapter<AvatarAdapter.AvatarViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AvatarViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_avatar, parent, false)

        // --- TỰ ĐỘNG ĐẶT KÍCH THƯỚC CHO AVATAR ---
        val layoutParams = view.layoutParams
        layoutParams.width = itemSize
        layoutParams.height = itemSize
        view.layoutParams = layoutParams

        return AvatarViewHolder(view)
    }

    override fun onBindViewHolder(holder: AvatarViewHolder, position: Int) {
        val avatarResId = avatarList[position]
        holder.avatarImageView.setImageResource(avatarResId)
        holder.itemView.setOnClickListener {
            onAvatarSelected(avatarResId)
        }
    }

    override fun getItemCount(): Int = avatarList.size

    class AvatarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarImageView: ImageView = itemView.findViewById(R.id.avatarItemImageView)
    }
}
