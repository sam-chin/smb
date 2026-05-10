package com.vedeng.fileserver.data.model

sealed class FileItem {
    abstract val name: String
    abstract val path: String
    abstract val isDirectory: Boolean
    abstract val size: Long
    abstract val lastModified: Long

    data class LocalFile(
        override val name: String,
        override val path: String,
        override val isDirectory: Boolean,
        override val size: Long,
        override val lastModified: Long,
        val mimeType: String? = null
    ) : FileItem()

    data class SmbFile(
        override val name: String,
        override val path: String,
        override val isDirectory: Boolean,
        override val size: Long,
        override val lastModified: Long,
        val share: String = ""
    ) : FileItem()

    data class FtpFile(
        override val name: String,
        override val path: String,
        override val isDirectory: Boolean,
        override val size: Long,
        override val lastModified: Long
    ) : FileItem()
}

data class ServerConfig(
    val id: String,
    val name: String,
    val type: ServerType,
    val host: String,
    val port: Int = 21,
    val username: String? = null,
    val password: String? = null,
    val share: String? = null,
    val anonymous: Boolean = false
)

enum class ServerType {
    SMB, FTP, LOCAL
}

data class MediaFile(
    val name: String,
    val path: String,
    val type: MediaType,
    val size: Long,
    val mimeType: String,
    val serverId: String
)

enum class MediaType {
    IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER
}
