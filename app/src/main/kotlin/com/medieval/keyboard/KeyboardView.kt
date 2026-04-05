package com.medieval.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

data class Key(
    val label: String,
    val code: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val isSpecial: Boolean = false
)

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnKeyboardActionListener {
        fun onKeyPress(primaryCode: Int, label: String)
        fun onLongPressSpace()
    }

    var listener: OnKeyboardActionListener? = null
    var shiftState: Int = 0
    var layoutMode: Int = 0 // 0=letters, 1=symbols1, 2=symbols2, 3=emoji
    private var _rageMode: Boolean = false
    val isRageMode: Boolean get() = _rageMode

    private val keys = mutableListOf<Key>()
    private var pressedKey: Key? = null
    private var longPressTriggered = false
    private var backspaceHeld = false
    private var backspaceRepeatRunnable: Runnable? = null

    private val bgColor = Color.parseColor("#1C1C1E")
    private val keyColor = Color.parseColor("#3A3A3C")
    private val keyPressedColor = Color.parseColor("#555558")
    private val specialKeyColor = Color.parseColor("#2C2C2E")
    private val textColor = Color.WHITE
    private val secondaryTextColor = Color.parseColor("#A0A0A0")
    private val rageKeyColor = Color.parseColor("#4A1010")
    private val ragePulseColor = Color.parseColor("#6A1515")
    private val cornerRadius = 12f * resources.displayMetrics.density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val gap = 3f * resources.displayMetrics.density
    private val rowHeight = 46f * resources.displayMetrics.density
    private val density = resources.displayMetrics.density

    private var ragePulseAlpha = 0f
    private val ragePulseAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            ragePulseAlpha = animation.animatedValue as Float
            if (isRageMode) invalidate()
        }
    }

    // Letter rows
    private val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val row3letters = listOf("z", "x", "c", "v", "b", "n", "m")

    // Symbol rows page 1
    private val sym1Row1 = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val sym1Row2 = listOf("-", "_", "=", "+", "[", "]", "{", "}", "\\")
    private val sym1Row3 = listOf("/", ":", ";", "'", "\"", "?", "|")

    // Symbol rows page 2
    private val sym2Row1 = listOf("~", "`", "<", ">", "\u20AC", "\u00A3", "\u00A5", "\u00A2", "\u00B0", "\u2022")
    private val sym2Row2 = listOf("\u00A9", "\u00AE", "\u2122", "\u00B6", "\u00A7", "\u00B1", "\u00D7", "\u00F7", "\u00AC")
    private val sym2Row3 = listOf("\u2026", "\u00AB", "\u00BB", "\u2014", "\u00BF", "\u00A1", "\u2020")

    // Emoji rows
    private val emojiRow0 = listOf("\uD83D\uDE02", "\uD83D\uDE2D", "\uD83E\uDD7A", "\uD83D\uDE0D", "\uD83E\uDD70", "\uD83D\uDE0A", "\uD83D\uDE0F", "\uD83D\uDE08", "\uD83E\uDD14", "\uD83D\uDE31")
    private val emojiRow1 = listOf("\uD83D\uDC80", "\uD83D\uDD25", "\u2764\uFE0F", "\u2728", "\uD83C\uDF89", "\uD83D\uDC51", "\uD83D\uDCAA", "\uD83D\uDE4F", "\uD83D\uDC4B", "\uD83D\uDCAF")
    private val emojiRow2 = listOf("\uD83D\uDE44", "\uD83D\uDE24", "\uD83E\uDD23", "\uD83D\uDE29", "\uD83E\uDD21", "\uD83D\uDC40", "\uD83D\uDC85", "\uD83E\uDD26", "\uD83E\uDEE1", "\uD83E\uDEE0")
    private val emojiRow3 = listOf("\uD83D\uDE34", "\uD83E\uDD2E", "\uD83D\uDC94", "\uD83D\uDE21", "\uD83E\uDD75", "\uD83E\uDD76", "\uD83E\uDD17", "\uD83E\uDD2B", "\uD83E\uDD2D", "\uD83D\uDE07")

    companion object {
        const val CODE_SHIFT = -1
        const val CODE_BACKSPACE = -2
        const val CODE_SYMBOLS = -3
        const val CODE_SYMBOLS2 = -4
        const val CODE_ABC = -5
        const val CODE_EMOJI = -6
        const val CODE_DIRECT_CHAR = -10
        const val CODE_SPACE = 32
        const val CODE_ENTER = 10
        const val CODE_COMMA = 44
        const val CODE_PERIOD = 46
    }

    private val longPressRunnable = Runnable {
        pressedKey?.let { key ->
            if (key.code == CODE_SPACE) {
                longPressTriggered = true
                hapticFeedback()
                listener?.onLongPressSpace()
            }
        }
    }

    fun setRageMode(enabled: Boolean) {
        _rageMode = enabled
        if (enabled) {
            if (!ragePulseAnimator.isRunning) ragePulseAnimator.start()
        } else {
            ragePulseAnimator.cancel()
            ragePulseAlpha = 0f
        }
        invalidate()
    }

    fun switchLayout(mode: Int) {
        layoutMode = mode
        if (width > 0) buildKeys(width.toFloat())
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val totalHeight = (rowHeight * 5 + gap * 6).toInt()
        setMeasuredDimension(w, totalHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildKeys(w.toFloat())
    }

    private fun buildKeys(totalWidth: Float) {
        keys.clear()
        when (layoutMode) {
            0 -> buildLetterKeys(totalWidth)
            1 -> buildSymbolKeys(totalWidth, sym1Row1, sym1Row2, sym1Row3, "#+=", CODE_SYMBOLS2)
            2 -> buildSymbolKeys(totalWidth, sym2Row1, sym2Row2, sym2Row3, "!#1", CODE_SYMBOLS)
            3 -> buildEmojiKeys(totalWidth)
        }
    }

    private fun buildLetterKeys(totalWidth: Float) {
        val keyWidth = (totalWidth - gap * 11) / 10f
        var y = gap

        // Number row
        for (i in numberRow.indices) {
            val x = gap + i * (keyWidth + gap)
            keys.add(Key(numberRow[i], numberRow[i][0].code, x, y, keyWidth, rowHeight))
        }
        y += rowHeight + gap

        // QWERTY row
        for (i in row1.indices) {
            val x = gap + i * (keyWidth + gap)
            keys.add(Key(row1[i], row1[i][0].code, x, y, keyWidth, rowHeight))
        }
        y += rowHeight + gap

        // ASDF row (9 keys, centered)
        val row2Offset = (totalWidth - (keyWidth * 9 + gap * 8)) / 2f
        for (i in row2.indices) {
            val x = row2Offset + i * (keyWidth + gap)
            keys.add(Key(row2[i], row2[i][0].code, x, y, keyWidth, rowHeight))
        }
        y += rowHeight + gap

        // Shift + ZXCVBNM + Backspace
        val shiftWidth = keyWidth * 1.4f
        val bkspWidth = keyWidth * 1.4f
        val letterAreaWidth = totalWidth - shiftWidth - bkspWidth - gap * 4
        val letterKeyWidth = (letterAreaWidth - gap * 6) / 7f

        keys.add(Key("\u21E7", CODE_SHIFT, gap, y, shiftWidth, rowHeight, isSpecial = true))
        val letterStart = gap + shiftWidth + gap
        for (i in row3letters.indices) {
            val x = letterStart + i * (letterKeyWidth + gap)
            keys.add(Key(row3letters[i], row3letters[i][0].code, x, y, letterKeyWidth, rowHeight))
        }
        keys.add(Key("\u232B", CODE_BACKSPACE, totalWidth - bkspWidth - gap, y, bkspWidth, rowHeight, isSpecial = true))
        y += rowHeight + gap

        // Bottom row: !#1 | emoji | , | Ye Olde | . | Enter
        buildBottomRow(totalWidth, y, "!#1", CODE_SYMBOLS, "\uD83D\uDE0A", CODE_EMOJI)
    }

    private fun buildSymbolKeys(totalWidth: Float, symRow1: List<String>, symRow2: List<String>, symRow3: List<String>, toggleLabel: String, toggleCode: Int) {
        val keyWidth = (totalWidth - gap * 11) / 10f
        var y = gap

        // Number row
        for (i in numberRow.indices) {
            val x = gap + i * (keyWidth + gap)
            keys.add(Key(numberRow[i], CODE_DIRECT_CHAR, x, y, keyWidth, rowHeight))
        }
        y += rowHeight + gap

        // Symbol row 1 (10 keys)
        for (i in symRow1.indices) {
            val x = gap + i * (keyWidth + gap)
            keys.add(Key(symRow1[i], CODE_DIRECT_CHAR, x, y, keyWidth, rowHeight))
        }
        y += rowHeight + gap

        // Symbol row 2 (9 keys, centered)
        val row2Offset = (totalWidth - (keyWidth * 9 + gap * 8)) / 2f
        for (i in symRow2.indices) {
            val x = row2Offset + i * (keyWidth + gap)
            keys.add(Key(symRow2[i], CODE_DIRECT_CHAR, x, y, keyWidth, rowHeight))
        }
        y += rowHeight + gap

        // Toggle + symbol row 3 (7 keys) + Backspace
        val toggleWidth = keyWidth * 1.4f
        val bkspWidth = keyWidth * 1.4f
        val charAreaWidth = totalWidth - toggleWidth - bkspWidth - gap * 4
        val charKeyWidth = (charAreaWidth - gap * 6) / 7f

        keys.add(Key(toggleLabel, toggleCode, gap, y, toggleWidth, rowHeight, isSpecial = true))
        val charStart = gap + toggleWidth + gap
        for (i in symRow3.indices) {
            val x = charStart + i * (charKeyWidth + gap)
            keys.add(Key(symRow3[i], CODE_DIRECT_CHAR, x, y, charKeyWidth, rowHeight))
        }
        keys.add(Key("\u232B", CODE_BACKSPACE, totalWidth - bkspWidth - gap, y, bkspWidth, rowHeight, isSpecial = true))
        y += rowHeight + gap

        // Bottom: ABC | emoji | , | space | . | enter
        buildBottomRow(totalWidth, y, "ABC", CODE_ABC, "\uD83D\uDE0A", CODE_EMOJI)
    }

    private fun buildEmojiKeys(totalWidth: Float) {
        val keyWidth = (totalWidth - gap * 11) / 10f
        var y = gap

        val emojiRows = listOf(emojiRow0, emojiRow1, emojiRow2, emojiRow3)
        for (row in emojiRows) {
            for (i in row.indices) {
                val x = gap + i * (keyWidth + gap)
                keys.add(Key(row[i], CODE_DIRECT_CHAR, x, y, keyWidth, rowHeight))
            }
            y += rowHeight + gap
        }

        // Bottom: ABC | !#1 | , | space | . | enter
        buildBottomRow(totalWidth, y, "ABC", CODE_ABC, "!#1", CODE_SYMBOLS)
    }

    private fun buildBottomRow(totalWidth: Float, y: Float, leftLabel: String, leftCode: Int, secondLabel: String, secondCode: Int) {
        val keyWidth = (totalWidth - gap * 11) / 10f
        val smallKeyWidth = keyWidth * 1.2f
        val emojiKeyWidth = keyWidth
        val spaceWidth = totalWidth - (smallKeyWidth * 2 + keyWidth * 3 + gap * 7)

        var bx = gap
        keys.add(Key(leftLabel, leftCode, bx, y, smallKeyWidth, rowHeight, isSpecial = true))
        bx += smallKeyWidth + gap

        keys.add(Key(secondLabel, secondCode, bx, y, emojiKeyWidth, rowHeight, isSpecial = true))
        bx += emojiKeyWidth + gap

        keys.add(Key(",", CODE_COMMA, bx, y, keyWidth, rowHeight))
        bx += keyWidth + gap

        keys.add(Key("Ye Olde", CODE_SPACE, bx, y, spaceWidth, rowHeight))
        bx += spaceWidth + gap

        keys.add(Key(".", CODE_PERIOD, bx, y, keyWidth, rowHeight))
        bx += keyWidth + gap

        keys.add(Key("\u23CE", CODE_ENTER, bx, y, smallKeyWidth, rowHeight, isSpecial = true))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)

        for (key in keys) {
            val isPressed = key == pressedKey
            keyPaint.color = when {
                isPressed -> keyPressedColor
                isRageMode && !key.isSpecial -> {
                    val r = lerp(Color.red(rageKeyColor), Color.red(ragePulseColor), ragePulseAlpha)
                    val g = lerp(Color.green(rageKeyColor), Color.green(ragePulseColor), ragePulseAlpha)
                    val b = lerp(Color.blue(rageKeyColor), Color.blue(ragePulseColor), ragePulseAlpha)
                    Color.rgb(r, g, b)
                }
                key.isSpecial -> specialKeyColor
                else -> keyColor
            }

            val rect = RectF(key.x, key.y, key.x + key.width, key.y + key.height)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)

            val displayLabel = getDisplayLabel(key)

            if (layoutMode == 3 && key.code == CODE_DIRECT_CHAR) {
                // Emoji - use larger paint
                emojiPaint.textSize = 22f * density
                val textY = key.y + key.height / 2f - (emojiPaint.descent() + emojiPaint.ascent()) / 2f
                canvas.drawText(displayLabel, key.x + key.width / 2f, textY, emojiPaint)
            } else if (key.code == CODE_SPACE) {
                smallTextPaint.textSize = 16f * density
                smallTextPaint.color = if (isRageMode) Color.parseColor("#FF6666") else secondaryTextColor
                val textY = key.y + key.height / 2f - (smallTextPaint.descent() + smallTextPaint.ascent()) / 2f
                canvas.drawText(displayLabel, key.x + key.width / 2f, textY, smallTextPaint)
            } else if (key.isSpecial) {
                textPaint.textSize = 18f * density
                textPaint.color = textColor
                val textY = key.y + key.height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(displayLabel, key.x + key.width / 2f, textY, textPaint)
            } else {
                textPaint.textSize = 22f * density
                textPaint.color = textColor
                val textY = key.y + key.height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(displayLabel, key.x + key.width / 2f, textY, textPaint)
            }
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }

    private fun getDisplayLabel(key: Key): String {
        if (layoutMode != 0) return key.label
        if (key.isSpecial || key.code == CODE_SPACE || key.code == CODE_COMMA || key.code == CODE_PERIOD || key.code == CODE_EMOJI) {
            if (key.code == CODE_SHIFT) {
                return when (shiftState) {
                    1 -> "\u21E7"
                    2 -> "\u21EA"
                    else -> "\u21E7"
                }
            }
            return key.label
        }
        if (key.label[0].isDigit()) return key.label
        return when (shiftState) {
            1 -> key.label.uppercase()
            2 -> key.label.replaceFirstChar { it.uppercase() }
            else -> key.label.lowercase()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                backspaceHeld = false
                pressedKey = findKey(event.x, event.y)
                pressedKey?.let {
                    hapticFeedback()
                    if (it.code == CODE_SPACE) {
                        handler?.postDelayed(longPressRunnable, 600)
                    }
                    if (it.code == CODE_BACKSPACE) {
                        backspaceHeld = true
                        startBackspaceRepeat()
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler?.removeCallbacks(longPressRunnable)
                stopBackspaceRepeat()
                backspaceHeld = false
                pressedKey?.let { key ->
                    if (!longPressTriggered) {
                        val label = getDisplayLabel(key)
                        listener?.onKeyPress(key.code, label)
                    }
                }
                pressedKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler?.removeCallbacks(longPressRunnable)
                stopBackspaceRepeat()
                backspaceHeld = false
                pressedKey = null
                longPressTriggered = false
                invalidate()
                return true
            }
        }
        return false
    }

    private fun startBackspaceRepeat() {
        backspaceRepeatRunnable = object : Runnable {
            override fun run() {
                if (backspaceHeld) {
                    listener?.onKeyPress(CODE_BACKSPACE, "\u232B")
                    hapticFeedbackLight()
                    handler?.postDelayed(this, 50)
                }
            }
        }
        handler?.postDelayed(backspaceRepeatRunnable!!, 400)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeatRunnable?.let { handler?.removeCallbacks(it) }
        backspaceRepeatRunnable = null
    }

    private fun findKey(x: Float, y: Float): Key? {
        return keys.find { key ->
            x >= key.x && x <= key.x + key.width &&
            y >= key.y && y <= key.y + key.height
        }
    }

    private fun hapticFeedback() {
        vibrate(15, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    private fun hapticFeedbackLight() {
        vibrate(8, 40)
    }

    private fun vibrate(durationMs: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            }
        } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ragePulseAnimator.cancel()
        stopBackspaceRepeat()
    }
}
