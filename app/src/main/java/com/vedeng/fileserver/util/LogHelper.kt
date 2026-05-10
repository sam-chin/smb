package com.vedeng.fileserver.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

enum class LogLevel(val priority: Int, val displayName: String) {
    VERBOSE(Log.VERBOSE, "V"),
    DEBUG(Log.DEBUG, "D"),
    INFO(Log.INFO, "I"),
    WARN(Log.WARN, "W"),
    ERROR(Log.ERROR, "E");

    companion object {
        fun fromPriority(priority: Int): LogLevel {
            return values().find { it.priority == priority } ?: DEBUG
        }
    }
}

object LogHelper {
    private const val MAX_LOGS = 1000
    private val logs = mutableListOf<LogEntry>()
    
    private val _logFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logFlow: StateFlow<List<LogEntry>> = _logFlow.asStateFlow()

    fun v(tag: String, msg: String, tr: Throwable? = null) {
        addLog(LogLevel.VERBOSE, tag, msg, tr)
        Log.v(tag, msg, tr)
    }

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        addLog(LogLevel.DEBUG, tag, msg, tr)
        Log.d(tag, msg, tr)
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) {
        addLog(LogLevel.INFO, tag, msg, tr)
        Log.i(tag, msg, tr)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        addLog(LogLevel.WARN, tag, msg, tr)
        Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        addLog(LogLevel.ERROR, tag, msg, tr)
        Log.e(tag, msg, tr)
    }

    private fun addLog(level: LogLevel, tag: String, msg: String, tr: Throwable?) {
        synchronized(logs) {
            logs.add(LogEntry(System.currentTimeMillis(), level, tag, msg, tr))
            if (logs.size > MAX_LOGS) {
                logs.removeAt(0)
            }
            _logFlow.value = ArrayList(logs)
        }
    }

    fun getLogs(): List<LogEntry> {
        return synchronized(logs) { ArrayList(logs) }
    }

    fun clearLogs() {
        synchronized(logs) {
            logs.clear()
            _logFlow.value = emptyList()
        }
        Log.i("LogHelper", "Logs cleared")
    }
}
