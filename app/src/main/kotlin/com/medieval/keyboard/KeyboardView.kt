package com.medieval.keyboard

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
    var shiftState: Int = 0 // 0=lower, 1=UPPER, 2=Title

    private val keys = mutableListOf<Key>()
    private var pressedKey: Key? = null
    private var longPressTriggered = false

    private val bgColor = Color.parseColor("#1C1C1E")
    private val keyColor = Color.parseColor("#3A3A3C")
    private val keyPressedColor = Color.parseColor("#555558")
    private val specialKeyColor = Color.parseColor("#2C2C2E")
    private val textColor = Color.WHITE
    private val secondaryTextColor = Color.parseColor("#A0A0A0")
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

    private val gap = 3f * resources.displayMetrics.density
    private val rowHeight = 46f * resources.displayMetrics.density
    private val density = resources.displayMetrics.density

    private val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val row3letters = listOf("z", "x", "c", "v", "b", "n", "m")
    private val bottomRow = listOf("!#1", ",", "space", ".", "⏎")

    companion object {
        const val CODE_SHIFT = -1
        const val CODE_BACKSPACE = -2
        const val CODE_SYMBOLS = -3
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

        keys.add(Key("⇧", CODE_SHIFT, gap, y, shiftWidth, rowHeight, isSpecial = true))

        val letterStart = gap + shiftWidth + gap
        for (i in row3letters.indices) {
            val x = letterStart + i * (letterKeyWidth + gap)
            keys.add(Key(row3letters[i], row3letters[i][0].code, x, y, letterKeyWidth, rowHeight))
        }

        keys.add(Key("⌫", CODE_BACKSPACE, totalWidth - bkspWidth - gap, y, bkspWidth, rowHeight, isSpecial = true))
        y += rowHeight + gap

        // Bottom row: !#1 | , | space | . | enter
        val smallKeyWidth = keyWidth * 1.2f
        val spaceWidth = totalWidth - (smallKeyWidth * 2 + keyWidth * 2 + gap * 6)

        var bx = gap
        keys.add(Key("!#1", CODE_SYMBOLS, bx, y, smallKeyWidth, rowHeight, isSpecial = true))
        bx += smallKeyWidth + gap

        keys.add(Key(",", CODE_COMMA, bx, y, keyWidth, rowHeight))
        bx += keyWidth + gap

        keys.add(Key("Ye Olde", CODE_SPACE, bx, y, spaceWidth, rowHeight))
        bx += spaceWidth + gap

        keys.add(Key(".", CODE_PERIOD, bx, y, keyWidth, rowHeight))
        bx += keyWidth + gap

        keys.add(Key("⏎", CODE_ENTER, bx, y, smallKeyWidth, rowHeight, isSpecial = true))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)

        for (key in keys) {
            val isPressed = key == pressedKey
            keyPaint.color = when {
                isPressed -> keyPressedColor
                key.isSpecial -> specialKeyColor
                else -> keyColor
            }

            val rect = RectF(key.x, key.y, key.x + key.width, key.y + key.height)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)

            val displayLabel = getDisplayLabel(key)

            val paint = if (key.code == CODE_SPACE) {
                smallTextPaint.apply {
                    textSize = 16f * density
                    color = secondaryTextColor
                }
            } else if (key.isSpecial) {
                textPaint.apply {
                    textSize = 20f * density
                    color = textColor
                }
            } else {
                textPaint.apply {
                    textSize = 22f * density
                    color = textColor
                }
            }

            val textY = key.y + key.height / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(displayLabel, key.x + key.width / 2f, textY, paint)
        }
    }

    private fun getDisplayLabel(key: Key): String {
        if (key.isSpecial || key.code == CODE_SPACE || key.code == CODE_COMMA || key.code == CODE_PERIOD) {
            if (key.code == CODE_SHIFT) {
                return when (shiftState) {
                    1 -> "⇧"  // filled
                    2 -> "⇪"
                    else -> "⇧"
                }
            }
            return key.label
        }
        // Number row
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
                pressedKey = findKey(event.x, event.y)
                pressedKey?.let {
                    hapticFeedback()
                    if (it.code == CODE_SPACE) {
                        handler.postDelayed(longPressRunnable, 600)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
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
                handler.removeCallbacks(longPressRunnable)
                pressedKey = null
                longPressTriggered = false
                invalidate()
                return true
            }
        }
        return false
    }

    private fun findKey(x: Float, y: Float): Key? {
        return keys.find { key ->
            x >= key.x && x <= key.x + key.width &&
            y >= key.y && y <= key.y + key.height
        }
    }

    private fun hapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }
}
