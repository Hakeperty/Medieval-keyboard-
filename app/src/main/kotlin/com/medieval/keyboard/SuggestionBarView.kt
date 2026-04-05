package com.medieval.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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

    interface OnToolbarActionListener {
        fun onRageModeToggle()
        fun onRageModeLongPress()
        fun onPeriodChanged(period: Int)
        fun onIntensityChanged(intensity: Int)
        fun onCopyLastTranslation()
        fun onAutoCorrectToggle()
    }

    var listener: OnSuggestionClickListener? = null
    var toolbarListener: OnToolbarActionListener? = null
    private var suggestions: List<String> = emptyList()
    private var isLoading = false
    private var shimmerOffset = 0f

    // State
    var currentPeriod: Int = 0  // 0=Medieval, 1=Tudor, 2=Pirate
    var currentIntensity: Int = 1  // 0=Mild, 1=Olde, 2=Forsooth
    var isRageMode: Boolean = false
    var isAutoCorrectEnabled: Boolean = true

    private val periodLabels = arrayOf("⚔️ Medieval", "🏰 Tudor", "☠️ Pirate")
    private val intensityLabels = arrayOf("Mild", "Olde", "Forsooth")

    private val bgColor = Color.parseColor("#1C1C1E")
    private val toolbarBg = Color.parseColor("#2A2A2C")
    private val buttonBg = Color.parseColor("#3A3A3C")
    private val buttonActiveBg = Color.parseColor("#5A5A5C")
    private val rageRedBg = Color.parseColor("#8B0000")
    private val rageRedLight = Color.parseColor("#CC0000")
    private val accentColor = Color.parseColor("#FF9500")

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val tinyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        strokeWidth = 1f
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    // Layout regions (computed in onSizeChanged)
    private val density = resources.displayMetrics.density
    private val toolbarHeight = 32f * density
    private val suggestionAreaTop get() = toolbarHeight
    private var rageButtonRect = RectF()
    private var periodButtonRect = RectF()
    private var intensityRects = arrayOf(RectF(), RectF(), RectF())
    private var copyButtonRect = RectF()
    private var autoToggleRect = RectF()

    private var rageDownTime = 0L

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (toolbarHeight + 44f * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeToolbarLayout(w.toFloat())
    }

    private fun computeToolbarLayout(totalWidth: Float) {
        val pad = 4f * density
        val btnHeight = toolbarHeight - 6f * density
        val btnY = 3f * density
        var x = pad

        // Rage button (⚔️)
        val rageBtnW = 36f * density
        rageButtonRect = RectF(x, btnY, x + rageBtnW, btnY + btnHeight)
        x += rageBtnW + pad

        // Auto-correct toggle [⚔️ Auto]
        val autoBtnW = 56f * density
        autoToggleRect = RectF(x, btnY, x + autoBtnW, btnY + btnHeight)
        x += autoBtnW + pad

        // Period selector button
        val periodBtnW = 80f * density
        periodButtonRect = RectF(x, btnY, x + periodBtnW, btnY + btnHeight)
        x += periodBtnW + pad

        // Intensity buttons (Mild / Olde / Forsooth)
        val intBtnW = 52f * density
        for (i in 0..2) {
            intensityRects[i] = RectF(x, btnY, x + intBtnW, btnY + btnHeight)
            x += intBtnW + 2f * density
        }
        x += pad

        // Copy button (📜) on far right
        val copyBtnW = 36f * density
        copyButtonRect = RectF(totalWidth - copyBtnW - pad, btnY, totalWidth - pad, btnY + btnHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw toolbar background
        buttonPaint.color = toolbarBg
        canvas.drawRect(0f, 0f, width.toFloat(), toolbarHeight, buttonPaint)

        drawToolbar(canvas)
        drawSuggestionArea(canvas)
    }

    private fun drawToolbar(canvas: Canvas) {
        val cornerR = 6f * density

        // Rage button
        buttonPaint.color = if (isRageMode) rageRedBg else buttonBg
        canvas.drawRoundRect(rageButtonRect, cornerR, cornerR, buttonPaint)
        iconPaint.color = if (isRageMode) Color.parseColor("#FF4444") else Color.WHITE
        val rageCx = rageButtonRect.centerX()
        val rageCy = rageButtonRect.centerY() - (iconPaint.descent() + iconPaint.ascent()) / 2f
        canvas.drawText("⚔️", rageCx, rageCy, iconPaint)

        // Auto-correct toggle
        buttonPaint.color = if (isAutoCorrectEnabled) buttonActiveBg else buttonBg
        canvas.drawRoundRect(autoToggleRect, cornerR, cornerR, buttonPaint)
        tinyTextPaint.color = if (isAutoCorrectEnabled) accentColor else Color.parseColor("#888888")
        val autoTy = autoToggleRect.centerY() - (tinyTextPaint.descent() + tinyTextPaint.ascent()) / 2f
        canvas.drawText("Auto", autoToggleRect.centerX(), autoTy, tinyTextPaint)
        tinyTextPaint.color = Color.parseColor("#AAAAAA")

        // Period selector
        buttonPaint.color = buttonBg
        canvas.drawRoundRect(periodButtonRect, cornerR, cornerR, buttonPaint)
        smallTextPaint.textSize = 18f * density / density * 3f  // ~18sp
        smallTextPaint.color = Color.WHITE
        val periodTy = periodButtonRect.centerY() - (smallTextPaint.descent() + smallTextPaint.ascent()) / 2f
        canvas.drawText(periodLabels[currentPeriod], periodButtonRect.centerX(), periodTy, smallTextPaint)

        // Intensity buttons
        for (i in 0..2) {
            buttonPaint.color = if (i == currentIntensity) accentColor else buttonBg
            canvas.drawRoundRect(intensityRects[i], cornerR, cornerR, buttonPaint)
            tinyTextPaint.color = if (i == currentIntensity) Color.BLACK else Color.parseColor("#AAAAAA")
            val ity = intensityRects[i].centerY() - (tinyTextPaint.descent() + tinyTextPaint.ascent()) / 2f
            canvas.drawText(intensityLabels[i], intensityRects[i].centerX(), ity, tinyTextPaint)
        }
        tinyTextPaint.color = Color.parseColor("#AAAAAA")

        // Copy button
        buttonPaint.color = buttonBg
        canvas.drawRoundRect(copyButtonRect, cornerR, cornerR, buttonPaint)
        iconPaint.color = Color.WHITE
        val copyCy = copyButtonRect.centerY() - (iconPaint.descent() + iconPaint.ascent()) / 2f
        canvas.drawText("📜", copyButtonRect.centerX(), copyCy, iconPaint)
    }

    private fun drawSuggestionArea(canvas: Canvas) {
        val top = suggestionAreaTop
        val bottom = height.toFloat()

        if (isLoading) {
            drawShimmer(canvas, top, bottom)
            return
        }

        val currentSuggestions = suggestions
        if (currentSuggestions.isEmpty()) return

        val sectionWidth = width.toFloat() / currentSuggestions.size
        textPaint.textSize = 36f
        textPaint.color = Color.WHITE

        currentSuggestions.forEachIndexed { index, suggestion ->
            val centerX = sectionWidth * index + sectionWidth / 2
            val centerY = top + (bottom - top) / 2f + textPaint.textSize / 3f
            canvas.drawText(suggestion, centerX, centerY, textPaint)

            if (index < currentSuggestions.size - 1) {
                val dividerX = sectionWidth * (index + 1)
                canvas.drawLine(dividerX, top + 8f, dividerX, bottom - 8f, dividerPaint)
            }
        }
    }

    private fun drawShimmer(canvas: Canvas, top: Float, bottom: Float) {
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
        val barY = top + (bottom - top - barHeight) / 2f
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
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (y <= toolbarHeight) {
                    // Track rage button for long-press
                    if (rageButtonRect.contains(x, y)) {
                        rageDownTime = System.currentTimeMillis()
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (y <= toolbarHeight) {
                    handleToolbarTap(x, y)
                    return true
                }
                // Suggestion area tap
                val currentSuggestions = suggestions
                if (currentSuggestions.isNotEmpty() && y > suggestionAreaTop) {
                    val sectionWidth = width.toFloat() / currentSuggestions.size
                    val index = (x / sectionWidth).toInt().coerceIn(0, currentSuggestions.size - 1)
                    listener?.onSuggestionClicked(currentSuggestions[index])
                    return true
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleToolbarTap(x: Float, y: Float) {
        when {
            rageButtonRect.contains(x, y) -> {
                val holdTime = System.currentTimeMillis() - rageDownTime
                if (holdTime >= 1000) {
                    toolbarListener?.onRageModeLongPress()
                } else {
                    toolbarListener?.onRageModeToggle()
                }
            }
            autoToggleRect.contains(x, y) -> {
                toolbarListener?.onAutoCorrectToggle()
            }
            periodButtonRect.contains(x, y) -> {
                currentPeriod = (currentPeriod + 1) % 3
                toolbarListener?.onPeriodChanged(currentPeriod)
                invalidate()
            }
            copyButtonRect.contains(x, y) -> {
                toolbarListener?.onCopyLastTranslation()
            }
            else -> {
                for (i in 0..2) {
                    if (intensityRects[i].contains(x, y)) {
                        currentIntensity = i
                        toolbarListener?.onIntensityChanged(i)
                        invalidate()
                        break
                    }
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (shimmerAnimator.isRunning) shimmerAnimator.cancel()
    }
}
