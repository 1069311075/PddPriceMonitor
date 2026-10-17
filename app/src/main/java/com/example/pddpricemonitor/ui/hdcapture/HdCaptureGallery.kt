package com.example.pddpricemonitor.ui.hdcapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.pddpricemonitor.data.HdCaptureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

// 本界面自带的「清爽红」设计 token（与主界面同一套语言；悬浮球下段产出的截图专用）
private val SheetBg = Color(0xFFF1F1F4)
private val CardWhite = Color(0xFFFFFFFF)
private val HairlineBorder = Color(0x1A1A1A1A)
private val BrandRed = Color(0xFFE02E24)
private val TextPrimary = Color(0xFF1A1A1A)
private val TextSecondary = Color(0xFF8A8F99)
private val ChipBg = Color(0xFFEDEDF0)
private val ViewerBg = Color(0xF20B0B0F)
private val ViewerInk = Color(0xFFB9BDC7)

private val hdTimeFormat = SimpleDateFormat("yyyy-M-d HH:mm:ss", Locale.CHINA)
private val hdStampFormat = SimpleDateFormat("yyyy-M-d HH:mm", Locale.CHINA)

private const val GRID_COLUMNS = 3
private const val DOUBLE_TAP_WINDOW_MS = 320L
private const val STATUS_MS = 2_400L

/**
 * 相册选择态：提升到宿主持有，横竖屏 / 进程重建后仍可恢复勾选。
 * [selecting] 是独立的选择模式开关，与「选中集合是否为空」解耦——取消最后一张仍停在选择态。
 */
data class HdSelection(
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet()
) {
    val count: Int get() = selected.size
}

