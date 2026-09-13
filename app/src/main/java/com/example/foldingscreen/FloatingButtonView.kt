package com.example.foldingscreen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 悬浮控制按钮 —— 半透明白色圆形（直径 48dp，透明度 50%）。
 *
 * 交互全集（与规格一一对应）：
 *  - 短按（单击）：切换当前模式 开启/关闭（[onSingleTap]）；
 *  - 双击：快速复位折叠角度到 0°（带动画过渡，仅折叠模式有效；[onDoubleTap]）；
 *  - 长按：弹出模式选择菜单（[onLongPress]）；
 *  - 单指上/下滑：调整折叠角度（上滑增大、下滑减小；折叠模式开启时）；
 *  - 单指左/右滑：旋转折叠轴朝向 0~360°（折叠模式开启时）；
 *  - 慢速拖拽：自由移动按钮，松手自动吸附左右边缘（[onPositionUpdate]）。
 *
 * 手势判定（ACTION_MOVE 时按速度即时分派，一旦判定本次手势不再改变）：
 *  - 折叠模式开启 + 快速滑动（> FAST_PX_PER_MS）→ 折叠调参手势（按钮不动）；
 *  - 其余 → 拖拽手势（按钮跟随手指）。
 * 这样既能滑动调参，也能拖拽移动按钮，两者互不干扰。
 */
class FloatingButtonView(context: Context) : TextView(context) {

    companion object {
        /** 按钮直径 48dp */
        private const val SIZE_DP = 48f
        /** 白色底色透明度 50% */
        private const val BG_ALPHA = 0.5f
        /** 点击与拖拽的判定阈值（位移小于该值视为点击） */
        private const val CLICK_SLOP_DP = 8f
        /** 边缘吸附动画时长 */
        private const val EDGE_ANIM_MS = 180L
        /** 长按触发时长 */
        private const val LONG_PRESS_MS = 500L
        /** 双击判定窗口 */
        private const val DOUBLE_TAP_MS = 300L
        /** 快速滑动判定：折叠调参 vs 拖拽（px/ms） */
        private const val FAST_PX_PER_MS = 0.45f
        /** 折叠角灵敏度（度/像素） */
        private const val ANGLE_SENS_DEG_PER_PX = 0.30f
        /** 折叠轴角灵敏度（度/像素） */
        private const val AXIS_SENS_DEG_PER_PX = 0.40f
    }

    private enum class Gesture { NONE, DRAG, FOLD_ADJUST }

    // ==================== 宿主回调 ====================

    /** 拖拽/吸附过程中更新窗口坐标（由宿主实现 updateViewLayout） */
    var onPositionUpdate: ((x: Int, y: Int) -> Unit)? = null

    /** 短按（单击）回调 */
    var onSingleTap: (() -> Unit)? = null

    /** 双击回调（复位折叠角度） */
    var onDoubleTap: (() -> Unit)? = null

    /** 长按回调（弹出模式选择菜单） */
    var onLongPress: (() -> Unit)? = null

    /** 折叠调参回调：(轴角增量 deg, 折叠角增量 deg) —— 右滑轴角增、上滑折叠角增 */
    var onFoldSwipe: ((axisDeltaDeg: Float, angleDeltaDeg: Float) -> Unit)? = null

    /** 手势路由：当前是否处于「折叠模式开启」状态（开启时快速滑动用于调参） */
    var foldActiveProvider: (() -> Boolean)? = null

    // ==================== 位置状态 ====================

    private var currentX = 0
    private var currentY = 0

    // 手势状态
    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var initX = 0
    private var initY = 0
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastEventTime = 0L
    private var longPressFired = false
    private var lastTapTime = 0L
    private var edgeAnimator: ValueAnimator? = null
    private var longPressRunnable: Runnable? = null
    private var pendingSingleTap: Runnable? = null

