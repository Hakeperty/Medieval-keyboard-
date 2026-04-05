package com.medieval.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import kotlinx.coroutines.*

class MedievalKeyboardService : InputMethodService(),
    KeyboardView.OnKeyboardActionListener,
    SuggestionBarView.OnSuggestionClickListener,
    SuggestionBarView.OnToolbarActionListener {

    private var keyboardView: KeyboardView? = null
    private var suggestionBar: SuggestionBarView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var suggestionJob: Job? = null
    private val composingWord = StringBuilder()
    private var isInputActive = false
    private var lastCorrectedWord = ""
    private var lastTranslatedSentence = ""

    // Feature state
    private var currentPeriod: Int = 0    // 0=Medieval, 1=Tudor, 2=Pirate
    private var currentIntensity: Int = 1 // 0=Mild, 1=Olde, 2=Forsooth
    private var isRageMode: Boolean = false
    private var isAutoCorrectEnabled: Boolean = true
    private val rageHandler = Handler(Looper.getMainLooper())
    private var rageTimeoutRunnable: Runnable? = null

    override fun onCreateInputView(): View? {
        return try {
            val layout = layoutInflater.inflate(R.layout.keyboard_view, null)
            keyboardView = layout.findViewById(R.id.keyboard_view)
            suggestionBar = layout.findViewById(R.id.suggestion_bar)
            keyboardView?.listener = this
            suggestionBar?.listener = this
            suggestionBar?.toolbarListener = this
            layout
        } catch (e: Exception) {
            null
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composingWord.clear()
        suggestionBar?.clearSuggestions()
        isInputActive = true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isInputActive = true
        composingWord.clear()
        suggestionBar?.clearSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        suggestionJob?.cancel()
        composingWord.clear()
        suggestionBar?.clearSuggestions()
        isInputActive = false
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        suggestionJob?.cancel()
        composingWord.clear()
        suggestionBar?.clearSuggestions()
        isInputActive = false
        super.onFinishInput()
    }

    // === Toolbar Actions ===

    override fun onRageModeToggle() {
        // Single tap — just a visual indicator, no action needed
    }

    override fun onRageModeLongPress() {
        isRageMode = !isRageMode
        keyboardView?.setRageMode(isRageMode)
        suggestionBar?.isRageMode = isRageMode
        suggestionBar?.invalidate()

        if (isRageMode) {
            hapticRageModeActivation()
            // Auto-deactivate after 30 seconds
            rageTimeoutRunnable?.let { rageHandler.removeCallbacks(it) }
            rageTimeoutRunnable = Runnable {
                isRageMode = false
                keyboardView?.setRageMode(false)
                suggestionBar?.isRageMode = false
                suggestionBar?.invalidate()
            }
            rageHandler.postDelayed(rageTimeoutRunnable!!, 30_000)
        } else {
            rageTimeoutRunnable?.let { rageHandler.removeCallbacks(it) }
        }
    }

    override fun onPeriodChanged(period: Int) {
        currentPeriod = period
        hapticSuggestionTap()
    }

    override fun onIntensityChanged(intensity: Int) {
        currentIntensity = intensity
        hapticSuggestionTap()
    }

    override fun onCopyLastTranslation() {
        if (lastTranslatedSentence.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("medieval", lastTranslatedSentence))
            Toast.makeText(this, "Proclamation copied to thine clipboard, milord!", Toast.LENGTH_SHORT).show()
            hapticSuggestionTap()
        }
    }

    override fun onAutoCorrectToggle() {
        isAutoCorrectEnabled = !isAutoCorrectEnabled
        suggestionBar?.isAutoCorrectEnabled = isAutoCorrectEnabled
        suggestionBar?.invalidate()
        hapticSuggestionTap()
    }

    override fun onRewriteContent() {
        val ic = currentInputConnection ?: return
        hapticSuggestionTap()
        elaborateRewriteContent(ic)
    }

    // === Key handling ===

    override fun onKeyPress(primaryCode: Int, label: String) {
        val ic = currentInputConnection ?: return
        when (primaryCode) {
            KeyboardView.CODE_SHIFT -> cycleShift()
            KeyboardView.CODE_BACKSPACE -> handleBackspace(ic)
            KeyboardView.CODE_ENTER -> handleEnter(ic)
            KeyboardView.CODE_SPACE -> handleSpace(ic)
            KeyboardView.CODE_SYMBOLS -> switchLayout(1)
            KeyboardView.CODE_SYMBOLS2 -> switchLayout(2)
            KeyboardView.CODE_ABC -> switchLayout(0)
            KeyboardView.CODE_EMOJI -> switchLayout(3)
            KeyboardView.CODE_DIRECT_CHAR -> handleDirectChar(ic, label)
            KeyboardView.CODE_COMMA -> handlePunctuation(ic, ",")
            KeyboardView.CODE_PERIOD -> handlePunctuation(ic, ".")
            else -> handleCharacter(ic, label)
        }
    }

    override fun onLongPressSpace() {
        val ic = currentInputConnection ?: return
        translateAllContent(ic)
    }

    override fun onSuggestionClicked(suggestion: String) {
        val ic = currentInputConnection ?: return
        hapticSuggestionTap()
        if (composingWord.isNotEmpty()) {
            ic.finishComposingText()
            val before = ic.getTextBeforeCursor(composingWord.length, 0) ?: ""
            if (before.toString().equals(composingWord.toString(), ignoreCase = true)) {
                ic.deleteSurroundingText(composingWord.length, 0)
            }
            ic.commitText(suggestion + " ", 1)
            composingWord.clear()
        } else if (lastCorrectedWord.isNotEmpty()) {
            val beforeText = ic.getTextBeforeCursor(lastCorrectedWord.length + 1, 0)?.toString() ?: ""
            val withSpace = lastCorrectedWord + " "
            if (beforeText.endsWith(withSpace)) {
                ic.deleteSurroundingText(withSpace.length, 0)
                ic.commitText(suggestion + " ", 1)
            } else if (beforeText.endsWith(lastCorrectedWord)) {
                ic.deleteSurroundingText(lastCorrectedWord.length, 0)
                ic.commitText(suggestion, 1)
            }
        }
        lastCorrectedWord = suggestion
        suggestionBar?.clearSuggestions()
    }

    // === Emoji interception ===

    override fun onWindowShown() {
        super.onWindowShown()
    }

    /**
     * Intercept text commits to check for emoji replacements.
     */
    private fun processTextForEmoji(text: String): String {
        var result = text
        for ((emoji, replacement) in MedievalFallbackMap.emojiMap) {
            if (result.contains(emoji)) {
                result = result.replace(emoji, replacement)
            }
        }
        return result
    }

    // === Character input ===

    private fun switchLayout(mode: Int) {
        if (composingWord.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composingWord.clear()
        }
        keyboardView?.switchLayout(mode)
    }

    private fun handleDirectChar(ic: InputConnection, label: String) {
        // Finish composing first
        if (composingWord.isNotEmpty()) {
            ic.finishComposingText()
            composingWord.clear()
        }
        // Check if it's an emoji that should be replaced
        val emojiReplacement = MedievalFallbackMap.translateEmoji(label)
        if (emojiReplacement != null) {
            ic.commitText(emojiReplacement, 1)
        } else {
            ic.commitText(label, 1)
        }
    }

    private fun handleCharacter(ic: InputConnection, label: String) {
        val kbView = keyboardView ?: return
        val char = when (kbView.shiftState) {
            1 -> label.uppercase()
            2 -> if (composingWord.isEmpty()) label.uppercase() else label.lowercase()
            else -> label.lowercase()
        }
        composingWord.append(char)

        if (isAutoCorrectEnabled) {
            val preview = MedievalFallbackMap.translateWithPeriod(composingWord.toString().lowercase(), currentPeriod)
            if (preview != null) {
                val primary = preview.split(",").first().trim()
                ic.setComposingText(primary, 1)
            } else {
                ic.setComposingText(composingWord.toString(), 1)
            }
        } else {
            ic.setComposingText(composingWord.toString(), 1)
        }
        fetchSuggestions(composingWord.toString())

        if (kbView.shiftState == 1) {
            kbView.shiftState = 0
            kbView.invalidate()
        }
    }

    private fun handleSpace(ic: InputConnection) {
        hapticTranslation()
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            composingWord.clear()
            if (isAutoCorrectEnabled) {
                autoCorrectAndCommit(ic, word, " ")
            } else {
                ic.finishComposingText()
                ic.commitText(" ", 1)
            }
        } else {
            ic.commitText(" ", 1)
        }
        suggestionBar?.clearSuggestions()
    }

    private fun handlePunctuation(ic: InputConnection, punct: String) {
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            composingWord.clear()
            if (isAutoCorrectEnabled) {
                autoCorrectAndCommit(ic, word, punct)
            } else {
                ic.finishComposingText()
                ic.commitText(punct, 1)
            }
        } else {
            ic.commitText(punct, 1)
        }
        suggestionBar?.clearSuggestions()
    }

    private fun autoCorrectAndCommit(ic: InputConnection, word: String, suffix: String) {
        // Check for emoji
        val emojiResult = MedievalFallbackMap.translateEmoji(word)
        if (emojiResult != null) {
            ic.finishComposingText()
            val previewLen = word.length
            ic.deleteSurroundingText(previewLen, 0)
            val finalText = if (isRageMode) emojiResult.uppercase() else emojiResult
            ic.commitText(finalText + suffix, 1)
            lastCorrectedWord = finalText
            return
        }

        // 1. Check local cache first (instant)
        val cached = TranslationCache.get(word)
        if (cached != null) {
            val primary = cached.split(",").first().trim()
            ic.finishComposingText()
            val previewLen = MedievalFallbackMap.translateWithPeriod(word.lowercase(), currentPeriod)
                ?.split(",")?.first()?.trim()?.length ?: word.length
            ic.deleteSurroundingText(previewLen, 0)
            val finalText = maybeAddRageCry(primary)
            ic.commitText(finalText + suffix, 1)
            lastCorrectedWord = finalText
            lastTranslatedSentence = finalText
            suggestionBar?.setSuggestions(cached.split(",").map { it.trim() }.take(3))
            return
        }

        // 2. Check fallback map (instant)
        val fallback = MedievalFallbackMap.translateWithPeriod(word.lowercase(), currentPeriod)
        if (fallback != null) {
            val primary = fallback.split(",").first().trim()
            ic.finishComposingText()
            ic.deleteSurroundingText(primary.length, 0)
            val finalText = maybeAddRageCry(primary)
            ic.commitText(finalText + suffix, 1)
            TranslationCache.put(word, fallback)
            lastCorrectedWord = finalText
            lastTranslatedSentence = finalText
            suggestionBar?.setSuggestions(fallback.split(",").map { it.trim() }.take(3))
            return
        }

        // 3. No instant match — commit as-is, then try API in background
        ic.finishComposingText()
        ic.commitText(suffix, 1)
        lastCorrectedWord = word
        translateAndReplaceAsync(word, suffix)
    }

    private fun maybeAddRageCry(text: String): String {
        if (!isRageMode) return text
        val cry = MedievalFallbackMap.rageCries.random()
        return "$text $cry"
    }

    private fun handleBackspace(ic: InputConnection) {
        if (composingWord.isNotEmpty()) {
            composingWord.deleteCharAt(composingWord.length - 1)
            if (composingWord.isEmpty()) {
                ic.finishComposingText()
                ic.deleteSurroundingText(1, 0)
                suggestionBar?.clearSuggestions()
            } else {
                if (isAutoCorrectEnabled) {
                    val preview = MedievalFallbackMap.translateWithPeriod(composingWord.toString().lowercase(), currentPeriod)
                    if (preview != null) {
                        val primary = preview.split(",").first().trim()
                        ic.setComposingText(primary, 1)
                    } else {
                        ic.setComposingText(composingWord.toString(), 1)
                    }
                } else {
                    ic.setComposingText(composingWord.toString(), 1)
                }
                fetchSuggestions(composingWord.toString())
            }
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun handleEnter(ic: InputConnection) {
        if (composingWord.isNotEmpty()) {
            ic.finishComposingText()
            composingWord.clear()
        }
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        suggestionBar?.clearSuggestions()
    }

    private fun cycleShift() {
        val kbView = keyboardView ?: return
        kbView.shiftState = (kbView.shiftState + 1) % 3
        kbView.invalidate()
    }

    private fun translateAndReplaceAsync(word: String, suffix: String) {
        serviceScope.launch {
            try {
                val translated = NvidiaApiClient.translateWord(word, currentPeriod, currentIntensity, isRageMode)
                val ic = currentInputConnection
                if (ic != null && isInputActive && translated != null) {
                    val primary = translated.split(",").first().trim()
                    ic.deleteSurroundingText(word.length + suffix.length, 0)
                    val finalText = maybeAddRageCry(primary)
                    ic.commitText(finalText + suffix, 1)
                    lastTranslatedSentence = finalText
                    suggestionBar?.setSuggestions(translated.split(",").map { it.trim() }.take(3))
                }
            } catch (_: Exception) {}
        }
    }

    private fun translateAllContent(ic: InputConnection) {
        val before = try {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        } catch (_: Exception) { null }
        val fullText = before?.text?.toString() ?: return
        if (fullText.isBlank()) return

        // Process emoji first
        val emojiProcessed = processTextForEmoji(fullText)

        suggestionBar?.setLoading(true)
        serviceScope.launch {
            try {
                val translated = NvidiaApiClient.translateSentence(emojiProcessed, currentPeriod, currentIntensity, isRageMode)
                val freshIc = currentInputConnection
                if (freshIc != null && isInputActive && translated != null) {
                    val finalText = if (isRageMode) {
                        val cry = MedievalFallbackMap.rageCries.random()
                        "$translated $cry"
                    } else translated
                    freshIc.performContextMenuAction(android.R.id.selectAll)
                    freshIc.commitText(finalText, 1)
                    lastTranslatedSentence = finalText
                }
            } catch (_: Exception) {
            } finally {
                suggestionBar?.setLoading(false)
            }
        }
    }

    private fun elaborateRewriteContent(ic: InputConnection) {
        val before = try {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        } catch (_: Exception) { null }
        val fullText = before?.text?.toString() ?: return
        if (fullText.isBlank()) return

        val emojiProcessed = processTextForEmoji(fullText)

        suggestionBar?.setLoading(true)
        serviceScope.launch {
            try {
                val translated = NvidiaApiClient.elaborateRewrite(emojiProcessed, currentPeriod, currentIntensity, isRageMode)
                val freshIc = currentInputConnection
                if (freshIc != null && isInputActive && translated != null) {
                    val finalText = if (isRageMode) {
                        val cry = MedievalFallbackMap.rageCries.random()
                        "$translated $cry"
                    } else translated
                    freshIc.performContextMenuAction(android.R.id.selectAll)
                    freshIc.commitText(finalText, 1)
                    lastTranslatedSentence = finalText
                }
            } catch (_: Exception) {
            } finally {
                suggestionBar?.setLoading(false)
            }
        }
    }

    private fun fetchSuggestions(word: String) {
        suggestionJob?.cancel()
        if (word.length < 2) {
            suggestionBar?.clearSuggestions()
            return
        }
        suggestionBar?.setLoading(true)
        suggestionJob = serviceScope.launch {
            try {
                val suggestions = NvidiaApiClient.getSuggestions(word, currentPeriod, currentIntensity)
                if (isActive && isInputActive) {
                    if (suggestions.isNotEmpty()) {
                        suggestionBar?.setSuggestions(suggestions)
                    } else {
                        suggestionBar?.clearSuggestions()
                    }
                }
            } catch (_: Exception) {
                if (isActive) suggestionBar?.clearSuggestions()
            }
        }
    }

    // === Medieval Haptics ===

    private fun hapticTranslation() {
        // Space triggers translation: 3 quick pulses
        serviceScope.launch(Dispatchers.Main) {
            repeat(3) {
                vibrate(12, VibrationEffect.DEFAULT_AMPLITUDE)
                delay(40)
            }
        }
    }

    private fun hapticSuggestionTap() {
        vibrate(40, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    private fun hapticRageModeActivation() {
        serviceScope.launch(Dispatchers.Main) {
            repeat(3) {
                vibrate(60, 255)
                delay(80)
            }
        }
    }

    private fun vibrate(durationMs: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isInputActive = false
        rageTimeoutRunnable?.let { rageHandler.removeCallbacks(it) }
        serviceScope.cancel()
        super.onDestroy()
    }
}
