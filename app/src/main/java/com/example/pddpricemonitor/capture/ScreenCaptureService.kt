package com.example.pddpricemonitor.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
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
import kotlinx.coroutines.withContext
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

    // 下段按钮的截图存档：独立目录、独立记录，不碰 OCR 附表与价格库
    @Inject
    lateinit var hdCaptureInteractor: HdCaptureInteractor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var hdWorker: Job? = null

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
        // 已在运行：只确保悬浮球显示（球可能已被系统摘过，controller 自愈后回到这里兜底）
        if (projectionController?.isStarted == true) {
            if (overlayController?.isShowing != true && !getOverlayController().show()) {
                stopSelf()
                return
            }
            isRunning = true
            return
        }

        val controller = getProjectionController()
        // 投射 token 失效时 start 返回 null（异常已在内部兜底清理）——别亮悬浮球，直接停服务
        if (controller.start(resultCode, data) == null) {
            MonitorDebugState.update("屏幕投射启动失败，请重新授权")
            stopSelf()
            return
        }

        if (!getOverlayController().show()) {
            MonitorDebugState.update("悬浮窗启动失败，请检查悬浮窗权限")
            stopSelf()
            return
        }
        MonitorDebugState.update("悬浮球已就绪，去商品页点它")
        isRunning = true
    }

    private fun captureOnce() {
        if (worker?.isActive == true) return
        // 截图进行中（正在隐藏控件取新帧）：不与其抢窗口和帧，等服务侧任务收尾
        if (hdWorker?.isActive == true) return
        val controller = projectionController ?: return
        // 结果面板还开着时再点球 = 「重新识别」语义：上一条未确认的自动保存视为作废；
        // 面板已自动收起则属「隐式落账」后的新一轮识别，不动旧数据。
        // 主线程读面板可见性后传入；撤回在 worker 内同步完成再开拍，避免与新一轮入库交错
        val abandonPending = overlayController?.isPanelShowing == true

        worker = scope.launch {
            if (abandonPending) {
                pendingAutoSavedHistoryId?.let { oldId ->
                    pendingAutoSavedHistoryId = null
                    ocrInteractor.deleteHistoryEntry(oldId)
                }
            }
            overlayController?.updateState(BallState.SCANNING, showPanel = false)

            val result = ocrInteractor.captureOnce(
                bitmapProvider = { controller.acquireBitmap() },
                onScreenshot = if (screenshotStore.isEnabled()) {
                    { bitmap, crop -> screenshotStore.savePending(bitmap, crop) }
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

    /**
     * 下段按钮：一键保存当前画面截图（长边压到上限、JPEG q85）。
     * 与 OCR 完全解耦——不识别、不写价格库、不碰待确认自动保存行，也不受「保存识别截图」开关影响。
     * 流程：隐藏本软件悬浮控件与结果面板 → 取一张隐藏之后的新帧 → 缩放编码落独立目录 → 恢复控件。
     */
    private fun captureHdOnce() {
        if (hdWorker?.isActive == true) return
        val controller = projectionController
        if (controller == null) {
            notifyUser("录屏服务未启动，请先点悬浮球启动")
            return
        }
        // 与 OCR 识别共用采集互斥：识别中不抢帧、不抢窗口隐藏
        if (worker?.isActive == true) {
            notifyUser("正在识别，稍后再截图")
            return
        }

        hdWorker = scope.launch {
            // 基准时间戳与隐藏动作取在同一主线程帧：先取时间再隐藏，
            // 「隐藏控件触发的新帧」时间戳必晚于基准，旧帧会被 acquireFreshFrame 过滤掉
            val sinceNanos = withContext(Dispatchers.Main) {
                val stamp = System.nanoTime()
                overlayController?.hideForCapture()
                stamp
            }
            val result = try {
                hdCaptureInteractor.capture(sinceNanos) { baseline ->
                    controller.acquireFreshFrame(baseline)
                }
            } catch (error: Throwable) {
                HdCaptureInteractor.Result.Failure("截图失败：${error.javaClass.simpleName}")
            } finally {
                // 成功、失败、取消都在 finally 恢复控件：面板原样回来并重新计时自动收起
                withContext(Dispatchers.Main) { overlayController?.restoreAfterCapture() }
            }

            val message = when (result) {
                is HdCaptureInteractor.Result.Success -> "截图已保存"
                is HdCaptureInteractor.Result.Failure -> result.reason
            }
            MonitorDebugState.update(message)
            withContext(Dispatchers.Main) { notifyUser(message) }
        }
    }

    /**
     * 用户反馈提示：优先走自有悬浮窗气泡。MIUI/HyperOS 会因应用通知权限静默抑制文本 Toast
     * （logcat: Suppressing toast from package ... by user request），只靠 Toast 等于不提示；
     * 悬浮窗不可用（未授权/已摘）时才退回系统 Toast 兜底。必须主线程调用。
     */
    private fun notifyUser(message: String) {
        val overlay = overlayController
        if (overlay != null && overlay.isShowing) {
            overlay.showHint(message)
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun autoSaveEnabled(): Boolean = CapturePrefs.isAutoSaveEnabled(this)

    // 最近一次「识别后自动保存」、尚未经用户确认的历史行 id：
    // 面板「修改保存」撤回该行再按面板值重新入库；「重新识别」（面板按钮，或面板
    // 开着时再点球）视为本次作废，同样撤回；面板自动收起则隐式落账，被下一次识别顶替
    private var pendingAutoSavedHistoryId: Long? = null

    // 保存按钮防抖：双击「保存」会开两个协程重复入库；回执模式双击「修改保存」更糟——
    // 第一击撤回自动保存行并入库，第二击 pending 已空再独立入库一条，错值对值并存
    private var saveJob: Job? = null

    private fun saveEditedProduct(title: String, priceCents: Long, ocrTitle: String) {
        if (saveJob?.isActive == true) return
        val product = ocrInteractor.createDetectedProduct(title, priceCents, ocrTitle)
        saveJob = scope.launch {
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

                    override fun onHdCaptureClick() {
                        captureHdOnce()
                    }

                    override fun onSaveClick(title: String, priceCents: Long, ocrTitle: String) {
                        saveEditedProduct(title, priceCents, ocrTitle)
                    }

                    override fun onReCaptureClick() {
                        // 面板按钮触发时面板必然展示中，captureOnce 内部即完成撤回
                        captureOnce()
                    }

                    override fun onPriceChanged(priceCents: Long?) {
                        // 价格变化时对比条已在 FloatingOverlayController 内部更新
                        // 这里预留扩展点
                    }

                    override fun onBallLost() {
                        // 球被系统摘除且自愈失败（悬浮窗权限被撤/ROM 限制）：服务已无
                        // 存在意义，停掉让主界面回到「等待启动」，而不是僵尸「运行中」
                        stopSelf()
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
        hdWorker?.cancel()
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
