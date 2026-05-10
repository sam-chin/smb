package com.vedeng.fileserver.network.ftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

class FtpClient(
    private val host: String,
    private val port: Int = 21,
    private val username: String = "anonymous",
    private val password: String = ""
) {
    private var socket: Socket? = null
    private var dataSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var dataReader: BufferedReader? = null
    private var dataWriter: PrintWriter? = null
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

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socket = Socket()
            socket?.connect(InetSocketAddress(host, port), 10000)
            socket?.soTimeout = 15000

            reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
            writer = PrintWriter(OutputStreamWriter(socket?.getOutputStream()), true)

            val response = readResponse()
            if (!isSuccessCode(response.code)) {
                return@withContext Result.failure(Exception("Connection failed: ${response.message}"))
            }

            isConnected.set(true)

            val loginResult = login(username, password)
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

            val listResult = enterPassiveMode()
            if (listResult.isFailure) {
                return@withContext Result.failure(listResult.exceptionOrNull() ?: Exception("PASV failed"))
            }

            sendCommand("LIST $path")
            var response = readResponse()

            if (response.code == 150 || response.code == 125) {
                val files = mutableListOf<FileInfo>()
                val dataReader = BufferedReader(InputStreamReader(dataSocket?.getInputStream()))

                try {
                    var line: String?
                    while (dataReader.readLine().also { line = it } != null) {
                        line?.let {
                            val fileInfo = parseListLine(it)
                            if (fileInfo != null) {
                                files.add(fileInfo)
                            }
                        }
                    }
                } finally {
                    dataReader.close()
                    closeDataSocket()
                }

                response = readResponse()
                if (!isSuccessCode(response.code)) {
                    return@withContext Result.failure(Exception("List failed: ${response.message}"))
                }

                currentDir = if (path.isEmpty() || path == "/") "/" else path
                return@withContext Result.success(files)
            }

            return@withContext Result.failure(Exception("List failed: ${response.message}"))
        } catch (e: Exception) {
            closeDataSocket()
            Result.failure(e)
        }
    }

    suspend fun listNames(path: String = "/"): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val pasvResult = enterPassiveMode()
            if (pasvResult.isFailure) {
                return@withContext Result.failure(pasvResult.exceptionOrNull() ?: Exception("PASV failed"))
            }

            sendCommand("NLST $path")
            var response = readResponse()

            if (response.code == 150 || response.code == 125) {
                val names = mutableListOf<String>()
                val dataReader = BufferedReader(InputStreamReader(dataSocket?.getInputStream()))

                try {
                    var line: String?
                    while (dataReader.readLine().also { line = it } != null) {
                        line?.let { names.add(it) }
                    }
                } finally {
                    dataReader.close()
                    closeDataSocket()
                }

                response = readResponse()
                if (isSuccessCode(response.code)) {
                    currentDir = if (path.isEmpty() || path == "/") "/" else path
                    return@withContext Result.success(names)
                }
            }

            return@withContext Result.failure(Exception("NLST failed: ${response.message}"))
        } catch (e: Exception) {
            closeDataSocket()
            Result.failure(e)
        }
    }

    suspend fun downloadFile(remotePath: String, localFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val pasvResult = enterPassiveMode()
            if (pasvResult.isFailure) {
                return@withContext Result.failure(pasvResult.exceptionOrNull() ?: Exception("PASV failed"))
            }

            localFile.parentFile?.mkdirs()
            val outputStream = FileOutputStream(localFile)

            sendCommand("RETR $remotePath")
            var response = readResponse()

            if (response.code == 150 || response.code == 125) {
                val inputStream = dataSocket?.getInputStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int

                try {
                    while (inputStream?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                } finally {
                    outputStream.close()
                    inputStream?.close()
                    closeDataSocket()
                }

                response = readResponse()
                if (!isSuccessCode(response.code)) {
                    return@withContext Result.failure(Exception("Download failed: ${response.message}"))
                }

                return@withContext Result.success(Unit)
            }

            return@withContext Result.failure(Exception("Download failed: ${response.message}"))
        } catch (e: Exception) {
            closeDataSocket()
            Result.failure(e)
        }
    }

    suspend fun downloadStream(remotePath: String): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val pasvResult = enterPassiveMode()
            if (pasvResult.isFailure) {
                return@withContext Result.failure(pasvResult.exceptionOrNull() ?: Exception("PASV failed"))
            }

            sendCommand("RETR $remotePath")
            var response = readResponse()

            if (response.code == 150 || response.code == 125) {
                val inputStream = dataSocket?.getInputStream()
                val pipStream = PipedInputStream()
                val pipWriter = PipedOutputStream(pipStream)

                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                            pipWriter.write(buffer, 0, bytesRead)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try {
                            pipWriter.close()
                            inputStream?.close()
                            closeDataSocket()
                            readResponse()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                return@withContext Result.success(pipStream as InputStream)
            }

            return@withContext Result.failure(Exception("Download failed: ${response.message}"))
        } catch (e: Exception) {
            closeDataSocket()
            Result.failure(e)
        }
    }

    suspend fun downloadFileRange(remotePath: String, start: Long, length: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val pasvResult = enterPassiveMode()
            if (pasvResult.isFailure) {
                return@withContext Result.failure(pasvResult.exceptionOrNull() ?: Exception("PASV failed"))
            }

            sendCommand("REST $start")
            var response = readResponse()
            if (response.code != 350) {
                return@withContext Result.failure(Exception("REST failed: ${response.message}"))
            }

            sendCommand("RETR $remotePath")
            response = readResponse()

            if (response.code == 150 || response.code == 125) {
                val inputStream = dataSocket?.getInputStream()
                val buffer = ByteArray(length)
                var totalRead = 0
                var bytesRead: Int

                try {
                    while (totalRead < length) {
                        bytesRead = inputStream?.read(buffer, totalRead, length - totalRead) ?: -1
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                    }
                } finally {
                    inputStream?.close()
                    closeDataSocket()
                    readResponse()
                }

                return@withContext Result.success(buffer.copyOf(totalRead))
            }

            return@withContext Result.failure(Exception("Download failed: ${response.message}"))
        } catch (e: Exception) {
            closeDataSocket()
            Result.failure(e)
        }
    }

    suspend fun getFileSize(remotePath: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            sendCommand("SIZE $remotePath")
            val response = readResponse()

            if (response.code == 213) {
                val size = response.message.substringAfter("213 ").toLongOrNull() ?: 0L
                return@withContext Result.success(size)
            }

            return@withContext Result.failure(Exception("SIZE failed: ${response.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun changeDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            sendCommand("CWD $path")
            val response = readResponse()

            if (isSuccessCode(response.code)) {
                currentDir = path
                return@withContext Result.success(Unit)
            }

            return@withContext Result.failure(Exception("CWD failed: ${response.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentDirectory(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            sendCommand("PWD")
            val response = readResponse()

            if (response.code == 257) {
                val dir = response.message.substringAfter("\"").substringBefore("\"")
                return@withContext Result.success(dir)
            }

            return@withContext Result.failure(Exception("PWD failed: ${response.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setPassiveMode(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sendCommand("PASV")
            val response = readResponse()

            if (response.code == 227) {
                parsePassiveResponse(response.message)
                return@withContext Result.success(Unit)
            }

            return@withContext Result.failure(Exception("PASV failed: ${response.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isConnected(): Boolean = isConnected.get()

    fun isLoggedIn(): Boolean = isLoggedIn.get()

    fun getCurrentPath(): String = currentDir

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            if (isConnected.get()) {
                sendCommand("QUIT")
                readResponse()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            closeAll()
        }
    }

    private suspend fun enterPassiveMode(): Result<Unit> {
        return setPassiveMode()
    }

    private fun parsePassiveResponse(message: String) {
        val regex = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)")
        val match = regex.find(message)

        match?.let {
            val groups = it.destructured
            passiveHost = "${groups.component1()}.${groups.component2()}.${groups.component3()}.${groups.component4()}"
            val p1 = groups.component5().toInt()
            val p2 = groups.component6().toInt()
            passivePort = (p1 shl 8) or p2
        }
    }

    private suspend fun openDataConnection(): Result<Unit> {
        try {
            dataSocket = Socket()
            dataSocket?.connect(InetSocketAddress(passiveHost, passivePort), 10000)
            dataSocket?.soTimeout = 30000
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private fun closeDataSocket() {
        try {
            dataSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        dataSocket = null
        dataReader = null
        dataWriter = null
    }

    private fun closeAll() {
        closeDataSocket()
        try {
            reader?.close()
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        reader = null
        writer = null
        socket = null
        isConnected.set(false)
        isLoggedIn.set(false)
    }

    private fun sendCommand(command: String) {
        writer?.println(command)
        writer?.flush()
    }

    private suspend fun readResponse(): FtpResponse {
        val line = reader?.readLine() ?: throw SocketException("Connection closed")
        val code = line.substring(0, 3).toInt()
        val message = line.substring(4)
        return FtpResponse(code, message)
    }

    private fun isSuccessCode(code: Int): Boolean {
        return code in 200..299
    }

    private fun parseListLine(line: String): FileInfo? {
        return try {
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 9) return null

            val isDirectory = line.startsWith('d')
            val size = parts[4].toLongOrNull() ?: 0L

            val month = parts[5]
            val day = parts[6]
            val yearOrTime = parts[7]
            val name = parts.subList(8, parts.size).joinToString(" ")

            FileInfo(
                name = name,
                path = if (currentDir.endsWith("/")) currentDir + name else "$currentDir/$name",
                isDirectory = isDirectory,
                size = size,
                lastModified = parseListDate(month, day, yearOrTime)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseListDate(month: String, day: String, yearOrTime: String): Long {
        return try {
            val calendar = java.util.Calendar.getInstance()
            val currentYear = calendar.get(java.util.Calendar.YEAR)

            val monthNum = when (month) {
                "Jan" -> 0; "Feb" -> 1; "Mar" -> 2; "Apr" -> 3
                "May" -> 4; "Jun" -> 5; "Jul" -> 6; "Aug" -> 7
                "Sep" -> 8; "Oct" -> 9; "Nov" -> 10; "Dec" -> 11
                else -> 0
            }

            calendar.set(java.util.Calendar.MONTH, monthNum)
            calendar.set(java.util.Calendar.DAY_OF_MONTH, day.toInt())

            if (yearOrTime.contains(":")) {
                val timeParts = yearOrTime.split(":")
                calendar.set(java.util.Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                calendar.set(java.util.Calendar.MINUTE, timeParts[1].toInt())
            } else {
                calendar.set(java.util.Calendar.YEAR, yearOrTime.toInt())
            }

            calendar.timeInMillis
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
