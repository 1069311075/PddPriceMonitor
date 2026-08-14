package com.example.pddpricemonitor.capture

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.pddpricemonitor.PddMonitorApp
import com.example.pddpricemonitor.R
import com.example.pddpricemonitor.data.ProductPriceComparison
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.ocr.DetectedProduct
import com.example.pddpricemonitor.ocr.ProductTextParser
import com.example.pddpricemonitor.ocr.TextRecognizerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 「清爽红」设计系统：与主界面保持一致，取拼多多品牌红做记忆点
private val BrandRed = Color.parseColor("#E02E24")
private val BrandRedSoft = Color.parseColor("#FFF1F0")
private val FreshGreen = Color.parseColor("#1DC981")
private val SoftGreen = Color.parseColor("#E9F9F1")
private val CardWhite = Color.WHITE
private val TextPrimary = Color.parseColor("#1A1A1A")
private val TextSecondary = Color.parseColor("#8A8F99")
private val HairlineBorder = Color.parseColor("#1A1A1A1A")
private val PanelBackground = Color.parseColor("#F5F5F7")

class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var autoCollapseJob: Job? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bitmapSource: ImageReaderBitmapSource? = null
    private var repository: ProductRepository? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var collapsedOverlayX: Int? = null
    private var collapsedOverlayY: Int? = null
    private var ballText: TextView? = null
    private var ballRing: View? = null
    private var ringAnimator: ValueAnimator? = null
    private var resultPanel: LinearLayout? = null
    private var titleEdit: EditText? = null
    private var priceEdit: EditText? = null
    private var comparisonStrip: LinearLayout? = null
    private var comparisonDot: View? = null
    private var comparisonTitle: TextView? = null
    private var comparisonHint: TextView? = null
    private var currentComparison: ProductPriceComparison? = null

    private val recognizer = TextRecognizerClient()
    private val parser = ProductTextParser()

    // 悬浮球三态：待机红球 / 识别中脉冲光圈 / 成功绿勾（出错为灰感叹号）
    private enum class BallState { IDLE, SCANNING, SUCCESS, ERROR }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY

        startProjection(resultCode, data)
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) {
            if (overlayView == null) {
                showFloatingBall()
            }
            return
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val manager = getSystemService(MediaProjectionManager::class.java)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        projection = manager.getMediaProjection(resultCode, data).also { mediaProjection ->
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, null)
        }
        virtualDisplay = projection?.createVirtualDisplay(
            "pdd-price-monitor",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        bitmapSource = ImageReaderBitmapSource(requireNotNull(imageReader))
        repository = ProductRepository((application as PddMonitorApp).database.productPriceDao())
        showFloatingBall()
        MonitorDebugState.update("悬浮球已就绪，去商品页点它")
    }

    private fun captureOnce() {
        if (worker?.isActive == true) return
        val source = bitmapSource ?: return

        worker = scope.launch {
            updateOverlayStatus(BallState.SCANNING, showPanel = false)
            val fullBitmap = source.acquireLatestBitmap()
            if (fullBitmap == null) {
                updateOverlayStatus(BallState.ERROR, showPanel = false)
                scheduleStatusRestore()
                MonitorDebugState.update("还没有画面，稍后再试")
                return@launch
            }

            try {
                val text = recognizer.recognize(fullBitmap)
                val result = parser.parseWithReason(text, fullBitmap)
                val product = result.products.singleOrNull()
                if (product == null) {
                    updateOverlayStatus(BallState.ERROR, showPanel = false)
                    scheduleStatusRestore()
                    MonitorDebugState.update(
                        message = result.skippedReason ?: "没有识别到商品",
                        textLength = text.text.length,
                        parsedProducts = result.products.size,
                        savedProducts = 0
                    )
                    return@launch
                }

                val comparison = repository?.findPriceComparison(product)
                showEditableResult(product, comparison)
                MonitorDebugState.update(
                    message = "识别成功，核对后点保存",
                    textLength = text.text.length,
                    parsedProducts = 1,
                    savedProducts = 0
                )
            } catch (error: Throwable) {
                updateOverlayStatus(BallState.ERROR, showPanel = false)
                scheduleStatusRestore()
                MonitorDebugState.update("识别出错：${error.javaClass.simpleName}")
            } finally {
                fullBitmap.recycle()
            }
        }
    }

    private fun saveEditedProduct() {
        val repo = repository ?: return
        val title = titleEdit?.text?.toString()?.trim().orEmpty()
        val priceCents = parsePriceCents(priceEdit?.text?.toString().orEmpty())
        if (title.isBlank() || priceCents == null) {
            scope.launch { updateOverlayStatus(BallState.ERROR, showPanel = true) }
            return
        }

        val product = DetectedProduct(
            title = title,
            normalizedTitle = parser.normalizeTitle(title),
            priceCents = priceCents,
            rawText = "manual overlay"
        )

        scope.launch {
            cancelAutoCollapse()
            val saved = repo.saveManualProduct(product)
            updateOverlayStatus(BallState.SUCCESS, showPanel = false)
            scheduleStatusRestore()
            MonitorDebugState.update(
                message = "已保存",
                parsedProducts = 1,
                savedProducts = saved
            )
        }
    }

    private fun showFloatingBall() {
        if (!Settings.canDrawOverlays(this) || overlayView != null) return

        windowManager = getSystemService(WindowManager::class.java)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        // 悬浮球：待机红球 / 识别中脉冲光圈 / 成功绿勾
        val ballWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            setOnClickListener { captureOnce() }
        }
        ballRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), BrandRed)
            }
            alpha = 0f
        }
        ballWrap.addView(ballRing, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        ballText = TextView(this).apply {
            text = "¥"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = circleBackground(BrandRed)
            elevation = dp(6).toFloat()
        }
        ballWrap.addView(ballText, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(CardWhite)
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            setOnTouchListener { _, _ ->
                scheduleAutoCollapse()
                false
            }
        }

        // 顶部：眉题 + 关闭
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "识别结果"
            textSize = 12f
            setTextColor(TextSecondary)
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 18f
            setTextColor(TextSecondary)
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener {
                cancelAutoCollapse()
                scope.launch { updateOverlayStatus(BallState.IDLE, showPanel = false) }
            }
        })

        // 商品标题：下划线式输入，更轻
        titleEdit = EditText(this).apply {
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
        val titleDivider = View(this).apply {
            setBackgroundColor(HairlineBorder)
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(6) }
        }

        // 价格：大号红色数字，视觉锚点
        val priceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        priceRow.addView(TextView(this).apply {
            text = "¥"
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(BrandRed)
        })
        priceEdit = EditText(this).apply {
            hint = "0.00"
            textSize = 26f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(BrandRed)
            setHintTextColor(Color.parseColor("#F3B8B4"))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
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
                    updateComparisonText(parsePriceCents(s?.toString().orEmpty()))
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        priceRow.addView(priceEdit)

        // 小数位修正：OCR 常把 185.05 读成 18.505 或 1850.5，一键 /10 或 x10
        val decimalGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, dp(2))
        }
        decimalGroup.addView(TextView(this).apply {
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
        decimalGroup.addView(TextView(this).apply {
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
        val priceHint = TextView(this).apply {
            text = "点数字可修改 · 识别不准可用 /10 x10 修正"
            textSize = 11f
            setTextColor(TextSecondary)
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        // 对比条：一眼看懂值不值得买
        comparisonStrip = LinearLayout(this).apply {
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
        comparisonDot = View(this).apply {
            background = circleBackground(FreshGreen)
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
        }
        comparisonTitle = TextView(this).apply {
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(TextPrimary)
            setPadding(dp(8), 0, dp(6), 0)
        }
        comparisonHint = TextView(this).apply {
            textSize = 12f
            setTextColor(TextSecondary)
        }
        comparisonStrip?.addView(comparisonDot)
        comparisonStrip?.addView(comparisonTitle)
        comparisonStrip?.addView(comparisonHint)

        // 按钮：保存（红填充）+ 重新识别（描边）
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        buttons.addView(TextView(this).apply {
            text = "保存"
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            background = roundedBackground(BrandRed, radiusDp = 10, withStroke = false)
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveEditedProduct() }
        })
        buttons.addView(TextView(this).apply {
            text = "重新识别"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(TextSecondary)
            background = roundedBackground(Color.TRANSPARENT, radiusDp = 10, withStroke = true)
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                cancelAutoCollapse()
                scope.launch { updateOverlayStatus(BallState.IDLE, showPanel = false) }
                captureOnce()
            }
        })

        resultPanel?.addView(header)
        resultPanel?.addView(titleEdit)
        resultPanel?.addView(titleDivider)
        resultPanel?.addView(priceRow)
        resultPanel?.addView(priceHint)
        resultPanel?.addView(comparisonStrip)
        resultPanel?.addView(buttons)
        root.addView(ballWrap)
        root.addView(resultPanel)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - dp(96)).coerceAtLeast(dp(24))
            y = dp(220)
        }

        attachDrag(root)
        attachDrag(ballWrap)
        overlayView = root
        runCatching {
            windowManager?.addView(root, overlayParams)
        }.onFailure { error ->
            overlayView = null
            MonitorDebugState.update("悬浮球创建失败：${error.javaClass.simpleName}")
        }
    }

    private fun attachDrag(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.action) {
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
                        params.x = clampOverlayX(startX + dx)
                        params.y = clampOverlayY(startY + dy)
                        windowManager?.updateViewLayout(overlayView, params)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapOverlayToNearestEdge()
                    }
                    moved
                }
                else -> false
            }
        }
    }

    private fun clampOverlayX(x: Int): Int {
        val edgeInset = dp(24)
        val overlayWidth = overlayView?.width?.takeIf { it > 0 } ?: dp(96)
        val maxX = (resources.displayMetrics.widthPixels - overlayWidth - edgeInset).coerceAtLeast(edgeInset)
        return x.coerceIn(edgeInset, maxX)
    }

    private fun clampOverlayY(y: Int): Int {
        val topInset = dp(24)
        val bottomInset = dp(24)
        val overlayHeight = overlayView?.height?.takeIf { it > 0 } ?: dp(96)
        val maxY = (resources.displayMetrics.heightPixels - overlayHeight - bottomInset).coerceAtLeast(topInset)
        return y.coerceIn(topInset, maxY)
    }

    private fun snapOverlayToNearestEdge() {
        val params = overlayParams ?: return
        overlayView?.post {
            val width = resources.displayMetrics.widthPixels
            val overlayWidth = overlayView?.width?.takeIf { it > 0 } ?: dp(96)
            val edgeInset = dp(24)
            val leftX = edgeInset
            val rightX = (width - overlayWidth - edgeInset).coerceAtLeast(edgeInset)
            val centerX = params.x + overlayWidth / 2
            params.x = if (centerX < width / 2) leftX else rightX
            params.y = clampOverlayY(params.y)
            if (resultPanel?.visibility != View.VISIBLE) {
                rememberCollapsedOverlayPosition()
            }
            runCatching { windowManager?.updateViewLayout(overlayView, params) }
        }
    }

    private fun keepOverlayInsideGestureArea() {
        val params = overlayParams ?: return
        params.x = clampOverlayX(params.x)
        params.y = clampOverlayY(params.y)
        runCatching { windowManager?.updateViewLayout(overlayView, params) }
    }

    private fun rememberCollapsedOverlayPosition() {
        val params = overlayParams ?: return
        collapsedOverlayX = params.x
        collapsedOverlayY = params.y
    }

    private fun restoreCollapsedOverlayPosition() {
        val params = overlayParams ?: return
        val x = collapsedOverlayX
        val y = collapsedOverlayY
        if (x != null && y != null) {
            params.x = clampOverlayX(x)
            params.y = clampOverlayY(y)
            runCatching { windowManager?.updateViewLayout(overlayView, params) }
        }
        collapsedOverlayX = null
        collapsedOverlayY = null
    }

    private fun scheduleAutoCollapse() {
        cancelAutoCollapse()
        autoCollapseJob = scope.launch {
            delay(AUTO_COLLAPSE_MS)
            updateOverlayStatus(BallState.IDLE, showPanel = false)
        }
    }

    private fun scheduleStatusRestore() {
        cancelAutoCollapse()
        autoCollapseJob = scope.launch {
            delay(BALL_RESTORE_MS)
            updateOverlayStatus(BallState.IDLE, showPanel = false)
        }
    }

    private fun cancelAutoCollapse() {
        autoCollapseJob?.cancel()
        autoCollapseJob = null
    }

    private suspend fun showEditableResult(
        product: DetectedProduct,
        comparison: ProductPriceComparison?
    ) {
        withContext(Dispatchers.Main) {
            if (resultPanel?.visibility != View.VISIBLE) {
                rememberCollapsedOverlayPosition()
            }
            setOverlayEditingMode(true)
            setBallState(BallState.SUCCESS)
            currentComparison = comparison
            titleEdit?.setText(product.title)
            priceEdit?.setText(formatPlainPrice(product.priceCents))
            updateComparisonText(product.priceCents)
            resultPanel?.visibility = View.VISIBLE
            overlayView?.post { keepOverlayInsideGestureArea() }
        }
        scheduleAutoCollapse()
    }

    // 对比条：绿 = 值得买（低于/持平历史最低），红 = 可以再等等
    private fun updateComparisonText(currentPriceCents: Long?) {
        val strip = comparisonStrip ?: return
        val current = currentPriceCents
        if (current == null) {
            strip.visibility = View.GONE
            return
        }
        strip.visibility = View.VISIBLE
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

    private fun applyComparisonStyle(backgroundColor: Int, dotColor: Int) {
        comparisonStrip?.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(backgroundColor)
        }
        comparisonDot?.background = circleBackground(dotColor)
    }

    private suspend fun updateOverlayStatus(state: BallState, showPanel: Boolean) {
        withContext(Dispatchers.Main) {
            val wasPanelVisible = resultPanel?.visibility == View.VISIBLE
            setBallState(state)
            resultPanel?.visibility = if (showPanel) View.VISIBLE else View.GONE
            setOverlayEditingMode(showPanel)
            if (wasPanelVisible && !showPanel) {
                overlayView?.post { restoreCollapsedOverlayPosition() }
            }
        }
    }

    private fun setBallState(state: BallState) {
        val label = ballText ?: return
        when (state) {
            BallState.IDLE -> {
                stopRingPulse()
                label.text = "¥"
                label.background = circleBackground(BrandRed)
            }
            BallState.SCANNING -> {
                label.text = "¥"
                label.background = circleBackground(BrandRed)
                startRingPulse()
            }
            BallState.SUCCESS -> {
                stopRingPulse()
                label.text = "✓"
                label.background = circleBackground(FreshGreen)
            }
            BallState.ERROR -> {
                stopRingPulse()
                label.text = "!"
                label.background = circleBackground(TextSecondary)
            }
        }
    }

    // 识别中：光圈脉冲，1.3s 一次，与呼吸感动效同一量级
    private fun startRingPulse() {
        val ring = ballRing ?: return
        stopRingPulse()
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_300L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val scale = 0.78f + progress * 0.4f
                ring.scaleX = scale
                ring.scaleY = scale
                ring.alpha = (1f - progress) * 0.65f
            }
            start()
        }
    }

    private fun stopRingPulse() {
        ringAnimator?.cancel()
        ringAnimator = null
        ballRing?.alpha = 0f
    }

    private fun setOverlayEditingMode(enabled: Boolean) {
        val params = overlayParams ?: return
        val view = overlayView ?: return
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
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun showKeyboard(view: View) {
        view.post {
            view.requestFocus()
            val inputManager = getSystemService(InputMethodManager::class.java)
            inputManager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val view = overlayView ?: return
        val inputManager = getSystemService(InputMethodManager::class.java)
        inputManager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

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
        scheduleAutoCollapse()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun circleBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun roundedBackground(color: Int, radiusDp: Int = 20, withStroke: Boolean = true): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (withStroke) setStroke(dp(1), HairlineBorder)
        }

    private fun createNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Price monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        worker?.cancel()
        cancelAutoCollapse()
        stopRingPulse()
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        virtualDisplay?.release()
        projection?.stop()
        imageReader?.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.pddpricemonitor.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val AUTO_COLLAPSE_MS = 8_000L
        private const val BALL_RESTORE_MS = 1_500L

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }
}
