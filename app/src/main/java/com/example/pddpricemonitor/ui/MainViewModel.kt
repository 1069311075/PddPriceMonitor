package com.example.pddpricemonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pddpricemonitor.data.ProductPrice
import com.example.pddpricemonitor.data.ProductPriceHistory
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.data.ScreenshotStore
import com.example.pddpricemonitor.sync.DiscoveredRoom
import com.example.pddpricemonitor.sync.SyncController
import com.example.pddpricemonitor.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ProductRepository,
    val sync: SyncController,
    val screenshotStore: ScreenshotStore
) : ViewModel() {
    val products: StateFlow<List<ProductPrice>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 「今日」的起点：每分钟重算一次，跨天挂着 App 统计口径也会自动翻篇
    private val todayStart = MutableStateFlow(currentDayStart())

    init {
        viewModelScope.launch {
            while (true) {
                todayStart.value = currentDayStart()
                delay(60_000)
            }
        }
    }

    // 今日创历史新低的商品 id 集（最新记录是今天、价=历史最低、且低于上次）：
    // 统计计数与点击过滤共用
    val newLowTodayIds: StateFlow<Set<Long>> = todayStart
        .flatMapLatest { repository.observeNewLowTodayIds(it) }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private fun currentDayStart(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // 截图存档开关：主界面 UI 状态；识别服务每次识别时直接读 ScreenshotStore 的持久化值
    private val _saveScreenshots = MutableStateFlow(screenshotStore.isEnabled())
    val saveScreenshots: StateFlow<Boolean> = _saveScreenshots

    fun setSaveScreenshots(enabled: Boolean) {
        screenshotStore.setEnabled(enabled)
        _saveScreenshots.value = enabled
    }

    // 存量归档截图补生成卡片缩略图：App 打开后台跑一次（固定比例带裁剪），已有 t 图的跳过；
    // 生成后 bump 版本号，让卡片重新检查 hasThumb（否则新生成的缩略图要等下次重组才出现）
    private val _thumbsVersion = MutableStateFlow(0)
    val thumbsVersion: StateFlow<Int> = _thumbsVersion

    fun backfillThumbs() {
        viewModelScope.launch {
            screenshotStore.backfillThumbs()
            _thumbsVersion.value += 1
        }
    }

    val syncState: StateFlow<SyncState> = sync.state

    val lastSyncAt: StateFlow<Long?> = sync.lastSyncAt

    val discoveredRooms: StateFlow<List<DiscoveredRoom>> = sync.discoveredRooms

    // 缓存每个商品的历史 StateFlow：否则每次重组都会新建冷 Flow 并重跑 Room 查询，
    // 造成持续的订阅销毁与查询抖动（CPU/内存浪费，还会让界面闪空数据）
    private val historyFlows = mutableMapOf<Long, StateFlow<List<ProductPriceHistory>>>()

    fun historyFor(productId: Long): StateFlow<List<ProductPriceHistory>> =
        historyFlows.getOrPut(productId) {
            repository.observeHistory(productId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAll()
            historyFlows.clear()
        }
    }

    fun deleteProduct(productId: Long) {
        viewModelScope.launch {
            repository.deleteProduct(productId)
            historyFlows.remove(productId)
        }
    }

    // 删除单条历史记录；若商品历史被清空，Repository 会连商品一起删，这里同步清缓存
    fun deleteHistoryEntry(historyId: Long, productId: Long) {
        viewModelScope.launch {
            repository.deleteHistoryEntry(historyId)
        }
    }

    fun startSyncHost() = sync.startHost()

    fun joinSyncHost(host: String) = sync.joinHost(host.trim())

    fun disconnectSync() = sync.disconnect()

    // 自动发现：仅空闲/出错状态才监听广播（主机在广播、已连接/连接中都不需要）
    fun startDiscovery() {
        val s = syncState.value
        if (s is SyncState.Idle || s is SyncState.Error) sync.startRoomListening()
    }

    fun stopDiscovery() = sync.stopRoomListening()
}
