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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pddpricemonitor.capture.MonitorDebugState
import com.example.pddpricemonitor.capture.PddForegroundState
import com.example.pddpricemonitor.capture.ScreenCaptureService
import com.example.pddpricemonitor.data.ProductPrice
import com.example.pddpricemonitor.data.ProductPriceHistory
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppBackground = Color(0xFFF5F7F6)
private val FreshGreen = Color(0xFF1F8A70)
private val PriceRed = Color(0xFFC43A3A)
private val SoftGreen = Color(0xFFE6F4EE)
private val DeleteRed = Color(0xFFE05252)

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
            title = { Text("删除商品") },
            text = { Text("删除该商品及全部历史记录？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProduct(item.id)
                        if (expandedProductId == item.id) expandedProductId = null
                        pendingDelete = null
                    }
                ) {
                    Text("删除", color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (clearConfirmStep > 0) {
        val remaining = 4 - clearConfirmStep
        AlertDialog(
            onDismissRequest = { clearConfirmStep = 0 },
            title = { Text("确认清空") },
            text = {
                Text(
                    if (clearConfirmStep < 3) {
                        "这会删除所有商品和全部价格历史。还需要确认 $remaining 次。"
                    } else {
                        "最后一次确认：清空后无法恢复，确定要删除全部数据吗？"
                    }
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
                        color = DeleteRed
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmStep = 0 }) {
                    Text("取消")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Header(
            captureStarted = captureStarted,
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
            onClearHistory = { clearConfirmStep = 1 },
            onOpenPdd = { openPdd(context) },
            debugMessage = debugInfo.message,
            debugTextLength = debugInfo.lastOcrTextLength,
            debugParsedProducts = debugInfo.lastParsedProducts,
            debugSavedProducts = debugInfo.lastSavedProducts,
            debugUpdatedAt = debugInfo.updatedAt
        )

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索商品") },
            placeholder = { Text("输入品牌、型号或关键词") },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    TextButton(onClick = { searchQuery = "" }) {
                        Text("清除")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "价格记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${filteredProducts.size}/${products.size} 件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                .background(DeleteRed, RoundedCornerShape(12.dp))
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = "删除",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
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
private fun Header(
    captureStarted: Boolean,
    onStartCapture: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenPdd: () -> Unit,
    debugMessage: String,
    debugTextLength: Int,
    debugParsedProducts: Int,
    debugSavedProducts: Int,
    debugUpdatedAt: Long
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "拼多多价格助手",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (captureStarted) "悬浮球已准备，去拼多多点击 OCR 小球即可保存价格" else "等待开启悬浮球权限",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartCapture,
                    colors = ButtonDefaults.buttonColors(containerColor = FreshGreen)
                ) {
                    Text("启动")
                }
                TextButton(onClick = onOpenPdd) {
                    Text("打开拼多多")
                }
                TextButton(onClick = onClearHistory) {
                    Text("清空")
                }
            }
            Text(
                text = "最近：$debugMessage | 文字：$debugTextLength | 解析：$debugParsedProducts | 保存：$debugSavedProducts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (debugUpdatedAt > 0L) {
                Text(
                    text = "更新时间：${formatTime(debugUpdatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyState(searchQuery: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = if (searchQuery.isBlank()) "还没有保存商品，先去拼多多点悬浮球识别一次。" else "没有找到匹配的商品。",
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "更新于 ${formatTime(item.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatPrice(item.priceCents),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PriceRed
                    )
                    Text(
                        text = "最低 ${formatPrice(minPrice)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FreshGreen
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
private fun ProductHistoryDetail(
    history: List<ProductPriceHistory>,
    currentPrice: Long
) {
    val prices = history.map { it.priceCents }
    val minPrice = prices.minOrNull() ?: currentPrice
    val maxPrice = prices.maxOrNull() ?: currentPrice

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = Color(0xFFE8ECEA))
    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatPill(label = "记录", value = "${history.size} 次")
        StatPill(label = "最低", value = formatPrice(minPrice))
        StatPill(label = "最高", value = formatPrice(maxPrice))
    }

    Spacer(modifier = Modifier.height(12.dp))
    PriceLineChart(history = history, fallbackPrice = currentPrice)

    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = "历史明细",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatPrice(record.priceCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (record.priceCents == minPrice) FreshGreen else MaterialTheme.colorScheme.onSurface
            )
        }
        if (index != recentHistory.lastIndex) {
            HorizontalDivider(color = Color(0xFFF0F2F1))
        }
    }
}

@Composable
private fun RowScope.StatPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .weight(1f)
            .background(SoftGreen, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = FreshGreen,
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
            .background(Color(0xFFF8FAF9), RoundedCornerShape(12.dp))
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
                    color = Color(0xFFE3E8E5),
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
                drawPath(path = path, color = FreshGreen, style = Stroke(width = 3.dp.toPx()))
            }

            points.forEach { point ->
                drawCircle(color = Color.White, radius = 5.dp.toPx(), center = point)
                drawCircle(color = FreshGreen, radius = 3.2.dp.toPx(), center = point)
            }
        }
        if (history.size <= 1) {
            Text(
                text = "仅 1 条记录，继续保存后会形成走势",
                modifier = Modifier.align(Alignment.BottomCenter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatPrice(cents: Long): String =
    "¥${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

private fun formatTime(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timeMillis))

private fun normalizeSearchText(text: String): String =
    text.lowercase()
        .replace(Regex("[^\\p{IsHan}a-z0-9]+"), "")
        .trim()

private fun openPdd(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(PddForegroundState.PDD_PACKAGE_NAME)
    if (launchIntent != null) {
        context.startActivity(launchIntent)
    } else {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${PddForegroundState.PDD_PACKAGE_NAME}"))
        )
    }
}
