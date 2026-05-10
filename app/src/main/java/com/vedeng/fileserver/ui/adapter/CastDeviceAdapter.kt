package com.vedeng.fileserver.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vedeng.fileserver.databinding.ItemCastDeviceBinding
import com.vedeng.fileserver.network.dlna.CastController

class CastDeviceAdapter(
    private val onDeviceClick: (CastController.CastDevice) -> Unit
) : ListAdapter<CastController.CastDevice, CastDeviceAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemCastDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(
        private val binding: ItemCastDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDeviceClick(getItem(position))
                }
            }
        }

        fun bind(device: CastController.CastDevice) {
            binding.tvDeviceName.text = device.name
            binding.tvDeviceAddress.text = "${device.address}:${device.port}"
            binding.tvDeviceInfo.text = buildString {
                if (device.manufacturer.isNotEmpty()) {
                    append(device.manufacturer)
                }
                if (device.modelName.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(device.modelName)
                }
                if (isEmpty()) {
                    append(device.udn)
                }
            }
        }
    }

    class DeviceDiffCallback : DiffUtil.ItemCallback<CastController.CastDevice>() {
        override fun areItemsTheSame(
            oldItem: CastController.CastDevice,
            newItem: CastController.CastDevice
        ): Boolean {
            return oldItem.udn == newItem.udn
        }

        override fun areContentsTheSame(
            oldItem: CastController.CastDevice,
            newItem: CastController.CastDevice
        ): Boolean {
            return oldItem == newItem
        }
    }
}
