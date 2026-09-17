package com.quantilytix.izwi.sync

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.quantilytix.izwi.data.BatchEntity
import com.quantilytix.izwi.data.UploadStatus
import com.quantilytix.izwi.databinding.ItemBatchBinding

class BatchAdapter(private val onRetry: (BatchEntity) -> Unit) : RecyclerView.Adapter<BatchAdapter.ViewHolder>() {

    private var items: List<BatchEntity> = emptyList()

    fun submit(newItems: List<BatchEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBatchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemBatchBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(batch: BatchEntity) {
            binding.batchTitle.text = "${batch.batchId} — ${batch.clipCount} clips"
            binding.batchStatus.text = when (batch.uploadStatus) {
                UploadStatus.UPLOADED -> "uploaded — ${batch.prUrl}"
                UploadStatus.FAILED -> "failed: ${batch.errorMessage}"
                UploadStatus.QUEUED -> "uploading…"
                else -> "pending"
            }
            binding.retryButton.visibility = if (batch.uploadStatus == UploadStatus.FAILED) android.view.View.VISIBLE else android.view.View.GONE
            binding.retryButton.setOnClickListener { onRetry(batch) }
        }
    }
}