/** rememberSaveable 用：Bundle 只认基本类型，落成 [Int, ArrayList<String>] */
val HdSelectionSaver = listSaver<HdSelection, Any>(
    save = { listOf(if (it.selecting) 1 else 0, ArrayList(it.selected)) },
    restore = {
        HdSelection(
            selecting = (it.getOrNull(0) as? Int) == 1,
            selected = (it.getOrNull(1) as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        )
    }
)

/** 缩略图加载态：区分「还在读」和「读不出来」，单条损坏要给可见反馈而不是一直空着 */
private sealed interface ThumbState {
    object Loading : ThumbState
    data class Ready(val bitmap: Bitmap) : ThumbState
    object Failed : ThumbState
}

/** 相册网格里的一格：日期分隔标题（带本组 id 便于按日选择） 或 一张截图 */
private sealed interface AlbumCell {
    data class Day(
        val key: String,
        val label: String,
        val dateUnknown: Boolean,
        val ids: List<String>
    ) : AlbumCell

    data class Photo(val entry: HdCaptureStore.Entry) : AlbumCell
}

/** 一次清理的预览结果（截止时间与候选 id 在此固定，确认时不再重新筛选） */
private data class PendingCleanup(
    val daysBefore: Int?,
    val cutoffMillis: Long?,
    val preview: HdCaptureStore.CleanupPreview
)

private data class GalleryStatus(val text: String, val ok: Boolean)

/**
 * 截图相册：按拍摄时间的日期分组三列网格（图片即内容），
 * 点开看大图可左右翻看、双指缩放 / 双击放大，顶部「清理」按 30 天 / 90 天 / 全部删除。
 * 与旧 OCR 记价记录、OCR 附图、调试转储完全隔离：这里只动 filesDir/hd_captures。
 */
@Composable
fun HdCaptureGalleryDialog(
    store: HdCaptureStore,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    /** 选择态提升到宿主持有：横竖屏/进程重建后仍能恢复勾选 */
    selection: HdSelection,
    onSelectionChange: (HdSelection) -> Unit,
    /** 宿主每次回到前台自增：相册开着时去拼多多截图再回来，列表也要刷新 */
    refreshSignal: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var viewIndex by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<HdCaptureStore.Entry?>(null) }
    var exporting by remember { mutableStateOf<HdCaptureStore.Entry?>(null) }
    var cleanupSheet by remember { mutableStateOf(false) }
    var pendingCleanup by remember { mutableStateOf<PendingCleanup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<GalleryStatus?>(null) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    // 网格滚动位置由本层持有：打开大图再返回时不跳回顶部
    val gridState = rememberLazyGridState()

    // 选择模式是独立开关（不把「选中集合非空」当开关）：取消最后一张仍停在选择态
    val selecting = selection.selecting
    val selected = selection.selected

    fun flash(text: String, ok: Boolean = true) {
        status = GalleryStatus(text, ok)
    }

    fun updateSelection(newSelecting: Boolean = selecting, newSelected: Set<String> = selected) {
        if (newSelecting != selecting || newSelected != selected) {
            onSelectionChange(HdSelection(newSelecting, newSelected))
        }
    }

    fun enterSelection() = updateSelection(newSelecting = true, newSelected = emptySet())

    fun exitSelection() = updateSelection(newSelecting = false, newSelected = emptySet())

    fun toggleSelection(id: String) {
        val next = if (id in selected) selected - id else selected + id
        updateSelection(newSelecting = true, newSelected = next)
    }

    /** 批量删除：把「点删除这一刻」的选中集合冻结成快照，只删快照里的 id，不重新计算全选 */
    fun deleteSelected(ids: List<String>) {
        if (busy || ids.isEmpty()) return
        confirmBulkDelete = false
        busy = true
        flash("正在删除…")
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { store.deleteIds(ids) }
            } catch (error: Throwable) {
                // 异常也要把忙碌态放回去，不能永久锁在「正在删除」
                busy = false
                flash("删除失败：${error.javaClass.simpleName}", ok = false)
                return@launch
            }
            busy = false
            // 成功项与已不存在的项从勾选里移除（不留虚假计数），失败项保留勾选供直接重试
            val keep = selected - result.deletedIds.toSet() - result.missingIds.toSet()
            updateSelection(newSelecting = keep.isNotEmpty(), newSelected = keep)
            onChanged()
            refreshKey++
            flash(
                text = when {
                    result.deleted == 0 && result.failed == 0 -> "没有可删除的截图"
                    result.failed == 0 -> "已删除 ${result.deleted} 张截图"
                    else -> "已删除 ${result.deleted} 张，失败 ${result.failed} 张"
                },
                ok = result.failed == 0
            )
        }
    }

    LaunchedEffect(status) {
        if (status != null) {
            delay(STATUS_MS)
            status = null
        }
    }

    val entries by produceState<List<HdCaptureStore.Entry>?>(
        initialValue = null,
        refreshKey,
        refreshSignal
    ) {
        value = withContext(Dispatchers.IO) { store.listEntries() }
    }

    // 列表变化后的对账（清理后、回到前台重新列举后）：
    // 空列表退出大图与选择态；否则把勾选与现存 id 取交集，已不存在的条目不留虚假计数
    val currentList = entries
    LaunchedEffect(currentList) {
        val list = currentList ?: return@LaunchedEffect
        if (list.isEmpty()) {
            viewIndex = null
            if (selecting) exitSelection()
            return@LaunchedEffect
        }
        val alive = list.mapTo(HashSet()) { it.id }
        val kept = selected.filter { it in alive }.toSet()
        if (kept != selected) updateSelection(newSelected = kept)
    }

    // 导出：按记录真实格式建文档（旧 PNG → image/png，新 JPEG → image/jpeg），
    // 字节原样拷贝，不做二次有损压缩
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val entry = exporting
        exporting = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && entry != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { exportFile(context, store, entry, uri) }
                flash(if (ok) "已导出 ${entry.exportFileName()}" else "导出失败，请重试", ok)
            }
        }
    }

    fun startExport(entry: HdCaptureStore.Entry) {
        if (entry.broken) {
            flash("这张截图已损坏，无法导出", ok = false)
            return
        }
        exporting = entry
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = entry.mime
            putExtra(Intent.EXTRA_TITLE, entry.exportFileName())
        }
        runCatching { exportLauncher.launch(intent) }.onFailure {
            exporting = null
            flash("无法打开文件选择器", ok = false)
        }
    }

    fun deleteOne(entry: HdCaptureStore.Entry) {
        scope.launch {
            val removed = try {
                withContext(Dispatchers.IO) { store.delete(entry.id) }
            } catch (error: Throwable) {
                flash("删除失败：${error.javaClass.simpleName}", ok = false)
                return@launch
            }
            flash(if (removed) "已删除 1 张截图" else "删除失败，请重试", removed)
            onChanged()
            refreshKey++
        }
    }

    // 选一个清理范围：先算候选张数与预计释放空间，候选为空则直接给提示，不弹空确认
    fun previewCleanup(daysBefore: Int?) {
        if (busy) return
        cleanupSheet = false
        busy = true
        scope.launch {
            val cutoff = daysBefore?.let { store.cutoffMillis(it) }
            val preview = try {
                withContext(Dispatchers.IO) { store.previewCleanup(cutoff) }
            } catch (error: Throwable) {
                busy = false
                flash("清理预览失败：${error.javaClass.simpleName}", ok = false)
                return@launch
            }
            busy = false
            if (preview.isEmpty) {
                flash(if (daysBefore == null) "还没有截图" else "没有符合条件的截图")
            } else {
                pendingCleanup = PendingCleanup(daysBefore, cutoff, preview)
            }
        }
    }

    // 确认后立即收起确认框（杜绝重复确认），只按预览时固定的 id 集合删除
    fun runCleanup(pending: PendingCleanup) {
        if (busy) return
        pendingCleanup = null
        busy = true
        flash("正在清理…")
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { store.deleteIds(pending.preview.ids) }
            } catch (error: Throwable) {
                busy = false
                flash("清理失败：${error.javaClass.simpleName}", ok = false)
                return@launch
            }
            busy = false
            onChanged()
            refreshKey++
            flash(
                text = when {
                    result.deleted == 0 && result.failed == 0 -> "没有符合条件的截图"
                    result.failed == 0 -> "已删除 ${result.deleted} 张截图"
                    else -> "已删除 ${result.deleted} 张，失败 ${result.failed} 张"
                },
                ok = result.failed == 0
            )
        }
    }

    Dialog(
        // 返回键 / 点击外部：选择态先退出选择，再按才离开相册
        onDismissRequest = { if (selecting) exitSelection() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(SheetBg)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题条：标题 + 清理 + 完成；选择态换成 取消 / 已选 N 张 / 删除
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!selecting) {
                        Text(
                            text = "截图",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        if (!currentList.isNullOrEmpty()) {
                            // 显式入口：不把选择功能只藏在长按里
                            TextButton(onClick = { enterSelection() }, enabled = !busy) {
                                Text("选择", color = TextSecondary)
                            }
                            TextButton(onClick = { cleanupSheet = true }, enabled = !busy) {
                                Text("清理", color = TextSecondary)
                            }
                        }
                        TextButton(onClick = onDismiss) {
                            Text("完成", color = BrandRed, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        val allIds = currentList.orEmpty().map { it.id }
                        val allSelected = allIds.isNotEmpty() && allIds.all { it in selected }
                        TextButton(onClick = { exitSelection() }, enabled = !busy) {
                            Text("取消", color = TextSecondary)
                        }
                        Text(
                            text = "已选 ${selected.size} 张",
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        TextButton(
                            onClick = {
                                // 全选是对当前全部记录的一次快照；之后新加入的截图不会被自动勾上
                                updateSelection(newSelected = if (allSelected) emptySet() else allIds.toSet())
                            },
                            enabled = !busy && allIds.isNotEmpty()
                        ) {
                            Text(if (allSelected) "取消全选" else "全选", color = TextSecondary)
                        }
                        TextButton(
                            onClick = { confirmBulkDelete = true },
                            enabled = !busy && selected.isNotEmpty()
                        ) {
                            Text(
                                text = "删除",
                                color = if (selected.isEmpty()) TextSecondary else BrandRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                when (val list = currentList) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在读取…", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }

                    else -> if (list.isEmpty()) {
                        EmptyHdState(modifier = Modifier.fillMaxSize())
                    } else {
                        val cells = remember(list) { albumCells(list) }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(GRID_COLUMNS),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            cells.forEach { cell ->
                                when (cell) {
                                    is AlbumCell.Day -> item(
                                        key = "day-${cell.key}",
                                        span = { GridItemSpan(maxLineSpan) }
                                    ) {
                                        val dayAllSelected =
                                            cell.ids.isNotEmpty() && cell.ids.all { it in selected }
                                        DayHeader(
                                            label = cell.label,
                                            selecting = selecting,
                                            dayIds = cell.ids,
                                            allDaySelected = dayAllSelected,
                                            enabled = !busy,
                                            onToggleDay = {
                                                // 只增删本日期组的勾选，不覆盖其他日期已有选择
                                                updateSelection(
                                                    newSelected = if (dayAllSelected) {
                                                        selected - cell.ids.toSet()
                                                    } else {
                                                        selected + cell.ids
                                                    }
                                                )
                                            }
                                        )
                                    }

                                    is AlbumCell.Photo -> item(key = cell.entry.id) {
                                        PhotoCell(
                                            store = store,
                                            entry = cell.entry,
                                            selecting = selecting,
                                            selected = cell.entry.id in selected,
                                            enabled = !busy,
                                            onClick = {
                                                if (selecting) {
                                                    // 选择态点图只切换勾选，不打开大图
                                                    toggleSelection(cell.entry.id)
                                                } else {
                                                    viewIndex = list.indexOfFirst { it.id == cell.entry.id }
                                                        .takeIf { it >= 0 }
                                                }
                                            },
                                            // 普通浏览时长按＝进入选择并勾上这张；选择态长按＝切换勾选
                                            onLongClick = { toggleSelection(cell.entry.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 短暂反馈：不长期挤占图片区域，也不推动网格布局
            status?.let { tip ->
                StatusPill(
                    text = tip.text,
                    ok = tip.ok,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                )
            }
        }
    }

    val list = currentList
    if (viewIndex != null && !list.isNullOrEmpty()) {
        HdViewerDialog(
            store = store,
            entries = list,
            initialIndex = viewIndex!!.coerceIn(0, list.lastIndex),
            status = status,
            onClose = { viewIndex = null },
            onExport = { startExport(it) },
            onDelete = { pendingDelete = it }
        )
    }

    pendingDelete?.let { entry ->
        HdConfirmDialog(
            title = "删除这张截图？",
            description = "只删除这张截图的主体文件、缩略图和记录，不影响记价记录与 OCR 附图。",
            confirmText = "删除",
            onConfirm = {
                deleteOne(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    if (confirmBulkDelete) {
        val ids = selected.toList()
        HdConfirmDialog(
            title = "删除选中的 ${ids.size} 张截图？",
            description = "只删除这些截图的主体文件、缩略图和记录，不影响记价记录与 OCR 附图。",
            confirmText = "删除",
            onConfirm = { deleteSelected(ids) },
            onDismiss = { confirmBulkDelete = false }
        )
    }

    if (cleanupSheet) {
        HdCleanupSheet(
            onPick = { previewCleanup(it) },
            onDismiss = { cleanupSheet = false }
        )
    }

    pendingCleanup?.let { pending ->
        HdConfirmDialog(
            title = pending.cleanupTitle(),
            description = pending.cleanupDescription(),
            confirmText = "删除",
            onConfirm = { runCleanup(pending) },
            onDismiss = { pendingCleanup = null }
        )
    }
}

// ---- 相册网格 ----

/**
 * 按本机时区把倒序记录切成「日期标题 + 若干张图」，标题占整行。
 * 日期未知的记录单独一组放在最后：不参与按时间清理，但可见、可按日选择、可手删。
 */
private fun albumCells(list: List<HdCaptureStore.Entry>): List<AlbumCell> {
    val today = LocalDate.now()
    val out = ArrayList<AlbumCell>(list.size + 8)
    // 列表本身按 capturedAt 倒序，groupBy 保留首次出现顺序＝日期倒序
    list.filterNot { it.dateUnknown }
        .groupBy { Instant.ofEpochMilli(it.capturedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
        .forEach { (day, items) ->
            out += AlbumCell.Day(
                key = day.toString(),
                label = dayLabel(day, today),
                dateUnknown = false,
                ids = items.map { it.id }
            )
            items.forEach { out += AlbumCell.Photo(it) }
        }
    val unknown = list.filter { it.dateUnknown }
    if (unknown.isNotEmpty()) {
        out += AlbumCell.Day(
            key = UNKNOWN_DAY_KEY,
            label = "日期未知",
            dateUnknown = true,
            ids = unknown.map { it.id }
        )
        unknown.forEach { out += AlbumCell.Photo(it) }
    }
    return out
}

private const val UNKNOWN_DAY_KEY = "unknown-date"

private fun dayLabel(day: LocalDate, today: LocalDate): String = when {
    day == today -> "今天"
    day == today.minusDays(1) -> "昨天"
    day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
    else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
}

@Composable
private fun DayHeader(
    label: String,
    selecting: Boolean = false,
    dayIds: List<String> = emptyList(),
    allDaySelected: Boolean = false,
    enabled: Boolean = true,
    onToggleDay: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        // 选择态可按组快选，不影响其他日期已有的勾选
        if (selecting && dayIds.isNotEmpty()) {
            TextButton(onClick = onToggleDay, enabled = enabled) {
                Text(
                    text = if (allDaySelected) "取消当日" else "选择当日",
                    color = BrandRed,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/** 网格单元：只放图片本身，保留完整截图比例（不裁切、不拉伸），不带尺寸/时间/按钮 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    store: HdCaptureStore,
    entry: HdCaptureStore.Entry,
    selecting: Boolean,
    selected: Boolean,
    /** 删除进行中锁住交互：防止在处理本次集合期间改勾选 */
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val thumbState by produceState<ThumbState>(ThumbState.Loading, entry.id) {
        val loaded = withContext(Dispatchers.IO) { store.loadThumb(entry) }
        value = if (loaded != null) ThumbState.Ready(loaded) else ThumbState.Failed
    }
    val bmp = (thumbState as? ThumbState.Ready)?.bitmap
    val aspect = when {
        entry.width > 0 && entry.height > 0 -> entry.width.toFloat() / entry.height
        bmp != null && bmp.height > 0 -> bmp.width.toFloat() / bmp.height
        else -> 3f / 4f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(ChipBg)
            .combinedClickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = when {
                    selecting && selected -> "取消选择"
                    selecting -> "选择"
                    else -> "打开截图"
                },
                onLongClickLabel = "选择",
                onLongClick = onLongClick,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "截图 ${hdStampFormat.format(Date(entry.capturedAt))}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else if (thumbState is ThumbState.Failed) {
            Text("无法读取", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        // 选择态画在图片之上：已选＝淡红描边；勾选标记只在选择态出现，不常驻堆在每张图上
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x1F101014))
                    .border(2.5.dp, BrandRed, RoundedCornerShape(8.dp))
            )
        }
        if (selecting) {
            SelectionMark(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
            )
        }
    }
}

/** 勾选标记：已选＝白底红勾；未选＝空心勾选位 */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val r = size.minDimension / 2f
        if (selected) {
            drawCircle(Color.White, radius = r)
            drawCircle(
                BrandRed,
                radius = r - 1.dp.toPx(),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx())
            )
            val w = size.width
            val h = size.height
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.30f, h * 0.52f)
                    lineTo(w * 0.45f, h * 0.67f)
                    lineTo(w * 0.72f, h * 0.36f)
                },
                color = BrandRed,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
        } else {
            // 半透明底：浅色截图上也能看清这是个勾选位，而不是图片本身的一部分
            drawCircle(Color(0x59000000), radius = r)
            drawCircle(
                Color.White,
                radius = r - 1.dp.toPx(),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx())
            )
        }
    }
}

@Composable
private fun EmptyHdState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp)
        ) {
            Canvas(modifier = Modifier.size(40.dp)) {
                val sw = 1.6.dp.toPx()
                val c = TextSecondary
                val bodyW = size.width * 0.94f
                val bodyH = size.height * 0.66f
                val left = (size.width - bodyW) / 2f
                val top = (size.height - bodyH) / 2f + size.height * 0.08f
                drawRoundRect(
                    color = c,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(bodyW, bodyH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyH * 0.2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw)
                )
                drawRoundRect(
                    color = c,
                    topLeft = Offset(left + bodyW * 0.32f, top - bodyH * 0.26f),
                    size = androidx.compose.ui.geometry.Size(bodyW * 0.32f, bodyH * 0.34f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyH * 0.1f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw)
                )
                drawCircle(
                    color = c,
                    radius = bodyH * 0.16f,
                    center = Offset(size.width / 2f, top + bodyH * 0.5f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "还没有截图",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "在拼多多选好配置后，点悬浮球下段（相机图标）拍下当前画面，截图会存到这里。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .border(0.6.dp, HairlineBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) TextPrimary else BrandRed,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---- 大图查看：左右翻看当前相册的前后截图 + 捏合/双击放大 ----

@Composable
private fun HdViewerDialog(
    store: HdCaptureStore,
    entries: List<HdCaptureStore.Entry>,
    initialIndex: Int,
    /** 反馈也显示在大图这一层：否则导出/删除的提示会被大图盖住 */
    status: GalleryStatus? = null,
    onClose: () -> Unit,
    onExport: (HdCaptureStore.Entry) -> Unit,
    onDelete: (HdCaptureStore.Entry) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { entries.size }
    val currentId = entries.getOrNull(pagerState.currentPage)?.id
    // 缩放状态按当前图持有：换图即重置上一张的缩放和偏移
    val scaleState = remember(currentId) { mutableFloatStateOf(1f) }
    val offsetState = remember(currentId) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val pagerEnabled by remember(currentId) { derivedStateOf { scaleState.floatValue <= 1.02f } }

    // 删除后列表变短：页码收进范围；列表空了直接退回网格空态
    LaunchedEffect(entries) {
        if (entries.isEmpty()) {
            onClose()
        } else if (pagerState.currentPage > entries.lastIndex) {
            pagerState.scrollToPage(entries.lastIndex)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerBg)
                .onSizeChanged { viewport = it }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 页面 key 用稳定 id：前后插入/删除其他截图时，正在看的那张不会因下标变化被换成别的
                key = { page -> entries.getOrNull(page)?.id ?: page },
                // 只加载当前图和一张邻图，不预载整个相册
                beyondViewportPageCount = 1,
                // 放大后禁掉翻页：平移不与翻页抢手势
                userScrollEnabled = pagerEnabled
            ) { page ->
                val entry = entries.getOrNull(page) ?: return@HorizontalPager
                val isCurrent = entry.id == currentId
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isCurrent) {
                                Modifier.albumZoomGestures(
                                    key = entry.id,
                                    currentScale = { scaleState.floatValue },
                                    onTransform = { zoom, pan ->
                                        val next = (scaleState.floatValue * zoom).coerceIn(1f, 6f)
                                        scaleState.floatValue = next
                                        offsetState.value =
                                            if (next <= 1f) Offset.Zero
                                            else clampPan(offsetState.value + pan, next, viewport)
                                    },
                                    onDoubleTap = {
                                        if (scaleState.floatValue > 1.05f) {
                                            scaleState.floatValue = 1f
                                            offsetState.value = Offset.Zero
                                        } else {
                                            scaleState.floatValue = 2.5f
                                        }
                                    }
                                )
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val imageState by produceState<ThumbState>(ThumbState.Loading, entry.id) {
                        val loaded = withContext(Dispatchers.IO) {
                            store.decodeMain(entry, HdCaptureStore.VIEW_LONG_SIDE)
                        }
                        value = if (loaded != null) ThumbState.Ready(loaded) else ThumbState.Failed
                    }
                    val bmp = (imageState as? ThumbState.Ready)?.bitmap
                    if (bmp != null) {
                        val shownScale = if (isCurrent) scaleState.floatValue else 1f
                        val shownOffset = if (isCurrent) offsetState.value else Offset.Zero
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "截图 ${hdStampFormat.format(Date(entry.capturedAt))}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = shownScale,
                                    scaleY = shownScale,
                                    translationX = shownOffset.x,
                                    translationY = shownOffset.y
                                )
                        )
                    } else {
                        Text(
                            text = if (imageState is ThumbState.Failed) "这张截图无法读取" else "正在载入…",
                            color = ViewerInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // 顶：拍摄时间 + 关闭；底：导出 / 删除（不常驻宽高、大小与手势说明）
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                val top = entries.getOrNull(pagerState.currentPage)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x66000000))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = top?.let { hdStampFormat.format(Date(it.capturedAt)) } ?: "",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(onClick = onClose) {
                        Text("关闭", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x66000000))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val bottom = entries.getOrNull(pagerState.currentPage)
                    if (bottom != null && !bottom.broken) {
                        TextButton(onClick = { onExport(bottom) }) {
                            Text("导出", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (bottom != null) {
                        TextButton(onClick = { onDelete(bottom) }) {
                            Text("删除", color = Color(0xFFF08A84), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // 反馈显示在用户当前能看见的这一层（大图之上），不只在被盖住的网格层
            status?.let { tip ->
                StatusPill(
                    text = tip.text,
                    ok = tip.ok,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 84.dp)
                )
            }
        }
    }
}

/**
 * 大图手势：1x 单指拖动原样留给 HorizontalPager 翻页；双指捏合、以及放大后的单指平移
 * 才由本图层接管（接管即消费事件）。双击在 1x / 2.5x 之间切换。
 * 按下事件不消费，避免打断 pager 的拖动识别。
 */
private fun Modifier.albumZoomGestures(
    key: Any?,
    currentScale: () -> Float,
    onTransform: (zoom: Float, pan: Offset) -> Unit,
    onDoubleTap: () -> Unit
): Modifier = this.pointerInput(key) {
    var lastDownAt = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downAt = down.uptimeMillis
        val slop = viewConfiguration.touchSlop
        var moved = Offset.Zero
        var owns = false
        var released = false
        var multiTouch = false
        while (!released) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) {
                released = true
                break
            }
            if (event.changes.count { it.pressed } >= 2) multiTouch = true
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            moved += pan
            if (!owns) {
                if (multiTouch || zoom != 1f) {
                    owns = true
                } else if (moved.getDistance() > slop) {
                    if (currentScale() > 1.02f) owns = true else released = true
                    if (released) break
                }
            }
            if (owns) {
                onTransform(zoom, pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (event.changes.all { !it.pressed }) break
        }
        val tapped = !owns && !released && !multiTouch
        if (tapped && downAt - lastDownAt in 1..DOUBLE_TAP_WINDOW_MS) {
            onDoubleTap()
            lastDownAt = 0L
        } else {
            lastDownAt = if (tapped) downAt else 0L
        }
    }
}

private fun clampPan(offset: Offset, scale: Float, viewport: IntSize): Offset {
    if (scale <= 1f || viewport.width == 0 || viewport.height == 0) return Offset.Zero
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

// ---- 清理选项面板 ----

@Composable
private fun HdCleanupSheet(onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
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
                .padding(top = 22.dp, start = 18.dp, end = 18.dp, bottom = 18.dp)
        ) {
            Text(
                text = "清理截图",
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "只清理本机截图归档，不影响商品记价记录与 OCR 附图。",
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            CleanupOption("删除 30 天以前", "按本机时间零点计算，截止日当天的截图保留") { onPick(30) }
            CleanupOption("删除 90 天以前", "按本机时间零点计算，截止日当天的截图保留") { onPick(90) }
            CleanupOption("删除全部截图", "清空本机全部截图", danger = true) { onPick(null) }
            Spacer(modifier = Modifier.height(12.dp))
            HdActionButton(
                text = "取消",
                onClick = onDismiss,
                background = ChipBg,
                foreground = TextPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CleanupOption(
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = title,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) BrandRed else TextPrimary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

// ---- 自绘确认弹窗（本界面自带，与主界面 ConfirmDialog 同形） ----

@Composable
private fun HdConfirmDialog(
    title: String,
    description: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
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
                .padding(top = 24.dp, start = 22.dp, end = 22.dp, bottom = 20.dp)
        ) {
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
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HdActionButton(
                    text = "取消",
                    onClick = onDismiss,
                    background = ChipBg,
                    foreground = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                HdActionButton(
                    text = confirmText,
                    onClick = onConfirm,
                    background = BrandRed,
                    foreground = Color.White,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HdActionButton(
    text: String,
    onClick: () -> Unit,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- 工具 ----

private fun PendingCleanup.cleanupTitle(): String = when (daysBefore) {
    null -> "清理全部截图？"
    else -> "清理 $daysBefore 天以前的截图？"
}

private fun PendingCleanup.cleanupDescription(): String {
    val head = if (cutoffMillis == null) {
        "将删除全部 ${preview.count} 张截图"
    } else {
        "将删除 ${formatCutoff(cutoffMillis)} 以前的 ${preview.count} 张截图"
    }
    // 日期未知的记录不按推测日期删，要如实说明跳过了多少
    val skipped = if (preview.skippedUnknown > 0) {
        "，另有 ${preview.skippedUnknown} 张日期未知的截图不参与按时间清理"
    } else {
        ""
    }
    return "$head，预计释放约 ${formatSize(preview.bytes)}$skipped。"
}

private fun formatCutoff(millis: Long): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日 00:00"
}

/** 复制主图文件字节到 SAF 目标：不重新编码，JPEG 仍是 JPEG、PNG 仍是 PNG */
private fun exportFile(
    context: Context,
    store: HdCaptureStore,
    entry: HdCaptureStore.Entry,
    uri: Uri
): Boolean {
    val source = store.mainFile(entry) ?: return false
    return runCatching {
        val out = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
        out.use { stream ->
            source.inputStream().use { input -> input.copyTo(stream) }
        }
        true
    }.getOrDefault(false)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}
