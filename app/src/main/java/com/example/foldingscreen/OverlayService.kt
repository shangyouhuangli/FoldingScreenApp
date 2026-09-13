package com.example.foldingscreen

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.nio.ByteBuffer

/**
 * 前台服务：应用核心 —— 悬浮窗管理、双模式状态机、全生命周期控制。
 *
 * 【模式状态机】
 *  - 未开启：桌面只有悬浮按钮（显示当前模式名）；
 *  - 已开启：悬浮按钮变「停止」；全屏渲染层叠加所有应用之上，执行
 *    空间锚定 / 万向折叠变换；再次短按 → 逆序释放渲染/采集/传感器资源；
 *  - 模式切换（长按菜单）：仅切换渲染路径与传感器，渲染层不重建，无黑屏闪烁。
 *
 * 【画面源（重要平台约束）】
 *  MediaProjection 会把 TYPE_APPLICATION_OVERLAY 悬浮窗一并采入画面（而
 *  FLAG_SECURE 又会使被覆盖区域在采集中整块变黑），因此全屏不透明渲染层
 *  出现后，无法再从系统采到纯净实时帧。本应用采用的正确时序：
 *  开启模式 → 立即启动采集（此刻渲染层尚未出现，采到一帧纯净的实时屏幕画面）
 *  → 停止采集 → 上渲染层 → 以该帧为纹理执行 60fps 实时变换（旋转/折叠动画）。
 *  模式切换与角度调整均为实时平滑，仅纹理源为「开启瞬间的实时快照」，
 *  每次重新开启都会刷新快照。
 *
 * 【资源释放】退出模式/退出应用时按创建逆序释放：
 *  渲染(EGL/线程) → 悬浮渲染层 → 屏幕采集(虚拟显示/ImageReader)
 *  → 姿态传感器 → MediaProjection → 悬浮按钮，无内存泄漏。
 */
class OverlayService : Service() {

    /** 渲染模式 */
    enum class Mode { SPATIAL, FOLD }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "folding_service"
        /** 首帧采集超时（毫秒）：超时说明录屏未出帧，清理并提示 */
        private const val FIRST_FRAME_TIMEOUT_MS = 3000L

        const val ACTION_START_SERVICE = "com.example.foldingscreen.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.foldingscreen.action.STOP_SERVICE"

        /** 单位矩阵（4x4 列主序），供姿态工具尚未就绪时兜底 */
        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    // ---- 系统服务 ----
    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager

    /** 主线程 Handler：首帧回调后的 UI 操作与超时任务 */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 模式状态（渲染线程 volatile 读取） ----
    @Volatile
    private var currentMode = Mode.SPATIAL
    @Volatile
    private var enabled = false
    @Volatile
    private var foldAngleDeg = 0f
    @Volatile
    private var foldAxisDeg = 0f

    // ---- 悬浮控制按钮 ----
    private var floatingButton: FloatingButtonView? = null

    // ---- 模式组件 ----
    private var orientationHelper: OrientationHelper? = null
    private var screenCapturer: ScreenCapturer? = null
    private var glRenderer: GLRenderer? = null
    private var overlayTextureView: TextureView? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionRequestInFlight = false

    // ---- 首帧纯净画面 ----
    private var pendingFirstFrame: ByteBuffer? = null
    private var firstFrameW = 0
    private var firstFrameH = 0
    @Volatile
    private var firstFrameReceived = false
    private var captureTimeoutRunnable: Runnable? = null

