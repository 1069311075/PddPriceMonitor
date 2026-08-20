package com.example.pddpricemonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pddpricemonitor.data.ProductPrice
import com.example.pddpricemonitor.data.ProductPriceHistory
import com.example.pddpricemonitor.data.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ProductRepository
) : ViewModel() {
    val products: StateFlow<List<ProductPrice>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
}
