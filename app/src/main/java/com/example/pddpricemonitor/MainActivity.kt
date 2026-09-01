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
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.core.content.ContextCompat
import com.example.pddpricemonitor.capture.CapturePrefs
import com.example.pddpricemonitor.capture.MonitorDebugState
import com.example.pddpricemonitor.capture.ScreenCaptureService
import com.example.pddpricemonitor.compare.CompareApp
import com.example.pddpricemonitor.compare.CompareApps
import com.example.pddpricemonitor.data.ProductPrice
import com.example.pddpricemonitor.data.ProductPriceHistory
import com.example.pddpricemonitor.data.ScreenshotStore
import com.example.pddpricemonitor.sync.DeviceIdentity
import com.example.pddpricemonitor.sync.DiscoveredRoom
import com.example.pddpricemonitor.sync.SyncState
import com.example.pddpricemonitor.ui.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 「清爽红」设计系统：取拼多多品牌红做记忆点，白底 + 大留白 + 克制用色
// 页面底色比卡片深一档：白色卡片靠"底色差 + 发丝描边"两级区分，而不是阴影
private val AppBackground = Color(0xFFF1F1F4)
private val CardWhite = Color(0xFFFFFFFF)
private val HairlineBorder = Color(0x1A1A1A1A)
private val BrandRed = Color(0xFFE02E24)
private val BrandRedSoft = Color(0xFFFFF1F0)
private val FreshGreen = Color(0xFF1DC981)
private val ChartInk = Color(0xFF33333D)
private val RefLine = Color(0xFFE4E4EA)
private val SoftGreen = Color(0xFFE9F9F1)
private val TextPrimary = Color(0xFF1A1A1A)
private val TextSecondary = Color(0xFF8A8F99)

// 斜体衬线字体：用于价格数字、品牌字等需要"艺术感"的关键元素
private val SerifItalic = FontFamily(
    Font(R.font.source_serif_4_italic, weight = FontWeight.Normal, style = FontStyle.Italic)
)

// 远端设备配色（本机固定品牌红）：蓝 → 橙 → 紫 → 青，
// 按 deviceId 排序稳定分配，两台手机上各自的"对方"颜色一致
private val RemoteDeviceColors = listOf(
    Color(0xFF22A5F7),
    Color(0xFFF59E0B),
    Color(0xFF8B5CF6),
    Color(0xFF14B8A6)
)

