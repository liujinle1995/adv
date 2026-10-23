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
 * - 用 [WindowManager] + TYPE_APPLICATION_OVERLAY 悬浮在目标 App 之上（z-order 更高）；
 * - 按钮可拖动，点击触发 [onClick]（停止目标 App 返回宿主）；
 * - 所有 window 操作都切到主线程执行，可从任意协程线程调用。
 */
class FloatingButtonManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 是否已授予「显示在其他应用上层」权限。 */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = floatingView != null

    /** 显示悬浮按钮；无权限或已显示则忽略。 */
    fun show(onClick: () -> Unit) {
        mainHandler.post {
            if (floatingView != null || !canDrawOverlays()) return@post

            val view = buildButton()
            val params = WindowManager.LayoutParams(
                dp(56),
                dp(56),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(280)
                y = dp(400)
            }

            // 区分拖动与点击
            var downRawX = 0f
            var downRawY = 0f
            var startX = params.x
            var startY = params.y
            var dragging = false

            view.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragging = true
                        if (dragging) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            runCatching { windowManager.updateViewLayout(view, params) }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!dragging) onClick()
                        true
                    }

                    else -> false
                }
            }

            runCatching { windowManager.addView(view, params) }
            floatingView = view
            layoutParams = params
        }
    }

    /** 隐藏悬浮按钮（幂等）。 */
    fun hide() {
        mainHandler.post {
            floatingView?.let { runCatching { windowManager.removeView(it) } }
            floatingView = null
            layoutParams = null
        }
    }

    private fun buildButton(): View {
        val container = FrameLayout(context)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC2E6BE6"))
            setStroke(dp(2), Color.WHITE)
        }
        container.background = background
        container.elevation = dp(6).toFloat()

        val label = TextView(context).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = false
        }
        container.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return container
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
