package com.example.pddpricemonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pddpricemonitor.capture.MonitorDebugState
import com.example.pddpricemonitor.capture.ScreenCaptureService
import com.example.pddpricemonitor.data.ProductPrice
import com.example.pddpricemonitor.data.ProductPriceHistory
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 「清爽红」设计系统：取拼多多品牌红做记忆点，白底 + 大留白 + 克制用色
private val AppBackground = Color(0xFFF5F5F7)
private val CardWhite = Color(0xFFFFFFFF)
private val HairlineBorder = Color(0x1A1A1A1A)
private val BrandRed = Color(0xFFE02E24)
private val BrandRedSoft = Color(0xFFFFF1F0)
private val FreshGreen = Color(0xFF1DC981)
private val SoftGreen = Color(0xFFE9F9F1)
private val TextPrimary = Color(0xFF1A1A1A)
private val TextSecondary = Color(0xFF8A8F99)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = ProductRepository((application as PddMonitorApp).database.productPriceDao())

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppBackground
                ) {
                    PriceMonitorApp(
                        viewModel = viewModel(factory = MainViewModel.Factory(repository))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceMonitorApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)
    }
    val products by viewModel.products.collectAsState()
    val debugInfo by MonitorDebugState.info.collectAsState()
    var captureStarted by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedProductId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<ProductPrice?>(null) }
    var clearConfirmStep by rememberSaveable { mutableStateOf(0) }
    var fullScreenList by rememberSaveable { mutableStateOf(false) }

    val filteredProducts = remember(products, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            products
        } else {
            val normalizedQuery = normalizeSearchText(query)
            products.filter { item ->
                item.title.contains(query, ignoreCase = true) ||
                    item.normalizedTitle.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }
    val todayCount = remember(products) { products.count { isToday(it.updatedAt) } }
    val updatedCount = remember(products) { products.count { it.updatedAt > it.firstSeenAt } }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val serviceIntent = ScreenCaptureService.startIntent(context, result.resultCode, data)
            ContextCompat.startForegroundService(context, serviceIntent)
            captureStarted = true
        }
    }

    LaunchedEffect(Unit) {
        if (Settings.canDrawOverlays(context)) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除商品", color = TextPrimary) },
            text = { Text("删除该商品及全部历史记录？", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProduct(item.id)
                        if (expandedProductId == item.id) expandedProductId = null
                        pendingDelete = null
                    }
                ) {
                    Text("删除", color = BrandRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (clearConfirmStep > 0) {
        val remaining = 4 - clearConfirmStep
        AlertDialog(
            onDismissRequest = { clearConfirmStep = 0 },
            title = { Text("确认清空", color = TextPrimary) },
            text = {
                Text(
                    text = if (clearConfirmStep < 3) {
                        "这会删除所有商品和全部价格历史。还需要确认 $remaining 次。"
                    } else {
                        "最后一次确认：清空后无法恢复，确定要删除全部数据吗？"
                    },
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (clearConfirmStep >= 3) {
                            viewModel.clearHistory()
                            expandedProductId = null
                            clearConfirmStep = 0
                        } else {
                            clearConfirmStep += 1
                        }
                    }
                ) {
                    Text(
                        text = if (clearConfirmStep >= 3) "彻底清空" else "继续确认",
                        color = BrandRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmStep = 0 }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        TopBar(
            fullScreenList = fullScreenList,
            onToggleFullScreen = { fullScreenList = !fullScreenList }
        )

        AnimatedVisibility(visible = !fullScreenList) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                OcrStatusPill(captureStarted = captureStarted)
                Spacer(modifier = Modifier.height(14.dp))
                OcrStartCard(
                    onStartCapture = {
                        if (Settings.canDrawOverlays(context)) {
                            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    },
                    debugMessage = debugInfo.message
                )
                Spacer(modifier = Modifier.height(12.dp))
                StatCards(
                    todayCount = todayCount,
                    productCount = products.size,
                    updatedCount = updatedCount
                )
                Spacer(modifier = Modifier.height(12.dp))
                SearchBox(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it }
                )
            }
        }

        Spacer(modifier = Modifier.height(if (fullScreenList) 8.dp else 14.dp))

        SectionHeader(
            fullScreenList = fullScreenList,
            filteredCount = filteredProducts.size,
            totalCount = products.size,
            onToggleFullScreen = { fullScreenList = !fullScreenList },
            onClearHistory = { clearConfirmStep = 1 }
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filteredProducts.isEmpty()) {
                item {
                    EmptyState(searchQuery)
                }
            }
            items(filteredProducts, key = { it.id }) { item ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            pendingDelete = item
                        }
                        false
                    }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BrandRed, RoundedCornerShape(14.dp))
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text("删除", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                ) {
                    ProductCard(
                        item = item,
                        viewModel = viewModel,
                        expanded = expandedProductId == item.id,
                        onClick = {
                            expandedProductId = if (expandedProductId == item.id) null else item.id
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    fullScreenList: Boolean,
    onToggleFullScreen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(34.dp))
        Text(
            text = if (fullScreenList) "商品价格" else "我爱拼多多",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        TextButton(onClick = onToggleFullScreen) {
            Text(
                text = if (fullScreenList) "返回" else "全屏",
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun OcrStatusPill(captureStarted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .background(
                    if (captureStarted) SoftGreen else CardWhite,
                    RoundedCornerShape(50)
                )
                .border(1.dp, HairlineBorder, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (captureStarted) FreshGreen else TextSecondary)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = if (captureStarted) "OCR 已就绪" else "等待启动 OCR",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (captureStarted) FreshGreen else TextSecondary
            )
        }
    }
}

@Composable
private fun OcrStartCard(
    onStartCapture: () -> Unit,
    debugMessage: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onStartCapture,
        colors = CardDefaults.cardColors(containerColor = BrandRed),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "启动悬浮球",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "去拼多多商品页，点一下悬浮球就能记住价格",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.92f)
            )
            if (debugMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = debugMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatCards(
    todayCount: Int,
    productCount: Int,
    updatedCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(title = "今日记录", value = "$todayCount 件")
        StatCard(title = "在记商品", value = "$productCount 件")
        StatCard(title = "有价格变动", value = "$updatedCount 件")
    }
}

@Composable
private fun RowScope.StatCard(title: String, value: String) {
    Card(
        modifier = Modifier.weight(1f),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun SearchBox(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        label = { Text("搜索商品") },
        placeholder = { Text("名称 / 型号 / 关键词") },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandRed,
            focusedLabelColor = BrandRed,
            cursorColor = BrandRed,
            unfocusedBorderColor = HairlineBorder,
            focusedContainerColor = CardWhite,
            unfocusedContainerColor = CardWhite
        ),
        trailingIcon = {
            if (searchQuery.isNotBlank()) {
                TextButton(onClick = { onSearchQueryChange("") }) {
                    Text("清除", color = TextSecondary)
                }
            }
        }
    )
}

@Composable
private fun SectionHeader(
    fullScreenList: Boolean,
    filteredCount: Int,
    totalCount: Int,
    onToggleFullScreen: () -> Unit,
    onClearHistory: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (fullScreenList) "全部商品" else "最近扫描",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$filteredCount/$totalCount",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onToggleFullScreen) {
            Text(
                text = if (fullScreenList) "退出全屏" else "查看更多",
                color = TextSecondary
            )
        }
        TextButton(onClick = onClearHistory) {
            Text("清空", color = TextSecondary)
        }
    }
}

@Composable
private fun EmptyState(searchQuery: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = if (searchQuery.isBlank()) "还没有保存商品，先去拼多多点悬浮球识别一次。" else "没有找到匹配的商品。",
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun ProductCard(
    item: ProductPrice,
    viewModel: MainViewModel,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val history by viewModel.historyFor(item.id).collectAsState(initial = emptyList())
    val minPrice = history.minOfOrNull { it.priceCents } ?: item.priceCents
    val isAtLowest = item.priceCents <= minPrice

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = if (expanded) 8 else 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前 ${formatPrice(item.priceCents)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = BrandRed,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "最低 ${formatPrice(minPrice)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (isAtLowest) {
                        LowestBadge()
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = "${history.size} 条 · ${shortTime(item.updatedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }

            if (expanded) {
                ProductHistoryDetail(history = history, currentPrice = item.priceCents)
            }
        }
    }
}

@Composable
private fun LowestBadge() {
    Box(
        modifier = Modifier
            .background(BrandRedSoft, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "● 历史最低",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = BrandRed
        )
    }
}

@Composable
private fun ProductHistoryDetail(
    history: List<ProductPriceHistory>,
    currentPrice: Long
) {
    val prices = history.map { it.priceCents }
    val minPrice = prices.minOrNull() ?: currentPrice
    val maxPrice = prices.maxOrNull() ?: currentPrice

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = Color(0xFFEFEFF2))
    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatPill(label = "记录", value = "${history.size} 次")
        StatPill(label = "最低", value = formatPrice(minPrice), highlight = true)
        StatPill(label = "最高", value = formatPrice(maxPrice))
    }

    Spacer(modifier = Modifier.height(12.dp))
    PriceLineChart(history = history, fallbackPrice = currentPrice)

    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = "历史明细",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary
    )
    Spacer(modifier = Modifier.height(6.dp))

    val recentHistory = history.asReversed().take(8)
    recentHistory.forEachIndexed { index, record ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(record.recordedAt),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = formatPrice(record.priceCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (record.priceCents == minPrice) FreshGreen else TextPrimary
            )
        }
        if (index != recentHistory.lastIndex) {
            HorizontalDivider(color = Color(0xFFF5F5F7))
        }
    }
}

