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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.pddpricemonitor.PddMonitorApp
import com.example.pddpricemonitor.R
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.ocr.DetectedProduct
import com.example.pddpricemonitor.ocr.ProductTextParser
import com.example.pddpricemonitor.ocr.TextRecognizerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bitmapSource: ImageReaderBitmapSource? = null
    private var repository: ProductRepository? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var ballText: TextView? = null
    private var resultPanel: LinearLayout? = null
    private var titleEdit: EditText? = null
    private var priceEdit: EditText? = null

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
        if (projection != null) return

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
                MonitorDebugState.update("No screen frame yet")
                return@launch
            }

            try {
                val text = recognizer.recognize(fullBitmap)
                val result = parser.parseWithReason(text, fullBitmap)
                val product = result.products.singleOrNull()
                if (product == null) {
                    updateOverlayStatus("No item", showPanel = false)
                    MonitorDebugState.update(
                        message = result.skippedReason ?: "No product detected",
                        textLength = text.text.length,
                        parsedProducts = result.products.size,
                        savedProducts = 0
                    )
                    return@launch
                }

                showEditableResult(product)
                MonitorDebugState.update(
                    message = "Recognized. Edit then save from floating panel.",
                    textLength = text.text.length,
                    parsedProducts = 1,
                    savedProducts = 0
                )
            } catch (error: Throwable) {
                updateOverlayStatus("Error", showPanel = false)
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
            val saved = repo.saveManualProduct(product)
            updateOverlayStatus(formatPrice(priceCents), showPanel = false)
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
            background = circleBackground(Color.rgb(194, 38, 45))
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            setOnClickListener { captureOnce() }
        }

        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(290), WindowManager.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }

        titleEdit = EditText(this).apply {
            hint = "Product title"
            minLines = 2
            maxLines = 3
            textSize = 13f
        }
        priceEdit = EditText(this).apply {
            hint = "Price, e.g. 185.05"
            textSize = 16f
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { saveEditedProduct() }
        })
        buttons.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { scope.launch { updateOverlayStatus("OCR", showPanel = false) } }
        })

        resultPanel?.addView(titleEdit)
        resultPanel?.addView(priceEdit)
        resultPanel?.addView(buttons)
        root.addView(ballText)
        root.addView(resultPanel)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(220)
        }

        attachDrag(root)
        ballText?.let { attachDrag(it) }
        overlayView = root
        windowManager?.addView(root, overlayParams)
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
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> moved
                else -> false
            }
        }
    }

    private suspend fun showEditableResult(product: DetectedProduct) {
        withContext(Dispatchers.Main) {
            ballText?.text = formatPrice(product.priceCents)
            titleEdit?.setText(product.title)
            priceEdit?.setText(formatPlainPrice(product.priceCents))
            resultPanel?.visibility = View.VISIBLE
        }
    }

    private suspend fun updateOverlayStatus(text: String, showPanel: Boolean) {
        withContext(Dispatchers.Main) {
            ballText?.text = text
            resultPanel?.visibility = if (showPanel) View.VISIBLE else View.GONE
        }
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

    private fun formatPrice(cents: Long): String =
        "CNY ${formatPlainPrice(cents)}"

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
            cornerRadius = dp(12).toFloat()
            setColor(color)
            setStroke(dp(1), Color.rgb(210, 210, 210))
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

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }
}
