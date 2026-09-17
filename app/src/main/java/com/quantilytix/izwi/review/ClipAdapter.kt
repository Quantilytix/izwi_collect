package com.quantilytix.izwi.review

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.quantilytix.izwi.data.ClipEntity
import com.quantilytix.izwi.data.ReviewStatus
import com.quantilytix.izwi.databinding.ItemClipBinding

class ClipAdapter(
    private val onPlay: (ClipEntity) -> Unit,
    private val onEditTranscript: (ClipEntity) -> Unit,
    private val onToggleStatus: (ClipEntity) -> Unit,
) : RecyclerView.Adapter<ClipAdapter.ViewHolder>() {

    private var items: List<ClipEntity> = emptyList()

    fun submit(newItems: List<ClipEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemClipBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(clip: ClipEntity) {
            binding.statusBadge.text = clip.reviewStatus.uppercase()
            binding.statusBadge.setBackgroundColor(colorFor(clip.reviewStatus))
            binding.transcriptText.text = clip.transcript
            binding.metaText.text = if (clip.reviewStatus == ReviewStatus.SKIPPED) {
                "skipped: ${clip.skipReason ?: ""}"
            } else {
                String.format("%.1fs · q=%.2f · snr=%.1fdB", clip.durationSeconds, clip.qualityScore, clip.snrProxy)
            }

            binding.playButton.isEnabled = clip.filePath != null
            binding.playButton.setOnClickListener { onPlay(clip) }
            binding.editTranscriptButton.setOnClickListener { onEditTranscript(clip) }

            binding.toggleStatusButton.text = if (clip.reviewStatus == ReviewStatus.RETAKE) "Mark accepted" else "Mark retake"
            binding.toggleStatusButton.isEnabled = clip.reviewStatus != ReviewStatus.SKIPPED
            binding.toggleStatusButton.setOnClickListener { onToggleStatus(clip) }
        }

        private fun colorFor(status: String): Int = when (status) {
            ReviewStatus.ACCEPTED -> Color.parseColor("#1F6F5C")
            ReviewStatus.WARNING -> Color.parseColor("#E8A93B")
            ReviewStatus.RETAKE -> Color.parseColor("#C0392B")
            else -> Color.parseColor("#6B7280")
        }
    }
}
