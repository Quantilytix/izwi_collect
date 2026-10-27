package com.quantilytix.izwi.session

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.quantilytix.izwi.data.ReviewStatus
import com.quantilytix.izwi.databinding.ItemPromptBinding

/** The Prompts screen's rows. A ListAdapter, so a keystroke or a new take
 * rebinds only the rows that changed, with the diff off the main thread. */
class PromptAdapter(
    private val onOpen: (PromptRow) -> Unit,
    private val onEdit: (PromptRow) -> Unit,
    private val onDelete: (PromptRow) -> Unit,
) : ListAdapter<PromptRow, PromptAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PromptRow>() {
            override fun areItemsTheSame(a: PromptRow, b: PromptRow) = a.prompt.id == b.prompt.id
            override fun areContentsTheSame(a: PromptRow, b: PromptRow) = a.prompt == b.prompt && a.status == b.status
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPromptBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemPromptBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: PromptRow) {
            val prompt = row.prompt
            val status = row.status
            val (label, color) = badgeFor(status)
            binding.statusBadge.text = label
            binding.statusBadge.setBackgroundColor(color)
            binding.promptCategoryText.text = prompt.category.replace('_', ' ').uppercase()
            binding.takeCountText.text = when (status.takeCount) {
                0 -> if (status.latestStatus == ReviewStatus.SKIPPED) "Skipped" else "No takes"
                1 -> "1 take"
                else -> "${status.takeCount} takes"
            }
            binding.promptTextView.text = prompt.text
            binding.promptIdText.text = if (prompt.version > 1) "${prompt.id} · version ${prompt.version}" else prompt.id
            binding.textChangedText.visibility = if (status.textChanged) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onOpen(row) }
            binding.recordPromptButton.setOnClickListener { onOpen(row) }
            binding.editPromptButton.setOnClickListener { onEdit(row) }
            binding.deletePromptButton.setOnClickListener { onDelete(row) }
        }

        // Same palette as the review queue's status badges.
        private fun badgeFor(status: PromptStatus): Pair<String, Int> = when {
            status.latestStatus == ReviewStatus.RETAKE -> "RETAKE" to Color.parseColor("#C0392B")
            status.latestStatus == ReviewStatus.SKIPPED -> "SKIPPED" to Color.parseColor("#6B7280")
            status.latestStatus == ReviewStatus.WARNING -> "WARNING" to Color.parseColor("#E8A93B")
            status.isRecorded -> "RECORDED" to Color.parseColor("#1F6F5C")
            else -> "OUTSTANDING" to Color.parseColor("#9CA3AF")
        }
    }
}
