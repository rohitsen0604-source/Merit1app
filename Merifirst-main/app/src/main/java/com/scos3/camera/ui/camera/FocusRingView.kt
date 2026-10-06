package com.scos3.camera.ui.camera

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Animated camera autofocus reticle.
 * Displays a zoom-in converging ring in bright cyan, pulses and turns neon green
 * upon lock, and smoothly fades out so the user clearly sees autofocus in action.
 */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = COLOR_FOCUSING
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_FOCUSING
    }

    private var currentAnimSet: AnimatorSet? = null

    init {
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f) - (6f * density)
        if (radius <= 0f) return

        // Main focus circle
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 4 viewfinder tick marks (top, bottom, left, right)
        val tickLen = 7f * density
        canvas.drawLine(cx, cy - radius - 2f, cx, cy - radius + tickLen, ringPaint)
        canvas.drawLine(cx, cy + radius + 2f, cx, cy + radius - tickLen, ringPaint)
        canvas.drawLine(cx - radius - 2f, cy, cx - radius + tickLen, cy, ringPaint)
        canvas.drawLine(cx + radius + 2f, cy, cx + radius - tickLen, cy, ringPaint)

        // Center dot
        canvas.drawCircle(cx, cy, 2.5f * density, centerPaint)
    }

    /**
     * Runs the zoom-in focus convergence and lock animation.
     * Can optionally position the reticle around (centerX, centerY).
     */
    fun startFocusAnimation(centerX: Float? = null, centerY: Float? = null, onLock: (() -> Unit)? = null) {
        currentAnimSet?.cancel()

        if (centerX != null && centerY != null && width > 0 && height > 0) {
            val parentW = (parent as? View)?.width ?: 0
            val parentH = (parent as? View)?.height ?: 0
            val halfW = width / 2f
            val halfH = height / 2f
            val targetX = (centerX - halfW).coerceIn(0f, (parentW - width).toFloat().coerceAtLeast(0f))
            val targetY = (centerY - halfH).coerceIn(0f, (parentH - height).toFloat().coerceAtLeast(0f))
            x = targetX
            y = targetY
        } else {
            // Center in parent if coordinates not given
            (parent as? View)?.let { parentView ->
                if (parentView.width > 0 && parentView.height > 0) {
                    x = (parentView.width - width) / 2f
                    y = (parentView.height - height) / 2f
                }
            }
        }

        visibility = VISIBLE
        alpha = 1f
        scaleX = 1.35f
        scaleY = 1.35f
        ringPaint.color = COLOR_FOCUSING
        centerPaint.color = COLOR_FOCUSING
        invalidate()

        // 1. Zoom-in converge (focusing: 1.35x -> 1.0x)
        val convergeX = ObjectAnimator.ofFloat(this, "scaleX", 1.35f, 1.0f).setDuration(320)
        val convergeY = ObjectAnimator.ofFloat(this, "scaleY", 1.35f, 1.0f).setDuration(320)
        convergeX.interpolator = AccelerateDecelerateInterpolator()
        convergeY.interpolator = AccelerateDecelerateInterpolator()

        // 2. Lock pop (green lock: 1.0x -> 1.12x -> 1.0x)
        val popX = ObjectAnimator.ofFloat(this, "scaleX", 1.0f, 1.12f, 1.0f).setDuration(240)
        val popY = ObjectAnimator.ofFloat(this, "scaleY", 1.0f, 1.12f, 1.0f).setDuration(240)
        popX.interpolator = OvershootInterpolator(2.2f)
        popY.interpolator = OvershootInterpolator(2.2f)

        // 3. Fade out after holding lock
        val fade = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).setDuration(300)
        fade.startDelay = 600

        val set = AnimatorSet()
        set.play(convergeX).with(convergeY)
        set.play(popX).with(popY).after(convergeX)
        set.play(fade).after(popX)

        popX.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                // Focus Lock achieved: switch to vibrant neon green
                ringPaint.color = COLOR_LOCKED
                centerPaint.color = COLOR_LOCKED
                invalidate()
                onLock?.invoke()
            }
        })

        fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                visibility = GONE
            }
        })

        currentAnimSet = set
        set.start()
    }

    companion object {
        private const val COLOR_FOCUSING = 0xEE00E5FF.toInt() // Bright Cyan / Light Blue
        private const val COLOR_LOCKED = 0xFF00E676.toInt()   // Vibrant Neon Green
    }
}
