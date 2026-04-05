package com.medieval.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(0xFF1C1C1E.toInt())
        }

        val title = TextView(this).apply {
            text = "⚔️ Medieval Keyboard"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Translate thy modern tongue into ye olde English"
            textSize = 14f
            setTextColor(0xFFA0A0A0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 64)
        }

        val step1Label = TextView(this).apply {
            text = "Step 1: Enable the keyboard"
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 12)
        }

        val enableBtn = Button(this).apply {
            text = "Open Input Method Settings"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3A3A3C.toInt())
            setPadding(32, 24, 32, 24)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }

        val step2Label = TextView(this).apply {
            text = "\nStep 2: Switch to Medieval Keyboard"
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 32, 0, 12)
        }

        val switchBtn = Button(this).apply {
            text = "Choose Input Method"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3A3A3C.toInt())
            setPadding(32, 24, 32, 24)
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }

        val hint = TextView(this).apply {
            text = "\nOnce enabled, open any app and start typing.\nThy words shall be translated into medieval English!"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(step1Label)
        layout.addView(enableBtn)
        layout.addView(step2Label)
        layout.addView(switchBtn)
        layout.addView(hint)

        setContentView(layout)
    }
}
