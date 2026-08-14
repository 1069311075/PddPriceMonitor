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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pddpricemonitor.capture.MonitorDebugState
import com.example.pddpricemonitor.capture.ScreenCaptureService
import com.example.pddpricemonitor.data.ProductPrice
import com.example.pddpricemonitor.data.ProductPriceHistory
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.ui.MainViewModel
import kotlinx.coroutines.delay
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
                val itemIndex = filteredProducts.indexOf(item)
                StaggeredAppearance(index = itemIndex) {
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
                                    .background(BrandRed, RoundedCornerShape(16.dp))
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
        // 品牌 Logo：与悬浮球同款红底白「¥」
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(BrandRed, Color(0xFFC21F16))
                    ),
                    shape = RoundedCornerShape(11.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "¥",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (fullScreenList) "商品价格" else "我爱拼多多",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            if (!fullScreenList) {
                Text(
                    text = "点一下，记住价格",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
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
    // 就绪时绿点呼吸：与悬浮球识别成功的绿勾同频
    val transition = rememberInfiniteTransition(label = "status-dot")
    val breathe by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )
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
                    .graphicsLayer {
                        if (captureStarted) {
                            scaleX = 0.8f + 0.35f * breathe
                            scaleY = 0.8f + 0.35f * breathe
                        }
                    }
                    .clip(CircleShape)
                    .background(
                        if (captureStarted) {
                            FreshGreen.copy(alpha = 0.55f + 0.45f * breathe)
                        } else {
                            TextSecondary
                        }
                    )
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = if (captureStarted) "悬浮球已就绪，去拼多多点它" else "等待启动悬浮球",
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "press"
    )
    // 流光：一道柔和高光每 3.2 秒从左向右扫过一次
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerProgress by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
        onClick = onStartCapture,
        interactionSource = interactionSource,
        colors = CardDefaults.cardColors(containerColor = BrandRed),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
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
            // 流光层：斜向白色高光带
            Canvas(modifier = Modifier.matchParentSize()) {
                val bandWidth = size.width * 0.28f
                val centerX = size.width * shimmerProgress
                translate(left = centerX - bandWidth / 2f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        ),
                        size = size.copy(width = bandWidth)
                    )
                }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatColumn(title = "今日记录", value = todayCount, accent = true)
            StatDivider()
            StatColumn(title = "在记商品", value = productCount)
            StatDivider()
            StatColumn(title = "有价格变动", value = updatedCount)
        }
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(Color(0xFFEFEFF2))
    )
}