    // ---- 屏幕指标 ----
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        queryScreenMetrics()
        // 前台服务契约：先进入前台（mediaProjection 类型，常驻通知栏防回收）
        startAsForeground()
        // 悬浮窗权限自检：缺失时引导重新授权并退出，避免 addView 崩溃
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "悬浮窗权限缺失，引导重新授权")
            try {
                startActivity(
                    Intent(this, LaunchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            stopSelf()
            return
        }
        showFloatingButton()
        Log.i(TAG, "服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopEverything()
                stopSelf()
            }
        }
        // 服务被杀后自动重建（START_STICKY），悬浮按钮随之恢复
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 兜底释放：无论处于何种状态，全部资源按序清理
        stopEverything()
        super.onDestroy()
        Log.i(TAG, "服务已销毁")
    }

    // ==================== 前台服务与通知 ====================

    /** 启动为前台服务（mediaProjection 类型，Android 14 创建投影前的强制要求） */
    private fun startAsForeground() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)

            // 通知上的「停止」按钮（点击退出整个服务）
            val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP_SERVICE)
            val stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setContentIntent(stopPi)
                .addAction(0, getString(R.string.action_stop), stopPi)
                .build()

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            // 前台服务启动失败不应导致进程崩溃（记录日志，系统会按超时规则处理）
            Log.e(TAG, "前台服务启动失败: ${e.message}")
        }
    }

    // ==================== 悬浮控制按钮 ====================

    /** 添加悬浮按钮（48dp 半透明白色圆形，可拖拽、边缘吸附、全套手势） */
    private fun showFloatingButton() {
        if (floatingButton != null) return
        try {
            val btn = FloatingButtonView(this)
            val size = (48 * resources.displayMetrics.density).toInt()

            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE, // 按钮不进入采集画面，避免残影
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 24
            lp.y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
            btn.syncPosition(lp.x, lp.y)

            // 拖拽/吸附：更新窗口坐标
            btn.onPositionUpdate = { x, y ->
                lp.x = x
                lp.y = y
                try {
                    windowManager.updateViewLayout(btn, lp)
                } catch (_: Exception) {
                }
            }
            // 短按：切换当前模式 开启/关闭
            btn.onSingleTap = { toggleMode() }
            // 双击：复位折叠角度（带动画过渡）
            btn.onDoubleTap = {
                if (currentMode == Mode.FOLD && enabled) {
                    foldAngleDeg = 0f
                }
            }
            // 长按：弹出模式选择菜单
            btn.onLongPress = { showModeMenu(btn) }
            // 折叠调参：左右滑转轴角、上下滑调折叠角
            btn.onFoldSwipe = { axisDelta, angleDelta ->
                foldAxisDeg = ((foldAxisDeg + axisDelta) % 360f + 360f) % 360f
                foldAngleDeg = (foldAngleDeg + angleDelta).coerceIn(0f, 180f)
            }
            // 手势路由：仅折叠模式开启时滑动用于调参
            btn.foldActiveProvider = { currentMode == Mode.FOLD && enabled }

            updateButtonText(btn)

            windowManager.addView(btn, lp)
            floatingButton = btn
        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮按钮失败: ${e.message}")
            floatingButton = null
        }
    }

    private fun removeFloatingButton() {
        floatingButton?.let { btn ->
            try {
                windowManager.removeView(btn)
            } catch (_: Exception) {
            }
        }
        floatingButton = null
    }

    /** 按钮文字：未开启显示模式名，开启后显示「停止」 */
    private fun updateButtonText(btn: FloatingButtonView) {
        btn.text = when {
            enabled -> getString(R.string.mode_stop)
            currentMode == Mode.SPATIAL -> getString(R.string.mode_spatial)
            else -> getString(R.string.mode_fold)
        }
    }

    // ==================== 模式选择菜单（长按） ====================

    /** 长按弹出模式菜单：PopupWindow 挂载在悬浮按钮窗口上（服务上下文可用） */
    private fun showModeMenu(anchor: View) {
        try {
            val popup = PopupWindow(this)
            val ll = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(0xF2FFFFFF.toInt())
                    cornerRadius = dp(10).toFloat()
                }
            }
            ll.addView(menuItem(getString(R.string.menu_spatial)) {
                popup.dismiss()
                switchMode(Mode.SPATIAL)
            })
            ll.addView(menuItem(getString(R.string.menu_fold)) {
                popup.dismiss()
                switchMode(Mode.FOLD)
            })
            popup.contentView = ll
            popup.isFocusable = true
            popup.width = dp(150)
            popup.height = WindowManager.LayoutParams.WRAP_CONTENT
            popup.showAsDropDown(anchor, -dp(50), dp(4))
        } catch (e: Exception) {
            Log.w(TAG, "模式菜单弹出失败: ${e.message}")
        }
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(0xFF212121.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 模式控制 ====================

    /** 短按：切换当前模式 开启/关闭 */
    private fun toggleMode() {
        if (enabled) {
            disableMode()
        } else {
            enableMode()
        }
    }

    /** 开启模式 */
    private fun enableMode() {
        if (enabled) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限已失效，请重新打开应用", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(this, LaunchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            return
        }
        // 首次开启：动态申请 MediaProjection 录屏授权；授权后自动继续
        if (mediaProjection == null) {
            requestProjection()
            return
        }
        startEffect()
    }

    /** 动态申请屏幕录制授权（首次开启时） */
    private fun requestProjection() {
        if (projectionRequestInFlight) return
        projectionRequestInFlight = true
        MediaProjectionRequestActivity.callback = { resultCode, data ->
            projectionRequestInFlight = false
            if (resultCode == Activity.RESULT_OK && data != null) {
                createProjection(resultCode, data)
                startEffect()
            } else {
                Toast.makeText(this, "未获得屏幕录制授权", Toast.LENGTH_SHORT).show()
            }
        }
        try {
            val intent = Intent(this, MediaProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动授权页失败: ${e.message}")
            projectionRequestInFlight = false
            Toast.makeText(this, "无法启动录屏授权，请重试", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 创建 MediaProjection 会话。
     * Android 14 要求：创建前本服务必须以 mediaProjection 类型前台服务运行（已满足）。
     * 投影会话在本服务生命周期内常驻，模式关闭/重开无需重复授权；
     * 系统撤销授权时自动退出当前模式。
     */
    private fun createProjection(resultCode: Int, data: Intent) {
        try {
            val projection = projectionManager.getMediaProjection(resultCode, data)
                ?: run {
                    Toast.makeText(this, "创建录屏会话失败", Toast.LENGTH_SHORT).show()
                    return
                }
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection 被系统停止")
                    mainHandler.post {
                        disableMode()
                        mediaProjection = null
                        Toast.makeText(this@OverlayService, "录屏会话已停止，重新开启将再次授权", Toast.LENGTH_SHORT).show()
                    }
                }
            }, null)
            mediaProjection = projection
            Log.i(TAG, "MediaProjection 会话已创建")
        } catch (e: Exception) {
            // Android 14：授权 token 只能使用一次；异常时清空以便重新授权
            Log.e(TAG, "创建投影失败: ${e.message}")
            mediaProjection = null
            Toast.makeText(this, "创建录屏会话失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 正式开启当前模式：
     * 1. 空间锚定模式 → 启动旋转矢量传感器（首个事件即基准姿态）；
     * 2. 启动屏幕采集（此刻全屏渲染层尚未出现 → 采到纯净实时帧）；
     * 3. 首帧就绪 → 停止采集 → 上渲染层 → 60fps 实时变换。
     */
    private fun startEffect() {
        if (enabled) return
        val projection = mediaProjection ?: return
        enabled = true
        floatingButton?.let { updateButtonText(it) }
        Toast.makeText(this, if (currentMode == Mode.SPATIAL) "空间锚定模式已开启" else "万向折叠模式已开启", Toast.LENGTH_SHORT).show()

        // 1. 空间锚定：记录基准姿态
        if (currentMode == Mode.SPATIAL) {
            startOrientation()
        }

        // 2. 采集首帧纯净画面（渲染层尚未出现）
        firstFrameReceived = false
        screenCapturer = ScreenCapturer(
            projection, screenWidth, screenHeight, screenDpi
        ) { buffer, w, h, _ ->
            // 并发保护：采集线程回调，可能晚于用户快速关闭模式；enabled 为 volatile
            if (!firstFrameReceived && enabled) {
                firstFrameReceived = true
                pendingFirstFrame = buffer
                firstFrameW = w
                firstFrameH = h
                captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                mainHandler.post {
                    // 3. 首帧就绪：停止采集，上渲染层
                    if (enabled) {
                        screenCapturer?.stop()
                        screenCapturer = null
                        showRenderingLayer()
                    }
                }
            }
        }.also { it.start() }

        // 首帧超时保护：录屏未出帧则清理退出
        val timeout = Runnable {
            if (!firstFrameReceived) {
                Log.w(TAG, "首帧采集超时")
                Toast.makeText(this, "屏幕采集超时，请重试", Toast.LENGTH_SHORT).show()
                disableMode()
            }
        }
        captureTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, FIRST_FRAME_TIMEOUT_MS)
    }

    /** 空间锚定：启动姿态监听，首个传感器事件即基准姿态 */
    private fun startOrientation() {
        val orientation = OrientationHelper(this)
        if (!orientation.isSupported) {
            Toast.makeText(this, "设备不支持旋转矢量传感器", Toast.LENGTH_SHORT).show()
        }
        orientationHelper = orientation
        orientation.start()
        Log.i(TAG, "姿态传感器已启动（基准姿态待首个事件）")
    }

    /**
     * 添加全屏悬浮渲染层并启动 GL 渲染器。
     * 窗口参数说明：
     *  - TYPE_APPLICATION_OVERLAY：覆盖所有应用之上；
     *  - FLAG_LAYOUT_IN_SCREEN / FLAG_LAYOUT_NO_LIMITS：无状态栏/导航栏遮挡；
     *  - FLAG_NOT_TOUCHABLE / FLAG_NOT_TOUCH_MODAL：触摸事件透传给下层应用；
     *  - 不使用 FLAG_SECURE：画面源已在开启瞬间定格，无反馈回路风险，
     *    避免部分机型 FLAG_SECURE 导致的整屏采集变黑。
     */
    private fun showRenderingLayer() {
        addOverlayTextureView()
        Log.i(TAG, "${if (currentMode == Mode.SPATIAL) "空间锚定" else "万向折叠"}模式已开启")
    }

    /** 创建全屏渲染层窗口（TextureView 作为 EGL 渲染目标） */
    private fun addOverlayTextureView() {
        if (overlayTextureView != null) return
        try {
            val tv = TextureView(this)
            // 渲染层完全不透明：配合片元着色器强制 alpha=1，杜绝底层实时桌面透出
            tv.setOpaque(true)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 0
            lp.y = 0

            // SurfaceTexture 就绪后启动 GL 渲染器
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    surface.setDefaultBufferSize(screenWidth, screenHeight)
                    startRenderer(surface)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }

            windowManager.addView(tv, lp)
            overlayTextureView = tv
        } catch (e: Exception) {
            Log.e(TAG, "添加渲染悬浮窗失败: ${e.message}")
            disableMode()
        }
    }

    /** 创建统一 GL 渲染器：双模式共用同一渲染线程与纹理 */
    private fun startRenderer(surface: SurfaceTexture) {
        val renderer = GLRenderer(surface, screenWidth, screenHeight)
        renderer.matrixProvider = { orientationHelper?.latestMatrix() ?: IDENTITY }
        renderer.foldStateProvider = { GLRenderer.FoldState(foldAngleDeg, foldAxisDeg) }
        renderer.mode = if (currentMode == Mode.SPATIAL) GLRenderer.Mode.SPATIAL else GLRenderer.Mode.FOLD
        // 喂入首帧纯净画面（渲染线程启动后自动上传纹理）
        pendingFirstFrame?.let {
            renderer.pushFrame(it, firstFrameW, firstFrameH, firstFrameW * 4)
        }
        renderer.start()
        glRenderer = renderer
    }

    /** 关闭当前模式：按创建逆序释放资源（投影会话保留，重开无需再授权） */
    private fun disableMode() {
        if (!enabled && glRenderer == null && screenCapturer == null) return
        enabled = false
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        captureTimeoutRunnable = null

        // 1. 停止渲染线程并释放 GL 资源（必须先于移除窗口，避免使用已销毁的 SurfaceTexture）
        glRenderer?.stop()
        glRenderer = null

        // 2. 移除全屏渲染层
        overlayTextureView?.let { tv ->
            try {
                windowManager.removeView(tv)
            } catch (_: Exception) {
            }
        }
        overlayTextureView = null

        // 3. 停止屏幕采集（释放虚拟显示与 ImageReader）
        screenCapturer?.stop()
        screenCapturer = null

        // 4. 停止姿态传感器（空间锚定专用）
        orientationHelper?.release()
        orientationHelper = null

        // 5. 清空首帧缓冲
        pendingFirstFrame = null
        firstFrameReceived = false

        floatingButton?.let { updateButtonText(it) }
        Log.i(TAG, "模式已关闭，渲染/采集/传感器资源已释放")
    }

    /** 切换模式（长按菜单）：仅切换渲染路径与传感器，渲染层不重建 */
    private fun switchMode(m: Mode) {
        if (currentMode == m) return
        currentMode = m
        floatingButton?.let { updateButtonText(it) }

        if (enabled) {
            // 空间锚定需要姿态传感器；折叠模式不需要
            if (m == Mode.SPATIAL) {
                if (orientationHelper == null) startOrientation()
            } else {
                orientationHelper?.release()
                orientationHelper = null
            }
            glRenderer?.mode = if (m == Mode.SPATIAL) GLRenderer.Mode.SPATIAL else GLRenderer.Mode.FOLD
        }
        Toast.makeText(this, if (m == Mode.SPATIAL) "已切换：空间锚定模式" else "已切换：万向折叠模式", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "当前模式: ${if (m == Mode.SPATIAL) "空间锚定" else "万向折叠"}")
    }

    /** 退出应用：释放全部资源（含 MediaProjection）并停止服务 */
    private fun stopEverything() {
        disableMode()
        mediaProjection?.stop()
        mediaProjection = null
        removeFloatingButton()
    }

    // ==================== 工具方法 ====================

    /** 获取全屏尺寸与 DPI（虚拟显示与渲染层需要与物理屏幕一致） */
    private fun queryScreenMetrics() {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        screenDpi = resources.displayMetrics.densityDpi
        Log.i(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}@${screenDpi}dpi")
    }
}
