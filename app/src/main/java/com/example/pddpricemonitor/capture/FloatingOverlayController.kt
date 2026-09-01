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
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import kotlin.math.min
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
private val InkColor = Color.parseColor("#2E3338")
private val FreshGreen = Color.parseColor("#1DC981")
private val SoftGreen = Color.parseColor("#E9F9F1")
private val CardWhite = Color.WHITE
private val TextPrimary = Color.parseColor("#1A1A1A")
private val TextSecondary = Color.parseColor("#8A8F99")
private val HairlineBorder = Color.parseColor("#1A1A1A1A")

/**
 * 边签：从屏幕边缘"长出来"的竖向小签——
 * ① 磨砂玻璃签体（垂直渐变半透明白，内侧圆角大、贴边侧圆角小）
 * ② 居中握柄（唯一着色元素，颜色即状态：待机墨 / 识别红 / 成功绿 / 失败灰）
 * ③ 识别光晕（沿签体轮廓向外扩散的红环，随 pulse 呼吸）
 * 签体贴边侧无 margin（签体压在屏幕边缘上，像从边缘长出来），内侧/上下留 margin 供光晕扩散；贴哪条边由 onRight 决定（圆角朝屏内）。
 */
private class EdgeTabDrawable(initialColor: Int, private val marginPx: Int) : Drawable() {

    private var accent = initialColor
    private var pressed = false
    private var onRight = true
    private var pulse = 0f
    private var glowLevel = 0f

    // 挂着识别卡片时握柄拉长，表示"签连着卡"
    var gripExtend = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    private val bodyPath = Path()
    private val glowPath = Path()
    private val bodyRect = RectF()
    private val glowRect = RectF()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setAccentColor(newColor: Int) {
        if (accent == newColor) return
        accent = newColor
        invalidateSelf()
    }

    fun setPressed(isPressed: Boolean) {
        if (pressed == isPressed) return
        pressed = isPressed
        invalidateSelf()
    }

    fun setOnRight(right: Boolean) {
        if (onRight == right) return
        onRight = right
        invalidateSelf()
    }

    fun setGlow(pulseValue: Float, level: Float) {
        pulse = pulseValue
        glowLevel = level
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val m = marginPx.toFloat()
        val tabW = w - 2f * m
        val tabH = h - 2f * m
        // 贴屏幕边的一侧不留 margin，签体直接压在边缘上
        bodyRect.set(
            if (onRight) w - tabW else 0f, m,
            if (onRight) w else tabW, h - m
        )

        // 识别光晕：沿签体轮廓向外扩散的红环（先画，垫在签体之下）
        if (glowLevel > 0.01f) {
            val expand = m * (0.3f + pulse * 0.95f)
            glowRect.set(
                bodyRect.left - expand, bodyRect.top - expand,
                bodyRect.right + expand, bodyRect.bottom + expand
            )
            val glowR = tabW * 0.5f + expand
            glowPath.reset()
            glowPath.addRoundRect(glowRect, cornerRadii(glowR, glowR, glowR * 0.6f), Path.Direction.CW)
            glowPaint.style = Paint.Style.STROKE
            glowPaint.strokeWidth = m * 0.45f
            glowPaint.color = accent
            glowPaint.alpha = (glowLevel * (1f - pulse) * 0.55f * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(glowPath, glowPaint)
        }

        // 签体：磨砂玻璃（内侧大圆角、贴边侧小圆角）
        bodyPath.reset()
        bodyPath.addRoundRect(bodyRect, cornerRadii(tabW * 0.5f, tabW * 0.5f, tabW * 0.3f), Path.Direction.CW)
        bodyPaint.shader = LinearGradient(
            bodyRect.centerX(), bodyRect.top, bodyRect.centerX(), bodyRect.bottom,
            0xE6FFFFFF.toInt(),
            0xB8EDEDEF.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bodyPath, bodyPaint)

        // 描边：中性、低透明度
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = (tabW * 0.04f).coerceAtLeast(1.2f)
        borderPaint.color = 0x33000000
        canvas.drawPath(bodyPath, borderPaint)

        // 握柄：居中竖条，颜色即状态
        val gripW = (tabW * 0.14f).coerceAtLeast(m * 0.2f)
        val gripH = tabH * (0.32f + 0.14f * gripExtend)
        gripPaint.color = accent
        canvas.drawRoundRect(
            bodyRect.centerX() - gripW / 2f, bodyRect.centerY() - gripH / 2f,
            bodyRect.centerX() + gripW / 2f, bodyRect.centerY() + gripH / 2f,
            gripW / 2f, gripW / 2f, gripPaint
        )

        // 按压：整体压一层淡黑（iOS 按钮手感）
        if (pressed) {
            pressPaint.color = 0x14000000
            canvas.drawPath(bodyPath, pressPaint)
        }
    }

    // 圆角：贴右缘时内侧在左（左上/左下大圆角）；贴左缘时镜像
    private fun cornerRadii(innerR: Float, innerRy: Float, outerR: Float): FloatArray =
        if (onRight) {
            floatArrayOf(innerR, innerRy, outerR, outerR, outerR, outerR, innerR, innerRy)
        } else {
            floatArrayOf(outerR, outerR, innerR, innerRy, innerR, innerRy, outerR, outerR)
        }

    // 提供签体轮廓（与 draw 中的 bodyRect 一致：贴边侧无 margin），elevation 投影才能渲染
    override fun getOutline(outline: Outline) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return
        val m = marginPx
        val tabW = w - 2 * m
        val left = if (onRight) w - tabW else 0
        outline.setRoundRect(left, m, left + tabW, h - m, tabW * 0.4f)
    }