@Composable
private fun RowScope.StatColumn(title: String, value: Int, accent: Boolean = false) {
    // 数字变化时滚动过渡，而不是生硬跳变
    val animatedValue by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "stat-$title"
    )
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = "$animatedValue 件",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (accent) BrandRed else TextPrimary
        )
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
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(15.dp)
                .background(BrandRed, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(7.dp))
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
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (searchQuery.isBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp)
            ) {
                Text(
                    text = "三步开始记价",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))
                GuideStep(index = 1, text = "点上方红色卡片，启动悬浮球")
                GuideStep(index = 2, text = "打开拼多多，进入想记价的商品页")
                GuideStep(index = 3, text = "点一下悬浮球，价格自动存进来", isLast = true)
            }
        } else {
            Text(
                text = "没有找到匹配的商品。",
                modifier = Modifier.padding(18.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun GuideStep(index: Int, text: String, isLast: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(BrandRedSoft, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$index",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandRed
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(18.dp)
                        .background(Color(0xFFF0D5D3))
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            modifier = Modifier.padding(bottom = if (isLast) 0.dp else 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
    }
}

// 列表交错入场：前 8 项依次上浮淡入，之后的项直接显示（避免滚动时重播）
@Composable
private fun StaggeredAppearance(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(index >= 8) }
    LaunchedEffect(Unit) {
        if (!visible) {
            delay(index * 70L)
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = 380)) +
            slideInVertically(animationSpec = tween(durationMillis = 380)) { it / 6 },
        exit = fadeOut(tween(durationMillis = 120))
    ) {
        content()
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

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "card-press"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .animateContentSize(),
        onClick = onClick,
        interactionSource = interactionSource,
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(16.dp),
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
                        text = formatPrice(item.priceCents),
                        fontSize = 19.sp,
                        color = BrandRed,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "最低 ${formatPrice(minPrice)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        val previousPrice = if (history.size >= 2) {
                            history[history.lastIndex - 1].priceCents
                        } else {
                            null
                        }
                        if (previousPrice != null && previousPrice != item.priceCents) {
                            Spacer(modifier = Modifier.width(8.dp))
                            PriceChangeChip(current = item.priceCents, previous = previousPrice)
                        }
                    }
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
    // 圆点脉动：强调"现在就是最低"这个关键信号
    val transition = rememberInfiniteTransition(label = "lowest-dot")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Row(
        modifier = Modifier
            .background(BrandRedSoft, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer {
                    scaleX = 0.75f + 0.4f * pulse
                    scaleY = 0.75f + 0.4f * pulse
                }
                .clip(CircleShape)
                .background(BrandRed.copy(alpha = 0.5f + 0.5f * pulse))
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "历史最低",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = BrandRed
        )
    }
}

// 涨跌标签：比上次便宜用绿（值得买信号），贵了用红（提醒等等）
@Composable
private fun PriceChangeChip(current: Long, previous: Long) {
    val diff = current - previous
    val cheaper = diff < 0
    Row(
        modifier = Modifier
            .background(
                if (cheaper) SoftGreen else BrandRedSoft,
                RoundedCornerShape(50)
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (cheaper) {
                "▼ 比上次降 ${formatPrice(-diff)}"
            } else {
                "▲ 比上次涨 ${formatPrice(diff)}"
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (cheaper) FreshGreen else BrandRed
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

    Spacer(modifier = Modifier.height(14.dp))
    HorizontalDivider(color = Color(0xFFEFEFF2))
    Spacer(modifier = Modifier.height(14.dp))

    // 「价格走势」区块：标题 + 图例，图表是本区块的视觉主角
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(13.dp)
                .background(BrandRed, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = "价格走势",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.weight(1f))
        LegendDot(color = BrandRed, label = "当前")
        Spacer(modifier = Modifier.width(10.dp))
        LegendDot(color = FreshGreen, label = "最低")
    }

    Spacer(modifier = Modifier.height(10.dp))
    PriceLineChart(history = history, fallbackPrice = currentPrice)

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatPill(label = "记录", value = "${history.size} 次")
        StatPill(label = "最低", value = formatPrice(minPrice), highlight = true)
        StatPill(label = "最高", value = formatPrice(maxPrice))
    }

    Spacer(modifier = Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(13.dp)
                .background(Color(0xFFD8D8DE), RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = "历史明细",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
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
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
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
    val minIndex = chartPrices.indexOf(minPrice).coerceAtLeast(0)
    val lastIndex = chartPrices.lastIndex

    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
    )
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(
        color = TextSecondary,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium
    )
    // 关键点价格标注样式：比轴标签略大加粗，一眼可读
    val pointLabelStyle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(Color(0xFFF5F5F7), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 14.dp.toPx()
            val bottom = size.height - 16.dp.toPx()
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

            val smoothPath = buildSmoothPath(points)

            // 平滑曲线 + 渐变面积，随动画从左向右揭开
            clipRect(right = left + width * progress) {
                if (points.size > 1) {
                    val areaPath = Path().apply {
                        addPath(smoothPath)
                        lineTo(points.last().x, bottom)
                        lineTo(points.first().x, bottom)
                        close()
                    }
                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            0.0f to Color(0x38E02E24),
                            1f to Color(0x00E02E24)
                        )
                    )
                    drawPath(
                        path = smoothPath,
                        color = BrandRed,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                // 中间数据点：白底红边小圆点，让每条记录都可见
                points.forEachIndexed { index, point ->
                    if (index != minIndex && index != lastIndex) {
                        drawCircle(Color.White, radius = 3.5.dp.toPx(), center = point)
                        drawCircle(
                            color = BrandRed,
                            radius = 3.5.dp.toPx(),
                            center = point,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
                // 历史最低点：绿点 + 柔和光晕
                if (points.size > 1 && minIndex != lastIndex) {
                    val minPoint = points[minIndex]
                    drawCircle(Color(0x1A1DC981), radius = 10.dp.toPx(), center = minPoint)
                    drawCircle(Color(0xFF1DC981), radius = 4.dp.toPx(), center = minPoint)
                    drawCircle(Color.White, radius = 1.6.dp.toPx(), center = minPoint)
                }
                // 当前点：品牌红 + 光晕，视觉焦点
                if (points.isNotEmpty()) {
                    val lastPoint = points[lastIndex]
                    drawCircle(Color(0x33E02E24), radius = 11.dp.toPx(), center = lastPoint)
                    drawCircle(BrandRed, radius = 5.dp.toPx(), center = lastPoint)
                    drawCircle(Color.White, radius = 2.dp.toPx(), center = lastPoint)
                }
            }

            // 最高价标注（左上）
            drawText(
                textMeasurer = textMeasurer,
                text = formatPrice(maxPrice),
                topLeft = Offset(left, 0f),
                style = axisStyle.copy(color = TextSecondary.copy(alpha = progress))
            )

            // 当前点价格标注（红色，点位上方）
            if (points.isNotEmpty()) {
                val lastPoint = points[lastIndex]
                val lastLabel = textMeasurer.measure(
                    text = formatPrice(chartPrices[lastIndex]),
                    style = pointLabelStyle.copy(color = BrandRed)
                )
                drawText(
                    textLayoutResult = lastLabel,
                    topLeft = Offset(
                        (lastPoint.x - lastLabel.size.width / 2f)
                            .coerceIn(left, (right - lastLabel.size.width).coerceAtLeast(left)),
                        (lastPoint.y - lastLabel.size.height - 7.dp.toPx()).coerceAtLeast(0f)
                    ),
                    alpha = progress
                )
            }

            // 最低价标注（绿色，点位上方；与当前点重合时不重复标）
            if (points.size > 1 && minIndex != lastIndex) {
                val minPoint = points[minIndex]
                val minLabel = textMeasurer.measure(
                    text = formatPrice(minPrice),
                    style = pointLabelStyle.copy(color = FreshGreen)
                )
                drawText(
                    textLayoutResult = minLabel,
                    topLeft = Offset(
                        (minPoint.x - minLabel.size.width / 2f)
                            .coerceIn(left, (right - minLabel.size.width).coerceAtLeast(left)),
                        (minPoint.y - minLabel.size.height - 7.dp.toPx()).coerceAtLeast(0f)
                    ),
                    alpha = progress
                )
            }

            // 首尾日期标注（底部两角），给出时间参照
            if (history.size > 1) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = chartDate(history.first().recordedAt),
                    topLeft = Offset(left, size.height - 13.dp.toPx()),
                    style = axisStyle.copy(color = TextSecondary.copy(alpha = progress))
                )
                val lastDateLabel = textMeasurer.measure(
                    text = chartDate(history.last().recordedAt),
                    style = axisStyle
                )
                drawText(
                    textLayoutResult = lastDateLabel,
                    topLeft = Offset(
                        right - lastDateLabel.size.width,
                        size.height - 13.dp.toPx()
                    ),
                    alpha = progress
                )
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

private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size < 3) {
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }
    for (i in 0 until points.size - 1) {
        val p0 = points[max(0, i - 1)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[min(points.size - 1, i + 2)]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y
        )
    }
    return path
}

private fun formatPrice(cents: Long): String =
    "¥${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

private fun formatTime(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timeMillis))

private fun shortTime(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeMillis))

private fun chartDate(timeMillis: Long): String =
    SimpleDateFormat("M/d", Locale.CHINA).format(Date(timeMillis))

private fun isToday(timeMillis: Long): Boolean {
    val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    return dayFormat.format(Date(timeMillis)) == dayFormat.format(Date())
}

private fun normalizeSearchText(text: String): String =
    text.lowercase()
        .replace(Regex("[^\\p{IsHan}a-z0-9]+"), "")
        .trim()
