package com.vedeng.fileserver.network.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory

class CastController {

    private var deviceAddress: String = ""
    private var devicePort: Int = 9197
    private var avTransportControlUrl: String = ""
    private var renderingControlUrl: String = ""
    private var connectionManagerUrl: String = ""
    private var isPlaying = AtomicBoolean(false)
    private var currentMediaUrl: String = ""
    private var currentPosition: Long = 0
    private var mediaDuration: Long = 0
    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val discoveredDevices = mutableListOf<CastDevice>()
    private var searchSocket: Socket? = null

    data class CastDevice(
        val name: String,
        val udn: String,
        val address: String,
        val port: Int,
        val manufacturer: String = "",
        val modelName: String = ""
    )

    companion object {
        private const val SEARCH_REQUEST = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Search xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
<s:Criteria>dc:title IS NOT NULL</s:Criteria>
<u:Filter>*</u:Filter>
<u:StartingIndex>0</u:StartingIndex>
<u:RequestedCount>0</u:RequestedCount>
<u:SortCriteria></u:SortCriteria>
</u:Search>
</s:Body>
</s:Envelope>"""

        private const val M_SEARCH_REQUEST = """M-SEARCH * HTTP/1.1\r
HOST: 239.255.255.250:1900\r
MAN: "ssdp:discover"\r
MX: 3\r
ST: urn:schemas-upnp-org:device:MediaRenderer:1\r
\r
"""
    }

    suspend fun searchDevices(timeout: Long = 5000): Result<List<CastDevice>> = withContext(Dispatchers.IO) {
        try {
            discoveredDevices.clear()

            val addresses = mutableListOf<InetAddress>()
            try {
                val wifiManager = android.content.Context::class.java
                    .getMethod("getSystemService", String::class.java)
                    .invoke(android.app.Application(), android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val dhcpInfo = wifiManager.dhcpInfo
                val ip = dhcpInfo.ipAddress
                val subnetMask = dhcpInfo.netmask
                val gateway = dhcpInfo.gateway

                for (i in 1..254) {
                    val addr = (gateway and subnetMask) or (i shl 0 and (subnetMask.inv()))
                    val bytes = byteArrayOf(
                        (addr and 0xFF).toByte(),
                        (addr shr 8 and 0xFF).toByte(),
                        (addr shr 16 and 0xFF).toByte(),
                        (addr shr 24 and 0xFF).toByte()
                    )
                    try {
                        val address = InetAddress.getByAddress(bytes)
                        addresses.add(address)
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }

            val multicastAddress = InetAddress.getByName("239.255.255.250")
            val multicastSocket = Socket()
            multicastSocket.soTimeout = timeout.toInt()

            try {
                multicastSocket.bind(InetSocketAddress(0))
                multicastSocket.connect(InetSocketAddress(multicastAddress, 1900), timeout.toInt())

                val request = M_SEARCH_REQUEST.replace("\r\n", "\n").replace("\n", "\r\n")
                multicastSocket.getOutputStream().write(request.toByteArray())
                multicastSocket.getOutputStream().flush()

                val responseBuffer = ByteArray(4096)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < timeout) {
                    try {
                        multicastSocket.soTimeout = (timeout - (System.currentTimeMillis() - startTime)).toInt()
                        val bytesRead = multicastSocket.getInputStream().read(responseBuffer)
                        if (bytesRead > 0) {
                            val response = String(responseBuffer, 0, bytesRead)
                            parseSsdpResponse(response)?.let { device ->
                                if (discoveredDevices.none { it.udn == device.udn }) {
                                    discoveredDevices.add(device)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (System.currentTimeMillis() - startTime >= timeout) break
                    }
                }
            } catch (e: Exception) {
            } finally {
                try {
                    multicastSocket.close()
                } catch (e: Exception) {
                }
            }

            for (address in addresses.take(50)) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(address, 1900), 1000)
                    socket.soTimeout = 2000

                    val request = M_SEARCH_REQUEST.replace("\r\n", "\n").replace("\n", "\r\n")
                    socket.getOutputStream().write(request.toByteArray())
                    socket.getOutputStream().flush()

                    val buffer = ByteArray(4096)
                    val bytesRead = socket.getInputStream().read(buffer)
                    if (bytesRead > 0) {
                        val response = String(buffer, 0, bytesRead)
                        parseSsdpResponse(response)?.let { device ->
                            if (discoveredDevices.none { it.udn == device.udn }) {
                                discoveredDevices.add(device)
                            }
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                }
            }

            withContext(Dispatchers.Main) {
            }

            Result.success(discoveredDevices.toList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSsdpResponse(response: String): CastDevice? {
        return try {
            val lines = response.split("\r\n", "\n")
            var location: String? = null
            var usn: String? = null
            var server: String? = null
            var st: String? = null

            for (line in lines) {
                val lowerLine = line.lowercase()
                when {
                    lowerLine.startsWith("location:") -> location = line.substringAfter("location:", "").trim()
                    lowerLine.startsWith("usn:") -> usn = line.substringAfter("usn:", "").trim()
                    lowerLine.startsWith("server:") -> server = line.substringAfter("server:", "").trim()
                    lowerLine.startsWith("st:") -> st = line.substringAfter("st:", "").trim()
                }
            }

            if (location != null && usn != null && st?.contains("mediarenderer", ignoreCase = true) == true) {
                val addrParts = location.replace("http://", "").replace("HTTPS://", "").split(":")
                val address = addrParts[0]
                val port = addrParts.getOrNull(1)?.split("/")?.firstOrNull()?.toIntOrNull() ?: 80

                val name = server?.substringAfter("/") ?: "DLNA Device"

                CastDevice(
                    name = name,
                    udn = usn!!,
                    address = address,
                    port = port,
                    manufacturer = server?.substringBefore("/") ?: ""
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun connectDevice(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            deviceAddress = device.address
            devicePort = device.port

            val deviceXmlUrl = "http://$deviceAddress:$devicePort/device.xml"
            val request = Request.Builder()
                .url(deviceXmlUrl)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val xmlContent = response.body?.string() ?: throw Exception("Failed to get device description")

            parseDeviceDescription(xmlContent)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseDeviceDescription(xml: String) {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(InputSource(StringReader(xml)))

            val services = document.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val service = services.item(i)
                val serviceType = service.attributes?.getNamedItem("serviceType")?.nodeValue ?: ""

                if (serviceType.contains("AVTransport")) {
                    val controlUrl = service.getElementsByTagName("controlURL").item(0)?.textContent ?: ""
                    avTransportControlUrl = resolveUrl(controlUrl)
                } else if (serviceType.contains("RenderingControl")) {
                    val controlUrl = service.getElementsByTagName("controlURL").item(0)?.textContent ?: ""
                    renderingControlUrl = resolveUrl(controlUrl)
                } else if (serviceType.contains("ConnectionManager")) {
                    val controlUrl = service.getElementsByTagName("controlURL").item(0)?.textContent ?: ""
                    connectionManagerUrl = resolveUrl(controlUrl)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveUrl(controlUrl: String): String {
        return when {
            controlUrl.startsWith("http://") || controlUrl.startsWith("https://") -> controlUrl
            controlUrl.startsWith("/") -> "http://$deviceAddress:$devicePort$controlUrl"
            else -> "http://$deviceAddress:$devicePort/$controlUrl"
        }
    }

    suspend fun play(mediaUrl: String, mediaTitle: String = "Video", mediaType: String = "video/*"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            currentMediaUrl = mediaUrl

            val setAvTransportUri = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$mediaUrl</CurrentURI>
<CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dc="http://purl.org/dc/elements/1.1/"&gt;&lt;item&gt;&lt;dc:title&gt;$mediaTitle&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;res protocolInfo="http-get:*:$mediaType:*"&gt;$mediaUrl&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""

            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"
            val result = sendSoapRequest(avTransportControlUrl, soapAction, setAvTransportUri)

            if (result.isFailure) {
                return@withContext result
            }

            kotlinx.coroutines.delay(500)

            val playAction = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>"""

            val playSoapAction = "urn:schemas-upnp-org:service:AVTransport:1#Play"
            val playResult = sendSoapRequest(avTransportControlUrl, playSoapAction, playAction)

            if (playResult.isSuccess) {
                isPlaying.set(true)
            }

            playResult
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pause(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val pauseAction = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Pause>
</s:Body>
</s:Envelope>"""

            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Pause"
            val result = sendSoapRequest(avTransportControlUrl, soapAction, pauseAction)

            if (result.isSuccess) {
                isPlaying.set(false)
            }

            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resume(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resumeAction = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>"""

            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Play"
            val result = sendSoapRequest(avTransportControlUrl, soapAction, resumeAction)

            if (result.isSuccess) {
                isPlaying.set(true)
            }

            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val stopAction = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Stop>
</s:Body>
</s:Envelope>"""

            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Stop"
            val result = sendSoapRequest(avTransportControlUrl, soapAction, stopAction)

            if (result.isSuccess) {
                isPlaying.set(false)
                currentMediaUrl = ""
            }

            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun seek(positionMillis: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val hours = positionMillis / 3600000
            val minutes = (positionMillis % 3600000) / 60000
            val seconds = (positionMillis % 60000) / 1000
            val timeFormat = String.format("%02d:%02d:%02d", hours, minutes, seconds)

            val seekAction = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Unit>REL_TIME</Unit>
<Target>$timeFormat</Target>
</u:Seek>
</s:Body>
</s:Envelope>"""

            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#Seek"
            sendSoapRequest(avTransportControlUrl, soapAction, seekAction)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPositionInfo(): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        try {
            val positionAction = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:GetPositionInfo>
</s:Body>
</s:Envelope>"""

            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo"
            val response = sendSoapRequestRaw(avTransportControlUrl, soapAction, positionAction)

            if (response != null) {
                val currentUri = extractXmlValue(response, "AbsTime") ?: "00:00:00"
                val duration = extractXmlValue(response, "TrackDuration") ?: "00:00:00"

                currentPosition = parseTimeToMillis(currentUri)
                mediaDuration = parseTimeToMillis(duration)

                Result.success(Pair(currentPosition, mediaDuration))
            } else {
                Result.failure(Exception("Failed to get position info"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTransportInfo(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val transportAction = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:GetTransportInfo>
</s:Body>
</s:Envelope>"""

            val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo"
            val response = sendSoapRequestRaw(avTransportControlUrl, soapAction, transportAction)

            if (response != null) {
                val state = extractXmlValue(response, "TransportState") ?: "UNKNOWN"
                Result.success(state)
            } else {
                Result.failure(Exception("Failed to get transport info"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setVolume(volume: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val volumeAction = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
<InstanceID>0</InstanceID>
<Channel>Master</Channel>
<DesiredVolume>$volume</DesiredVolume>
</u:SetVolume>
</s:Body>
</s:Envelope>"""

            val soapAction = "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume"
            sendSoapRequest(renderingControlUrl, soapAction, volumeAction)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendSoapRequest(url: String, soapAction: String, body: String): Result<Unit> {
        return try {
            val requestBody = RequestBody.create(
                MediaType.parse("text/xml; charset=utf-8"),
                body
            )

            val request = Request.Builder()
                .url(url)
                .header("SOAPACTION", "\"$soapAction\"")
                .header("Content-Type", "text/xml; charset=utf-8")
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful || responseBody.contains("200 OK") || responseBody.contains("s:Result")) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("SOAP request failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendSoapRequestRaw(url: String, soapAction: String, body: String): String? {
        return try {
            val requestBody = RequestBody.create(
                MediaType.parse("text/xml; charset=utf-8"),
                body
            )

            val request = Request.Builder()
                .url(url)
                .header("SOAPACTION", "\"$soapAction\"")
                .header("Content-Type", "text/xml; charset=utf-8")
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.body?.string()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractXmlValue(xml: String, tagName: String): String? {
        val regex = Regex("<$tagName[^>]*>([^<]*)</$tagName>")
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseTimeToMillis(time: String): Long {
        return try {
            val parts = time.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val seconds = parts[2].toLong()
                (hours * 3600 + minutes * 60 + seconds) * 1000
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying.get()

    fun getCurrentMediaUrl(): String = currentMediaUrl

    fun disconnect() {
        try {
            stop()
        } catch (e: Exception) {
        }
        isPlaying.set(false)
        currentMediaUrl = ""
    }
}
