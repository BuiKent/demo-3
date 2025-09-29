package com.example.realtalkenglishwithAI.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.ListItemSavedWordBinding
import com.example.realtalkenglishwithAI.model.Vocabulary

class SavedWordsAdapter(
    private val onItemClick: (Vocabulary) -> Unit, // Thêm listener cho sự kiện nhấn vào mục
    private val onFavoriteClick: (Vocabulary) -> Unit
) :
    ListAdapter<Vocabulary, SavedWordsAdapter.WordViewHolder>(WordDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val binding = ListItemSavedWordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        val vocabulary = getItem(position)
        holder.bind(vocabulary, onItemClick, onFavoriteClick) // Truyền listener vào hàm bind
    }

    class WordViewHolder(private val binding: ListItemSavedWordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            vocabulary: Vocabulary,
            onItemClick: (Vocabulary) -> Unit,
            onFavoriteClick: (Vocabulary) -> Unit
        ) {
            binding.savedWordTextView.text = vocabulary.word.replaceFirstChar { it.uppercase() }
            binding.savedIpaTextView.text = vocabulary.ipa ?: ""

            val starIcon = if (vocabulary.isFavorite) {
                R.drawable.ic_star_filled
            } else {
                R.drawable.ic_star_outline
            }
            binding.favoriteButton.setImageResource(starIcon)

            binding.favoriteButton.setOnClickListener {
                onFavoriteClick(vocabulary)
            }

            // Đặt listener cho cả item view
            itemView.setOnClickListener {
                onItemClick(vocabulary)
            }
        }
    }
}

class WordDiffCallback : DiffUtil.ItemCallback<Vocabulary>() {
    override fun areItemsTheSame(oldItem: Vocabulary, newItem: Vocabulary): Boolean {
        return oldItem.word == newItem.word
    }

    override fun areContentsTheSame(oldItem: Vocabulary, newItem: Vocabulary): Boolean {
        return oldItem == newItem
    }
}

