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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ProductRepository,
    val sync: SyncController,
    val screenshotStore: ScreenshotStore
) : ViewModel() {
    val products: StateFlow<List<ProductPrice>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 截图存档开关：主界面 UI 状态；识别服务每次识别时直接读 ScreenshotStore 的持久化值
    private val _saveScreenshots = MutableStateFlow(screenshotStore.isEnabled())
    val saveScreenshots: StateFlow<Boolean> = _saveScreenshots

    fun setSaveScreenshots(enabled: Boolean) {
        screenshotStore.setEnabled(enabled)
        _saveScreenshots.value = enabled
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