    private val density = resources.displayMetrics.density
    private val buttonSize = (SIZE_DP * density).toInt()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // 半透明白色圆形背景（透明度 50%）
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.argb((255 * BG_ALPHA).toInt(), 255, 255, 255))
        background = bg

        text = ""
        setTextColor(Color.BLACK)
        textSize = 10f
        gravity = Gravity.CENTER
        maxLines = 1
        isClickable = true
    }

    /** 宿主 addView 后同步初始位置 */
    fun syncPosition(x: Int, y: Int) {
        currentX = x
        currentY = y
    }

    // ==================== 手势处理 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeAnimator?.cancel()
                cancelPendingSingleTap()
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                initX = currentX
                initY = currentY
                lastRawX = event.rawX
                lastRawY = event.rawY
                lastEventTime = event.eventTime
                gesture = Gesture.NONE
                longPressFired = false
                startLongPressTimer()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dxTotal = event.rawX - downRawX
                val dyTotal = event.rawY - downRawY

                if (gesture == Gesture.NONE) {
                    val moved = abs(dxTotal) > CLICK_SLOP_DP * density ||
                        abs(dyTotal) > CLICK_SLOP_DP * density
                    if (moved) {
                        cancelLongPressTimer()
                        // 折叠模式开启 + 快速滑动 → 折叠调参；否则 → 拖拽
                        val foldActive = foldActiveProvider?.invoke() == true
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        val dtMs = (event.eventTime - lastEventTime).coerceAtLeast(1L)
                        val speed = hypot(dx, dy) / dtMs
                        gesture = if (foldActive && speed > FAST_PX_PER_MS) {
                            Gesture.FOLD_ADJUST
                        } else {
                            Gesture.DRAG
                        }
                    }
                }

                when (gesture) {
                    Gesture.DRAG -> {
                        currentX = (initX + dxTotal).toInt()
                        currentY = (initY + dyTotal).toInt()
                        clampToScreen()
                        onPositionUpdate?.invoke(currentX, currentY)
                    }
                    Gesture.FOLD_ADJUST -> {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        // 右滑(dx>0)轴角增大；上滑(dy<0)折叠角增大
                        onFoldSwipe?.invoke(dx * AXIS_SENS_DEG_PER_PX, -dy * ANGLE_SENS_DEG_PER_PX)
                    }
                    Gesture.NONE -> Unit
                }

                lastRawX = event.rawX
                lastRawY = event.rawY
                lastEventTime = event.eventTime
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPressTimer()
                when (gesture) {
                    Gesture.DRAG -> snapToEdge()
                    Gesture.FOLD_ADJUST -> Unit
                    Gesture.NONE -> handleTap()
                }
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                if (gesture == Gesture.DRAG) snapToEdge()
            }
        }
        return super.onTouchEvent(event)
    }

    /** 单击/双击判定：300ms 窗口内第二次点击为双击（复位折叠），否则延迟触发单击 */
    private fun handleTap() {
        if (longPressFired) {
            longPressFired = false
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastTapTime < DOUBLE_TAP_MS) {
            // 双击：取消挂起的单击，触发复位
            cancelPendingSingleTap()
            onDoubleTap?.invoke()
            lastTapTime = 0L
        } else {
            lastTapTime = now
            pendingSingleTap = Runnable { onSingleTap?.invoke() }.also {
                mainHandler.postDelayed(it, DOUBLE_TAP_MS)
            }
        }
    }

    // ==================== 长按 ====================

    private fun startLongPressTimer() {
        cancelLongPressTimer()
        longPressRunnable = Runnable {
            longPressFired = true
            onLongPress?.invoke()
        }.also { mainHandler.postDelayed(it, LONG_PRESS_MS) }
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTap?.let { mainHandler.removeCallbacks(it) }
        pendingSingleTap = null
    }

    // ==================== 拖拽与吸附 ====================

    /** 松手后吸附到左右边缘 */
    private fun snapToEdge() {
        val screenW = resources.displayMetrics.widthPixels
        val targetX = if (currentX + buttonSize / 2 < screenW / 2) 0 else screenW - buttonSize
        edgeAnimator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = EDGE_ANIM_MS
            addUpdateListener { anim ->
                currentX = anim.animatedValue as Int
                onPositionUpdate?.invoke(currentX, currentY)
            }
            start()
        }
    }

    /** 限制按钮不出屏（Y 方向也做保护） */
    private fun clampToScreen() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        if (currentX < 0) currentX = 0
        if (currentX > screenW - buttonSize) currentX = screenW - buttonSize
        if (currentY < 0) currentY = 0
        if (currentY > screenH - buttonSize) currentY = screenH - buttonSize
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelLongPressTimer()
        cancelPendingSingleTap()
        edgeAnimator?.cancel()
    }
}
