package com.vedeng.fileserver.network.dlna

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

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
        private const val TAG = "CastController"
        private const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TIMEOUT = 5000

        private const val M_SEARCH_REQUEST = """M-SEARCH * HTTP/1.1\r
HOST: 239.255.255.250:1900\r
MAN: "ssdp:discover"\r
MX: 3\r
ST: urn:schemas-upnp-org:device:MediaRenderer:1\r
\r
"""
    }

    fun searchDevices(callback: (List<CastDevice>) -> Unit) {
        discoveredDevices.clear()

        Thread {
            try {
                val multicastSocket = MulticastSocket(SSDP_PORT)
                multicastSocket.soTimeout = SEARCH_TIMEOUT
                multicastSocket.joinGroup(InetAddress.getByName(SSDP_MULTICAST_ADDRESS))

                val sendData = M_SEARCH_REQUEST.toByteArray()
                val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName(SSDP_MULTICAST_ADDRESS), SSDP_PORT)
                multicastSocket.send(sendPacket)

                val buffer = ByteArray(4096)
                val endTime = System.currentTimeMillis() + SEARCH_TIMEOUT

                while (System.currentTimeMillis() < endTime) {
                    try {
                        val receivePacket = DatagramPacket(buffer, buffer.size)
                        multicastSocket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length)
                        parseDeviceResponse(response, receivePacket.address.hostAddress ?: "")
                    } catch (e: Exception) {
                        // Timeout or receive error, continue
                    }
                }

                multicastSocket.leaveGroup(InetAddress.getByName(SSDP_MULTICAST_ADDRESS))
                multicastSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}")
            }

            callback(discoveredDevices.toList())
        }.start()
    }

    private fun parseDeviceResponse(response: String, address: String) {
        if (!response.contains("urn:schemas-upnp-org:device:MediaRenderer")) {
            return
        }

        val lines = response.split("\r\n")
        var location: String? = null
        var st: String? = null
        var usn: String? = null

        for (line in lines) {
            when {
                line.startsWith("LOCATION:", true) -> location = line.substringAfter(":").trim()
                line.startsWith("ST:", true) -> st = line.substringAfter(":").trim()
                line.startsWith("USN:", true) -> usn = line.substringAfter(":").trim()
            }
        }

        if (location != null && st?.contains("MediaRenderer") == true) {
            fetchDeviceDescription(location, address)
        }
    }

    private fun fetchDeviceDescription(location: String, address: String) {
        try {
            val request = Request.Builder()
                .url(location)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return

            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(body.byteInputStream())

            val deviceList = doc.getElementsByTagName("device")
            for (i in 0 until deviceList.length) {
                val deviceNode = deviceList.item(i)
                if (deviceNode.nodeType == Node.ELEMENT_NODE) {
                    val deviceElement = deviceNode as Element
                    val deviceType = getElementText(deviceElement, "deviceType") ?: ""
                    if (deviceType.contains("MediaRenderer")) {
                        val device = CastDevice(
                            name = getElementText(deviceElement, "friendlyName") ?: "Unknown Device",
                            udn = getElementText(deviceElement, "UDN") ?: "",
                            address = address,
                            port = extractPort(location),
                            manufacturer = getElementText(deviceElement, "manufacturer") ?: "",
                            modelName = getElementText(deviceElement, "modelName") ?: ""
                        )

                        if (discoveredDevices.none { it.udn == device.udn }) {
                            discoveredDevices.add(device)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch device description: ${e.message}")
        }
    }

    private fun getElementText(element: Element, tagName: String): String? {
        val nodeList = element.getElementsByTagName(tagName)
        return if (nodeList.length > 0) {
            val item = nodeList.item(0)
            item?.firstChild?.nodeValue
        } else null
    }

    private fun extractPort(url: String): Int {
        val regex = Regex("://([^:]+):(\\d+)")
        val match = regex.find(url)
        return match?.groupValues?.get(2)?.toIntOrNull() ?: 9197
    }

    fun selectDevice(device: CastDevice) {
        deviceAddress = device.address
        devicePort = device.port
        avTransportControlUrl = "http://$deviceAddress:$devicePort/av_transport/control"
        renderingControlUrl = "http://$deviceAddress:$devicePort/rendering_control/control"
        connectionManagerUrl = "http://$deviceAddress:$devicePort/connection_manager/control"
    }

    fun play(mediaUrl: String, mediaType: String = "video/mp4", title: String = "Video", position: Long = 0) {
        if (avTransportControlUrl.isEmpty()) {
            Log.e(TAG, "No device selected")
            return
        }

        Thread {
            try {
                stop()
                Thread.sleep(500)

                val setAvTransportUri = buildAvTransportAction("SetAVTransportURI", """
                    <CurrentURI>$mediaUrl</CurrentURI>
                    <CurrentURIMetaData>
                        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
                            <item id="1" parentID="0" restricted="0">
                                <dc:title xmlns:dc="urn:schemas-pnp-org metadata-1-0/upnp/metadata/">$title</dc:title>
                                <res protocolInfo="*:*:$mediaType:*">$mediaUrl</res>
                            </item>
                        </DIDL-Lite>
                    </CurrentURIMetaData>
                """)

                val setResponse = sendSoapRequest(avTransportControlUrl, setAvTransportUri)
                Log.d(TAG, "SetAVTransportURI response: $setResponse")

                if (position > 0) {
                    val seekAction = buildAvTransportAction("Seek", """
                        <Unit>REL_TIME</Unit>
                        <Target>${formatTime(position)}</Target>
                    """)
                    sendSoapRequest(avTransportControlUrl, seekAction)
                }

                Thread.sleep(500)

                val playAction = buildAvTransportAction("Play", "<Speed>1</Speed>")
                val playResponse = sendSoapRequest(avTransportControlUrl, playAction)
                Log.d(TAG, "Play response: $playResponse")

                currentMediaUrl = mediaUrl
                currentPosition = position
                isPlaying.set(true)
            } catch (e: Exception) {
                Log.e(TAG, "Play failed: ${e.message}")
            }
        }.start()
    }

    fun pause() {
        if (avTransportControlUrl.isEmpty()) return

        Thread {
            try {
                val pauseAction = buildAvTransportAction("Pause", "")
                val response = sendSoapRequest(avTransportControlUrl, pauseAction)
                Log.d(TAG, "Pause response: $response")
                isPlaying.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "Pause failed: ${e.message}")
            }
        }.start()
    }

    fun resume() {
        play(currentMediaUrl, "video/mp4", "Resume", currentPosition)
    }

    fun stop() {
        if (avTransportControlUrl.isEmpty()) return

        Thread {
            try {
                val stopAction = buildAvTransportAction("Stop", "")
                val response = sendSoapRequest(avTransportControlUrl, stopAction)
                Log.d(TAG, "Stop response: $response")
                isPlaying.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "Stop failed: ${e.message}")
            }
        }.start()
    }

    fun seek(position: Long) {
        if (avTransportControlUrl.isEmpty()) return

        Thread {
            try {
                val seekAction = buildAvTransportAction("Seek", """
                    <Unit>REL_TIME</Unit>
                    <Target>${formatTime(position)}</Target>
                """)
                val response = sendSoapRequest(avTransportControlUrl, seekAction)
                Log.d(TAG, "Seek response: $response")
                currentPosition = position
            } catch (e: Exception) {
                Log.e(TAG, "Seek failed: ${e.message}")
            }
        }.start()
    }

    fun setVolume(volume: Int) {
        if (renderingControlUrl.isEmpty()) return

        Thread {
            try {
                val volumeAction = buildRenderingControlAction("SetVolume", """
                    <InstanceID>0</InstanceID>
                    <Channel>Master</Channel>
                    <DesiredVolume>$volume</DesiredVolume>
                """)
                val response = sendSoapRequest(renderingControlUrl, volumeAction)
                Log.d(TAG, "SetVolume response: $response")
            } catch (e: Exception) {
                Log.e(TAG, "SetVolume failed: ${e.message}")
            }
        }.start()
    }

    fun getPosition(): Long {
        return currentPosition
    }

    fun getDuration(): Long {
        return mediaDuration
    }

    fun isCurrentlyPlaying(): Boolean {
        return isPlaying.get()
    }

    private fun buildAvTransportAction(action: String, body: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
$body
</u:$action>
</s:Body>
</s:Envelope>"""
    }

    private fun buildRenderingControlAction(action: String, body: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:$action xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
$body
</u:$action>
</s:Body>
</s:Envelope>"""
    }

    private fun sendSoapRequest(url: String, soapAction: String): String {
        val requestBody = soapAction.toRequestBody("text/xml; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
            .addHeader("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#${extractActionName(soapAction)}\"")
            .build()

        val response = okHttpClient.newCall(request).execute()
        return response.body?.string() ?: ""
    }

    private fun extractActionName(soapAction: String): String {
        val regex = Regex("u:(\\w+)")
        return regex.find(soapAction)?.groupValues?.get(1) ?: ""
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun disconnect() {
        stop()
        deviceAddress = ""
        devicePort = 0
        avTransportControlUrl = ""
        renderingControlUrl = ""
        connectionManagerUrl = ""
    }
}
