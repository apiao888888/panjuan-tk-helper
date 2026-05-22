package com.tiktokassist.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tiktokassist.databinding.ItemKeywordBinding

class KeywordAdapter(
    private val keywords: MutableList<String>,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<KeywordAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemKeywordBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemKeywordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val keyword = keywords[position]
        holder.binding.tvKeyword.text = keyword
        holder.binding.btnDeleteKeyword.setOnClickListener {
            onDelete(keyword)
        }
    }

    override fun getItemCount() = keywords.size

    fun addKeyword(keyword: String) {
        if (!keywords.contains(keyword)) {
            keywords.add(keyword)
            notifyItemInserted(keywords.size - 1)
        }
    }

    fun removeKeyword(keyword: String) {
        val index = keywords.indexOf(keyword)
        if (index >= 0) {
            keywords.removeAt(index)
            notifyItemRemoved(index)
            notifyItemRangeChanged(index, keywords.size)
        }
    }

    fun getKeywords(): MutableList<String> = keywords
}
