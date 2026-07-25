package com.mit.attendance.ui.certifications

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mit.attendance.databinding.ItemCertificationBinding
import com.mit.attendance.model.CertificationItem

class DynamicCertificationsAdapter(private val onItemClick: (CertificationItem) -> Unit) :
    ListAdapter<CertificationItem, DynamicCertificationsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCertificationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ViewHolder(private val binding: ItemCertificationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CertificationItem, position: Int) {
            binding.root.rotation = when (position % 6) {
                0 -> -1.2f
                1 -> 0.9f
                2 -> 0.55f
                3 -> -0.95f
                4 -> -0.45f
                else -> 1.15f
            }
            binding.root.translationX = when (position % 4) {
                0 -> -3f
                1 -> 4f
                2 -> 2f
                else -> -4f
            }
            binding.root.translationY = when (position % 4) {
                0 -> 0f
                1 -> 8f
                2 -> -5f
                else -> 6f
            }
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text = item.subtitle ?: item.platform
            binding.tvPlatform.text = item.platform
            binding.btnAction.setOnClickListener { onItemClick(item) }
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CertificationItem>() {
        override fun areItemsTheSame(oldItem: CertificationItem, newItem: CertificationItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: CertificationItem, newItem: CertificationItem) = oldItem == newItem
    }
}
