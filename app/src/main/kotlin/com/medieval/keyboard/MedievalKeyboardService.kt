package com.medieval.keyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.*

class MedievalKeyboardService : InputMethodService(),
    KeyboardView.OnKeyboardActionListener,
    SuggestionBarView.OnSuggestionClickListener {

    private lateinit var keyboardView: KeyboardView
    private lateinit var suggestionBar: SuggestionBarView
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var suggestionJob: Job? = null
    private var composingWord = StringBuilder()

    override fun onCreateInputView(): View {
        val layout = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = layout.findViewById(R.id.keyboard_view)
        suggestionBar = layout.findViewById(R.id.suggestion_bar)
        keyboardView.listener = this
        suggestionBar.listener = this
        return layout
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composingWord.clear()
        suggestionBar.clearSuggestions()
    }

    override fun onKeyPress(primaryCode: Int, label: String) {
        val ic = currentInputConnection ?: return
        when (primaryCode) {
            KeyboardView.CODE_SHIFT -> cycleShift()
            KeyboardView.CODE_BACKSPACE -> handleBackspace(ic)
            KeyboardView.CODE_ENTER -> handleEnter(ic)
            KeyboardView.CODE_SPACE -> handleSpace(ic)
            KeyboardView.CODE_SYMBOLS -> {} // future symbol layer
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
        if (composingWord.isNotEmpty()) {
            ic.finishComposingText()
            val before = ic.getTextBeforeCursor(composingWord.length, 0) ?: ""
            if (before.toString().equals(composingWord.toString(), ignoreCase = true)) {
                ic.deleteSurroundingText(composingWord.length, 0)
            }
        }
        ic.commitText(suggestion, 1)
        composingWord.clear()
        suggestionBar.clearSuggestions()
    }

    private fun handleCharacter(ic: InputConnection, label: String) {
        val char = when (keyboardView.shiftState) {
            1 -> label.uppercase()
            2 -> if (composingWord.isEmpty()) label.uppercase() else label.lowercase()
            else -> label.lowercase()
        }
        composingWord.append(char)
        ic.setComposingText(composingWord.toString(), 1)
        fetchSuggestions(composingWord.toString())

        // Auto-reset shift after one character in UPPERCASE mode
        if (keyboardView.shiftState == 1) {
            keyboardView.shiftState = 0
            keyboardView.invalidate()
        }
    }

    private fun handleSpace(ic: InputConnection) {
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            composingWord.clear()
            ic.finishComposingText()
            translateAndReplace(ic, word)
        } else {
            ic.commitText(" ", 1)
        }
        suggestionBar.clearSuggestions()
    }

    private fun handlePunctuation(ic: InputConnection, punct: String) {
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            composingWord.clear()
            ic.finishComposingText()
            translateAndReplace(ic, word, punct)
        } else {
            ic.commitText(punct, 1)
        }
        suggestionBar.clearSuggestions()
    }

    private fun handleBackspace(ic: InputConnection) {
        if (composingWord.isNotEmpty()) {
            composingWord.deleteCharAt(composingWord.length - 1)
            if (composingWord.isEmpty()) {
                ic.finishComposingText()
                ic.deleteSurroundingText(1, 0)
                suggestionBar.clearSuggestions()
            } else {
                ic.setComposingText(composingWord.toString(), 1)
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
        suggestionBar.clearSuggestions()
    }

    private fun cycleShift() {
        keyboardView.shiftState = (keyboardView.shiftState + 1) % 3
        keyboardView.invalidate()
    }

    private fun translateAndReplace(ic: InputConnection, word: String, suffix: String = " ") {
        serviceScope.launch {
            val translated = NvidiaApiClient.translateWord(word)
            if (translated != null) {
                // The word was already committed via finishComposingText, so delete it
                ic.deleteSurroundingText(word.length, 0)
                // Take only the first suggestion if comma-separated
                val primary = translated.split(",").first().trim()
                ic.commitText(primary + suffix, 1)
            } else {
                ic.commitText(suffix, 1)
            }
        }
    }

    private fun translateAllContent(ic: InputConnection) {
        val before = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val fullText = before?.text?.toString() ?: return
        if (fullText.isBlank()) return

        suggestionBar.setLoading(true)
        serviceScope.launch {
            val translated = NvidiaApiClient.translateSentence(fullText)
            if (translated != null) {
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText(translated, 1)
            }
            suggestionBar.setLoading(false)
        }
    }

    private fun fetchSuggestions(word: String) {
        suggestionJob?.cancel()
        if (word.length < 2) {
            suggestionBar.clearSuggestions()
            return
        }
        suggestionBar.setLoading(true)
        suggestionJob = serviceScope.launch {
            val suggestions = NvidiaApiClient.getSuggestions(word)
            if (isActive) {
                if (suggestions.isNotEmpty()) {
                    suggestionBar.setSuggestions(suggestions)
                } else {
                    suggestionBar.clearSuggestions()
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
