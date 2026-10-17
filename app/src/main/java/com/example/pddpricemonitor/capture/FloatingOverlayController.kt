package com.example.pddpricemonitor.capture

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.pddpricemonitor.R
import com.example.pddpricemonitor.compare.ClipRelayActivity
import com.example.pddpricemonitor.compare.CompareApps
import com.example.pddpricemonitor.data.ProductPriceComparison
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.Settings

// 「清爽红」设计系统：与主界面保持一致，取拼多多品牌红做记忆点
private val BrandRed = Color.parseColor("#E02E24")
private val BrandRedSoft = Color.parseColor("#FFF1F0")
private val FreshGreen = Color.parseColor("#1DC981")
private val SoftGreen = Color.parseColor("#E9F9F1")
private val CardWhite = Color.WHITE
private val TextPrimary = Color.parseColor("#1A1A1A")
private val TextSecondary = Color.parseColor("#8A8F99")
private val HairlineBorder = Color.parseColor("#1A1A1A1A")

// 一体双段「圆角方柱」按钮配色：白壳 + 上段浅灰扫描（深色图标）+ 下段淡红相机（红色图标）
private val ScanIdleBg = Color.parseColor("#F4F4F6")
private val ScanScanBg = Color.parseColor("#FDEBEA")
private val ScanOkBg = Color.parseColor("#E9F9F1")
private val ScanErrBg = Color.parseColor("#EDEDF0")
private val ScanIconInk = Color.parseColor("#242429")
private val CamIdleBg = Color.parseColor("#FDEFEE")
private val CamIconRed = Color.parseColor("#E63B35")
private val DividerColor = Color.parseColor("#E2E2E7")
private val PressTint = Color.parseColor("#14000000")

/** 双段按钮的两块按压区（整块外壳的空白、中缝、外边距都不属于任何一段） */
private enum class ColumnSegment { TOP, BOTTOM }

/**
 * 一体双段「圆角方柱」：白色圆角矩形外壳，上下两块等大按压区，中缝一条细分隔线。
 * 上段浅灰底扫描图标＝原有识别；下段淡红底相机图标＝截图。
 * 命中判定只认两段内部矩形——外壳空白、中缝细线、透明外边距都不会被当成功能按钮；
 * 但整块窗口（含空白与边距）都可拖动。
 */
private class RoundedColumnDrawable(
    private val marginPx: Int,
    private val segSizePx: Int,
    private val shellPadPx: Int,
    private val segGapPx: Int,
    private val shellRadiusPx: Float,
    private val segRadiusPx: Float,
    private val iconPx: Int,
    private val density: Float
) : Drawable() {

    var scanBg: Int = ScanIdleBg
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    var scanFg: Int = ScanIconInk
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    var pressedSegment: ColumnSegment? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    private val shellPath = Path()
    private val shellRect = RectF()
    private val segRect = RectF()
    private val iconRect = RectF()

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 落点属于哪一段；两边都不是（外壳空白/中缝/边距）返回 null */
    fun hitSegment(x: Float, y: Float): ColumnSegment? {
        topSegmentRect(segRect)
        if (segRect.contains(x, y)) return ColumnSegment.TOP
        bottomSegmentRect(segRect)
        if (segRect.contains(x, y)) return ColumnSegment.BOTTOM
        return null
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        // 外壳：白底 + 发丝描边（项目语言：靠底色差 + 描边两级区分）
        fillShellRect(shellRect)
        shellPath.reset()
        shellPath.addRoundRect(shellRect, shellRadiusPx, shellRadiusPx, Path.Direction.CW)
        shellPaint.color = CardWhite
        canvas.drawPath(shellPath, shellPaint)
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 0.6f * density
        borderPaint.color = HairlineBorder
        canvas.drawPath(shellPath, borderPaint)

        // 上段：浅灰底 + 扫描图标
        topSegmentRect(segRect)
        shellPaint.color = scanBg
        canvas.drawRoundRect(segRect, segRadiusPx, segRadiusPx, shellPaint)
        drawPressOverlay(canvas, ColumnSegment.TOP)
        drawScanIcon(canvas, segRect.centerX(), segRect.centerY(), scanFg)

        // 下段：淡红底 + 相机图标
        bottomSegmentRect(segRect)
        shellPaint.color = CamIdleBg
        canvas.drawRoundRect(segRect, segRadiusPx, segRadiusPx, shellPaint)
        drawPressOverlay(canvas, ColumnSegment.BOTTOM)
        drawCameraIcon(canvas, segRect.centerX(), segRect.centerY(), CamIconRed)

        // 中缝细分隔线
        fillShellRect(shellRect)
        val lineWidth = dividerWidthPx().toFloat()
        val lineY = shellRect.top + shellPadPx + segSizePx + segGapPx / 2f
        borderPaint.strokeWidth = 1f * density
        borderPaint.color = DividerColor
        canvas.drawLine(
            shellRect.centerX() - lineWidth / 2f, lineY,
            shellRect.centerX() + lineWidth / 2f, lineY,
            borderPaint
        )
    }

    private fun drawPressOverlay(canvas: Canvas, segment: ColumnSegment) {
        if (pressedSegment != segment) return
        pressPaint.color = PressTint
        canvas.drawRoundRect(segRect, segRadiusPx, segRadiusPx, pressPaint)
    }

    // 扫描图标：四角圆括号 + 中心圆角方框（识别/取景语义，与设计稿一致）
    // 每个角的弧都必须以「当前点即弧起点」为准：起点角 = 该边射入角，sweep 朝角内收拢，
    // 否则 forceMoveTo=false 会先补一条通到弧起点的斜线，四角会卷成逗号形
    private fun drawScanIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        iconPaint.strokeWidth = 1.5f * density
        val half = iconPx / 2f
        val arm = iconPx * 0.34f
        val cornerR = iconPx * 0.13f
        val cl = cx - half
        val ct = cy - half
        val cr = cx + half
        val cb = cy + half
        val path = Path()
        // 左上：沿上边向左 → 逆时针绕角 → 沿左边向下
        path.moveTo(cl + arm, ct)
        path.lineTo(cl + cornerR, ct)
        iconRect.set(cl, ct, cl + 2f * cornerR, ct + 2f * cornerR)
        path.arcTo(iconRect, 270f, -90f)
        path.lineTo(cl, ct + arm)
        // 右上：沿上边向右 → 顺时针绕角 → 沿右边向下
        path.moveTo(cr - arm, ct)
        path.lineTo(cr - cornerR, ct)
        iconRect.set(cr - 2f * cornerR, ct, cr, ct + 2f * cornerR)
        path.arcTo(iconRect, 270f, 90f)
        path.lineTo(cr, ct + arm)
        // 右下：沿下边向右 → 逆时针绕角 → 沿右边向上
        path.moveTo(cr - arm, cb)
        path.lineTo(cr - cornerR, cb)
        iconRect.set(cr - 2f * cornerR, cb - 2f * cornerR, cr, cb)
        path.arcTo(iconRect, 90f, -90f)
        path.lineTo(cr, cb - arm)
        // 左下：沿下边向左 → 顺时针绕角 → 沿左边向上
        path.moveTo(cl + arm, cb)
        path.lineTo(cl + cornerR, cb)
        iconRect.set(cl, cb - 2f * cornerR, cl + 2f * cornerR, cb)
        path.arcTo(iconRect, 90f, 90f)
        path.lineTo(cl, cb - arm)
        canvas.drawPath(path, iconPaint)

        val inner = iconPx * 0.30f
        canvas.drawRoundRect(
            RectF(cx - inner / 2f, cy - inner / 2f, cx + inner / 2f, cy + inner / 2f),
            inner * 0.30f, inner * 0.30f, iconPaint
        )
    }

    // 相机图标：机身圆角矩形 + 顶部取景凸起 + 镜头圈
    private fun drawCameraIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        iconPaint.strokeWidth = 1.5f * density
        val bodyW = iconPx * 0.96f
        val bodyH = iconPx * 0.68f
        val top = cy - bodyH * 0.30f
        canvas.drawRoundRect(
            RectF(cx - bodyW / 2f, top, cx + bodyW / 2f, top + bodyH),
            bodyH * 0.20f, bodyH * 0.20f, iconPaint
        )
        val bumpW = bodyW * 0.34f
        val bumpH = bodyH * 0.26f
        canvas.drawRoundRect(
            RectF(cx - bodyW * 0.18f, top - bumpH, cx - bodyW * 0.18f + bumpW, top + bumpH * 0.8f),
            bumpH * 0.45f, bumpH * 0.45f, iconPaint
        )
        canvas.drawCircle(cx, top + bodyH * 0.52f, bodyH * 0.29f, iconPaint)
    }

    private fun fillShellRect(out: RectF) {
        out.set(
            marginPx.toFloat(), marginPx.toFloat(),
            bounds.width() - marginPx.toFloat(), bounds.height() - marginPx.toFloat()
        )
    }

    private fun topSegmentRect(out: RectF) {
        fillShellRect(out)
        val left = out.left + shellPadPx
        val top = out.top + shellPadPx
        out.set(left, top, left + segSizePx, top + segSizePx)
    }

    private fun bottomSegmentRect(out: RectF) {
        fillShellRect(out)
        val left = out.left + shellPadPx
        val top = out.top + shellPadPx + segSizePx + segGapPx
        out.set(left, top, left + segSizePx, top + segSizePx)
    }

    private fun dividerWidthPx(): Int = (segSizePx * 0.64f).toInt()

    // elevation 投影只认外壳轮廓（不含透明外边距）
    override fun getOutline(outline: Outline) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return
        outline.setRoundRect(marginPx, marginPx, w - marginPx, h - marginPx, shellRadiusPx)
    }

    override fun setAlpha(alpha: Int) {}

    @Deprecated("Deprecated in Java")
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 边签状态：上段底色与图标颜色即状态——待机灰 / 识别红 / 成功绿 / 失败灰
 */
