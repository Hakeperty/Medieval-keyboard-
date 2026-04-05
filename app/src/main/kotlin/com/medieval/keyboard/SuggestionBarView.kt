package com.medieval.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

class SuggestionBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnSuggestionClickListener {
        fun onSuggestionClicked(suggestion: String)
    }

    var listener: OnSuggestionClickListener? = null
    private var suggestions: List<String> = emptyList()
    private var isLoading = false
    private var shimmerOffset = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        textAlign = Paint.Align.CENTER
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        strokeWidth = 1f
    }

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            shimmerOffset = animation.animatedValue as Float
            invalidate()
        }
    }

    fun setSuggestions(newSuggestions: List<String>) {
        suggestions = newSuggestions.take(3)
        isLoading = false
        if (shimmerAnimator.isRunning) shimmerAnimator.cancel()
        invalidate()
    }

    fun setLoading(loading: Boolean) {
        isLoading = loading
        if (loading) {
            suggestions = emptyList()
            if (!shimmerAnimator.isRunning) shimmerAnimator.start()
        } else {
            if (shimmerAnimator.isRunning) shimmerAnimator.cancel()
        }
        invalidate()
    }

    fun clearSuggestions() {
        suggestions = emptyList()
        isLoading = false
        if (shimmerAnimator.isRunning) shimmerAnimator.cancel()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isLoading) {
            drawShimmer(canvas)
            return
        }

        val currentSuggestions = suggestions
        if (currentSuggestions.isEmpty()) return

        val sectionWidth = width.toFloat() / currentSuggestions.size

        currentSuggestions.forEachIndexed { index, suggestion ->
            val centerX = sectionWidth * index + sectionWidth / 2
            val centerY = height / 2f + textPaint.textSize / 3f
            canvas.drawText(suggestion, centerX, centerY, textPaint)

            if (index < currentSuggestions.size - 1) {
                val dividerX = sectionWidth * (index + 1)
                canvas.drawLine(dividerX, 8f, dividerX, height - 8f, dividerPaint)
            }
        }
    }

    private fun drawShimmer(canvas: Canvas) {
        val shimmerWidth = width * 0.4f
        val start = width * shimmerOffset - shimmerWidth
        val end = start + shimmerWidth

        val gradient = LinearGradient(
            start, 0f, end, 0f,
            intArrayOf(
                Color.parseColor("#2A2A2C"),
                Color.parseColor("#4A4A4C"),
                Color.parseColor("#2A2A2C")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerPaint.shader = gradient

        val barHeight = 14f
        val barY = (height - barHeight) / 2f
        val sectionWidth = width / 3f

        for (i in 0 until 3) {
            val barLeft = sectionWidth * i + sectionWidth * 0.15f
            val barRight = sectionWidth * (i + 1) - sectionWidth * 0.15f
            canvas.drawRoundRect(
                RectF(barLeft, barY, barRight, barY + barHeight),
                7f, 7f, shimmerPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val currentSuggestions = suggestions
            if (currentSuggestions.isNotEmpty()) {
                val sectionWidth = width.toFloat() / currentSuggestions.size
                val index = (event.x / sectionWidth).toInt().coerceIn(0, currentSuggestions.size - 1)
                listener?.onSuggestionClicked(currentSuggestions[index])
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (shimmerAnimator.isRunning) shimmerAnimator.cancel()
    }
}