private fun deviceColorOf(deviceId: String, localDeviceId: String, deviceIds: Set<String>): Color {
    if (deviceId == localDeviceId || deviceId == "local") return BrandRed
    val remote = deviceIds.filter { it != localDeviceId && it != "local" }.sorted()
    val index = remote.indexOf(deviceId)
    return if (index < 0) BrandRed else RemoteDeviceColors[index % RemoteDeviceColors.size]
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppBackground
                ) {
                    PriceMonitorApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceMonitorApp(viewModel: MainViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)
    }
    val products by viewModel.products.collectAsState()
    val debugInfo by MonitorDebugState.info.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()
    val saveScreenshots by viewModel.saveScreenshots.collectAsState()
    var showSyncDialog by rememberSaveable { mutableStateOf(false) }
    var captureStarted by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedProductId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<ProductPrice?>(null) }
    var clearConfirmStep by rememberSaveable { mutableStateOf(0) }
    var fullScreenList by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // 长按比价跳转：径向菜单状态 + 应用选择器；选择器保存后 compareRefresh++ 触发重读
    var compareMenu by remember { mutableStateOf<CompareMenuState?>(null) }
    var showComparePicker by remember { mutableStateOf(false) }
    var compareRefresh by remember { mutableStateOf(0) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val compareApps by produceState(emptyList<CompareApp>(), compareRefresh) {
        value = withContext(Dispatchers.Default) { CompareApps.resolveSelected(context) }
    }
    val compareDensity = LocalDensity.current
    val compareHaptic = LocalHapticFeedback.current

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

    // 全屏模式下按系统返回键先退出全屏，而不是直接退出应用——
    // 用户在全屏列表里按返回，预期永远是"回到上一层级"
    BackHandler(enabled = fullScreenList) {
        fullScreenList = false
    }

    LaunchedEffect(Unit) {
        // 打开 app 只看记录，不自动启动悬浮球——
        // 服务还活着就同步下状态徽标（说明球在，不用再点启动）；
        // 服务没活也不再自动弹录屏授权，想识别时点启动卡自行授权
        if (ScreenCaptureService.isRunning) {
            captureStarted = true
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            onDismiss = { pendingDelete = null },
            title = "删除商品",
            description = "删除该商品及全部历史记录？删除后无法恢复。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteProduct(item.id)
                if (expandedProductId == item.id) expandedProductId = null
                pendingDelete = null
            }
        )
    }

    if (clearConfirmStep > 0) {
        val remaining = 4 - clearConfirmStep
        ConfirmDialog(
            onDismiss = { clearConfirmStep = 0 },
            title = "确认清空",
            description = if (clearConfirmStep < 3) {
                "这会删除所有商品和全部价格历史。还需要确认 $remaining 次。"
            } else {
                "最后一次确认：清空后无法恢复，确定要删除全部数据吗？"
            },
            confirmText = if (clearConfirmStep >= 3) "彻底清空" else "继续确认",
            onConfirm = {
                if (clearConfirmStep >= 3) {
                    viewModel.clearHistory()
                    expandedProductId = null
                    clearConfirmStep = 0
                } else {
                    clearConfirmStep += 1
                }
            }
        )
    }

    val focusManager = LocalFocusManager.current
    // 展开卡片时自动滚到该卡片，让详情内容立刻进入视野（否则底部卡片展开后内容在屏幕外，看不出已打开）
    val listState = rememberLazyListState()
    val listScope = rememberCoroutineScope()

    // 列表一旦滚动就收起搜索键盘：键盘占着半屏滑列表是常态场景，别让用户手动去点空白
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }

    // 比价径向菜单控制器：回调里读的都是委托状态（compareMenu/rootSize/compareApps），保证拿到最新值；
    // ProductCard 内部用 rememberUpdatedState 接住，手势协程不会因重组拿到旧引用
    val compareController = CompareMenuController(
        apps = compareApps,
        onOpen = { item, pos ->
            // 菜单展开即复制标题：即使松手在空白处取消，标题也已在剪贴板（保底原长按复制行为）。
            // APP 在前台，写剪贴板不会触发系统剪贴板悬浮窗；markCopied 同时更新意图账本——
            // 这是最新动作，悬浮球双击据此知道剪贴板已是这个标题：轮换时不覆盖、不重复复制
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("title", item.title))
            CompareApps.markCopied(item.title)
            val radiusPx = with(compareDensity) { CompareMenuRadius.toPx() }
            compareMenu = CompareMenuState(
                center = compareCenterClamped(pos, rootSize, radiusPx),
                apps = compareApps
            )
        },
        onDrag = { pos ->
            val st = compareMenu
            if (st != null) {
                val radiusPx = with(compareDensity) { CompareMenuRadius.toPx() }
                val centers = compareIconCenters(st.center, st.apps.size, rootSize, radiusPx)
                val nearest = compareNearestIndex(
                    centers,
                    pos,
                    with(compareDensity) { CompareMenuHitRadius.toPx() }
                )
                if (nearest != st.selection) {
                    st.selection = nearest
                    if (nearest != null) {
                        compareHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
        },
        onRelease = {
            val st = compareMenu
            compareMenu = null
            val sel = st?.selection
            if (st != null && sel != null && sel < st.apps.size) {
                val app = st.apps[sel]
                if (!CompareApps.launch(context, app.packageName)) {
                    Toast.makeText(context, "无法打开「${app.label}」", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onCancel = { compareMenu = null }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootSize = it.size }
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        TopBar(
            fullScreenList = fullScreenList,
            syncConnected = syncState is SyncState.Connected,
            lastSyncAt = lastSyncAt,
            onSyncClick = { showSyncDialog = true },
            // 全屏模式下点标题回顶：列表很长时滚到底部，不用一路滑回去
            onTitleClick = if (fullScreenList) {
                { listScope.launch { listState.animateScrollToItem(0) } }
            } else null,
            onSettingsClick = { showSettings = true }
        )

        if (showSyncDialog) {
            SyncDialog(
                viewModel = viewModel,
                syncState = syncState,
                onDismiss = { showSyncDialog = false }
            )
        }

        AnimatedVisibility(visible = !fullScreenList) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                // 未启动时显示状态胶囊（引导启动），启动后只保留红色卡片，避免信息重复
                if (!captureStarted) {
                    OcrStatusPill(captureStarted = captureStarted)
                    Spacer(modifier = Modifier.height(14.dp))
                }
                OcrStartCard(
                    onStartCapture = {
                        if (ScreenCaptureService.isRunning) {
                            captureStarted = true
                        } else if (Settings.canDrawOverlays(context)) {
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
                    debugMessage = debugInfo.message,
                    captureStarted = captureStarted
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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filteredProducts.isEmpty()) {
                item {
                    EmptyState(searchQuery)
                }
            }
            itemsIndexed(filteredProducts, key = { _, item -> item.id }) { index, item ->
                // animateItem 会缓存条目测量尺寸，与卡片内部 animateContentSize 冲突，
                // 导致展开时内容被压进过期高度里互相叠印（v0.8.1 叠字 bug 根因），必须去掉
                StaggeredAppearance(index = index) {
                    val isExpanded = expandedProductId == item.id
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
                        // 展开的卡片又高又大：斜着滑列表、或点头部收起时手指带一点横向漂移，
                        // 都会被误判成左滑删除，红色底纹就莫名闪出来。
                        // 折线图展开期间干脆禁用横滑手势，收起卡片后再滑才有效
                        gesturesEnabled = !isExpanded,
                        backgroundContent = {
                            // 红底不再常驻：只有滑动真正越过中点（系统判定"打算删除"）时才淡入。
                            // 轻微误触滑出的缝隙里露出的是页面底色，不再一惊一乍
                            val revealAlpha by animateFloatAsState(
                                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) 1f else 0f,
                                animationSpec = tween(durationMillis = 180),
                                label = "delete-reveal"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = revealAlpha }
                                    .background(BrandRed, RoundedCornerShape(16.dp))
                                    .padding(end = 24.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TrashIcon(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "删除",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    ) {
                        ProductCard(
                            item = item,
                            viewModel = viewModel,
                            expanded = isExpanded,
                            onClick = {
                                val willExpand = expandedProductId != item.id
                                expandedProductId = if (willExpand) item.id else null
                                if (willExpand) {
                                    listScope.launch {
                                        // 展开动画需要 320ms；立刻滚动的话列表还处于收起布局
                                        // （内容比视口短，根本滚不动），必须等卡片撑高后再滚
                                        delay(260)
                                        listState.animateScrollToItem(index)
                                    }
                                }
                            },
                            compare = compareController
                        )
                    }
                }
            }
        }
    }

    // 比价径向菜单：覆盖整屏（坐标与卡片手势上报的 root 坐标系一致）
    compareMenu?.let { menuState ->
        CompareMenuOverlay(state = menuState, rootSize = rootSize)
    }
    }

    if (showSettings) {
        SettingsDialog(
            saveScreenshots = saveScreenshots,
            onSaveScreenshotsChange = viewModel::setSaveScreenshots,
            compareAppCount = compareApps.size,
            onCompareAppsClick = {
                showSettings = false
                showComparePicker = true
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showComparePicker) {
        CompareAppsPickerDialog(
            onDismiss = {
                showComparePicker = false
                compareRefresh++
            }
        )
    }
}

@Composable
private fun TopBar(
    fullScreenList: Boolean,
    syncConnected: Boolean = false,
    lastSyncAt: Long? = null,
    onSyncClick: () -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {}
) {
    // 运行时读取真实版本号：一眼确认手机上装的是否为最新版
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 品牌 Logo：红底白「¥」印章——衬线斜体的笔画自带书法粗细对比，
        // 与「Love PDD」标题、价格数字同一套字语；渐变左上受光、右下沉色，如朱砂落印
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFEC4A3F), Color(0xFFC21F16))
                    ),
                    shape = RoundedCornerShape(11.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "¥",
                fontSize = 21.sp,
                fontFamily = SerifItalic,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onTitleClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTitleClick
                        )
                    } else Modifier
                )
        ) {
            Text(
                text = if (fullScreenList) "商品价格" else "Love PDD",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = if (fullScreenList) FontFamily.Default else SerifItalic,
                fontWeight = FontWeight.Bold,
                color = if (fullScreenList) TextPrimary else BrandRed
            )
            if (!fullScreenList) {
                Text(
                    text = "点一下，记住价格 · v$versionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        // 多机同步入口：状态点 + 文案，已连接时染绿常亮
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (syncConnected) SoftGreen else Color(0xFFF0F0F3))
                .clickable(onClick = onSyncClick)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (syncConnected) FreshGreen else Color(0xFFB9BDC7))
            )
            Spacer(modifier = Modifier.width(5.dp))
            // 已连接时带上最近一次收到数据的时间，方便确认新鲜度（"已同步 14:32"）
            val syncTimeText = lastSyncAt?.let {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
            }
            Text(
                text = if (syncConnected) {
                    if (syncTimeText != null) "已同步 $syncTimeText" else "已同步"
                } else "同步",
                style = MaterialTheme.typography.labelMedium,
                color = if (syncConnected) TextPrimary else TextSecondary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 设置入口：细线滑杆图标（两条横线各带一个圆点，一左一右错开）——
        // 与展开 chevron 同一套细线美学；挂在同步胶囊右侧
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSettingsClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(width = 16.dp, height = 12.dp)) {
                val stroke = 1.6.dp.toPx()
                val lineColor = TextSecondary
                val dotColor = TextPrimary
                // 上杆：圆点偏左
                drawLine(lineColor, Offset(0f, size.height * 0.3f), Offset(size.width, size.height * 0.3f), stroke, StrokeCap.Round)
                drawCircle(dotColor, radius = 2.dp.toPx(), center = Offset(size.width * 0.32f, size.height * 0.3f))
                // 下杆：圆点偏右
                drawLine(lineColor, Offset(0f, size.height * 0.7f), Offset(size.width, size.height * 0.7f), stroke, StrokeCap.Round)
                drawCircle(dotColor, radius = 2.dp.toPx(), center = Offset(size.width * 0.68f, size.height * 0.7f))
            }
        }
    }
}

