package com.example.pddpricemonitor.capture

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import androidx.core.content.res.ResourcesCompat
import com.example.pddpricemonitor.R
import com.example.pddpricemonitor.data.ProductPriceComparison
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

/**
 * 悬浮球三态：待机红球 / 识别中脉冲光圈 / 成功绿勾（出错为灰感叹号）
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
        fun onSaveClick(title: String, priceCents: Long)
        fun onReCaptureClick()
        fun onPriceChanged(priceCents: Long?)
    }

    // 单击=价格识别；双击/长按=跳转拼多多。由 GestureDetector 统一判定，
    // 避免 View 自带的 click/longClick 与拖拽触摸监听相互干扰
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        // 手动双击判定：系统默认 300ms 窗口在部分 ROM 上偏短，放宽到 500ms。
        // 单击延迟 500ms 派发识别，期间第二击到达则取消并跳转拼多多
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - lastTapUpTime < DOUBLE_TAP_TIMEOUT_MS) {
                resetTapState()
                cancelAutoCollapse()
                launchPdd()
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
            return true
        }

        // 快速双击（间隔 <300ms）系统走这里而不触发 onSingleTapUp，需单独接住
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetTapState()
            cancelAutoCollapse()
            launchPdd()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            resetTapState()
            cancelAutoCollapse()
            launchPdd()
        }
    }

    private fun resetTapState() {
        lastTapUpTime = 0L
        pendingSingleTap?.cancel()
        pendingSingleTap = null
    }

    private val gestureDetector = GestureDetector(context, gestureListener)

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

    // 悬浮球相关
    private var ballWrap: FrameLayout? = null
    private var ballText: TextView? = null
    private var ballRing: View? = null
    private var ringAnimator: ValueAnimator? = null
    private var ringFadeAnimator: ValueAnimator? = null
    private var ringFadeLevel = 0f
    private var pulseProgress = 0f
    private var ballColorAnimator: ValueAnimator? = null
    private var currentBallColor = BrandRed
    private var glyphAnimator: AnimatorSet? = null

    // 结果面板相关
    private var resultPanel: LinearLayout? = null
    private var titleEdit: EditText? = null
    private var priceEdit: EditText? = null
    private var comparisonStrip: LinearLayout? = null
    private var comparisonDot: View? = null
    private var comparisonTitle: TextView? = null
    private var comparisonHint: TextView? = null
    private var currentComparison: ProductPriceComparison? = null

    private var autoCollapseJob: Job? = null

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
        }.onFailure {
            Log.e("PddBall", "launchPdd failed", it)
            Toast.makeText(context, "跳转失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun show() {
        if (!Settings.canDrawOverlays(context) || overlayView != null) return

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
            x = (context.resources.displayMetrics.widthPixels - dp(96)).coerceAtLeast(dp(24))
            y = dp(220)
        }

        overlayView = ball
        runCatching {
            windowManager?.addView(ball, overlayParams)
        }.onFailure {
            overlayView = null
            return
        }

        // 入场：淡入 + 弹性放大
        ball.alpha = 0f
        ball.scaleX = 0.6f
        ball.scaleY = 0.6f
        ball.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(340L)
            .setInterpolator(OvershootInterpolator(1.1f))
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
    }

    private fun createBallView(): FrameLayout {
        val ball = FrameLayout(context).apply {
            tag = "ballWrap"
        }
        ballWrap = ball

        // 光圈
        ballRing = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), BrandRed)
            }
            alpha = 0f
        }
        ball.addView(ballRing, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))

        // 球面文字
        ballText = TextView(context).apply {
            text = "love"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = ResourcesCompat.getFont(context, R.font.source_serif_4_italic)
            background = circleBackground(BrandRed)
            elevation = dp(6).toFloat()
        }
        ball.addView(ballText, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        attachDrag(ball, withGestures = true)
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
                dp(300),
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
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
            text = "点数字可修改 · 识别不准可用 /10 x10 修正"
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

        // 按钮行
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        buttons.addView(TextView(context).apply {
            text = "保存"
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            background = roundedBackground(BrandRed, radiusDp = 10, withStroke = false)
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { handleSave() }
        })
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
        callbacks.onSaveClick(title, priceCents)
    }

    // ---- 拖拽相关 ----

    private fun attachDrag(view: View, withGestures: Boolean = false) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            if (withGestures) gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    withGestures
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                        moved = true
                        moveOverlayTo(startX + dx, startY + dy)
                    }
                    withGestures
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapOverlayToNearestEdge()
                    }
                    withGestures || moved
                }
                else -> withGestures
            }
        }
    }

    // 只移动球窗口；若面板窗口已挂载则同步跟随
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
        if (!pv.isAttachedToWindow) return
        computePanelPosition()
        runCatching { windowManager?.updateViewLayout(pv, pp) }
    }

    // 面板窗口位置：显示在球正下方；球在右半屏时面板左移，使面板右缘对齐球右缘（面板朝屏幕内侧展开）
    private fun computePanelPosition() {
        val ballP = overlayParams ?: return
        val panelP = panelParams ?: return
        val screenWidth = context.resources.displayMetrics.widthPixels
        val ballW = dp(72)
        val panelW = dp(300)
        val ballCenterX = ballP.x + ballW / 2
        panelP.x = if (ballCenterX >= screenWidth / 2) {
            ballP.x + ballW - panelW
        } else {
            ballP.x
        }
        panelP.x = panelP.x.coerceIn(0, (screenWidth - panelW).coerceAtLeast(0))
        panelP.y = ballP.y + ballW
    }

    private fun clampOverlayX(x: Int): Int {
        val edgeInset = dp(24)
        val overlayWidth = overlayView?.width?.takeIf { it > 0 } ?: dp(96)
        val maxX = (context.resources.displayMetrics.widthPixels - overlayWidth - edgeInset)
            .coerceAtLeast(edgeInset)
        return x.coerceIn(edgeInset, maxX)
    }

    private fun clampOverlayY(y: Int): Int {
        val topInset = dp(24)
        val bottomInset = dp(24)
        val overlayHeight = overlayView?.height?.takeIf { it > 0 } ?: dp(96)
        val maxY = (context.resources.displayMetrics.heightPixels - overlayHeight - bottomInset)
            .coerceAtLeast(topInset)
        return y.coerceIn(topInset, maxY)
    }

    private fun snapOverlayToNearestEdge() {
        val params = overlayParams ?: return
        overlayView?.post {
            val width = context.resources.displayMetrics.widthPixels
            val overlayWidth = overlayView?.width?.takeIf { it > 0 } ?: dp(72)
            val edgeInset = dp(24)
            val leftX = edgeInset
            val rightX = (width - overlayWidth - edgeInset).coerceAtLeast(edgeInset)
            val centerX = params.x + overlayWidth / 2
            params.x = if (centerX < width / 2) leftX else rightX
            params.y = clampOverlayY(params.y)
            runCatching { windowManager?.updateViewLayout(overlayView, params) }
            syncPanelPosition()
        }
    }

    // ---- 自动折叠 ----

    private fun scheduleAutoCollapse() {
        cancelAutoCollapse()
        autoCollapseJob = scope.launch {
            delay(AUTO_COLLAPSE_MS)
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
        comparison: ProductPriceComparison?
    ) {
        withContext(Dispatchers.Main) {
            setOverlayEditingMode(true)
            setBallState(BallState.SUCCESS)
            currentComparison = comparison
            titleEdit?.setText(title)
            priceEdit?.setText(formatPlainPrice(priceCents))
            updateComparisonText(priceCents)
            showPanelAnimated()
        }
        scheduleAutoCollapse()
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

    // ---- 面板动画 ----

    private fun showPanelAnimated() {
        val panel = resultPanel ?: return
        val pv = panelView ?: return
        val pp = panelParams ?: return

        // 先取消进行中的隐藏动画，避免其 withEndAction 在展示后把面板又收掉
        panel.animate().cancel()

        if (panel.visibility == View.VISIBLE && pv.isAttachedToWindow) {
            // 已在展示中：确保视觉状态复位并跟随球的位置
            panel.alpha = 1f
            panel.translationY = 0f
            panel.scaleX = 1f
            panel.scaleY = 1f
            syncPanelPosition()
            return
        }

        // 挂载/更新面板窗口——球窗口完全不动
        computePanelPosition()
        if (!pv.isAttachedToWindow) {
            runCatching { windowManager?.addView(pv, pp) }
        } else {
            runCatching { windowManager?.updateViewLayout(pv, pp) }
        }

        panel.alpha = 0f
        panel.translationY = dp(10).toFloat()
        panel.scaleX = 0.97f
        panel.scaleY = 0.97f
        panel.visibility = View.VISIBLE
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hidePanelAnimated() {
        val panel = resultPanel ?: return
        if (panel.visibility != View.VISIBLE) return
        val pv = panelView

        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .translationY(dp(6).toFloat())
            .scaleX(0.98f)
            .scaleY(0.98f)
            .setDuration(170L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                panel.visibility = View.GONE
                panel.alpha = 1f
                panel.translationY = 0f
                panel.scaleX = 1f
                panel.scaleY = 1f
                if (pv != null && pv.isAttachedToWindow) {
                    runCatching { windowManager?.removeView(pv) }
                }
            }
            .start()
    }

    // ---- 悬浮球状态 ----

    private fun setBallState(state: BallState) {
        val label = ballText ?: return
        when (state) {
            BallState.IDLE -> {
                stopRingPulse()
                swapBallGlyph("love")
                animateBallColor(BrandRed)
            }
            BallState.SCANNING -> {
                swapBallGlyph("love")
                animateBallColor(BrandRed)
                startRingPulse()
            }
            BallState.SUCCESS -> {
                stopRingPulse()
                swapBallGlyph("✓")
                animateBallColor(FreshGreen)
            }
            BallState.ERROR -> {
                stopRingPulse()
                swapBallGlyph("!")
                animateBallColor(TextSecondary)
            }
        }
    }

    private fun animateBallColor(target: Int) {
        val label = ballText ?: return
        if (currentBallColor == target) return
        ballColorAnimator?.cancel()
        ballColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentBallColor, target).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                currentBallColor = animator.animatedValue as Int
                (label.background as? GradientDrawable)?.setColor(currentBallColor)
            }
            start()
        }
    }

    private fun swapBallGlyph(newText: String) {
        val label = ballText ?: return
        if (label.text.toString() == newText) return
        glyphAnimator?.cancel()
        val shrink = ValueAnimator.ofFloat(label.scaleX, 0.6f).apply {
            duration = 110L
            interpolator = AccelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                label.scaleX = scale
                label.scaleY = scale
            }
        }
        val expand = ValueAnimator.ofFloat(0.6f, 1f).apply {
            duration = 260L
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                label.scaleX = scale
                label.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    label.text = newText
                }
            })
        }
        AnimatorSet().apply {
            playSequentially(shrink, expand)
            glyphAnimator = this
            start()
        }
    }

    private fun startRingPulse() {
        val ring = ballRing ?: return
        ringAnimator?.cancel()
        ringFadeAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                pulseProgress = animator.animatedValue as Float
                applyRingVisual(ring)
            }
            start()
        }
        ringFadeAnimator = ValueAnimator.ofFloat(ringFadeLevel, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                ringFadeLevel = animator.animatedValue as Float
                applyRingVisual(ring)
            }
            start()
        }
    }

    private fun stopRingPulse() {
        val ring = ballRing ?: return
        ringFadeAnimator?.cancel()
        ringFadeAnimator = ValueAnimator.ofFloat(ringFadeLevel, 0f).apply {
            duration = 240L
            addUpdateListener { animator ->
                ringFadeLevel = animator.animatedValue as Float
                applyRingVisual(ring)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator, isReverse: Boolean) {
                    if (ringFadeLevel <= 0.01f) {
                        ringAnimator?.cancel()
                        ringAnimator = null
                        ring.alpha = 0f
                    }
                }
            })
            start()
        }
    }

    private fun applyRingVisual(ring: View) {
        val scale = 0.78f + pulseProgress * 0.4f
        ring.scaleX = scale
        ring.scaleY = scale
        ring.alpha = ringFadeLevel * (1f - pulseProgress) * 0.65f
    }

    // ---- 编辑模式 ----

    private fun setOverlayEditingMode(enabled: Boolean) {
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

    private fun parsePriceCents(text: String): Long? {
        val normalized = text
            .replace("CNY", "", ignoreCase = true)
            .filter { it.isDigit() || it == '.' || it == ',' }
            .trim()
        val parts = Regex("(\\d{1,6})(?:[.,](\\d{1,2}))?").find(normalized) ?: return null
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
        cancelAutoCollapse()
        ringAnimator?.cancel()
        ringFadeAnimator?.cancel()
        ballColorAnimator?.cancel()
        glyphAnimator?.cancel()
        ballWrap?.animate()?.cancel()
        resultPanel?.animate()?.cancel()
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
        private const val AUTO_COLLAPSE_MS = 8_000L
        private const val BALL_RESTORE_MS = 1_500L
    }
}
