package com.quantilytix.izwi.session

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.quantilytix.izwi.databinding.ItemPromptBinding

class PromptAdapter(
    private val onEdit: (Int, Prompt) -> Unit,
    private val onDelete: (Int, Prompt) -> Unit,
) : RecyclerView.Adapter<PromptAdapter.ViewHolder>() {

    private var items: List<Prompt> = emptyList()

    fun submit(newItems: List<Prompt>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPromptBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], position)
    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemPromptBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(prompt: Prompt, position: Int) {
            binding.promptCategoryText.text = prompt.category.replace('_', ' ').uppercase()
            binding.promptTextView.text = prompt.text
            binding.editPromptButton.setOnClickListener { onEdit(position, prompt) }
            binding.deletePromptButton.setOnClickListener { onDelete(position, prompt) }
        }
    }
}