@Composable
private fun RowScope.StatPill(label: String, value: String, highlight: Boolean = false) {
    Column(
        modifier = Modifier
            .weight(1f)
            .background(
                if (highlight) BrandRedSoft else Color(0xFFF5F5F7),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) BrandRed else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PriceLineChart(
    history: List<ProductPriceHistory>,
    fallbackPrice: Long
) {
    val chartPrices = if (history.isEmpty()) listOf(fallbackPrice) else history.map { it.priceCents }
    val minPrice = chartPrices.minOrNull() ?: fallbackPrice
    val maxPrice = chartPrices.maxOrNull() ?: fallbackPrice

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .background(Color(0xFFF5F5F7), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            val range = (maxPrice - minPrice).takeIf { it > 0 } ?: 1L

            repeat(3) { index ->
                val y = top + height * index / 2f
                drawLine(
                    color = Color(0xFFE8E8EC),
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val points = chartPrices.mapIndexed { index, price ->
                val x = if (chartPrices.size == 1) {
                    left + width / 2f
                } else {
                    left + width * index / (chartPrices.lastIndex).coerceAtLeast(1)
                }
                val y = bottom - height * ((price - minPrice).toFloat() / range.toFloat())
                Offset(x, y)
            }

            if (points.size > 1) {
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path = path, color = BrandRed, style = Stroke(width = 3.dp.toPx()))
            }

            points.forEach { point ->
                drawCircle(color = Color.White, radius = 5.dp.toPx(), center = point)
                drawCircle(color = BrandRed, radius = 3.2.dp.toPx(), center = point)
            }
        }
        if (history.size <= 1) {
            Text(
                text = "仅 1 条记录，继续保存后会形成走势",
                modifier = Modifier.align(Alignment.BottomCenter),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

private fun formatPrice(cents: Long): String =
    "¥${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

private fun formatTime(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timeMillis))

private fun shortTime(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeMillis))

private fun isToday(timeMillis: Long): Boolean {
    val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    return dayFormat.format(Date(timeMillis)) == dayFormat.format(Date())
}

private fun normalizeSearchText(text: String): String =
    text.lowercase()
        .replace(Regex("[^\\p{IsHan}a-z0-9]+"), "")
        .trim()
