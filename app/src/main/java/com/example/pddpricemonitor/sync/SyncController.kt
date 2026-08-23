package com.example.pddpricemonitor.sync

import android.content.Context
import com.example.pddpricemonitor.data.ProductRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

// 已连接的对方设备（主机模式可能同时挂多台）
data class Peer(val deviceId: String, val deviceName: String)

sealed class SyncState {
    object Idle : SyncState()
    // 主机模式：监听中，等待对方加入（可接多台），广播房间供自动发现
    data class Hosting(val hostIp: String, val port: Int) : SyncState()
    data class Connecting(val host: String) : SyncState()
    data class Connected(val peers: List<Peer>) : SyncState()
    data class Error(val message: String) : SyncState()
}

// 局域网 P2P 同步：一台手机当主机开 ServerSocket（可接多台），其余直连。
// 连接建立后互发全量数据（含删除墓碑）合并，之后本机每次保存实时推送全量（demo 数据量小，幂等合并）。
@Singleton
class SyncController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProductRepository,
    private val discovery: RoomDiscovery
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    // 最近一次收到对端数据的时间（全量对账或增量推送都算），UI 显示"最后同步 HH:mm"
    private val _lastSyncAt = MutableStateFlow<Long?>(null)
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt

    var lastMergedCount = 0
        private set

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())

    // 会话列表：主机模式可同时存在多个（多设备演示），客户端模式恒为 0/1 个
    private val sessions = java.util.concurrent.CopyOnWriteArrayList<Session>()
    private var serverSocket: ServerSocket? = null
    private var hostInfo: SyncState.Hosting? = null

    fun localDeviceName(): String = DeviceIdentity.deviceName(context)

    // 发现的房间列表（仅客户端监听期间有值），UI 直接收集展示
    val discoveredRooms: StateFlow<List<DiscoveredRoom>> = discovery.rooms

    fun startRoomListening() = discovery.startListening()

    fun stopRoomListening() = discovery.stopListening()

    fun localIpAddress(): String? = runCatching {
        val upInterfaces = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .toList()
        // 优先取 wlan（WiFi）接口：开移动数据时蜂窝接口（rmnet/ccmni 等）可能排在前面，
        // 拿到的是运营商内网地址（10.x），对方根本连不上
        (upInterfaces.filter { it.name.startsWith("wlan") } + upInterfaces)
            .asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    fun startHost(port: Int = DEFAULT_PORT) {
        disconnect()
        val hosting = SyncState.Hosting(hostIp = localIpAddress() ?: "未连接 WiFi", port = port)
        hostInfo = hosting
        _state.value = hosting
        // 广播房间：其他手机打开同步弹窗即可看到"发现房间：本机名"，点一下连接
        discovery.startBroadcast(port)
        scope.launch {
            runCatching {
                val server = ServerSocket(port)
                serverSocket = server
                // 多设备支持：循环 accept，每来一台就开一个会话；socket 关闭时 accept 抛异常退出
                while (isActive) {
                    val socket = server.accept()
                    startSession(socket, isHost = true)
                }
            }.onFailure {
                // 用户主动断开（disconnect 先置 Idle 再关 socket）不算失败
                if (_state.value is SyncState.Hosting) {
                    _state.value = SyncState.Error("创建房间失败：${it.message ?: "未知错误"}（两台手机需连同一 WiFi）")
                }
            }
        }
    }

    fun joinHost(hostInput: String, port: Int = DEFAULT_PORT) {
        disconnect()
        // 容错解析：房间弹窗展示的是 "IP:8899" 且支持一键复制，用户多半整串粘贴；
        // 兼容带端口 / 全角冒号 / 多余空格的输入
        val cleaned = hostInput.trim().replace("：", ":").replace(" ", "")
        val colonIndex = cleaned.lastIndexOf(':')
        val host: String
        val usePort: Int
        if (colonIndex > 0) {
            host = cleaned.substring(0, colonIndex)
            usePort = cleaned.substring(colonIndex + 1).toIntOrNull() ?: port
        } else {
            host = cleaned
            usePort = port
        }
        if (host.isBlank()) {
            _state.value = SyncState.Error("请先输入对方手机上显示的地址")
            return
        }
        _state.value = SyncState.Connecting(host)
        scope.launch {
            runCatching {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(host, usePort), 8000)
                startSession(socket, isHost = false)
            }.onFailure {
                if (_state.value is SyncState.Connecting) {
                    _state.value = SyncState.Error(
                        "连接失败：${it.message ?: "无法连到主机"}\n\n" +
                            "请逐项检查：\n" +
                            "· 对方手机已点「创建房间」且停在 App 里\n" +
                            "· 两台手机连的是同一个 WiFi（不是访客网络）\n" +
                            "· 对方显示的 IP 是 192.168 开头（若不是，关掉它的移动数据重新创建房间）\n" +
                            "· 路由器需关闭「AP 隔离 / 无线隔离」"
                    )
                }
            }
        }
    }

    fun disconnect() {
        // 先置 Idle 再关 socket：accept()/connect() 抛出的异常回调读到 Idle 就不会误报 Error
        _state.value = SyncState.Idle
        hostInfo = null
        _peers.value = emptyList()
        discovery.shutdown()
        sessions.toList().forEach { it.close() }
        sessions.clear()
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
    }

    private fun startSession(socket: Socket, isHost: Boolean) {
        val outgoing = Channel<String>(Channel.UNLIMITED)
        val deviceName = DeviceIdentity.deviceName(context)
        val deviceId = DeviceIdentity.deviceId(context)

        socket.soTimeout = 0
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(socket.getOutputStream(), true)

        fun send(message: SyncMessage) {
            outgoing.trySend(SyncProtocol.encode(message))
        }

        val session = Session(socket, outgoing)

        // 写协程：队列串行写出，一行一条
        session.writerJob = scope.launch {
            for (line in outgoing) {
                writer.println(line)
                if (writer.checkError()) break
            }
        }

        // 读协程：阻塞读行 → 分发处理
        session.readerJob = scope.launch {
            var peer: Peer? = null
            try {
                send(SyncMessage.Hello(deviceId, deviceName))
                while (true) {
                    val line = reader.readLine() ?: break
                    when (val message = SyncProtocol.decode(line)) {
                        is SyncMessage.Hello -> {
                            peer = Peer(message.deviceId, message.deviceName)
                            addPeer(peer)
                            // 握手完成即对账：互发全量（含墓碑）
                            send(SyncMessage.Full(repository.exportForSync(), repository.exportTombstones()))
                        }
                        is SyncMessage.Full -> {
                            val inserted = repository.mergeFromPeer(message.products, message.tombstones)
                            if (inserted > 0) lastMergedCount = inserted
                            _lastSyncAt.value = System.currentTimeMillis()
                        }
                        SyncMessage.Bye -> break
                        null -> Unit // 忽略无法解析的行
                    }
                }
            } catch (_: Exception) {
                // 连接中断：走 finally 清理
            } finally {
                session.alive = false
                runCatching { socket.close() }
                outgoing.close()
                session.writerJob?.cancel()
                session.changeJob?.cancel()
                sessions.remove(session)
                peer?.let { removePeer(it, isHost) }
            }
        }

        // 本地保存/删除 → 实时推全量给对方（同步吸收的数据不触发 localChanges，无回环）
        session.changeJob = scope.launch {
            repository.localChanges.collect {
                if (session.alive) {
                    send(SyncMessage.Full(repository.exportForSync(), repository.exportTombstones()))
                }
            }
        }

        sessions.add(session)
    }

    private fun addPeer(peer: Peer) {
        // 同一台设备重连时替换旧条目（旧连接可能还没来得及清掉）
        _peers.value = (_peers.value.filter { it.deviceId != peer.deviceId } + peer)
        refreshState()
    }

    private fun removePeer(peer: Peer, isHost: Boolean) {
        _peers.value = _peers.value.filter { it.deviceId != peer.deviceId }
        if (!isHost) {
            // 客户端断开 → 回 Idle，需手动重连
            _state.value = SyncState.Idle
            _peers.value = emptyList()
        } else {
            refreshState()
        }
    }

    private fun refreshState() {
        val hosting = hostInfo
        if (hosting == null) {
            // 客户端模式（joinHost 不设 hostInfo）：收到对方 Hello 即已连接。
            // 之前这里直接 return，导致客户端永远停在 Connecting（数据在跑、状态不更新）
            if (_state.value is SyncState.Connecting && _peers.value.isNotEmpty()) {
                _state.value = SyncState.Connected(_peers.value)
            }
            return
        }
        // 主机模式：还有会话挂着就保持 Connected，全部走了回 Hosting 继续等下一台
        _state.value = if (_peers.value.isEmpty()) hosting else SyncState.Connected(_peers.value)
    }

    private class Session(
        val socket: Socket,
        val outgoing: Channel<String>
    ) {
        @Volatile var alive = true
        var writerJob: Job? = null
        var readerJob: Job? = null
        var changeJob: Job? = null

        fun close() {
            alive = false
            runCatching { socket.close() }
            writerJob?.cancel()
            readerJob?.cancel()
            changeJob?.cancel()
        }
    }

    companion object {
        const val DEFAULT_PORT = 8899
    }
}
