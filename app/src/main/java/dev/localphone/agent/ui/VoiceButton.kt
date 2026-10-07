package dev.localphone.agent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

enum class VoicePhase { IDLE, PREPARING, LISTENING, PROCESSING }

/** A native button: quiet at rest, sound-responsive waves while listening. */
class VoiceButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val purple = Color.rgb(104, 91, 223)
    private var level = 0f
    var phase = VoicePhase.IDLE
        private set
    var animationPosition = 0f
        private set
    val isAnimating get() = animator.isRunning
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animationPosition = it.animatedValue as Float; invalidate() }
    }
    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setPhase(VoicePhase.IDLE)
    }
    fun setPhase(value: VoicePhase) {
        phase = value
        isEnabled = value != VoicePhase.PROCESSING
        contentDescription = when (value) {
            VoicePhase.IDLE -> "음성으로 요청하기"
            VoicePhase.PREPARING -> "음성 준비 중, 누르면 취소"
            VoicePhase.LISTENING -> "듣고 있어요, 누르면 취소"
            VoicePhase.PROCESSING -> "요청 처리 중"
        }
        if (value == VoicePhase.IDLE) { animator.cancel(); level = 0f; animationPosition = 0f }
        else if (isAttachedToWindow && !animator.isRunning) animator.start()
        invalidate()
    }
    fun setLevel(value: Float) { level = (level * 0.55f + value.coerceIn(0f, 1f) * 0.45f); invalidate() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (phase != VoicePhase.IDLE) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.className = "android.widget.Button"
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = minOf(width, height) / 240f
        val x = width / 2f; val y = height / 2f
        val listening = phase == VoicePhase.LISTENING
        val pulse = (sin(animationPosition * 2 * PI).toFloat() + 1f) / 2f
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(235, 232, 251)
        canvas.drawCircle(x, y, (101f + if (listening) 8f * pulse + 7f * level else 0f) * unit, paint)
        if (listening) {
            for (ring in 0..1) {
                val progress = (animationPosition + ring * 0.5f) % 1f
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * unit
                paint.color = purple; paint.alpha = ((1f - progress) * 60f).toInt()
                canvas.drawCircle(x, y, (87 + 29 * progress + level * 4) * unit, paint)
            }
        }
        paint.alpha = 255; paint.style = Paint.Style.FILL; paint.color = purple
        canvas.drawCircle(x, y, (77f + if (listening) pulse * 1.5f + level * 2f else 0f) * unit, paint)
        paint.color = Color.WHITE; paint.strokeWidth = 3.6f * unit; paint.strokeCap = Paint.Cap.ROUND
        if (listening) {
            for (bar in -2..2) {
                val wave = (sin(animationPosition * 4 * PI + bar * 1.1).toFloat() + 1f) / 2f
                val half = (7 + (1 - kotlin.math.abs(bar) * 0.2f) * (9 * wave + 18 * level)) * unit
                canvas.drawLine(x + bar * 11f * unit, y - half, x + bar * 11f * unit, y + half, paint)
            }
        } else if (phase == VoicePhase.PREPARING || phase == VoicePhase.PROCESSING) {
            paint.style = Paint.Style.STROKE
            canvas.drawArc(RectF(x - 19 * unit, y - 19 * unit, x + 19 * unit, y + 19 * unit),
                animationPosition * 360, 260f, false, paint)
        } else {
            paint.style = Paint.Style.STROKE
            canvas.drawRoundRect(RectF(x - 9 * unit, y - 24 * unit, x + 9 * unit, y + 8 * unit), 9 * unit, 9 * unit, paint)
            canvas.drawArc(RectF(x - 17 * unit, y - 15 * unit, x + 17 * unit, y + 17 * unit), 0f, 180f, false, paint)
            canvas.drawLine(x, y + 18 * unit, x, y + 26 * unit, paint)
            canvas.drawLine(x - 10 * unit, y + 26 * unit, x + 10 * unit, y + 26 * unit, paint)
        }
    }
}