    override fun setAlpha(alpha: Int) {}

    @Deprecated("Deprecated in Java")
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 边签状态：签体始终是磨砂玻璃，居中握柄颜色即状态——待机墨 / 识别红 + 呼吸光晕 / 成功绿 / 失败灰
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
        fun onSaveClick(title: String, priceCents: Long, ocrTitle: String)
        fun onReCaptureClick()
        fun onPriceChanged(priceCents: Long?)
    }

    // 单击=价格识别；双击=轮换跳转比价应用（一个都没设则回拼多多）；长按=跳转拼多多。
    // 由 GestureDetector 统一判定，避免 View 自带的 click/longClick 与拖拽触摸监听相互干扰
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        // 手动双击判定：系统默认 300ms 窗口在部分 ROM 上偏短，放宽到 500ms。
        // 单击延迟 500ms 派发识别，期间第二击到达则取消并跳转比价应用
        override fun onSingleTapUp(e: MotionEvent): Boolean {
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
            return true
        }

        // 快速双击（间隔 <300ms）系统走这里而不触发 onSingleTapUp，需单独接住
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetTapState()
            cancelAutoCollapse()
            launchNextCompareApp()
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

    // 边签相关
    private var ballWrap: FrameLayout? = null
    private var ballFace: View? = null
    private var ballFaceDrawable: EdgeTabDrawable? = null
    private var glowPulseAnimator: ValueAnimator? = null
    private var glowFadeAnimator: ValueAnimator? = null
    private var glowLevel = 0f
    private var glowPulse = 0f
    private var gripExtendAnimator: ValueAnimator? = null
    private var ballColorAnimator: ValueAnimator? = null
    private var currentBallColor = InkColor
    private var panelSlideAnimator: ValueAnimator? = null

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
    // 「跳转比价时复制标题」开启时，比较意图账本：desiredTitle（最新想要的标题）与
    // knownClipTitle（剪贴板里实际内容）不一致才经中转页复制——识别后首次双击复制一次，
    // 之后应用间轮换纯跳转；长按卡片复制过的标题既不会被覆盖也不会重弹
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
        if (CompareApps.launch(context, pkg)) {
            Toast.makeText(context, "比价 → $label", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "无法打开「$label」", Toast.LENGTH_SHORT).show()
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
            x = context.resources.displayMetrics.widthPixels - tabViewW()
            y = dp(160)
            restoreTabPosition(this)
        }

        overlayView = ball
        runCatching {
            windowManager?.addView(ball, overlayParams)
        }.onFailure {
            overlayView = null
            return
        }

        // 入场：从所在边缘"长出来"（贴哪条边就朝屏内滑入）
        val slideFrom = if (currentTabOnRight()) {
            dp(TAB_MARGIN_DP + TAB_W_DP).toFloat()
        } else {
            -dp(TAB_MARGIN_DP + TAB_W_DP).toFloat()
        }
        ball.alpha = 0f
        ball.translationX = slideFrom
        ball.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(360L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        updateTabSide()

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

        // 边签：签体在 view 内居中、四周留 margin 供光晕扩散
        val face = EdgeTabDrawable(currentBallColor, dp(TAB_MARGIN_DP))
        ballFaceDrawable = face
        ballFace = View(context).apply {
            background = face
            elevation = dp(6).toFloat()
        }
        ball.addView(ballFace, FrameLayout.LayoutParams(tabViewW(), tabViewH(), Gravity.CENTER))

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
                    if (withGestures) animateBallPressed(true)
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
                    if (withGestures) animateBallPressed(false)
                    if (moved) {
                        snapOverlayToNearestEdge()
                    }
                    withGestures || moved
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (withGestures) animateBallPressed(false)
                    withGestures
                }
                else -> withGestures
            }
        }
    }

    // 只移动球窗口；若面板窗口已挂载则同步跟随。备用球周期内不挂载，无需跟移——
    // 挂载时从 overlayParams 同步位置
    private fun moveOverlayTo(x: Int, y: Int) {
        val params = overlayParams ?: return
        params.x = clampOverlayX(x)
        params.y = clampOverlayY(y)
        runCatching { windowManager?.updateViewLayout(overlayView, params) }
        updateTabSide()
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

    private fun updateTabSide() {
        ballFaceDrawable?.setOnRight(currentTabOnRight())
    }

    // 面板窗口位置：识别卡片贴着边签朝屏幕内侧展开，垂直方向与签中心对齐（像挂在签上的活页）
    private fun computePanelPosition() {
        val tabP = overlayParams ?: return
        val panelP = panelParams ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val viewW = overlayView?.width?.takeIf { it > 0 } ?: tabViewW()
        val tabH = overlayView?.height?.takeIf { it > 0 } ?: tabViewH()
        val bodyW = dp(TAB_W_DP)
        val panelW = dp(PANEL_W_DP)
        val panelH = panelHeightEstimate()
        val gap = dp(3)

        // 以签体边缘（而非 view 边缘）为基准：view 贴边侧内还有空白，不能算进间距
        panelP.x = if (currentTabOnRight()) {
            tabP.x + viewW - bodyW - gap - panelW
        } else {
            tabP.x + bodyW + gap
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
            updateTabSide()
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
            animateGripExtend(1f)
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
        animateGripExtend(1f)

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
        animateGripExtend(0f)

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

    // ---- 边签状态 ----

    private fun setBallState(state: BallState) {
        when (state) {
            BallState.IDLE -> {
                stopGlowPulse()
                animateBallColor(InkColor)
            }
            BallState.SCANNING -> {
                animateBallColor(BrandRed)
                startGlowPulse()
            }
            BallState.SUCCESS -> {
                stopGlowPulse()
                animateBallColor(FreshGreen)
            }
            BallState.ERROR -> {
                stopGlowPulse()
                animateBallColor(TextSecondary)
            }
        }
    }

    private fun animateBallColor(target: Int) {
        val drawable = ballFaceDrawable ?: return
        if (currentBallColor == target) return
        ballColorAnimator?.cancel()
        ballColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentBallColor, target).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                currentBallColor = animator.animatedValue as Int
                drawable.setAccentColor(currentBallColor)
            }
            start()
        }
    }

    // 按压手感：按下缩到 0.94 + 高光点下移（光被压低），松手弹性回弹
    private fun animateBallPressed(isPressed: Boolean) {
        val wrap = ballWrap ?: return
        ballFaceDrawable?.setPressed(isPressed)
        wrap.animate().cancel()
        if (isPressed) {
            wrap.animate()
                .scaleX(0.94f).scaleY(0.94f)
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

    // 识别呼吸光晕：沿签体轮廓扩散的红环，直接画在签的 drawable 里
    private fun startGlowPulse() {
        val drawable = ballFaceDrawable ?: return
        glowPulseAnimator?.cancel()
        glowFadeAnimator?.cancel()
        glowPulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                glowPulse = animator.animatedValue as Float
                drawable.setGlow(glowPulse, glowLevel)
            }
            start()
        }
        glowFadeAnimator = ValueAnimator.ofFloat(glowLevel, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                glowLevel = animator.animatedValue as Float
                drawable.setGlow(glowPulse, glowLevel)
            }
            start()
        }
    }

    private fun stopGlowPulse() {
        val drawable = ballFaceDrawable ?: return
        glowPulseAnimator?.cancel()
        glowFadeAnimator?.cancel()
        glowFadeAnimator = ValueAnimator.ofFloat(glowLevel, 0f).apply {
            duration = 240L
            addUpdateListener { animator ->
                glowLevel = animator.animatedValue as Float
                drawable.setGlow(glowPulse, glowLevel)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator, isReverse: Boolean) {
                    if (glowLevel <= 0.01f) {
                        glowPulseAnimator?.cancel()
                        glowPulseAnimator = null
                    }
                }
            })
            start()
        }
    }

    // 握柄伸缩：卡片挂上时签的握柄拉长，表示"签连着卡"
    private fun animateGripExtend(target: Float) {
        val drawable = ballFaceDrawable ?: return
        gripExtendAnimator?.cancel()
        val start = drawable.gripExtend
        if (kotlin.math.abs(start - target) < 0.01f) return
        gripExtendAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                drawable.gripExtend = animator.animatedValue as Float
            }
            start()
        }
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

    // 边签 view 尺寸 = 签体 + 四周光晕 margin
    private fun tabViewW(): Int = dp(TAB_W_DP + TAB_MARGIN_DP * 2)
    private fun tabViewH(): Int = dp(TAB_H_DP + TAB_MARGIN_DP * 2)

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
        glowPulseAnimator?.cancel()
        glowFadeAnimator?.cancel()
        gripExtendAnimator?.cancel()
        panelSlideAnimator?.cancel()
        ballColorAnimator?.cancel()
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
        private const val BALL_RESTORE_MS = 1_500L
        private const val TAB_W_DP = 27
        private const val TAB_H_DP = 76
        private const val TAB_MARGIN_DP = 6
        private const val PANEL_W_DP = 300
    }
}