// 多机同步弹窗：自动发现房间（点一下即连）/ 创建房间 / 手动输入兜底，状态随 SyncState 变化
@Composable
private fun SyncDialog(
    viewModel: MainViewModel,
    syncState: SyncState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hostInput by rememberSaveable { mutableStateOf("") }
    // 弹窗打开即监听局域网广播，关闭即停止；连接成功后停止（省电且释放 UDP 口）
    val discoveredRooms by viewModel.discoveredRooms.collectAsState()
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    fun copyAddress(address: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("sync_address", address))
        Toast.makeText(context, "已复制 $address", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .background(BrandRed, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "多机同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                when (syncState) {
                    is SyncState.Idle -> {
                        Text(
                            text = "两台手机连同一 WiFi，一台创建房间、另一台打开这个弹窗就能看到它，点一下即连。不同手机记的价格会用不同颜色画进同一张走势图。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            lineHeight = 19.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        // 自动发现的房间：点一下直接加入，无需输入地址
                        if (discoveredRooms.isNotEmpty()) {
                            Text(
                                text = "发现的房间",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            discoveredRooms.forEach { room ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFF5F5F7))
                                        .clickable { viewModel.joinSyncHost("${room.hostIp}:${room.port}") }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(BrandRed)
                                    )
                                    Spacer(modifier = Modifier.width(9.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = room.hostDeviceName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "${room.hostIp}:${room.port}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    Text(
                                        text = "加入",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BrandRed
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        // 本机名称：同步后图例/明细里显示的就是它
                        var localName by remember {
                            mutableStateOf(DeviceIdentity.deviceName(context))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "本机名称",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = localName,
                                onValueChange = {
                                    if (it.length <= 12) {
                                        localName = it
                                        DeviceIdentity.rename(context, it)
                                    }
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandRed,
                                    unfocusedBorderColor = Color(0xFFE3E3E8)
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.startSyncHost() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                        ) {
                            Text("创建房间（本机当主机）", fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "或加入对方房间",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = hostInput,
                                onValueChange = { hostInput = it },
                                placeholder = {
                                    Text("输入对方显示的地址", style = MaterialTheme.typography.bodySmall)
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandRed,
                                    unfocusedBorderColor = Color(0xFFE3E3E8)
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { if (hostInput.isNotBlank()) viewModel.joinSyncHost(hostInput) },
                                enabled = hostInput.isNotBlank(),
                                modifier = Modifier.height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (hostInput.isNotBlank()) TextPrimary else Color(0xFFD9D9DE)
                                )
                            ) {
                                Text("加入", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    is SyncState.Hosting -> {
                        Text(
                            text = "房间已创建，等待另一台手机加入…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "把下面的地址告诉对方",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFF5F5F7))
                                .clickable { copyAddress("${syncState.hostIp}:${syncState.port}") }
                                .padding(horizontal = 14.dp, vertical = 13.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${syncState.hostIp}:${syncState.port}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = SerifItalic,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandRed
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "点击复制",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(onClick = { viewModel.disconnectSync() }, modifier = Modifier.fillMaxWidth()) {
                            Text("取消房间", color = TextSecondary)
                        }
                    }

                    is SyncState.Connecting -> {
                        Text(
                            text = "正在连接 ${syncState.host} …",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(onClick = { viewModel.disconnectSync() }, modifier = Modifier.fillMaxWidth()) {
                            Text("取消", color = TextSecondary)
                        }
                    }

                    is SyncState.Connected -> {
                        val peerNames = syncState.peers.joinToString("、") { it.deviceName }
                        val peerCount = syncState.peers.size
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(FreshGreen)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = "已连接 · $peerNames",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (peerCount > 1) {
                                "已连 $peerCount 台设备，数据自动合并。折线图里本机记的价格是红点，其他设备各有专属颜色，点图例可只看某台设备。"
                            } else {
                                "两台手机的数据已自动合并，之后保存的价格会实时同步。折线图里，本机记的价格是红点，$peerNames 记的是蓝点。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            lineHeight = 19.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(onClick = { viewModel.disconnectSync() }, modifier = Modifier.fillMaxWidth()) {
                            Text("断开连接", color = BrandRed)
                        }
                    }

                    is SyncState.Error -> {
                        Text(
                            text = syncState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandRed,
                            lineHeight = 19.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.disconnectSync() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TextPrimary)
                        ) {
                            Text("返回重试", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun OcrStatusPill(captureStarted: Boolean) {
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
    debugMessage: String,
    captureStarted: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "press"
    )
    // 流光：一道柔和高光每 3.2 秒从左向右扫过一次（仅未启动时显示）
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
                    .padding(horizontal = 18.dp, vertical = if (captureStarted) 14.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (captureStarted) {
                    // 已启动状态：简洁一行，不再重复"已就绪"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "悬浮球运行中 · 去拼多多点它",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                    if (debugMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = debugMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
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
            // 流光层：仅未启动时显示，避免视觉过载
            if (!captureStarted) {
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
}

// —— 弹窗骨架（「清爽红」设计系统）：替代默认 AlertDialog 的通用样式 ——
// 24dp 大圆角白卡 + 可选软红圈图标 + 居中标题/描述 + 底部按钮组，由调用方组合
@Composable
private fun AppDialogShell(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CardWhite)
        ) {
            content()
        }
    }
}

// 确认类弹窗：删除/清空共用。软红圈垃圾桶图标传递"危险但克制"的信号，
// 双按钮并排：取消（浅灰）+ 确认（品牌红）
@Composable
private fun ConfirmDialog(
    onDismiss: () -> Unit,
    title: String,
    description: String,
    confirmText: String,
    onConfirm: () -> Unit,
    cancelText: String = "取消"
) {
    AppDialogShell(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 26.dp, start = 22.dp, end = 22.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(BrandRedSoft),
                contentAlignment = Alignment.Center
            ) {
                TrashIcon(modifier = Modifier.size(22.dp), color = BrandRed)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DialogActionButton(
                    text = cancelText,
                    onClick = onDismiss,
                    style = DialogActionStyle.Quiet,
                    modifier = Modifier.weight(1f)
                )
                DialogActionButton(
                    text = confirmText,
                    onClick = onConfirm,
                    style = DialogActionStyle.Destructive,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private enum class DialogActionStyle { Primary, Destructive, Quiet }

@Composable
private fun DialogActionButton(
    text: String,
    onClick: () -> Unit,
    style: DialogActionStyle = DialogActionStyle.Quiet,
    modifier: Modifier = Modifier
) {
    val bg = when (style) {
        DialogActionStyle.Destructive -> BrandRed
        DialogActionStyle.Primary -> TextPrimary
        DialogActionStyle.Quiet -> Color(0xFFF2F3F5)
    }
    val fg = if (style == DialogActionStyle.Quiet) TextPrimary else Color.White
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// 线条式垃圾桶图标：提手 + 桶盖 + 圆角桶身 + 两道竖线，与 App 的细线视觉语言一致
@Composable
private fun TrashIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        // 提手
        drawLine(color, Offset(w * 0.40f, h * 0.14f), Offset(w * 0.40f, h * 0.30f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.60f, h * 0.14f), Offset(w * 0.60f, h * 0.30f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.14f), Offset(w * 0.60f, h * 0.14f), strokeWidth = sw, cap = StrokeCap.Round)
        // 桶盖
        drawLine(color, Offset(w * 0.12f, h * 0.30f), Offset(w * 0.88f, h * 0.30f), strokeWidth = sw, cap = StrokeCap.Round)
        // 桶身
        drawPath(
            path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = w * 0.22f,
                        top = h * 0.36f,
                        right = w * 0.78f,
                        bottom = h * 0.90f,
                        cornerRadius = CornerRadius(w * 0.08f)
                    )
                )
            },
            color = color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // 内部两道竖线
        drawLine(color, Offset(w * 0.42f, h * 0.50f), Offset(w * 0.42f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.58f, h * 0.50f), Offset(w * 0.58f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

// 设置弹窗：集中收纳所有可配置项（首页只保留业务内容，设置不占主界面空间）
@Composable
private fun SettingsDialog(
    saveScreenshots: Boolean,
    onSaveScreenshotsChange: (Boolean) -> Unit,
    compareAppCount: Int,
    onCompareAppsClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    AppDialogShell(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 12.dp)
            )
            // iOS 分组式设置：浅灰分组容器 + 组内细线分隔，开关行收纳其中
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                SettingsGroup {
                    GroupRow {
                        CompareAppsRow(
                            count = compareAppCount,
                            onClick = onCompareAppsClick
                        )
                    }
                    GroupDivider()
                    // 跳转比价时复制标题：与双击跳转比价应用配套，需用户主动授权开启。
                    // 复制发生在双击瞬间（经前台中转页），不在识别完成时——避免触发系统剪贴板悬浮窗
                    GroupRow {
                        var autoCopyTitle by remember { mutableStateOf(CompareApps.isAutoCopyTitle(context)) }
                        SettingsRow(
                            title = "跳转比价时复制标题",
                            subtitle = "识别后首次双击跳转时复制一次商品名，之后仅跳转",
                            checked = autoCopyTitle,
                            onChange = { enabled ->
                                CompareApps.setAutoCopyTitle(context, enabled)
                                autoCopyTitle = enabled
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SettingsGroup {
                    // 识别后自动保存：识别成功即入库，面板转回执（对了不用管，
                    // 错了点「修改保存」或「重新识别」，自动保存的行会被撤回重记）
                    GroupRow {
                        var autoSave by remember { mutableStateOf(CapturePrefs.isAutoSaveEnabled(context)) }
                        SettingsRow(
                            title = "识别后自动保存",
                            subtitle = "识别成功即入库，面板只作回执；错了可修改重记",
                            checked = autoSave,
                            onChange = { enabled ->
                                CapturePrefs.setAutoSaveEnabled(context, enabled)
                                autoSave = enabled
                            }
                        )
                    }
                    GroupDivider()
                    GroupRow {
                        SettingsRow(
                            title = "保存识别截图",
                            subtitle = "压缩后每张约 40KB · 删记录同步删图",
                            checked = saveScreenshots,
                            onChange = onSaveScreenshotsChange
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 识别结果显示时长：面板停留多久自动收起；「常驻」适合慢核对的场景
                SettingsGroup {
                    GroupRow {
                        var receiptDuration by remember { mutableStateOf(CapturePrefs.getReceiptDurationMs(context)) }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "识别结果显示时长",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "结果面板停留多久自动收起；「常驻」则一直显示，点 × 或重新识别收起",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(9.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    4_000L to "4秒",
                                    8_000L to "8秒",
                                    15_000L to "15秒",
                                    30_000L to "30秒",
                                    0L to "常驻"
                                ).forEach { (ms, label) ->
                                    val selected = receiptDuration == ms
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) BrandRed else TextSecondary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (selected) BrandRedSoft else Color(0xFFE9EAEE))
                                            .clickable {
                                                CapturePrefs.setReceiptDurationMs(context, ms)
                                                receiptDuration = ms
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
            DialogActionButton(
                text = "完成",
                onClick = onDismiss,
                style = DialogActionStyle.Destructive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
            )
            Text(
                text = "Love PDD · v$versionName",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp, bottom = 16.dp)
            )
        }
    }
}

// 设置分组容器：浅灰圆角底，组内行之间用细线分隔（iOS 分组列表语义）
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF5F5F7))
    ) {
        content()
    }
}

@Composable
private fun GroupRow(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        content()
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        thickness = 0.5.dp,
        color = Color(0x14A0A0A8)
    )
}

// 设置行：标题 + 副文案 + 开关（副文案讲清行为与代价，让用户放心开关）
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = BrandRed,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFD9D9DE),
                uncheckedThumbColor = Color.White
            )
        )
    }
}

// 截图全屏查看：黑底沉浸，点任意处关闭，底部半透明信息条带时间与价格
@Composable
private fun ScreenshotViewer(
    screenshotStore: ScreenshotStore,
    record: ProductPriceHistory,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, record.id) {
            value = withContext(Dispatchers.IO) { screenshotStore.decodeSampled(record.id, 1280) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF20B0B0F))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(record.recordedAt),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatPrice(record.priceCents),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
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
    // 去卡片化：用大数字 + 细分割线撑起层次，不再用圆角白卡容器
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatColumn(title = "今日记录", value = todayCount, accent = true)
        StatDivider()
        StatColumn(title = "在记商品", value = productCount)
        StatDivider()
        StatColumn(title = "有价格变动", value = updatedCount)
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$animatedValue",
                fontSize = 22.sp,
                fontFamily = SerifItalic,
                fontWeight = FontWeight.Bold,
                color = if (accent) BrandRed else TextPrimary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "件",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (accent) BrandRed else TextSecondary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun SearchBox(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    // 内联化搜索：浅灰圆底 + 无边框，视觉更轻，贴合 Apple 风格
    val focusManager = LocalFocusManager.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF2F2F4), RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            ),
            cursorBrush = SolidColor(BrandRed),
            // 键盘右下角「完成」直接收起：输完关键词就该看到结果，而不是让键盘一直占半屏
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索商品名称 / 型号",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        innerTextField()
                    }
                    if (searchQuery.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        // 紧凑圆形 ✕：替代原文字按钮「清除」，输入框右侧不再被文字撑宽
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE3E3E8))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onSearchQueryChange("") }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "×",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        )
    }
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
                text = if (fullScreenList) "退出全屏" else "全屏查看",
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
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFFF0D5D3), thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "悬浮球操作",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                GestureHint(gesture = "单击", description = "识别当前商品的价格")
                GestureHint(gesture = "双击", description = "跳转到拼多多")
                GestureHint(gesture = "长按", description = "跳转到拼多多")
                GestureHint(gesture = "拖动", description = "移动位置，松手自动贴边", isLast = true)
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

// 悬浮球手势说明行：左侧手势胶囊 + 右侧功能描述
@Composable
private fun GestureHint(gesture: String, description: String, isLast: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 44.dp)
                .background(BrandRedSoft, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = gesture,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = BrandRed
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = description,
            modifier = Modifier.padding(bottom = if (isLast) 0.dp else 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

// 列表交错入场：前 6 项依次上浮淡入，之后的项直接显示（避免滚动时重播与首屏卡顿）
// 用 rememberSaveable 而非 remember：条目滚出视口被 LazyColumn 销毁后再回来时，
// 记住"已播放过"状态，否则收起卡片/滚动列表会重播淡入动画 → 界面闪烁
@Composable
private fun StaggeredAppearance(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var visible by rememberSaveable { mutableStateOf(index >= 6) }
    LaunchedEffect(Unit) {
        if (!visible) {
            delay(index * 50L)
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(durationMillis = 320)) +
            slideInVertically(animationSpec = tween(durationMillis = 320)) { it / 6 },
        exit = fadeOut(tween(durationMillis = 120))
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductCard(
    item: ProductPrice,
    viewModel: MainViewModel,
    expanded: Boolean,
    onClick: () -> Unit,
    compare: CompareMenuController? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // 径向菜单控制器：pointerInput(Unit) 的手势协程不会随重组重启，用最新引用接住
    val currentCompare by rememberUpdatedState(compare)
    // 手势上报 root 坐标的锚点：pointerInput 局部坐标 + 本节点在 root 中的位置
    var headerPosInRoot by remember { mutableStateOf(Offset.Zero) }
    val history by viewModel.historyFor(item.id).collectAsState()
    val minPrice = history.minOfOrNull { it.priceCents } ?: item.priceCents
    val isAtLowest = item.priceCents <= minPrice
    val previousPrice = if (history.size >= 2) history[history.lastIndex - 1].priceCents else null
    val isDowngrade = previousPrice != null && item.priceCents < previousPrice
    // 关键买入信号：当前已是历史最低，且比上次还便宜 → 卡片渲染淡绿色光晕
    val showBuyGlow = isAtLowest && isDowngrade

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
            // 固定 320ms 的展开/收起动画：时长确定，自动滚动才能配合时序（见 onClick 中的 delay）
            .animateContentSize(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
            )
            // 发丝描边划出卡片边界（买入信号时换成绿框）：零阴影的扁平层级靠底色差 + 描边区分
            .border(
                width = 0.6.dp,
                color = if (showBuyGlow) FreshGreen.copy(alpha = 0.35f) else Color(0x0F1A1A1A),
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // 只有头部（标题 + 价格）可点击展开/收起，详情区与折线图不再响应收起
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { headerPosInRoot = it.positionInRoot() }
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isPressed) Color(0x0F1A1A1A) else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        // 未配置比价应用时保留原行为：长按直接复制商品名
                        onLongClick = if (compare == null) {
                            {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("title", item.title))
                                CompareApps.markCopied(item.title)
                                Toast.makeText(context, "已复制商品名", Toast.LENGTH_SHORT).show()
                            }
                        } else null
                    )
                    // 配置了比价应用：长按后从按压点展开径向菜单，滑到图标上松手即复制标题并跳转；
                    // 长按之后的事件流由本手势消费，不会误触发上面的单击展开
                    .then(
                        if (compare != null) {
                            Modifier.pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val ctrl = currentCompare ?: return@detectDragGesturesAfterLongPress
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        ctrl.onOpen(item, headerPosInRoot + offset)
                                    },
                                    onDrag = { change, _ ->
                                        currentCompare?.onDrag(headerPosInRoot + change.position)
                                    },
                                    onDragEnd = { currentCompare?.onRelease() },
                                    onDragCancel = { currentCompare?.onCancel() }
                                )
                            }
                        } else Modifier
                    )
            ) {
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
                    // 当前价 + 涨跌胶囊并排（学 Keepa/股票软件：变化是价格的注释，不是独立信息）；
                    // 底部对齐让小胶囊贴着价格基线，读起来像后缀而非并列元素
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = formatPrice(item.priceCents),
                            fontSize = 20.sp,
                            fontFamily = SerifItalic,
                            color = BrandRed,
                            fontWeight = FontWeight.Bold
                        )
                        if (previousPrice != null && previousPrice != item.priceCents) {
                            Spacer(modifier = Modifier.width(6.dp))
                            PriceChangeChip(
                                current = item.priceCents,
                                previous = previousPrice,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "最低",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = formatPrice(minPrice),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = SerifItalic,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (isAtLowest) {
                        LowestBadge()
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    // 多设备时显示"几台设备"，比单机的更新时间更有信息量
                    val deviceCount = remember(history) { history.map { it.deviceId }.toSet().size }
                    Text(
                        text = if (deviceCount > 1) {
                            "${history.size} 条 · ${deviceCount} 台设备"
                        } else {
                            "${history.size} 条 · ${shortTime(item.updatedAt)}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
                // 展开指示箭头：极简单笔画 chevron（两点一线，圆头细线），
                // 展开时旋转 180° 并染品牌红——状态用颜色表达，形状保持克制
                Spacer(modifier = Modifier.width(12.dp))
                val chevronRotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    label = "expand-chevron"
                )
                val chevronColor by animateColorAsState(
                    targetValue = if (expanded) BrandRed else Color(0xFFB9BDC7),
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    label = "expand-chevron-color"
                )
                Canvas(
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                        .semantics {
                            contentDescription = if (expanded) "收起详情" else "展开详情"
                        }
                ) {
                    val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val path = Path().apply {
                        moveTo(size.width * 0.18f, size.height * 0.34f)
                        lineTo(size.width * 0.5f, size.height * 0.66f)
                        lineTo(size.width * 0.82f, size.height * 0.34f)
                    }
                    drawPath(path, chevronColor, style = stroke)
                }
            }
            }

            if (expanded) {
                // 高度变化统一由外层 animateContentSize 平滑完成；
                // 不再包一层 AnimatedVisibility，避免嵌套尺寸动画干扰测量
                ProductHistoryDetail(
                    history = history,
                    currentPrice = item.priceCents,
                    screenshotStore = viewModel.screenshotStore,
                    onDeleteEntry = { historyId ->
                        viewModel.deleteHistoryEntry(historyId, item.id)
                    }
                )
            }
        }
    }
}

// ==================== 长按比价径向菜单 ====================

// 图标中心距按住点 84dp，命中半径 42dp
private val CompareMenuRadius = 84.dp
private val CompareMenuHitRadius = 42.dp

private class CompareMenuState(
    val center: Offset,
    val apps: List<CompareApp>
) {
    var selection by mutableStateOf<Int?>(null)
}

private class CompareMenuController(
    val apps: List<CompareApp>,
    val onOpen: (ProductPrice, Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onRelease: () -> Unit,
    val onCancel: () -> Unit
)

// 按住点靠近屏幕边缘时收进安全区，保证图标弧不出屏
private fun compareCenterClamped(raw: Offset, rootSize: IntSize, radiusPx: Float): Offset {
    val marginX = radiusPx + 24f
    val marginY = radiusPx + 48f
    return Offset(
        raw.x.coerceIn(marginX, (rootSize.width - marginX).coerceAtLeast(marginX)),
        raw.y.coerceIn(marginY, (rootSize.height - marginY - radiusPx).coerceAtLeast(marginY))
    )
}

// 图标弧几何：按住点在屏幕下半区时弧朝上（210°→330°），上半区朝下（30°→150°），横跨 120°
private fun compareIconCenters(
    center: Offset,
    count: Int,
    rootSize: IntSize,
    radiusPx: Float
): List<Offset> {
    if (count <= 0) return emptyList()
    val up = center.y > rootSize.height * 0.45f
    val startAngle = if (up) 210f else 30f
    val step = if (count > 1) 120f / (count - 1) else 0f
    return List(count) { i ->
        val angleRad = Math.toRadians((startAngle + step * i).toDouble())
        Offset(
            center.x + (cos(angleRad) * radiusPx).toFloat(),
            center.y + (sin(angleRad) * radiusPx * 0.92f).toFloat()
        )
    }
}

private fun compareNearestIndex(centers: List<Offset>, pos: Offset, hitPx: Float): Int? {
    var best: Int? = null
    var bestDist = hitPx
    centers.forEachIndexed { i, c ->
        val d = (c - pos).getDistance()
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

// 全屏覆盖层：暗色蒙层 + 中心按住点 + 弧形图标 + 虚线引导。坐标是 root 坐标系，与卡片手势上报一致
@Composable
private fun CompareMenuOverlay(state: CompareMenuState, rootSize: IntSize) {
    if (rootSize == IntSize.Zero) return
    val density = LocalDensity.current
    val radiusPx = with(density) { CompareMenuRadius.toPx() }
    val centers = remember(state, rootSize, radiusPx) {
        compareIconCenters(state.center, state.apps.size, rootSize, radiusPx)
    }
    val up = state.center.y > rootSize.height * 0.45f

    // 整体进场：进度 0→1，图标按索引错峰展开（每档 30ms）
    val appear = remember(state) { Animatable(0f) }
    LaunchedEffect(state) {
        appear.animateTo(1f, tween(durationMillis = 340, easing = FastOutSlowInEasing))
    }
    val scrimAlpha = (appear.value * 0.75f).coerceIn(0f, 0.42f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
    ) {
        // 虚线引导：从按住点指向各图标
        Canvas(modifier = Modifier.matchParentSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))
            centers.forEach { c ->
                drawLine(
                    color = Color.White.copy(alpha = 0.32f * appear.value),
                    start = state.center,
                    end = c,
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )
            }
        }

        // 按住点：中心红点 + 呼吸光环 + 操作提示
        val pulse by rememberInfiniteTransition(label = "compare-pulse").animateFloat(
            initialValue = 0.9f,
            targetValue = 1.7f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 1300, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "compare-pulse"
        )
        Canvas(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (state.center.x - 14.dp.toPx()).roundToInt(),
                        (state.center.y - 14.dp.toPx()).roundToInt()
                    )
                }
                .size(28.dp)
        ) {
            drawCircle(BrandRed.copy(alpha = 0.28f), radius = 7.dp.toPx() * pulse)
            drawCircle(BrandRed, radius = 4.dp.toPx())
        }
        var captionSize by remember { mutableStateOf(IntSize.Zero) }
        Text(
            text = "已复制标题 · 滑到图标松手",
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.9f * appear.value),
                shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 6f)
            ),
            modifier = Modifier
                .onGloballyPositioned { captionSize = it.size }
                .offset {
                    // 提示放在弧的反方向一侧，不与图标抢位置
                    val dy = (if (up) 40.dp else -(40.dp + captionSize.height.toDp())).toPx()
                    IntOffset(
                        (state.center.x - captionSize.width / 2f).roundToInt(),
                        (state.center.y + dy).roundToInt()
                    )
                }
        )

        centers.forEachIndexed { i, c ->
            val app = state.apps[i]
            val selected = state.selection == i
            val appearI = ((appear.value - i * 0.08f) / 0.42f).coerceIn(0f, 1f)
            val scale = 0.55f + 0.45f * appearI
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (c.x - 23.dp.toPx()).roundToInt(),
                            (c.y - 23.dp.toPx()).roundToInt()
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = appearI
                    }
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .graphicsLayer {
                            val s = if (selected) 1.15f else 1f
                            scaleX = s
                            scaleY = s
                        }
                        .shadow(elevation = 6.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) BrandRed else Color(0xFFE6E6EC),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.75f),
                        shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 6f)
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

// 首页「比价应用」设置行
@Composable
private fun CompareAppsRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "比价应用",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (count == 0) {
                    "未设置 · 长按商品卡弹出菜单，双击悬浮球轮换跳转"
                } else {
                    "已选 $count 个 · 双击球按顺序轮换比价，长按球回拼多多后从头开始"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = TextSecondary.copy(alpha = 0.55f)
        )
    }
}

