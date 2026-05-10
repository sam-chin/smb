package com.vedeng.fileserver.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vedeng.fileserver.R
import com.vedeng.fileserver.data.model.FileItem
import com.vedeng.fileserver.databinding.ItemFileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Unit = {}
) : ListAdapter<FileItem, FileAdapter.FileViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }

            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(getItem(position))
                }
                true
            }
        }

        fun bind(item: FileItem) {
            binding.tvFileName.text = item.name

            if (item.isDirectory) {
                binding.ivFileIcon.setImageResource(R.drawable.ic_folder)
                binding.tvFileSize.text = ""
                binding.tvFileDate.visibility = View.GONE
            } else {
                binding.tvFileDate.visibility = View.VISIBLE
                binding.tvFileDate.text = formatDate(item.lastModified)
                binding.tvFileSize.text = formatFileSize(item.size)
                binding.ivFileIcon.setImageResource(getFileIcon(item.name))
            }
        }

        private fun getFileIcon(fileName: String): Int {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when (extension) {
                "jpg", "jpeg", "png", "gif", "bmp", "webp" -> R.drawable.ic_image
                "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v" -> R.drawable.ic_video
                "mp3", "wav", "aac", "flac", "ogg", "m4a" -> R.drawable.ic_audio
                "pdf" -> R.drawable.ic_document
                "txt" -> R.drawable.ic_document
                "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
                else -> R.drawable.ic_file
            }
        }

        private fun formatFileSize(size: Long): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
                else -> "${size / (1024 * 1024 * 1024)} GB"
            }
        }

        private fun formatDate(timestamp: Long): String {
            if (timestamp <= 0) return ""
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem == newItem
        }
    }
}
