package com.example.pddpricemonitor.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.pddpricemonitor.R
import com.example.pddpricemonitor.data.ScreenshotStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 屏幕捕获前台服务 —— 重构后作为轻薄协调层：
 * - 管理 Service 生命周期（前台通知、启停）
 * - 协调 MediaProjectionController（屏幕录制）
 * - 协调 FloatingOverlayController（悬浮窗 UI）
 * - 协调 OcrCaptureInteractor（OCR 业务逻辑）
 *
 * 具体的 UI 绘制、动画、业务逻辑都下沉到独立类中，
 * Service 只负责把它们串联起来并管理生命周期。
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject
    lateinit var ocrInteractor: OcrCaptureInteractor

    @Inject
    lateinit var screenshotStore: ScreenshotStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null

    private var projectionController: MediaProjectionController? = null
    private var overlayController: FloatingOverlayController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification().build())

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY

        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    // 用户从最近任务列表划掉应用时，立即停止前台服务并释放屏幕投射
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        // 已在运行：只确保悬浮球显示
        if (projectionController?.isStarted == true) {
            if (overlayController?.isShowing != true) {
                getOverlayController().show()
            }
            isRunning = true
            return
        }

        val controller = getProjectionController()
        controller.start(resultCode, data)

        getOverlayController().show()
        MonitorDebugState.update("悬浮球已就绪，去商品页点它")
        isRunning = true
    }

    private fun captureOnce() {
        if (worker?.isActive == true) return
        val controller = projectionController ?: return

        worker = scope.launch {
            overlayController?.updateState(BallState.SCANNING, showPanel = false)

            val result = ocrInteractor.captureOnce(
                bitmapProvider = { controller.acquireBitmap() },
                onScreenshot = if (screenshotStore.isEnabled()) {
                    { bitmap -> screenshotStore.savePending(bitmap) }
                } else null
            )

            when (result) {
                is OcrCaptureInteractor.CaptureResult.Success -> {
                    // 识别后自动保存（默认关）：识别即入库（历史行打 autoSaved 标），
                    // 面板转为「回执」——对了不用管，错了点「修改保存」或「重新识别」。
                    // 入库失败（id≤0）时按普通模式弹面板，用户手动点保存兜底
                    val autoSavedId = if (autoSaveEnabled()) {
                        val historyId = ocrInteractor.saveManualProduct(
                            result.product,
                            autoSaved = true
                        )
                        if (historyId > 0) screenshotStore.commitFor(historyId)
                        historyId.takeIf { it > 0 }
                    } else null
                    // 登记待确认行：面板「修改保存/重新识别」时撤回，用户置之不理则就此落账
                    pendingAutoSavedHistoryId = autoSavedId
                    overlayController?.showEditableResult(
                        title = result.product.title,
                        priceCents = result.product.priceCents,
                        comparison = result.comparison,
                        autoSaved = autoSavedId != null
                    )
                    MonitorDebugState.update(
                        message = if (autoSavedId != null) "已自动保存，核对结果" else "识别成功，核对后点保存",
                        textLength = 0,
                        parsedProducts = 1,
                        savedProducts = if (autoSavedId != null) 1 else 0
                    )
                }
                is OcrCaptureInteractor.CaptureResult.NoProduct -> {
                    overlayController?.updateState(BallState.ERROR, showPanel = false)
                    overlayController?.scheduleStatusRestore()
                    MonitorDebugState.update(
                        message = result.reason,
                        textLength = result.textLength,
                        parsedProducts = result.parsedCount,
                        savedProducts = 0
                    )
                }
                is OcrCaptureInteractor.CaptureResult.Error -> {
                    overlayController?.updateState(BallState.ERROR, showPanel = false)
                    overlayController?.scheduleStatusRestore()
                    MonitorDebugState.update("识别出错：${result.throwable.javaClass.simpleName}")
                }
                is OcrCaptureInteractor.CaptureResult.NoFrame -> {
                    overlayController?.updateState(BallState.ERROR, showPanel = false)
                    overlayController?.scheduleStatusRestore()
                    MonitorDebugState.update("还没有画面，稍后再试")
                }
            }
        }
    }

    private fun autoSaveEnabled(): Boolean = CapturePrefs.isAutoSaveEnabled(this)

    // 最近一次「识别后自动保存」、尚未经用户确认的历史行 id：
    // 面板「修改保存」撤回该行再按面板值重新入库；「重新识别」视为本次作废，同样撤回
    private var pendingAutoSavedHistoryId: Long? = null

    private fun discardPendingAutoSave() {
        val oldId = pendingAutoSavedHistoryId ?: return
        pendingAutoSavedHistoryId = null
        scope.launch { ocrInteractor.deleteHistoryEntry(oldId) }
    }

    private fun saveEditedProduct(title: String, priceCents: Long, ocrTitle: String) {
        val product = ocrInteractor.createDetectedProduct(title, priceCents, ocrTitle)
        scope.launch {
            // 回执模式的「修改保存」：自动保存的行还没被用户确认——先撤回
            // （截图退回暂存位、行删除、连带空壳商品清理与同步墓碑），再按面板值
            // 重新入库，否则识别错值会留在账里，纠错就名不副实了
            pendingAutoSavedHistoryId?.let { oldId ->
                pendingAutoSavedHistoryId = null
                screenshotStore.revertToPending(oldId)
                ocrInteractor.deleteHistoryEntry(oldId)
            }
            val historyId = ocrInteractor.saveManualProduct(product)
            // 把识别瞬间的暂存截图归档为该条记录的存档图（开关关闭时 pending 不存在，静默跳过）
            if (historyId > 0) screenshotStore.commitFor(historyId)
            overlayController?.updateState(BallState.SUCCESS, showPanel = false)
            overlayController?.scheduleStatusRestore()
            MonitorDebugState.update(
                message = "已保存",
                parsedProducts = 1,
                savedProducts = if (historyId > 0) 1 else 0
            )
        }
    }

    // ---- 控制器懒加载 ----

    private fun getProjectionController(): MediaProjectionController {
        if (projectionController == null) {
            projectionController = MediaProjectionController(this) {
                // MediaProjection 被系统停止时，停止服务
                stopSelf()
            }
        }
        return projectionController!!
    }

    private fun getOverlayController(): FloatingOverlayController {
        if (overlayController == null) {
            overlayController = FloatingOverlayController(
                context = this,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                callbacks = object : FloatingOverlayController.Callbacks {
                    override fun onCaptureClick() {
                        captureOnce()
                    }

                    override fun onSaveClick(title: String, priceCents: Long, ocrTitle: String) {
                        saveEditedProduct(title, priceCents, ocrTitle)
                    }

                    override fun onReCaptureClick() {
                        discardPendingAutoSave()
                        captureOnce()
                    }

                    override fun onPriceChanged(priceCents: Long?) {
                        // 价格变化时对比条已在 FloatingOverlayController 内部更新
                        // 这里预留扩展点
                    }
                }
            )
        }
        return overlayController!!
    }

    // ---- 通知 ----

    private fun createNotification(): NotificationCompat.Builder {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.capture_notification_stop), stopPendingIntent)
    }

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
        isRunning = false
        worker?.cancel()
        overlayController?.destroy()
        overlayController = null
        projectionController?.release()
        projectionController = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // 悬浮球是否在运行：主界面据此跳过重复的系统录屏授权弹窗
        var isRunning = false
            private set

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
