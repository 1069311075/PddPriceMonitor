package com.example.pddpricemonitor.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.widget.Button
import android.widget.EditText
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
    private var resultPanel: LinearLayout? = null
    private var titleEdit: EditText? = null
    private var priceEdit: EditText? = null
    private var comparisonText: TextView? = null
    private var currentComparison: ProductPriceComparison? = null

    private val recognizer = TextRecognizerClient()
    private val parser = ProductTextParser()

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
        MonitorDebugState.update("Floating ball ready. Tap it on a product page.")
    }

    private fun captureOnce() {
        if (worker?.isActive == true) return
        val source = bitmapSource ?: return

        worker = scope.launch {
            updateOverlayStatus("OCR...", showPanel = false)
            val fullBitmap = source.acquireLatestBitmap()
            if (fullBitmap == null) {
                updateOverlayStatus("No frame", showPanel = false)
                scheduleStatusRestore()
                MonitorDebugState.update("No screen frame yet")
                return@launch
            }

            try {
                val text = recognizer.recognize(fullBitmap)
                val result = parser.parseWithReason(text, fullBitmap)
                val product = result.products.singleOrNull()
                if (product == null) {
                    updateOverlayStatus("No item", showPanel = false)
                    scheduleStatusRestore()
                    MonitorDebugState.update(
                        message = result.skippedReason ?: "No product detected",
                        textLength = text.text.length,
                        parsedProducts = result.products.size,
                        savedProducts = 0
                    )
                    return@launch
                }

                val comparison = repository?.findPriceComparison(product)
                showEditableResult(product, comparison)
                MonitorDebugState.update(
                    message = "Recognized. Edit then save from floating panel.",
                    textLength = text.text.length,
                    parsedProducts = 1,
                    savedProducts = 0
                )
            } catch (error: Throwable) {
                updateOverlayStatus("Error", showPanel = false)
                scheduleStatusRestore()
                MonitorDebugState.update("Capture/OCR error: ${error.javaClass.simpleName}")
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
            scope.launch { updateOverlayStatus("Check input", showPanel = true) }
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
            updateOverlayStatus("OCR", showPanel = false)
            MonitorDebugState.update(
                message = "Saved edited product",
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

        ballText = TextView(this).apply {
            text = "OCR"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = circleBackground(Color.rgb(233, 68, 79))
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
            setOnClickListener { captureOnce() }
        }

        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(Color.WHITE)
            elevation = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(292), WindowManager.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            setOnTouchListener { _, _ ->
                scheduleAutoCollapse()
                false
            }
        }

        titleEdit = EditText(this).apply {
            hint = "商品名称"
            minLines = 2
            maxLines = 3
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
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
        priceEdit = EditText(this).apply {
            hint = "价格，例如 185.05"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
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
        comparisonText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(31, 138, 112))
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(6))
        }

        val decimalButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        decimalButtons.addView(Button(this).apply {
            text = "/10"
            setTextColor(Color.rgb(31, 41, 51))
            setOnClickListener { shiftPriceDecimal(-1) }
        })
        decimalButtons.addView(Button(this).apply {
            text = "x10"
            setTextColor(Color.rgb(31, 41, 51))
            setOnClickListener { shiftPriceDecimal(1) }
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { saveEditedProduct() }
        })
        buttons.addView(Button(this).apply {
            text = "关闭"
            setOnClickListener {
                cancelAutoCollapse()
                scope.launch { updateOverlayStatus("OCR", showPanel = false) }
            }
        })

        resultPanel?.addView(titleEdit)
        resultPanel?.addView(priceEdit)
        resultPanel?.addView(comparisonText)
        resultPanel?.addView(decimalButtons)
        resultPanel?.addView(buttons)
        root.addView(ballText)
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
        ballText?.let { attachDrag(it) }
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
            updateOverlayStatus("OCR", showPanel = false)
        }
    }

    private fun scheduleStatusRestore() {
        cancelAutoCollapse()
        autoCollapseJob = scope.launch {
            delay(AUTO_COLLAPSE_MS)
            updateOverlayStatus("OCR", showPanel = false)
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
            ballText?.text = formatPrice(product.priceCents)
            currentComparison = comparison
            titleEdit?.setText(product.title)
            priceEdit?.setText(formatPlainPrice(product.priceCents))
            updateComparisonText(product.priceCents)
            resultPanel?.visibility = View.VISIBLE
            overlayView?.post { keepOverlayInsideGestureArea() }
        }
        scheduleAutoCollapse()
    }

    private fun formatComparison(currentPriceCents: Long, comparison: ProductPriceComparison?): String {
        comparison ?: return ""
        val diff = currentPriceCents - comparison.previousLowestCents
        val lowest = formatPlainPrice(comparison.previousLowestCents)
        val previous = formatPlainPrice(comparison.previousPriceCents)
        return when {
            diff < 0 -> "历史最低 ¥$lowest，本次低 ¥${formatPlainPrice(-diff)}"
            diff == 0L -> "历史最低 ¥$lowest，本次等于历史最低"
            else -> "历史最低 ¥$lowest，本次高 ¥${formatPlainPrice(diff)}"
        } + "；上次价 ¥$previous"
    }
    private fun updateComparisonText(currentPriceCents: Long?) {
        val textValue = currentPriceCents?.let { formatComparison(it, currentComparison) }.orEmpty()
        comparisonText?.text = textValue
        comparisonText?.visibility = if (textValue.isBlank()) View.GONE else View.VISIBLE
    }

    private suspend fun updateOverlayStatus(text: String, showPanel: Boolean) {
        withContext(Dispatchers.Main) {
            val wasPanelVisible = resultPanel?.visibility == View.VISIBLE
            ballText?.text = text
            resultPanel?.visibility = if (showPanel) View.VISIBLE else View.GONE
            setOverlayEditingMode(showPanel)
            if (wasPanelVisible && !showPanel) {
                overlayView?.post { restoreCollapsedOverlayPosition() }
            }
        }
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

    private fun shiftPriceDecimal(direction: Int) {
        val current = parsePriceCents(priceEdit?.text?.toString().orEmpty()) ?: return
        val shifted = when {
            direction < 0 -> (current / 10).coerceAtLeast(1L)
            direction > 0 -> current * 10
            else -> current
        }.takeIf { it in 1..999_999_00 } ?: return

        priceEdit?.setText(formatPlainPrice(shifted))
        priceEdit?.setSelection(priceEdit?.text?.length ?: 0)
        scheduleAutoCollapse()
    }

    private fun formatPrice(cents: Long): String =
        "¥${formatPlainPrice(cents)}"

    private fun formatPlainPrice(cents: Long): String =
        "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun circleBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun roundedBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
            setStroke(dp(1), Color.rgb(232, 236, 234))
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
        private const val AUTO_COLLAPSE_MS = 5_000L

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }
}
