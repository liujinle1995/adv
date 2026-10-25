package com.adv.runner.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮返回按钮管理器：给被虚拟化运行的目标 App 叠加一个可拖动的悬浮按钮。
 *
 * 交互参考 iOS 辅助触控（小白点）：
 * - 拖动松手后吸附到最近的左/右边缘，并**只露出一半**（半圆贴边）；
 * - 闲置 3 秒自动变淡，触摸立即恢复；
 * - 半圆状态按下会先滑出成完整圆，再决定是点击还是继续拖动。
 *
 * 实现要点：ColorOS 会把 overlay 窗口的 x 夹在屏幕内（x 不会小于 0），
 * 所以半圆不是靠"窗口移到屏幕外"实现的，而是把**窗口本身收窄一半**，
 * 再让内部圆view平移半个身位，让窗口边界把圆裁掉一半。
 */
class FloatingButtonManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var floatingView: View? = null
    private var circleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 当前贴边方向：-1 左、1 右、0 未贴边（未贴边时按钮完整可见）。 */
    private var snappedSide = 0

    /** 闲置变淡任务。 */
    private val idleFadeRunnable = Runnable {
        floatingView?.animate()?.alpha(IDLE_ALPHA)?.setDuration(400)?.start()
    }

    /** 是否已授予「显示在其他应用上层」权限。 */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = floatingView != null

    /** 显示悬浮按钮；无权限或已显示则忽略。 */
    fun show(onClick: () -> Unit) {
        mainHandler.post {
            if (floatingView != null || !canDrawOverlays()) return@post

            val (root, circle) = buildButton()
            val params = WindowManager.LayoutParams(
                dp(SIZE_DP),
                dp(SIZE_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 默认位置：保持原 56dp 时的圆心（x=308dp, y=428dp）不变
                x = dp(286)
                y = dp(406)
            }

            // 区分拖动与点击
            var downRawX = 0f
            var downRawY = 0f
            var startX = params.x
            var startY = params.y
            var dragging = false

            root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 半圆贴边时，按下先把按钮完整拉出来，
                        // 否则会从"只剩一半"的位置开始跟手，视觉上会跳。
                        if (snappedSide != 0) pullOut(root, circle, params)
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        cancelIdleFade()
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragging = true
                        if (dragging) {
                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt()
                            runCatching { windowManager.updateViewLayout(root, params) }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            onClick()
                        } else {
                            collapseToNearestEdge(root, circle, params)
                        }
                        scheduleIdleFade()
                        true
                    }

                    else -> false
                }
            }

            runCatching { windowManager.addView(root, params) }
            floatingView = root
            circleView = circle
            layoutParams = params
            snappedSide = 0
            scheduleIdleFade()
        }
    }

    /** 隐藏悬浮按钮（幂等）。 */
    fun hide() {
        mainHandler.post {
            idleHandler.removeCallbacks(idleFadeRunnable)
            floatingView?.let { runCatching { windowManager.removeView(it) } }
            floatingView = null
            circleView = null
            layoutParams = null
            snappedSide = 0
        }
    }

    /**
     * 闲置自动变淡：一段时间没碰就降到 [IDLE_ALPHA]，触摸时立即恢复。
     */
    private fun scheduleIdleFade() {
        idleHandler.removeCallbacks(idleFadeRunnable)
        idleHandler.postDelayed(idleFadeRunnable, IDLE_TIMEOUT_MS)
    }

    private fun cancelIdleFade() {
        idleHandler.removeCallbacks(idleFadeRunnable)
        floatingView?.animate()?.alpha(ACTIVE_ALPHA)?.setDuration(120)?.start()
    }

    /**
     * 松手吸附到最近的边缘，并收成半圆。
     *
     * 先动画滑到贴边位置，动画结束后把窗口宽度收窄为一半、圆平移半个身位，
     * 由窗口边界裁出半圆（窗口 x 不能为负，只能这样实现）。
     */
    private fun collapseToNearestEdge(root: View, circle: View, params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val size = dp(SIZE_DP)
        val half = size / 2
        val toLeft = params.x + size / 2 < metrics.widthPixels / 2
        val targetY = params.y.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
        val edgeX = if (toLeft) 0 else metrics.widthPixels - size

        animateTo(root, params, edgeX, targetY) {
            params.width = half
            params.x = if (toLeft) 0 else metrics.widthPixels - half
            circle.translationX = if (toLeft) -half.toFloat() else 0f
            snappedSide = if (toLeft) -1 else 1
            runCatching { windowManager.updateViewLayout(root, params) }
        }
    }

    /** 半圆 → 完整圆（按下时立即执行，保证跟手不跳）。 */
    private fun pullOut(root: View, circle: View, params: WindowManager.LayoutParams) {
        if (snappedSide == 0) return
        val size = dp(SIZE_DP)
        params.width = size
        params.x = if (snappedSide < 0) 0 else context.resources.displayMetrics.widthPixels - size
        circle.translationX = 0f
        snappedSide = 0
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    /** 位置过渡动画，结束后回调 [onEnd]。 */
    private fun animateTo(
        root: View,
        params: WindowManager.LayoutParams,
        targetX: Int,
        targetY: Int,
        onEnd: () -> Unit
    ) {
        val startX = params.x
        val startY = params.y
        if (startX == targetX && startY == targetY) {
            onEnd()
            return
        }
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                runCatching { windowManager.updateViewLayout(root, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    /**
     * 按钮视觉：小尺寸 + 半透明白底 + 深色 ✕。
     *
     * 返回 (root, circle)：root 是加进 WindowManager 的根容器（宽度会随贴边收窄），
     * circle 是真正画圆的子 view（固定尺寸，贴边时靠平移露出半边）。
     */
    private fun buildButton(): Pair<View, View> {
        val circle = FrameLayout(context)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#99FFFFFF"))        // 白色，60% 不透明
            // 极淡的深色描边：白圆在亮画面上会和背景糊在一起，靠它勾出轮廓；
            // 暗画面上则几乎看不见，不影响观感。
            setStroke(dp(1), Color.parseColor("#33000000"))
        }
        circle.background = background
        circle.elevation = dp(2).toFloat()

        val label = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#B3000000")) // 70% 黑，白底上清晰但不刺眼
            gravity = Gravity.CENTER
            isClickable = false
        }
        circle.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val root = FrameLayout(context)
        root.addView(
            circle,
            FrameLayout.LayoutParams(dp(SIZE_DP), dp(SIZE_DP))
        )
        root.alpha = ACTIVE_ALPHA
        return root to circle
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        /** 按钮直径（dp）。 */
        const val SIZE_DP = 44

        /** 正常状态不透明度。 */
        const val ACTIVE_ALPHA = 0.95f

        /** 闲置后的不透明度：留一层浅影，既不挡画面又能找到。 */
        const val IDLE_ALPHA = 0.38f

        /** 闲置多久后变淡（毫秒）。 */
        const val IDLE_TIMEOUT_MS = 3000L

        /** 贴边动画时长。 */
        const val SNAP_DURATION_MS = 220L
    }
}