// 应用选择器：枚举手机上全部可启动应用，勾选加入径向菜单（上限 5 个，顺序即菜单顺序）
@Composable
private fun CompareAppsPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(CompareApps.selectedPackages(context)) }
    var query by remember { mutableStateOf("") }
    val allApps by produceState<List<CompareApp>?>(null) {
        value = withContext(Dispatchers.Default) { CompareApps.listLaunchable(context) }
    }

    AppDialogShell(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // 标题行：标题在左、完成在右上（iOS 导航语义，省出底部按钮空间给列表）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, start = 6.dp, end = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "比价应用",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "完成",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandRed,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Text(
                text = "长按商品卡片，滑到图标上松手即复制标题并跳转该应用。" +
                    "双击悬浮球按此顺序轮换比价，长按悬浮球回拼多多后从头开始。" +
                    "最多 ${CompareApps.MAX_APPS} 个，已选的可用 ↑↓ 调整顺序。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF2F2F4), RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(BrandRed),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (query.isEmpty()) {
                                Text(
                                    text = "搜索应用",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            val apps = allApps
            if (apps == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "正在读取应用列表…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                val q = query.trim()
                val filtered = if (q.isEmpty()) {
                    apps
                } else {
                    apps.filter {
                        it.label.contains(q, true) || it.packageName.contains(q, true)
                    }
                }
                // 已选的排前面（按勾选顺序），其余按名称
                val ordered = filtered.sortedBy { app ->
                    val idx = selected.indexOf(app.packageName)
                    if (idx >= 0) idx else Int.MAX_VALUE
                }
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .padding(bottom = 12.dp)
                ) {
                    items(ordered, key = { it.packageName }) { app ->
                        val isSelected = app.packageName in selected
                        val full = selected.size >= CompareApps.MAX_APPS
                        val selIdx = selected.indexOf(app.packageName)
                        CompareAppRow(
                            app = app,
                            selected = isSelected,
                            enabled = isSelected || !full,
                            showMove = isSelected && selected.size >= 2,
                            canMoveUp = selIdx > 0,
                            canMoveDown = selIdx in 0 until selected.size - 1,
                            onMove = { delta ->
                                val to = selIdx + delta
                                if (selIdx >= 0 && to in selected.indices) {
                                    val next = selected.toMutableList().apply {
                                        add(to, removeAt(selIdx))
                                    }
                                    selected = next
                                    CompareApps.saveSelected(context, next)
                                }
                            },
                            onToggle = {
                                selected = if (isSelected) {
                                    selected - app.packageName
                                } else {
                                    selected + app.packageName
                                }
                                CompareApps.saveSelected(context, selected)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareAppRow(
    app: CompareApp,
    selected: Boolean,
    enabled: Boolean,
    showMove: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMove: (Int) -> Unit = {},
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onToggle
            )
            .padding(horizontal = 4.dp, vertical = 7.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // 顺序调整：只在已选行出现，↑↓ 移动其在轮换序列中的位置（首/尾对应方向禁用）
        if (showMove) {
            MoveButton(symbol = "↑", enabled = canMoveUp) { onMove(-1) }
            Spacer(modifier = Modifier.width(4.dp))
            MoveButton(symbol = "↓", enabled = canMoveDown) { onMove(1) }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Canvas(modifier = Modifier.size(20.dp)) {
            if (selected) {
                drawCircle(BrandRed)
                val path = Path().apply {
                    moveTo(size.width * 0.27f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.69f)
                    lineTo(size.width * 0.75f, size.height * 0.33f)
                }
                drawPath(
                    path,
                    Color.White,
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            } else {
                drawCircle(
                    color = Color(0xFFC9C9D0),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}

// 顺序调整按钮：小圆角方块 + 箭头字符，禁用时低透明度占位（位置不动、布局不跳）
@Composable
private fun MoveButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF2F3F5))
            .graphicsLayer { alpha = if (enabled) 1f else 0.25f }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
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
                .background(SoftGreen, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 圆点脉动：强调"现在就是最低"这个关键信号。
            // 脉动值只在 Canvas 绘制块里读取——仅失效 draw 阶段，不触发重组/重排，
            // 否则每个徽章 60fps 持续重组会在滑动列表时挤爆帧预算（滚动卡顿根因）
            Canvas(modifier = Modifier.size(6.dp)) {
                val scale = 0.75f + 0.4f * pulse
                drawCircle(
                    color = FreshGreen.copy(alpha = 0.5f + 0.5f * pulse),
                    radius = size.minDimension / 2f * scale
                )
            }
            Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "历史最低",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = FreshGreen
        )
    }
}

// 涨跌胶囊：贴在当前价右侧的后缀注释（"▲ ¥3"），方向 + 幅度一眼读完；
// 比上次便宜用绿（值得买信号），贵了用红（提醒等等），数字滚动从旧值到新值增强反馈
@Composable
private fun PriceChangeChip(
    current: Long,
    previous: Long,
    modifier: Modifier = Modifier
) {
    val diff = current - previous
    val diffFloat = diff.toFloat()
    val cheaper = diff < 0
    // 数字滚动动画：从 0 → diff，强化"变化"的视觉反馈
    val animatedDiff by animateFloatAsState(
        targetValue = diffFloat,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "price-diff"
    )
    val roundedDiff = kotlin.math.abs(animatedDiff.roundToInt()) / 100
    Row(
        modifier = modifier
            .background(
                if (cheaper) SoftGreen else BrandRedSoft,
                RoundedCornerShape(50)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (cheaper) {
                "▼ ¥$roundedDiff"
            } else {
                "▲ ¥$roundedDiff"
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
    currentPrice: Long,
    screenshotStore: ScreenshotStore,
    onDeleteEntry: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    val localDeviceId = remember { DeviceIdentity.deviceId(ctx) }
    val deviceIds = remember(history) { history.map { it.deviceId }.toSet() }
    // 设备筛选：点图例开关某台设备的数据点（只影响折线图和统计，不隐藏明细列表）；
    // 全关时回退显示全部，避免出现空图
    var hiddenDevices by rememberSaveable(history.firstOrNull()?.productId ?: 0L) {
        mutableStateOf(ArrayList<String>())
    }
    val filterActive = hiddenDevices.isNotEmpty() && !deviceIds.all { hiddenDevices.contains(it) }
    val visibleHistory = if (filterActive) history.filter { !hiddenDevices.contains(it.deviceId) } else history

    val prices = visibleHistory.map { it.priceCents }
    val minPrice = prices.minOrNull() ?: currentPrice
    var showAll by remember { mutableStateOf(false) }
    // 截图存档存在性：一次目录列举（IO 线程）拿到全量 id 集合，
    // 替代原来每行 File.exists() 的主线程同步 I/O——那是展开动画首帧卡顿的主因
    val shotIds by produceState<Set<Long>>(emptySet(), screenshotStore) {
        value = screenshotStore.existingShotIds()
    }

    Spacer(modifier = Modifier.height(14.dp))
    HorizontalDivider(color = Color(0xFFEFEFF2))
    Spacer(modifier = Modifier.height(14.dp))

    // 「价格走势」区块：日期范围和最高/最低都收进图表自身（底轴 + 右侧价签），
    // 标题行只留语义；设备筛选生效时右侧给出"可见/总数"反馈
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
        if (filterActive) {
            Text(
                text = "${visibleHistory.size}/${history.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 设备图例：多设备时出现，点一下开/关该设备的数据点（再点一次恢复）
    if (deviceIds.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            deviceIds.sorted().forEach { deviceId ->
                val isLocal = deviceId == localDeviceId || deviceId == "local"
                val name = if (isLocal) {
                    "本机"
                } else {
                    history.firstOrNull { it.deviceId == deviceId }?.deviceName?.ifBlank { "对方设备" } ?: "对方设备"
                }
                val isHidden = hiddenDevices.contains(deviceId)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val next = ArrayList(hiddenDevices)
                            if (isHidden) next.remove(deviceId) else next.add(deviceId)
                            hiddenDevices = next
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendDot(
                        color = if (isHidden) Color(0xFFB9BDC7) else deviceColorOf(deviceId, localDeviceId, deviceIds),
                        label = name,
                        dimmed = isHidden
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
    PriceLineChart(history = visibleHistory, fallbackPrice = currentPrice)

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = Color(0xFFEFEFF2))
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
    Spacer(modifier = Modifier.height(10.dp))

    val allReversed = history.asReversed()
    val displayed = if (showAll) allReversed else allReversed.take(8)
    var pendingDelete by remember { mutableStateOf<ProductPriceHistory?>(null) }
    var viewingShot by remember { mutableStateOf<ProductPriceHistory?>(null) }
    displayed.forEach { record ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(record.recordedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                // 自动保存的记录：未经过面板确认的弱标记，提示这条可重点核对
                if (record.autoSaved) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFF0F1F3))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "auto",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                // 远端设备记的记录：设备名小标签着设备色，一眼区分谁记的
                if (record.deviceId != localDeviceId && record.deviceId != "local") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(deviceColorOf(record.deviceId, localDeviceId, deviceIds).copy(alpha = 0.10f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = record.deviceName.ifBlank { "对方设备" },
                            style = MaterialTheme.typography.labelSmall,
                            color = deviceColorOf(record.deviceId, localDeviceId, deviceIds)
                        )
                    }
                }
            }
            Text(
                text = formatPrice(record.priceCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (record.priceCents == minPrice) FreshGreen else TextPrimary
            )
            // 识别截图存档：有图才出现的文字按钮，点开全屏查看
            if (record.id in shotIds) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "截图",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { viewingShot = record }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB9B9C0),
                modifier = Modifier.clickable { pendingDelete = record }
            )
        }
        if (record != displayed.last()) {
            HorizontalDivider(color = Color(0xFFF5F5F7))
        }
    }
    if (!showAll && history.size > 8) {
        TextButton(
            onClick = { showAll = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "展开全部（共 ${history.size} 条）",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
    }

    viewingShot?.let { record ->
        ScreenshotViewer(
            screenshotStore = screenshotStore,
            record = record,
            onDismiss = { viewingShot = null }
        )
    }

    pendingDelete?.let { record ->
        ConfirmDialog(
            onDismiss = { pendingDelete = null },
            title = "删除这条记录",
            description = "删除 ${formatTime(record.recordedAt)} 的 ${formatPrice(record.priceCents)}？最低价会自动重算。",
            confirmText = "删除",
            onConfirm = {
                onDeleteEntry(record.id)
                pendingDelete = null
            }
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String, dimmed: Boolean = false) {
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
            color = if (dimmed) Color(0xFFB9BDC7) else TextSecondary
        )
    }
}

@Composable
private fun PriceLineChart(
    history: List<ProductPriceHistory>,
    fallbackPrice: Long
) {
    // 本机设备 ID：数据点按记录来源设备着色（本机红点、远端蓝/橙点）
    val ctx = LocalContext.current
    val localDeviceId = remember { DeviceIdentity.deviceId(ctx) }
    val deviceIds = remember(history) { history.map { it.deviceId }.toSet() }
    // 右侧价签（最高/最低/scrub 浮标）画进 Canvas，需要文本测量
    val textMeasurer = rememberTextMeasurer()
    // 价签文本测量缓存：reveal 动画 900ms 内每帧重绘，draw 阶段反复 measure 开销不小；
    // 价签文本静态（最高/最低价），按 内容+样式 缓存 TextLayoutResult，只量一次
    val tagLayouts = remember { HashMap<String, TextLayoutResult>() }
    fun measureTag(text: String, color: Color, fontSize: TextUnit, bold: Boolean): TextLayoutResult =
        tagLayouts.getOrPut("$text|$color|$fontSize|$bold") {
            textMeasurer.measure(
                AnnotatedString(text),
                TextStyle(
                    fontSize = fontSize,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
                    color = color
                )
            )
        }
    val chartPrices = if (history.isEmpty()) listOf(fallbackPrice) else history.map { it.priceCents }
    val minPrice = chartPrices.minOrNull() ?: fallbackPrice
    val maxPrice = chartPrices.maxOrNull() ?: fallbackPrice
    val minIndex = chartPrices.indexOf(minPrice).coerceAtLeast(0)
    val lastIndex = chartPrices.lastIndex
    val flat = history.size > 1 && minPrice == maxPrice

    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
    )
    // 选中的数据点索引：点击/横滑后详情条与右侧浮标实时跟随
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    // 行情图骨架（学 TradingView/Keepa 的右轴样式）：走势区右侧留一条价签栏，
    // 最高/最低各挂一条虚线参考线；最低线染绿——"跌到这里值得买"是全图最重要的信号
    Column(modifier = Modifier.fillMaxWidth()) {
        // 选中详情条：多设备时带上记录者，"谁在什么时候记的"一目了然
        AnimatedVisibility(
            visible = selectedIndex != null && selectedIndex in chartPrices.indices && history.size > 1,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 }
        ) {
            val sel = selectedIndex
            if (sel != null && sel < history.size) {
                val rec = history[sel]
                val who = if (rec.deviceId != localDeviceId && rec.deviceId != "local") {
                    rec.deviceName.ifBlank { "对方设备" }
                } else {
                    null
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (who != null) {
                            "$who · ${chartDateTime(rec.recordedAt)}"
                        } else {
                            chartDateTime(rec.recordedAt)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = formatPrice(chartPrices[sel]),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = SerifItalic,
                        fontWeight = FontWeight.Bold,
                        color = if (sel == minIndex) FreshGreen else TextPrimary
                    )
                }
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .pointerInput(chartPrices.size) {
                    detectTapGestures { tap ->
                        if (chartPrices.size > 1) {
                            val lastIdx = chartPrices.lastIndex
                            val leftPx = 10.dp.toPx()
                            val rightPx = size.width - 48.dp.toPx()
                            val widthPx = (rightPx - leftPx).coerceAtLeast(1f)
                            val fraction = ((tap.x - leftPx) / widthPx).coerceIn(0f, 1f)
                            val nearest = (fraction * lastIdx).roundToInt().coerceIn(0, lastIdx)
                            selectedIndex = if (selectedIndex == nearest) null else nearest
                        }
                    }
                }
                // 横向滑动选点（scrub）：手指沿折线扫过，信息条与右侧浮标实时跟随——
                // 只认水平手势，垂直滑动仍交给外层列表滚动，互不抢事件
                .pointerInput(chartPrices.size) {
                    detectHorizontalDragGestures { change, _ ->
                        if (chartPrices.size > 1) {
                            val lastIdx = chartPrices.lastIndex
                            val leftPx = 10.dp.toPx()
                            val rightPx = size.width - 48.dp.toPx()
                            val widthPx = (rightPx - leftPx).coerceAtLeast(1f)
                            val fraction = ((change.position.x - leftPx) / widthPx).coerceIn(0f, 1f)
                            selectedIndex = (fraction * lastIdx).roundToInt().coerceIn(0, lastIdx)
                            change.consume()
                        }
                    }
                }
        ) {
            // 走势区右侧让出一条价签栏（学 TradingView 右轴）：最高/最低/scrub 浮标都挂在这里
            val gutter = 48.dp.toPx()
            val left = 10.dp.toPx()
            val right = (size.width - gutter).coerceAtLeast(left + 1f)
            val top = 14.dp.toPx()
            val bottom = size.height - 16.dp.toPx()
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            val range = (maxPrice - minPrice).takeIf { it > 0 } ?: 1L
            val midY = (top + bottom) / 2f

            val points = chartPrices.mapIndexed { index, price ->
                val x = if (chartPrices.size == 1) {
                    left
                } else {
                    left + width * index / (chartPrices.lastIndex).coerceAtLeast(1)
                }
                // 价格全平时落在中线（最低参考线上），单点也居中——避免点贴底显得坠落
                val y = if (flat || chartPrices.size == 1) {
                    midY
                } else {
                    bottom - height * ((price - minPrice).toFloat() / range.toFloat())
                }
                Offset(x, y)
            }

            // ---- 骨架：最高/最低虚线参考线 + 右侧价签。参考线是裸 sparkline 缺的"骨"，
            // 让眼睛有锚点；最低线染绿，与价签同色呼应"值得买"信号。
            // 选中价恰为最高/最低（或全平）时不另弹浮动价签，直接把右轴已有价签强化成实色胶囊 ----
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))
            fun drawRefLine(y: Float, color: Color) {
                drawLine(
                    color = color,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )
            }
            fun drawPriceTag(text: String, color: Color, y: Float, highlight: Color? = null) {
                if (highlight == null) {
                    val layout = measureTag(text, color, 9.sp, false)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(right + 6.dp.toPx(), y - layout.size.height / 2f)
                    )
                } else {
                    // 强化态：实色胶囊 + 白字加粗，参考线同步加深，注意力集中在锚点价上
                    val layout = measureTag(text, Color.White, 10.sp, true)
                    val pillW = layout.size.width + 10.dp.toPx()
                    val pillH = layout.size.height + 6.dp.toPx()
                    drawRoundRect(
                        color = highlight,
                        topLeft = Offset(right + 4.dp.toPx(), y - pillH / 2f),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(pillH / 2f)
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(right + 9.dp.toPx(), y - layout.size.height / 2f)
                    )
                }
            }
            val selIdx = selectedIndex
            val selPrice = if (selIdx != null && selIdx in chartPrices.indices) chartPrices[selIdx] else null
            val maxHit = selPrice != null && !flat && selPrice == maxPrice
            val minHit = selPrice != null && !flat && selPrice == minPrice
            val anchorHit = selPrice != null && history.size > 1 && (flat || maxHit || minHit)
            if (history.size > 1) {
                if (flat) {
                    drawRefLine(midY, if (anchorHit) ChartInk.copy(alpha = 0.45f) else RefLine)
                    drawPriceTag(formatPrice(minPrice), TextSecondary, midY, if (anchorHit) ChartInk else null)
                } else {
                    drawRefLine(top, if (maxHit) ChartInk.copy(alpha = 0.45f) else RefLine)
                    drawRefLine(bottom, if (minHit) FreshGreen else FreshGreen.copy(alpha = 0.40f))
                    drawPriceTag(formatPrice(maxPrice), TextSecondary, top, if (maxHit) ChartInk else null)
                    drawPriceTag(formatPrice(minPrice), FreshGreen, bottom, if (minHit) FreshGreen else null)
                }
            } else {
                // 单点构图：红点锚在左端起点，价格水平虚线向右渐隐——
                // "走势还没发生，但方向已就绪"；右轴挂墨色价签收住构图
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to ChartInk.copy(alpha = 0.42f),
                        1f to ChartInk.copy(alpha = 0.08f),
                        startX = left,
                        endX = right
                    ),
                    start = Offset(left, midY),
                    end = Offset(right, midY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )
                drawPriceTag(formatPrice(minPrice), ChartInk, midY)
            }

            // ---- 数据：直角折线 + 极淡面积，随动画从左向右揭开。
            // 线用「旧淡新浓」的墨色渐变，视线被自然引向"现在的价格" ----
            val linePath = buildLinePath(points)
            clipRect(right = left + width * progress + 12.dp.toPx()) {
                if (points.size > 1) {
                    val areaPath = Path().apply {
                        addPath(linePath)
                        lineTo(points.last().x, bottom)
                        lineTo(points.first().x, bottom)
                        close()
                    }
                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            0.0f to Color(0x1233333D),
                            1f to Color(0x0033333D)
                        )
                    )
                    drawPath(
                        path = linePath,
                        brush = Brush.horizontalGradient(
                            0f to Color(0xFFB9BBC4),
                            1f to ChartInk,
                            startX = left,
                            endX = right
                        ),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                // 中间数据点：纯色小圆点，颜色 = 记录它的设备
                points.forEachIndexed { index, point ->
                    if (points.size > 1 && index != minIndex && index != lastIndex) {
                        val deviceColor = deviceColorOf(history[index].deviceId, localDeviceId, deviceIds)
                        drawCircle(deviceColor, radius = 3.dp.toPx(), center = point)
                    }
                }
                // 历史最低点：绿点白芯（精确价看右下角绿色价签）
                if (points.size > 1 && minIndex != lastIndex) {
                    drawCircle(FreshGreen, radius = 3.5.dp.toPx(), center = points[minIndex])
                    drawCircle(Color.White, radius = 1.4.dp.toPx(), center = points[minIndex])
                }
                // 当前端点：品牌红点白芯（价格数字在卡片头部，不重复标注）
                if (points.isNotEmpty()) {
                    drawCircle(BrandRed, radius = 4.dp.toPx(), center = points[lastIndex])
                    drawCircle(Color.White, radius = 1.6.dp.toPx(), center = points[lastIndex])
                }
            }

            // ---- 选中：全高十字线 + 放大点 + 右侧浮动价签（像行情软件的十字光标）。
            // 选中价恰为最高/最低（或全平）时右轴价签已强化，跳过浮动价签避免与锚点重复 ----
            val sel = selectedIndex
            if (sel != null && sel in points.indices && history.size > 1) {
                val selPoint = points[sel]
                val selColor = if (sel == minIndex) FreshGreen else ChartInk
                drawLine(
                    color = selColor.copy(alpha = 0.25f),
                    start = Offset(selPoint.x, top),
                    end = Offset(selPoint.x, bottom),
                    strokeWidth = 1.dp.toPx()
                )
                drawCircle(selColor.copy(alpha = 0.14f), radius = 9.dp.toPx(), center = selPoint)
                drawCircle(selColor, radius = 4.5.dp.toPx(), center = selPoint)
                drawCircle(Color.White, radius = 1.8.dp.toPx(), center = selPoint)
                if (!anchorHit) {
                    val selTag = measureTag(formatPrice(chartPrices[sel]), ChartInk, 9.sp, false)
                    val pillW = selTag.size.width + 8.dp.toPx()
                    val pillH = selTag.size.height + 5.dp.toPx()
                    val pillY = (selPoint.y - pillH / 2f).coerceIn(top, bottom - pillH)
                    drawRoundRect(
                        color = Color(0xFFEDEDF1),
                        topLeft = Offset(right + 4.dp.toPx(), pillY),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(pillH / 2f)
                    )
                    drawText(
                        textLayoutResult = selTag,
                        topLeft = Offset(right + 8.dp.toPx(), pillY + 2.5.dp.toPx())
                    )
                }
            }
        }
        // 底部时间轴：首尾日期与走势线两端对齐（首端缩进与线起点同宽，右端让出价签栏）
        Spacer(modifier = Modifier.height(8.dp))
        if (history.size > 1) {
            Row(modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 48.dp)
            ) {
                Text(
                    text = chartDate(history.first().recordedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = chartDate(history.last().recordedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.7f)
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "仅 1 条记录 · 继续记价，走势将从这里向右生长",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

// 直角折线：相邻数据点用直线连接，转折处加圆角，直观不花哨
private fun buildLinePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        path.lineTo(points[i].x, points[i].y)
    }
    return path
}

private fun formatPrice(cents: Long): String =
    "¥${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

// SimpleDateFormat 构造开销不小，这里全部在主线程调用，缓存复用避免每次重组重复创建
private val fullTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val clockFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
private val dayFormat = SimpleDateFormat("M/d", Locale.CHINA)
private val dayTimeFormat = SimpleDateFormat("M/d HH:mm", Locale.CHINA)
private val todayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.CHINA)

private fun formatTime(timeMillis: Long): String =
    fullTimeFormat.format(Date(timeMillis))

private fun shortTime(timeMillis: Long): String =
    clockFormat.format(Date(timeMillis))

private fun chartDate(timeMillis: Long): String =
    dayFormat.format(Date(timeMillis))

private fun chartDateTime(timeMillis: Long): String =
    dayTimeFormat.format(Date(timeMillis))

private fun isToday(timeMillis: Long): Boolean =
    todayKeyFormat.format(Date(timeMillis)) == todayKeyFormat.format(Date())

private fun normalizeSearchText(text: String): String =
    text.lowercase()
        .replace(Regex("[^\\p{IsHan}a-z0-9]+"), "")
        .trim()
