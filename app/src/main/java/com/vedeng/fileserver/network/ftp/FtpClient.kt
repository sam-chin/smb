package com.vedeng.fileserver.network.ftp

import com.vedeng.fileserver.util.LogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

class FtpClient {

    companion object {
        private const val TAG = "FtpClient"
    }

    private var host: String = ""
    private var port: Int = 21
    private var username: String = "anonymous"
    private var password: String = ""

    private var socket: Socket? = null
    private var dataSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var dataReader: BufferedReader? = null
    private var dataInputStream: InputStream? = null
    private var isConnected = AtomicBoolean(false)
    private var isLoggedIn = AtomicBoolean(false)
    private var currentDir: String = "/"
    private var passiveHost: String = ""
    private var passivePort: Int = 0

    data class FtpResponse(
        val code: Int,
        val message: String
    )

    data class FileInfo(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    suspend fun connect(host: String, port: Int, username: String?, password: String?): Result<Unit> = withContext(Dispatchers.IO) {
        this@FtpClient.host = host
        this@FtpClient.port = port
        this@FtpClient.username = username ?: "anonymous"
        this@FtpClient.password = password ?: ""

        try {
            socket = Socket()
            socket?.connect(InetSocketAddress(this@FtpClient.host, this@FtpClient.port), 10000)
            socket?.soTimeout = 15000

            reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
            writer = PrintWriter(OutputStreamWriter(socket?.getOutputStream()), true)

            val response = readResponse()
            if (!isSuccessCode(response.code)) {
                return@withContext Result.failure(Exception("Connection failed: ${response.message}"))
            }

            isConnected.set(true)

            val loginResult = login(this@FtpClient.username, this@FtpClient.password)
            if (loginResult.isFailure) {
                disconnect()
                return@withContext Result.failure(loginResult.exceptionOrNull() ?: Exception("Login failed"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    suspend fun login(user: String, pass: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sendCommand("USER $user")
            var response = readResponse()

            if (response.code == 331) {
                sendCommand("PASS $pass")
                response = readResponse()
            }

            if (!isSuccessCode(response.code)) {
                return@withContext Result.failure(Exception("Login failed: ${response.message}"))
            }

            isLoggedIn.set(true)

            sendCommand("TYPE I")
            readResponse()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFiles(path: String = "/"): Result<List<FileInfo>> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val targetPath = if (path.isEmpty() || path == "/") "/" else path
            LogHelper.d(TAG, "Listing files at: $targetPath")

            val cwdResult = changeDirectory(targetPath)
            if (cwdResult.isFailure) {
                LogHelper.e(TAG, "CWD failed for $targetPath, trying root")
                val rootCwd = changeDirectory("/")
                if (rootCwd.isFailure) {
                    return@withContext Result.failure(cwdResult.exceptionOrNull() ?: Exception("Failed to change directory"))
                }
            }

            LogHelper.d(TAG, "Entering passive mode")
            val listResult = enterPassiveMode()
            if (listResult.isFailure) {
                return@withContext Result.failure(listResult.exceptionOrNull() ?: Exception("Passive mode failed"))
            }

            LogHelper.d(TAG, "Sending LIST command")
            sendCommand("LIST")

            val listResponse = readResponse()
            LogHelper.d(TAG, "LIST response: ${listResponse.code} ${listResponse.message}")
            
            if (!isSuccessCode(listResponse.code) && listResponse.code != 150 && listResponse.code != 125) {
                closeDataConnection()
                return@withContext Result.failure(Exception("List failed: ${listResponse.message}"))
            }

            val files = mutableListOf<FileInfo>()
            var line: String?
            var lineCount = 0

            try {
                while (dataReader?.readLine().also { line = it } != null) {
                    line?.let { 
                        lineCount++
                        LogHelper.d(TAG, "FTP line $lineCount: $it")
                        parseListLine(it, currentDir)?.let { fileInfo -> 
                            files.add(fileInfo)
                        }
                    }
                }
            } catch (e: Exception) {
                LogHelper.e(TAG, "Error reading FTP list data", e)
            }

            LogHelper.d(TAG, "Read $lineCount lines, parsed ${files.size} files")
            closeDataConnection()

            try {
                val finalResponse = readResponse()
                LogHelper.d(TAG, "Final response after LIST: ${finalResponse.code}")
            } catch (e: Exception) {
                LogHelper.w(TAG, "Error reading final response", e)
            }

            val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            Result.success(sortedFiles)
        } catch (e: Exception) {
            LogHelper.e(TAG, "List files failed", e)
            Result.failure(e)
        }
    }

    suspend fun changeDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sendCommand("CWD $path")
            val response = readResponse()
            if (isSuccessCode(response.code)) {
                currentDir = path
                Result.success(Unit)
            } else {
                Result.failure(Exception("CWD failed: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadStream(path: String): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val listResult = enterPassiveMode()
            if (listResult.isFailure) {
                return@withContext Result.failure(listResult.exceptionOrNull() ?: Exception("Passive mode failed"))
            }

            sendCommand("RETR $path")
            val response = readResponse()

            if (!isSuccessCode(response.code) && response.code != 150 && response.code != 125) {
                closeDataConnection()
                return@withContext Result.failure(Exception("Download failed: ${response.message}"))
            }

            val inputStream = dataInputStream
            if (inputStream == null) {
                closeDataConnection()
                return@withContext Result.failure(Exception("Failed to get input stream"))
            }

            Result.success(inputStream)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disconnect() {
        try {
            isConnected.set(false)
            isLoggedIn.set(false)
            closeDataConnection()
            socket?.close()
            socket = null
            reader = null
            writer = null
        } catch (e: Exception) {
        }
    }

    private suspend fun enterPassiveMode(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sendCommand("PASV")
            val response = readResponse()

            if (!isSuccessCode(response.code)) {
                return@withContext Result.failure(Exception("PASV failed: ${response.message}"))
            }

            val pasvMatch = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(response.message)
            if (pasvMatch == null) {
                return@withContext Result.failure(Exception("Invalid PASV response"))
            }

            val parts = pasvMatch.destructured
            passiveHost = "${parts.component1()}.${parts.component2()}.${parts.component3()}.${parts.component4()}"
            passivePort = parts.component5().toInt() * 256 + parts.component6().toInt()

            dataSocket = Socket()
            dataSocket?.connect(InetSocketAddress(passiveHost, passivePort), 10000)

            dataReader = BufferedReader(InputStreamReader(dataSocket?.getInputStream()))
            dataInputStream = dataSocket?.getInputStream()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun closeDataConnection() {
        try {
            dataReader?.close()
            dataInputStream?.close()
            dataSocket?.close()
            dataSocket = null
            dataReader = null
            dataInputStream = null
        } catch (e: Exception) {
        }
    }

    private fun sendCommand(command: String) {
        writer?.println(command)
        writer?.flush()
    }

    private fun readResponse(): FtpResponse {
        val line = reader?.readLine() ?: throw SocketException("Connection closed")
        val code = line.substring(0, 3).toInt()
        val message = line.substring(4)
        return FtpResponse(code, message)
    }

    private fun isSuccessCode(code: Int): Boolean = code in 200..299

    private fun parseListLine(line: String, basePath: String): FileInfo? {
        if (line.isBlank()) return null
        
        val trimmedLine = line.trim()
        
        try {
            val parts = trimmedLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
            
            if (parts.size < 9) {
                LogHelper.d(TAG, "Skipping short line: $trimmedLine")
                return null
            }

            val isDir = trimmedLine.startsWith('d') || trimmedLine.startsWith('l')
            
            val size = try {
                parts[4].toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            }

            val nameStartIndex = trimmedLine.indexOf(parts[8])
            val name = if (nameStartIndex >= 0) {
                trimmedLine.substring(nameStartIndex).trim()
            } else {
                parts.drop(8).joinToString(" ")
            }

            if (name == "." || name == "..") {
                return null
            }

            val path = if (basePath == "/") {
                "/$name"
            } else if (basePath.endsWith('/')) {
                "$basePath$name"
            } else {
                "$basePath/$name"
            }

            val lastModified = System.currentTimeMillis()

            LogHelper.d(TAG, "Parsed: name=$name, isDir=$isDir, path=$path")
            return FileInfo(name, path, isDir, size, lastModified)
        } catch (e: Exception) {
            LogHelper.e(TAG, "Error parsing line: $trimmedLine", e)
            return null
        }
    }

    private fun parseListDate(dateStr: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.ENGLISH)
            format.timeZone = java.util.TimeZone.getDefault()
            val date = format.parse(dateStr) ?: return System.currentTimeMillis()
            val calendar = java.util.Calendar.getInstance()
            calendar.time = date
            if (calendar.get(java.util.Calendar.YEAR) == 1970) {
                calendar.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
            }
            calendar.timeInMillis
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
