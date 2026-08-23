package com.example.pddpricemonitor.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredRoom(
    val hostIp: String,
    val port: Int,
    val hostDeviceName: String,
    val lastSeenAt: Long
)

// 局域网房间自动发现：主机每 1.2s 向 255.255.255.255:8898 广播房间信息，
// 客户端监听同一端口收集广播，弹窗里显示"发现房间：xxx"点一下即连。
// 免去复制粘贴 IP 的步骤。
@Singleton
class RoomDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _rooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    val rooms: StateFlow<List<DiscoveredRoom>> = _rooms

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    // 两个 socket 必须分开持有：广播（发送）与监听（接收）可能同时活跃，
    // 共用一个字段会导致后启动的把先启动的 socket 覆盖掉、停止时误关对方
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null

    companion object {
        const val DISCOVERY_PORT = 8898
        private const val BROADCAST_INTERVAL_MS = 1200L
        private const val ROOM_TTL_MS = 5000L
    }

    // ---------- 主机侧：广播 ----------

    fun startBroadcast(port: Int) {
        stopBroadcast()
        val deviceName = DeviceIdentity.deviceName(context)
        broadcastJob = scope.launch {
            val socket = DatagramSocket().also { broadcastSocket = it }
            socket.broadcast = true
            val payload = JSONObject()
                .put("app", "pdd-price-monitor")
                .put("port", port)
                .put("name", deviceName)
                .toString().toByteArray(Charsets.UTF_8)
            while (isActive) {
                runCatching {
                    val packet = DatagramPacket(
                        payload, payload.size,
                        java.net.InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
                    )
                    socket.send(packet)
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    fun stopBroadcast() {
        broadcastJob?.cancel()
        broadcastJob = null
        broadcastSocket?.let { runCatching { it.close() } }
        broadcastSocket = null
    }

    // ---------- 客户端侧：监听 ----------

    fun startListening() {
        stopListening()
        _rooms.value = emptyList()
        listenJob = scope.launch {
            runCatching {
                val socket = DatagramSocket(null).also { listenSocket = it }
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(DISCOVERY_PORT))
                socket.soTimeout = 1500
                val buf = ByteArray(512)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        // 顺带清理过期房间（停止广播的主机从列表消失）
                        _rooms.value = _rooms.value.filter { System.currentTimeMillis() - it.lastSeenAt < ROOM_TTL_MS }
                        continue
                    }
                    val hostIp = packet.address.hostAddress ?: continue
                    val json = runCatching {
                        JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    }.getOrNull() ?: continue
                    if (json.optString("app") != "pdd-price-monitor") continue
                    val room = DiscoveredRoom(
                        hostIp = hostIp,
                        port = json.optInt("port"),
                        hostDeviceName = json.optString("name", "对方设备"),
                        lastSeenAt = System.currentTimeMillis()
                    )
                    _rooms.value = (_rooms.value.filter { it.hostIp != hostIp } + room)
                }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        listenSocket?.let { runCatching { it.close() } }
        listenSocket = null
        _rooms.value = emptyList()
    }

    // 主机停止后，客户端列表里它的房间最长 TTL 后自动消失
    fun shutdown() {
        stopBroadcast()
        stopListening()
    }
}