enum class BallState { IDLE, SCANNING, SUCCESS, ERROR }

/**
 * 悬浮窗 UI 控制器：
 * - 管理悬浮球和结果面板的创建、显示、隐藏
 * - 处理拖拽、吸附边缘等交互
 * - 管理所有动画效果
 * - 通过回调通知外部用户操作（点击识别、保存、重新识别等）
 *
 * 不包含任何业务逻辑（OCR、数据库等），纯 UI 层。
 */
class FloatingOverlayController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onCaptureClick()
        // 下段按钮：单击保存一张截图（不 OCR、不写价格库、不弹菜单）
        fun onHdCaptureClick()
        fun onSaveClick(title: String, priceCents: Long, ocrTitle: String)
        fun onReCaptureClick()
        fun onPriceChanged(priceCents: Long?)
        // 球窗口被系统摘除且自愈失败（悬浮窗权限被撤/ROM 反复摘窗）：服务已无存在意义
        fun onBallLost()
    }

    // 单击=价格识别；双击=轮换跳转比价应用（一个都没设则回拼多多）；长按=跳转拼多多。
    // 全部由自绘手势状态机判定：按下瞬间锁定归属段，拖动一接管就作废所有待触发动作
    private fun resetTapState() {
        lastTapUpTime = 0L
        pendingSingleTap?.cancel()
        pendingSingleTap = null
    }

    private var lastTapUpTime = 0L
    private var pendingSingleTap: Job? = null
    private val DOUBLE_TAP_TIMEOUT_MS = 500L

    private var windowManager: WindowManager? = null
    // 球窗口：只含悬浮球，尺寸恒定；只有用户拖拽会移动它，面板展开/收起绝不触碰
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    // 面板窗口：独立窗口，随显隐挂载/移除，位置始终跟随球
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    // 本方主动摘窗标志：destroy() 摘窗前置位——detach 哨兵靠它区分「自己关的」
    // 和「系统摘的」，只有后者才走自愈/上报
    @Volatile private var selfRemoving = false
    // 意外摘除的自愈限速：短窗口内反复被系统摘（ROM 限制循环）时放弃重挂
    private var lastDetachAt = 0L
    private var detachBurst = 0

    // 双段按钮相关
    private var ballWrap: FrameLayout? = null
    private var ballFace: View? = null
    private var ballFaceDrawable: RoundedColumnDrawable? = null
    private var scanBgAnimator: ValueAnimator? = null
    private var scanFgAnimator: ValueAnimator? = null
    private var currentScanBg = ScanIdleBg
    private var currentScanFg = ScanIconInk
    private var panelSlideAnimator: ValueAnimator? = null

    // 截图期间临时隐藏悬浮控件：记录面板原有可见性，截图结束后原样恢复
    private var captureHidden = false
    private var capturePanelWasVisible = false

    // 悬浮提示气泡：Toast 在 MIUI/HyperOS 上会因应用通知权限被系统静默抑制
    // （logcat: Suppressing toast ... by user request），反馈改由自有悬浮窗给出，恒定可见
    private var hintView: TextView? = null
    private var hintParams: WindowManager.LayoutParams? = null
    private var hintJob: Job? = null

    // 结果面板相关
    private var resultPanel: LinearLayout? = null
    private var titleEdit: EditText? = null
    private var priceEdit: EditText? = null
    private var comparisonStrip: LinearLayout? = null
    private var comparisonDot: View? = null
    private var comparisonTitle: TextView? = null
    private var comparisonHint: TextView? = null
    private var saveButton: TextView? = null
    private var currentComparison: ProductPriceComparison? = null
    private var pendingOcrTitle: String = ""
    // 当前面板是否处于「识别后自动保存」模式：true 时按钮组换成回执形态
    // （改错了才需要动，改对不用点保存），false 时保持原有「保存」主按钮
    private var panelAutoSaved: Boolean = false

    private var autoCollapseJob: Job? = null
    private var editingMode = false

    val isShowing: Boolean
        get() = overlayView != null

    private val pddPackageCandidates = listOf(
        "com.xunmeng.pinduoduo",      // 拼多多
        "com.xunmeng.pinduoduo.lite"  // 拼多多极速版
    )

    private fun launchPdd() {
        val pm = context.packageManager
        val found = pddPackageCandidates.mapNotNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()?.let { pkg to it }
        }
        Log.d("PddBall", "launchPdd: found=${found.map { it.first }}")
        val intent = found.firstOrNull()?.second
        if (intent == null) {
            Toast.makeText(context, "未找到拼多多，请确认已安装", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching {
            context.startActivity(intent)
            // 回到拼多多 = 本轮比价结束：轮换位置归零，下次双击从第一个比价应用重新开始
            CompareApps.resetLaunchIndex(context)
        }.onFailure {
            Log.e("PddBall", "launchPdd failed", it)
            Toast.makeText(context, "跳转失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 双击：轮换跳转用户选定的比价应用（按勾选顺序循环）。
    // 复制优先、直达搜索其次——剪贴板有词是兜底线：闲鱼等不支持搜索 deeplink 的应用落首页后，
    // 靠用户粘贴或应用自身剪贴板检测填充搜索框；复制经前台中转页完成，跳转同时带词走 deeplink
    private fun launchNextCompareApp() {
        val pkg = CompareApps.nextPackageName(context) ?: run {
            launchPdd()
            return
        }
        val label = CompareApps.appLabel(context, pkg)
        val desired = CompareApps.desiredTitle
        if (CompareApps.isAutoCopyTitle(context) && !desired.isNullOrBlank() && desired != CompareApps.knownClipTitle) {
            ClipRelayActivity.start(context, desired, pkg)
            return
        }
        if (CompareApps.isDirectSearch(context) && !desired.isNullOrBlank()) {
            if (CompareApps.launch(context, pkg, query = desired)) {
                Toast.makeText(context, "比价 → $label", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (CompareApps.launch(context, pkg)) {
            Toast.makeText(context, "比价 → $label", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "无法打开「$label」", Toast.LENGTH_SHORT).show()
        }
    }

    // @return 球是否成功挂上窗口（权限缺失/addView 抛异常都返回 false，
    //         调用方据此停服务，避免「服务跑着但球不存在」的僵尸态）
    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        // 僵尸引用守卫：权限被撤或 ROM 清理后 overlayView 可能还持着已摘除的 View
        if (overlayView?.isAttachedToWindow == true) return true

        windowManager = context.getSystemService(WindowManager::class.java)
        val ball = createBallView()

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - tabViewW()
            y = dp(160)
            restoreTabPosition(this)
        }

        overlayView = ball
        runCatching {
            windowManager?.addView(ball, overlayParams)
        }.onFailure {
            overlayView = null
            return false
        }

        // 系统摘窗哨兵：权限被撤/ROM 后台清理摘掉 overlay 窗口时，系统不会通知
        // 服务——球没了服务还在跑，主界面永远显示「运行中」。在 detach 瞬间感知：
        // 权限还在就重挂（自愈），重挂不动或权限没了才上报停服务
        ball.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}

            override fun onViewDetachedFromWindow(v: View) {
                if (selfRemoving || v !== overlayView) return
                val now = SystemClock.elapsedRealtime()
                detachBurst = if (now - lastDetachAt < REATTACH_WINDOW_MS) detachBurst + 1 else 1
                lastDetachAt = now
                overlayView = null
                // 球被摘时面板若还悬着（独立窗口系统未必连坐），一并收掉
                detachPanelSilently()
                if (detachBurst <= MAX_REATTACH_ATTEMPTS &&
                    Settings.canDrawOverlays(context) && show()
                ) {
                    return
                }
                callbacks.onBallLost()
            }
        })

        // 入场：从所在边缘"长出来"（贴哪条边就朝屏内滑入）
        val slideFrom = if (currentTabOnRight()) {
            dp(COLUMN_MARGIN_DP + SHELL_W_DP).toFloat()
        } else {
            -dp(COLUMN_MARGIN_DP + SHELL_W_DP).toFloat()
        }
        ball.alpha = 0f
        ball.translationX = slideFrom
        ball.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(360L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 面板结构预先创建；其窗口独立于球窗口，展示时才挂载
        resultPanel = createResultPanel()
        val panelRoot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panelRoot.addView(resultPanel)
        panelView = panelRoot
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        attachDrag(panelRoot)
        return true
    }

    // 意外摘球时同步收面板：不走收起动画（球已不在，面板悬空属异常态），直接摘窗
    private fun detachPanelSilently() {
        cancelAutoCollapse()
        panelSlideAnimator?.cancel()
        resultPanel?.animate()?.cancel()
        panelView?.let { pv ->
            if (pv.isAttachedToWindow) runCatching { windowManager?.removeView(pv) }
        }
        panelView = null
    }

    private fun createBallView(): FrameLayout {
        val ball = FrameLayout(context).apply {
            tag = "ballWrap"
        }
        ballWrap = ball

        // 圆角方柱：外壳四周留 margin 供投影扩散，贴边时间距即与此一致（8dp）
        val face = RoundedColumnDrawable(
            marginPx = dp(COLUMN_MARGIN_DP),
            segSizePx = dp(SEG_DP),
            shellPadPx = dp(SHELL_PAD_DP),
            segGapPx = dp(SEG_GAP_DP),
            shellRadiusPx = dp(SHELL_RADIUS_DP).toFloat(),
            segRadiusPx = dp(SEG_RADIUS_DP).toFloat(),
            iconPx = dp(ICON_DP),
            density = context.resources.displayMetrics.density
        )
        ballFaceDrawable = face
        ballFace = View(context).apply {
            background = face
            elevation = dp(4).toFloat()
        }
        // 无障碍名称：上段识别、下段截图，功能不靠颜色辨认
        ballFace?.contentDescription = "上段：识别价格；下段：保存截图"
        ball.addView(ballFace, FrameLayout.LayoutParams(tabViewW(), tabViewH(), Gravity.CENTER))

        attachBallTouch(ball)
        return ball
    }

    private fun createResultPanel(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(CardWhite)
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                dp(PANEL_W_DP),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setOnTouchListener { _, _ ->
                scheduleAutoCollapse()
                false
            }
        }

        // 顶部：眉题 + 关闭
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "识别结果"
            textSize = 12f
            setTextColor(TextSecondary)
            layoutParams = LinearLayout.LayoutParams(
                0,
                WindowManager.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        header.addView(TextView(context).apply {
            text = "×"
            textSize = 18f
            setTextColor(TextSecondary)
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener {
                cancelAutoCollapse()
                scope.launch { updateState(BallState.IDLE, showPanel = false) }
            }
        })

        // 商品标题输入
        titleEdit = EditText(context).apply {
            hint = "商品名称"
            minLines = 1
            maxLines = 3
            textSize = 14f
            setTextColor(TextPrimary)
            setHintTextColor(TextSecondary)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                cancelAutoCollapse()
                showKeyboard(this)
            }
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    cancelAutoCollapse()
                    showKeyboard(view)
                }
            }
            // 面板编辑即登记最新剪贴板意图：双击跳转时以这里最后编辑的标题为准
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString()?.trim()
                    if (!text.isNullOrEmpty()) CompareApps.recordDesiredTitle(text)
                }
            })
        }
        val titleDivider = View(context).apply {
            setBackgroundColor(HairlineBorder)
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(6) }
        }

        // 价格行
        val priceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        priceRow.addView(TextView(context).apply {
            text = "¥"
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(BrandRed)
        })
        priceEdit = EditText(context).apply {
            hint = "0.00"
            textSize = 26f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(BrandRed)
            setHintTextColor(Color.parseColor("#F3B8B4"))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                WindowManager.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener {
                cancelAutoCollapse()
                showKeyboard(this)
            }
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    cancelAutoCollapse()
                    showKeyboard(view)
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val cents = parsePriceCents(s?.toString().orEmpty())
                    updateComparisonText(cents)
                    callbacks.onPriceChanged(cents)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        priceRow.addView(priceEdit)

        // 小数位修正按钮
        val decimalGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, dp(2))
        }
        decimalGroup.addView(TextView(context).apply {
            text = "/10"
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(TextSecondary)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.TRANSPARENT, radiusDp = 8, withStroke = true)
            setPadding(0, dp(5), 0, dp(5))
            layoutParams = LinearLayout.LayoutParams(dp(40), WindowManager.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                cancelAutoCollapse()
                shiftPriceDecimal(-1)
            }
        })
        decimalGroup.addView(TextView(context).apply {
            text = "x10"
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(TextSecondary)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.TRANSPARENT, radiusDp = 8, withStroke = true)
            setPadding(0, dp(5), 0, dp(5))
            layoutParams = LinearLayout.LayoutParams(dp(40), WindowManager.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                cancelAutoCollapse()
                shiftPriceDecimal(1)
            }
        })
        priceRow.addView(decimalGroup)

        val priceHint = TextView(context).apply {
            text = "识别有误？标题可直接点改 · 价格错位用 /10 x10"
            textSize = 11f
            setTextColor(TextSecondary)
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        // 对比条
        comparisonStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBackground(SoftGreen, radiusDp = 10, withStroke = false)
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        comparisonDot = View(context).apply {
            background = circleBackground(FreshGreen)
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
        }
        comparisonTitle = TextView(context).apply {
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(TextPrimary)
            setPadding(dp(8), 0, dp(6), 0)
        }
        comparisonHint = TextView(context).apply {
            textSize = 12f
            setTextColor(TextSecondary)
        }
        comparisonStrip?.addView(comparisonDot)
        comparisonStrip?.addView(comparisonTitle)
        comparisonStrip?.addView(comparisonHint)

        // 按钮行——两种形态：
        // 常规：红底「保存」+ 描边「重新识别」
        // 回执（自动保存开启）：数据已入库，主按钮变「修改保存」，
        //   对了什么都不用点，改错了才需要动作
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        saveButton = TextView(context).apply {
            text = "保存"
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            background = roundedBackground(BrandRed, radiusDp = 10, withStroke = false)
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { handleSave() }
        }
        buttons.addView(saveButton)
        buttons.addView(TextView(context).apply {
            text = "重新识别"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(TextSecondary)
            background = roundedBackground(Color.TRANSPARENT, radiusDp = 10, withStroke = true)
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(
                0,
                WindowManager.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                cancelAutoCollapse()
                scope.launch { updateState(BallState.IDLE, showPanel = false) }
                callbacks.onReCaptureClick()
            }
        })

        panel.addView(header)
        panel.addView(titleEdit)
        panel.addView(titleDivider)
        panel.addView(priceRow)
        panel.addView(priceHint)
        panel.addView(comparisonStrip)
        panel.addView(buttons)

        return panel
    }

    private fun handleSave() {
        val title = titleEdit?.text?.toString()?.trim().orEmpty()
        val priceCents = parsePriceCents(priceEdit?.text?.toString().orEmpty())
        if (title.isBlank() || priceCents == null) {
            scope.launch { updateState(BallState.ERROR, showPanel = true) }
            return
        }
        callbacks.onSaveClick(title, priceCents, pendingOcrTitle)
    }

    // ---- 拖拽相关 ----

    // 面板容器可拖动：按住卡片空白也能移动整组（与球共用同一套位置参数）
    private fun attachDrag(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                        moved = true
                        moveOverlayTo(startX + dx, startY + dy)
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) snapOverlayToNearestEdge()
                    moved
                }
                else -> false
            }
        }
    }

    /**
     * 悬浮球手势：自绘状态机（替代 GestureDetector，避免一次触摸触发两条回调）。
     * - 按下瞬间按落点锁定归属段，手指随后滑到另一段也不会触发它（跨段不误触）；
     * - 落点在外壳空白 / 中缝细线 / 透明边距 = 本次手势只能拖动，不触发任何功能；
     * - 位移超过系统手势阈值即由拖动接管：作废待触发的点击、长按与双击，松手只贴边。
     */
    private fun attachBallTouch(view: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressFired = false
        var segment: ColumnSegment? = null
        var longPressJob: Job? = null

        view.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressFired = false
                    segment = ballFaceDrawable?.hitSegment(event.x, event.y)
                    setSegmentPressed(segment)
                    animateBallPressed(true)
                    if (segment == ColumnSegment.TOP) {
                        longPressJob = scope.launch {
                            delay(longPressMs)
                            longPressJob = null
                            if (!dragging && segment == ColumnSegment.TOP) {
                                longPressFired = true
                                setSegmentPressed(null)
                                resetTapState()
                                cancelAutoCollapse()
                                launchPdd()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (dx * dx + dy * dy > slop * slop) {
                            dragging = true
                            // 拖动接管：所有待触发动作立即作废，松手不得识别/截图/跳应用
                            longPressJob?.cancel()
                            longPressJob = null
                            resetTapState()
                            setSegmentPressed(null)
                        }
                    }
                    if (dragging) {
                        moveOverlayTo(
                            startX + (event.rawX - downRawX).toInt(),
                            startY + (event.rawY - downRawY).toInt()
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressJob?.cancel()
                    longPressJob = null
                    animateBallPressed(false)
                    setSegmentPressed(null)
                    when {
                        dragging -> snapOverlayToNearestEdge()
                        longPressFired -> Unit
                        segment == ColumnSegment.TOP -> handleTopTap()
                        segment == ColumnSegment.BOTTOM -> {
                            cancelAutoCollapse()
                            callbacks.onHdCaptureClick()
                        }
                        else -> Unit
                    }
                    segment = null
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressJob?.cancel()
                    longPressJob = null
                    animateBallPressed(false)
                    setSegmentPressed(null)
                    segment = null
                    true
                }
                else -> false
            }
        }
    }

    // 上段点击：500ms 内第二击 = 双击跳比价应用；否则延迟 500ms 派发识别
    private fun handleTopTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapUpTime < DOUBLE_TAP_TIMEOUT_MS) {
            resetTapState()
            cancelAutoCollapse()
            launchNextCompareApp()
        } else {
            lastTapUpTime = now
            pendingSingleTap?.cancel()
            pendingSingleTap = scope.launch {
                delay(DOUBLE_TAP_TIMEOUT_MS)
                pendingSingleTap = null
                cancelAutoCollapse()
                callbacks.onCaptureClick()
            }
        }
    }

    private fun setSegmentPressed(segment: ColumnSegment?) {
        ballFaceDrawable?.pressedSegment = segment
    }

    // 只移动球窗口；若面板窗口已挂载则同步跟随。备用球周期内不挂载，无需跟移——
    // 挂载时从 overlayParams 同步位置
    private fun moveOverlayTo(x: Int, y: Int) {
        val params = overlayParams ?: return
        params.x = clampOverlayX(x)
        params.y = clampOverlayY(y)
        runCatching { windowManager?.updateViewLayout(overlayView, params) }
        syncPanelPosition()
    }

    private fun syncPanelPosition() {
        val pv = panelView ?: return
        val pp = panelParams ?: return
        panelSlideAnimator?.cancel()
        panelSlideAnimator = null
        if (!pv.isAttachedToWindow) return
        computePanelPosition()
        runCatching { windowManager?.updateViewLayout(pv, pp) }
    }

    private fun currentTabOnRight(): Boolean {
        val params = overlayParams ?: return true
        val screenW = context.resources.displayMetrics.widthPixels
        val w = overlayView?.width?.takeIf { it > 0 } ?: tabViewW()
        return params.x + w / 2 >= screenW / 2
    }

    // 面板窗口位置：识别卡片贴着圆角方柱朝屏幕内侧展开，垂直方向与按钮中心对齐
    private fun computePanelPosition() {
        val tabP = overlayParams ?: return
        val panelP = panelParams ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val viewW = overlayView?.width?.takeIf { it > 0 } ?: tabViewW()
        val tabH = overlayView?.height?.takeIf { it > 0 } ?: tabViewH()
        val bodyW = dp(SHELL_W_DP)
        val margin = dp(COLUMN_MARGIN_DP)
        val panelW = dp(PANEL_W_DP)
        val panelH = panelHeightEstimate()
        val gap = dp(3)

        // 以外壳边缘（而非 view 边缘）为基准：view 四周内还有透明边距，不能算进间距
        panelP.x = if (currentTabOnRight()) {
            tabP.x + viewW - margin - bodyW - gap - panelW
        } else {
            tabP.x + margin + bodyW + gap
        }
        panelP.x = panelP.x.coerceIn(0, (screenW - panelW).coerceAtLeast(0))

        val tabCenterY = tabP.y + tabH / 2
        panelP.y = (tabCenterY - panelH / 2)
            .coerceIn(dp(12), (screenH - panelH - dp(12)).coerceAtLeast(dp(12)))
    }

    // 面板高度：优先取已布局的真实高度；首次展示前用 measure 兜底
    private fun panelHeightEstimate(): Int {
        val panel = resultPanel ?: return dp(300)
        if (panel.height > 0) return panel.height
        return runCatching {
            panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            panel.measuredHeight
        }.getOrNull()?.takeIf { it > 0 } ?: dp(300)
    }

    private fun clampOverlayX(x: Int): Int {
        val overlayWidth = overlayView?.width?.takeIf { it > 0 } ?: tabViewW()
        val maxX = (context.resources.displayMetrics.widthPixels - overlayWidth)
            .coerceAtLeast(0)
        return x.coerceIn(0, maxX)
    }

    private fun clampOverlayY(y: Int): Int {
        val topInset = dp(24)
        val bottomInset = dp(24)
        val overlayHeight = overlayView?.height?.takeIf { it > 0 } ?: tabViewH()
        val maxY = (context.resources.displayMetrics.heightPixels - overlayHeight - bottomInset)
            .coerceAtLeast(topInset)
        return y.coerceIn(topInset, maxY)
    }

    // 边签松手后平贴到最近的屏幕边缘（左边签 / 右边签），沿边缘上下滑动
    private fun snapOverlayToNearestEdge() {
        val params = overlayParams ?: return
        overlayView?.post {
            val screenW = context.resources.displayMetrics.widthPixels
            val overlayWidth = overlayView?.width?.takeIf { it > 0 } ?: tabViewW()
            val centerX = params.x + overlayWidth / 2
            params.x = if (centerX < screenW / 2) 0 else screenW - overlayWidth
            params.y = clampOverlayY(params.y)
            runCatching { windowManager?.updateViewLayout(overlayView, params) }
            syncPanelPosition()
            persistTabPosition()
        }
    }

    // ---- 位置记忆：拖到哪里，下次启动就停在哪里 ----
    // 拼多多商品页的悬浮视频窗固定从右缘弹出（延迟约 0.5s），若边签停在同一位置会被盖住、
    // 后续点击还会误触视频。错开位置一次即可永久避开——把选择权交给用户比追着视频窗
    // 挪动（已被证实的闪烁深渊）可靠得多

    private fun positionPrefs() =
        context.getSharedPreferences("overlay_position", Context.MODE_PRIVATE)

    private fun persistTabPosition() {
        val params = overlayParams ?: return
        val onRight = currentTabOnRight()
        positionPrefs().edit()
            .putBoolean("onRight", onRight)
            .putInt("y", params.y)
            .apply()
    }

    private fun restoreTabPosition(params: WindowManager.LayoutParams): WindowManager.LayoutParams {
        val prefs = positionPrefs()
        if (!prefs.contains("onRight")) return params
        val screenW = context.resources.displayMetrics.widthPixels
        val viewW = tabViewW()
        params.x = if (prefs.getBoolean("onRight", true)) screenW - viewW else 0
        params.y = clampOverlayY(prefs.getInt("y", dp(160)))
        return params
    }

    // ---- 自动折叠 ----

    private fun scheduleAutoCollapse() {
        cancelAutoCollapse()
        // 时长由设置决定（默认 8 秒）；选「常驻」则不启动折叠，靠 × / 重新识别 / 下次识别收起
        val durationMs = CapturePrefs.getReceiptDurationMs(context)
        if (durationMs <= 0L) return
        autoCollapseJob = scope.launch {
            delay(durationMs)
            updateState(BallState.IDLE, showPanel = false)
        }
    }

    fun scheduleStatusRestore() {
        cancelAutoCollapse()
        autoCollapseJob = scope.launch {
            delay(BALL_RESTORE_MS)
            updateState(BallState.IDLE, showPanel = false)
        }
    }

    private fun cancelAutoCollapse() {
        autoCollapseJob?.cancel()
        autoCollapseJob = null
    }

    // ---- 状态更新 ----

    // 结果面板（卡片）是否正展示中：服务侧据此把「面板开着时再点球」判为重新识别——
    // 撤回上一条未确认的自动保存；面板已自动收起则属隐式落账后的新一轮识别，不动旧数据。
    // 收回动画结束才置 GONE，动画期间按展示中处理
    val isPanelShowing: Boolean
        get() = resultPanel?.visibility == View.VISIBLE

    suspend fun updateState(state: BallState, showPanel: Boolean) {
        withContext(Dispatchers.Main) {
            setBallState(state)
            if (showPanel) showPanelAnimated() else hidePanelAnimated()
            setOverlayEditingMode(showPanel)
        }
    }

    suspend fun showEditableResult(
        title: String,
        priceCents: Long,
        comparison: ProductPriceComparison?,
        autoSaved: Boolean = false
    ) {
        // 本次识别的原始标题（未经编辑）：保存时随面板值一起上抛，
        // 两者差异即用户本次的编辑幅度，仓库侧据此决定沿用商品已有标题还是采用本次值
        pendingOcrTitle = title
        panelAutoSaved = autoSaved
        // 只登记意图，不在识别完成时写剪贴板——服务进程在后台写会触发
        // MIUI/HyperOS 的系统剪贴板悬浮窗，贴着屏幕边缘把悬浮球盖掉；
        // 复制推迟到双击跳转时经前台中转页（ClipRelayActivity）完成。
        // 意图账本存 CompareApps（进程内共享）：双击只认账本不读面板 EditText，
        // 否则「识别A后长按复制B再双击」会被面板里残留的旧A覆盖掉新B
        CompareApps.recordDesiredTitle(title)
        withContext(Dispatchers.Main) {
            setOverlayEditingMode(true)
            setBallState(BallState.SUCCESS)
            currentComparison = comparison
            titleEdit?.setText(title)
            priceEdit?.setText(formatPlainPrice(priceCents))
            updateComparisonText(priceCents)
            applyReceiptMode(autoSaved)
            showPanelAnimated()
        }
        scheduleAutoCollapse()
    }

    /**
     * 回执模式切换：识别后自动保存开启时，数据已在库里——主按钮从「保存」换成
     * 「修改保存」（浅灰底，弱化视觉权重：确认过的动作不需要大红色吸引）；
     * 关闭时恢复红底「保存」。服务侧的修改保存会先撤回自动保存行再按面板值重记，
     * 重新识别同样撤回，识别错值不会留在账里
     */
    private fun applyReceiptMode(autoSaved: Boolean) {
        val btn = saveButton ?: return
        if (autoSaved) {
            btn.text = "修改保存"
            btn.setTextColor(TextPrimary)
            btn.background = roundedBackground(Color.parseColor("#F2F3F5"), radiusDp = 10, withStroke = true)
        } else {
            btn.text = "保存"
            btn.setTextColor(Color.WHITE)
            btn.background = roundedBackground(BrandRed, radiusDp = 10, withStroke = false)
        }
    }

    // ---- 对比条 ----

    private fun updateComparisonText(currentPriceCents: Long?) {
        val strip = comparisonStrip ?: return
        val current = currentPriceCents
        if (current == null) {
            setStripVisible(false)
            return
        }
        setStripVisible(true)
        val comparison = currentComparison
        if (comparison == null) {
            applyComparisonStyle(BrandRedSoft, BrandRed)
            comparisonTitle?.text = "首次记录"
            comparisonHint?.text = "价格锚点已建立"
            return
        }
        val diff = current - comparison.previousLowestCents
        when {
            diff < 0 -> {
                applyComparisonStyle(SoftGreen, FreshGreen)
                comparisonTitle?.text = "比历史最低还低 ¥${formatPlainPrice(-diff)}"
                comparisonHint?.text = "值得入手"
            }
            diff == 0L -> {
                applyComparisonStyle(SoftGreen, FreshGreen)
                comparisonTitle?.text = "持平历史最低"
                comparisonHint?.text = "好价"
            }
            else -> {
                applyComparisonStyle(BrandRedSoft, BrandRed)
                comparisonTitle?.text = "比历史最低高 ¥${formatPlainPrice(diff)}"
                comparisonHint?.text = "可以再等等"
            }
        }
    }

    private fun setStripVisible(visible: Boolean) {
        val strip = comparisonStrip ?: return
        if (visible) {
            if (strip.visibility == View.VISIBLE) return
            strip.animate().cancel()
            strip.alpha = 0f
            strip.visibility = View.VISIBLE
            strip.animate().alpha(1f).setDuration(180L).start()
        } else {
            if (strip.visibility != View.VISIBLE) return
            strip.animate().cancel()
            strip.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction { strip.visibility = View.GONE }
                .start()
        }
    }

    private fun applyComparisonStyle(backgroundColor: Int, dotColor: Int) {
        comparisonStrip?.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(backgroundColor)
        }
        comparisonDot?.background = circleBackground(dotColor)
    }

    // ---- 面板动画：卡片沿边签方向从屏幕边缘抽出 / 收回，签与卡始终连为一体 ----

    private fun showPanelAnimated() {
        val panel = resultPanel ?: return
        val pv = panelView ?: return
        val pp = panelParams ?: return

        // 先取消进行中的收回动画，避免其 onAnimationEnd 在展示后把面板又收掉
        panel.animate().cancel()
        panelSlideAnimator?.cancel()

        if (panel.visibility == View.VISIBLE && pv.isAttachedToWindow) {
            // 已在展示中：复位视觉状态并跟随签的位置
            panel.alpha = 1f
            syncPanelPosition()
            return
        }

        computePanelPosition()
        val targetX = pp.x
        val onRight = currentTabOnRight()
        val slide = dp(72)

        // 签窗口完全不动；卡片先挂载在签后方（向边缘一侧偏移），再向屏内滑出
        pp.x = targetX + if (onRight) slide else -slide
        if (!pv.isAttachedToWindow) {
            runCatching { windowManager?.addView(pv, pp) }
        } else {
            runCatching { windowManager?.updateViewLayout(pv, pp) }
        }

        panel.alpha = 0f
        panel.visibility = View.VISIBLE

        panelSlideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                pp.x = (targetX + (1f - t) * (if (onRight) slide else -slide)).toInt()
                runCatching { windowManager?.updateViewLayout(pv, pp) }
                panel.alpha = (t * 1.8f).coerceAtMost(1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator, isReverse: Boolean) {
                    pp.x = targetX
                    runCatching { windowManager?.updateViewLayout(pv, pp) }
                    panel.alpha = 1f
                }
            })
            start()
        }
    }

    private fun hidePanelAnimated() {
        val panel = resultPanel ?: return
        if (panel.visibility != View.VISIBLE) return
        val pv = panelView ?: return
        val pp = panelParams ?: return

        panel.animate().cancel()
        panelSlideAnimator?.cancel()

        val onRight = currentTabOnRight()
        val slide = dp(72)
        val startX = pp.x
        val startAlpha = panel.alpha

        // 收回：卡片沿原路滑回签后方并淡出
        panelSlideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = AccelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                pp.x = (startX + t * (if (onRight) slide else -slide)).toInt()
                runCatching { windowManager?.updateViewLayout(pv, pp) }
                panel.alpha = startAlpha * (1f - t)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator, isReverse: Boolean) {
                    panel.visibility = View.GONE
                    panel.alpha = 1f
                    if (pv.isAttachedToWindow) {
                        runCatching { windowManager?.removeView(pv) }
                    }
                }
            })
            start()
        }
    }

    // ---- 按钮状态：上段底色与图标颜色即状态 ----

    private fun setBallState(state: BallState) {
        val (bg, fg) = when (state) {
            BallState.IDLE -> ScanIdleBg to ScanIconInk
            BallState.SCANNING -> ScanScanBg to BrandRed
            BallState.SUCCESS -> ScanOkBg to FreshGreen
            BallState.ERROR -> ScanErrBg to TextSecondary
        }
        animateScanColors(bg, fg)
    }

    private fun animateScanColors(bg: Int, fg: Int) {
        val drawable = ballFaceDrawable ?: return
        if (currentScanBg != bg) {
            scanBgAnimator?.cancel()
            scanBgAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentScanBg, bg).apply {
                duration = 220L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    currentScanBg = animator.animatedValue as Int
                    drawable.scanBg = currentScanBg
                }
                start()
            }
        }
        if (currentScanFg != fg) {
            scanFgAnimator?.cancel()
            scanFgAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentScanFg, fg).apply {
                duration = 220L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    currentScanFg = animator.animatedValue as Int
                    drawable.scanFg = currentScanFg
                }
                start()
            }
        }
    }

    // 按压手感：按下缩到 0.95，松手弹性回弹
    private fun animateBallPressed(isPressed: Boolean) {
        val wrap = ballWrap ?: return
        wrap.animate().cancel()
        if (isPressed) {
            wrap.animate()
                .scaleX(0.95f).scaleY(0.95f)
                .setDuration(110L)
                .setInterpolator(AccelerateInterpolator())
                .start()
        } else {
            wrap.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(260L)
                .setInterpolator(OvershootInterpolator(1.4f))
                .start()
        }
    }

    // ---- 截图期间临时隐藏 ----

    /**
     * 截图前隐藏悬浮控件：球窗口与结果面板一起隐去（窗口仍挂着，只是不渲染），
     * 这样取到的帧里不会带本软件按钮；面板被临时隐藏期间暂停自动收起，
     * 结束后按原可见性恢复，不改动面板里的商品与价格。
     */
    fun hideForCapture() {
        if (captureHidden) return
        captureHidden = true
        capturePanelWasVisible = resultPanel?.visibility == View.VISIBLE
        if (capturePanelWasVisible) cancelAutoCollapse()
        // 提示气泡也是本软件的控件，必须立刻摘掉（不做淡出动画）：否则会被拍进截图
        removeHintNow()
        overlayView?.visibility = View.INVISIBLE
        if (capturePanelWasVisible) panelView?.visibility = View.INVISIBLE
    }

    fun restoreAfterCapture() {
        if (!captureHidden) return
        captureHidden = false
        overlayView?.visibility = View.VISIBLE
        if (capturePanelWasVisible) {
            panelView?.visibility = View.VISIBLE
            // 面板本来就在展示中：恢复后继续原有的自动收起计时
            scheduleAutoCollapse()
        }
        capturePanelWasVisible = false
    }

    // ---- 悬浮提示气泡 ----

    /**
     * 在自有悬浮窗里弹一条短提示（1.8 秒后自动消失）。
     * 位置固定在屏幕底部居中——不贴球，避免与结果面板、悬浮球重叠，也不会挡到拖动。
     */
    fun showHint(message: String) {
        val wm = windowManager ?: return
        if (!isShowing) return
        val view = ensureHintView()
        view.text = message
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dp(HINT_MAX_W_DP), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED
        )
        val params = hintParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
            .also { hintParams = it }

        val hintW = view.measuredWidth.coerceAtLeast(dp(96))
        val hintH = view.measuredHeight.coerceAtLeast(dp(40))
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        params.x = ((screenW - hintW) / 2).coerceAtLeast(0)
        params.y = (screenH - hintH - dp(HINT_BOTTOM_MARGIN_DP))
            .coerceIn(0, (screenH - hintH).coerceAtLeast(0))

        if (view.isAttachedToWindow) {
            runCatching { wm.updateViewLayout(view, params) }
        } else {
            view.alpha = 0f
            runCatching { wm.addView(view, params) }
        }
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(HINT_FADE_MS).start()

        hintJob?.cancel()
        hintJob = scope.launch {
            delay(HINT_MS)
            fadeOutHint()
        }
    }

    private fun ensureHintView(): TextView = hintView ?: TextView(context).apply {
        setTextColor(TextPrimary)
        textSize = 13f
        setPadding(dp(16), dp(10), dp(16), dp(10))
        maxWidth = dp(HINT_MAX_W_DP)
        background = roundedBackground(CardWhite, radiusDp = 14, withStroke = true)
        elevation = dp(6).toFloat()
        hintView = this
    }

    private fun fadeOutHint() {
        hintJob?.cancel()
        hintJob = null
        val view = hintView ?: return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(HINT_FADE_MS)
            .withEndAction {
                if (view.isAttachedToWindow) runCatching { windowManager?.removeView(view) }
            }
            .start()
    }

    // 立即摘掉提示（无动画）：截图前必须保证画面上没有本软件的控件
    private fun removeHintNow() {
        hintJob?.cancel()
        hintJob = null
        val view = hintView ?: return
        view.animate().cancel()
        if (view.isAttachedToWindow) runCatching { windowManager?.removeView(view) }
    }

    // ---- 编辑模式 ----

    private fun setOverlayEditingMode(enabled: Boolean) {
        editingMode = enabled
        val params = panelParams ?: return
        val view = panelView ?: return
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        params.flags = if (enabled) {
            params.flags and notFocusable.inv()
        } else {
            hideKeyboard()
            titleEdit?.clearFocus()
            priceEdit?.clearFocus()
            params.flags or notFocusable
        }
        params.softInputMode = if (enabled) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        if (view.isAttachedToWindow) {
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    private fun showKeyboard(view: View) {
        view.post {
            view.requestFocus()
            val inputManager = context.getSystemService(InputMethodManager::class.java)
            inputManager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val view = panelView ?: return
        val inputManager = context.getSystemService(InputMethodManager::class.java)
        inputManager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ---- 价格工具函数 ----

    private val priceTextRegex = Regex("(\\d{1,6})(?:[.,](\\d{1,2}))?")

    private fun parsePriceCents(text: String): Long? {
        val normalized = text
            .replace("CNY", "", ignoreCase = true)
            .filter { it.isDigit() || it == '.' || it == ',' }
            .trim()
        val parts = priceTextRegex.find(normalized) ?: return null
        val yuan = parts.groupValues[1].toLongOrNull() ?: return null
        val cents = parts.groupValues.getOrNull(2).orEmpty().padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        return (yuan * 100 + cents).takeIf { it in 1..999_999_00 }
    }

    private fun formatPlainPrice(cents: Long): String =
        "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    private fun shiftPriceDecimal(direction: Int) {
        val current = parsePriceCents(priceEdit?.text?.toString().orEmpty()) ?: return
        val shifted = when {
            direction < 0 -> (current / 10).coerceAtLeast(1L)
            direction > 0 -> {
                val multiplied = current * 10
                if (multiplied > 999_999_00) return
                multiplied
            }
            else -> return
        }
        priceEdit?.setText(formatPlainPrice(shifted))
        priceEdit?.setSelection(priceEdit?.text?.length ?: 0)
        updateComparisonText(shifted)
        callbacks.onPriceChanged(shifted)
        scheduleAutoCollapse()
    }

    // ---- 工具函数 ----

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // 双段按钮 view 尺寸 = 外壳 + 四周透明边距（含投影扩散空间）
    private fun tabViewW(): Int = dp(SHELL_W_DP + COLUMN_MARGIN_DP * 2)
    private fun tabViewH(): Int = dp(SHELL_H_DP + COLUMN_MARGIN_DP * 2)

    private fun circleBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int = 20,
        withStroke: Boolean = true
    ): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (withStroke) setStroke(dp(1), HairlineBorder)
        }

    // ---- 生命周期 ----

    fun destroy() {
        // 先置位再摘窗：本方主动摘除不触发 detach 哨兵的自愈/上报
        selfRemoving = true
        cancelAutoCollapse()
        scanBgAnimator?.cancel()
        scanFgAnimator?.cancel()
        panelSlideAnimator?.cancel()
        ballWrap?.animate()?.cancel()
        resultPanel?.animate()?.cancel()
        removeHintNow()
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        panelView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        panelView = null
        scope.cancel()
    }

    companion object {
        private const val BALL_RESTORE_MS = 1_500L
        // 「一体双段 · 圆角方柱」尺寸（设计定稿起步值，按真机触达与画面占用校准）
        private const val SEG_DP = 44              // 单段按压区
        private const val SHELL_PAD_DP = 4         // 外壳内边距
        private const val SEG_GAP_DP = 8           // 两段间隔（中缝）
        private const val SHELL_RADIUS_DP = 16     // 外壳圆角
        private const val SEG_RADIUS_DP = 11       // 每段圆角
        private const val ICON_DP = 18             // 图标大小
        private const val COLUMN_MARGIN_DP = 8     // 外壳距屏幕边缘 / 窗口内透明边距
        private const val SHELL_W_DP = SEG_DP + SHELL_PAD_DP * 2
        private const val SHELL_H_DP = SEG_DP * 2 + SHELL_PAD_DP * 2 + SEG_GAP_DP
        private const val PANEL_W_DP = 300
        // 悬浮提示气泡：最大宽度、距屏幕底部留白、停留时长与淡入淡出时长
        private const val HINT_MAX_W_DP = 260
        private const val HINT_BOTTOM_MARGIN_DP = 132
        private const val HINT_MS = 1_800L
        private const val HINT_FADE_MS = 150L
        // 系统摘窗自愈的限速参数：5 秒窗口内最多重挂 2 次，超过判定为 ROM 限制循环
        private const val REATTACH_WINDOW_MS = 5_000L
        private const val MAX_REATTACH_ATTEMPTS = 2
    }
}
