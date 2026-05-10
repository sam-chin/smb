package com.vedeng.fileserver.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vedeng.fileserver.R
import com.vedeng.fileserver.databinding.ActivityLogViewerBinding
import com.vedeng.fileserver.util.LogEntry
import com.vedeng.fileserver.util.LogHelper
import com.vedeng.fileserver.util.LogLevel
import kotlinx.coroutines.launch

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private lateinit var adapter: LogAdapter
    private var currentFilterLevel: LogLevel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupLevelFilter()
        setupClearButton()
        observeLogs()
    }

    private fun setupRecyclerView() {
        adapter = LogAdapter()
        binding.recyclerViewLogs.apply {
            layoutManager = LinearLayoutManager(this@LogViewerActivity).apply {
                stackFromEnd = true
                reverseLayout = false
            }
            adapter = this@LogViewerActivity.adapter
        }
    }

    private fun setupLevelFilter() {
        val levels = listOf("All") + LogLevel.values().map { it.displayName + " - " + it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, levels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLevel.adapter = adapter

        binding.spinnerLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentFilterLevel = if (position == 0) {
                    null
                } else {
                    LogLevel.values()[position - 1]
                }
                updateLogs()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupClearButton() {
        binding.btnClear.setOnClickListener {
            LogHelper.clearLogs()
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            LogHelper.logFlow.collect {
                updateLogs()
                if (it.isNotEmpty()) {
                    binding.recyclerViewLogs.smoothScrollToPosition(it.size - 1)
                }
            }
        }
    }

    private fun updateLogs() {
        val allLogs = LogHelper.getLogs()
        val filteredLogs = if (currentFilterLevel == null) {
            allLogs
        } else {
            allLogs.filter { it.level.ordinal >= currentFilterLevel!!.ordinal }
        }
        adapter.submitList(filteredLogs)
    }

    class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

        private var logs: List<LogEntry> = emptyList()

        fun submitList(list: List<LogEntry>) {
            logs = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(logs[position])
        }

        override fun getItemCount(): Int = logs.size

        class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val tvLevel: TextView = itemView.findViewById(R.id.tvLevel)
            private val tvTag: TextView = itemView.findViewById(R.id.tvTag)
            private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)

            fun bind(entry: LogEntry) {
                tvTime.text = entry.formattedTime
                tvLevel.text = entry.level.displayName
                tvLevel.setBackgroundColor(getLevelColor(entry.level))
                tvTag.text = entry.tag
                tvMessage.text = buildMessage(entry)
            }

            private fun buildMessage(entry: LogEntry): String {
                return if (entry.throwable != null) {
                    "${entry.message}\n${android.util.Log.getStackTraceString(entry.throwable)}"
                } else {
                    entry.message
                }
            }

            private fun getLevelColor(level: LogLevel): Int {
                return when (level) {
                    LogLevel.VERBOSE -> Color.parseColor("#666666")
                    LogLevel.DEBUG -> Color.parseColor("#00BCD4")
                    LogLevel.INFO -> Color.parseColor("#4CAF50")
                    LogLevel.WARN -> Color.parseColor("#FF9800")
                    LogLevel.ERROR -> Color.parseColor("#F44336")
                }
            }
        }
    }
}
