package com.medieval.keyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.*

class MedievalKeyboardService : InputMethodService(),
    KeyboardView.OnKeyboardActionListener,
    SuggestionBarView.OnSuggestionClickListener {

    private var keyboardView: KeyboardView? = null
    private var suggestionBar: SuggestionBarView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var suggestionJob: Job? = null
    private val composingWord = StringBuilder()
    private var isInputActive = false

    override fun onCreateInputView(): View? {
        return try {
            val layout = layoutInflater.inflate(R.layout.keyboard_view, null)
            keyboardView = layout.findViewById(R.id.keyboard_view)
            suggestionBar = layout.findViewById(R.id.suggestion_bar)
            keyboardView?.listener = this
            suggestionBar?.listener = this
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
        suggestionBar?.clearSuggestions()
    }

    private fun handleCharacter(ic: InputConnection, label: String) {
        val kbView = keyboardView ?: return
        val char = when (kbView.shiftState) {
            1 -> label.uppercase()
            2 -> if (composingWord.isEmpty()) label.uppercase() else label.lowercase()
            else -> label.lowercase()
        }
        composingWord.append(char)
        ic.setComposingText(composingWord.toString(), 1)
        fetchSuggestions(composingWord.toString())

        if (kbView.shiftState == 1) {
            kbView.shiftState = 0
            kbView.invalidate()
        }
    }

    private fun handleSpace(ic: InputConnection) {
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            composingWord.clear()
            ic.finishComposingText()
            translateAndReplace(word)
        } else {
            ic.commitText(" ", 1)
        }
        suggestionBar?.clearSuggestions()
    }

    private fun handlePunctuation(ic: InputConnection, punct: String) {
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            composingWord.clear()
            ic.finishComposingText()
            translateAndReplace(word, punct)
        } else {
            ic.commitText(punct, 1)
        }
        suggestionBar?.clearSuggestions()
    }

    private fun handleBackspace(ic: InputConnection) {
        if (composingWord.isNotEmpty()) {
            composingWord.deleteCharAt(composingWord.length - 1)
            if (composingWord.isEmpty()) {
                ic.finishComposingText()
                ic.deleteSurroundingText(1, 0)
                suggestionBar?.clearSuggestions()
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
        suggestionBar?.clearSuggestions()
    }

    private fun cycleShift() {
        val kbView = keyboardView ?: return
        kbView.shiftState = (kbView.shiftState + 1) % 3
        kbView.invalidate()
    }

    private fun translateAndReplace(word: String, suffix: String = " ") {
        serviceScope.launch {
            try {
                val translated = NvidiaApiClient.translateWord(word)
                val ic = currentInputConnection
                if (ic != null && isInputActive) {
                    if (translated != null) {
                        ic.deleteSurroundingText(word.length, 0)
                        val primary = translated.split(",").first().trim()
                        ic.commitText(primary + suffix, 1)
                    } else {
                        ic.commitText(suffix, 1)
                    }
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

        suggestionBar?.setLoading(true)
        serviceScope.launch {
            try {
                val translated = NvidiaApiClient.translateSentence(fullText)
                val freshIc = currentInputConnection
                if (freshIc != null && isInputActive && translated != null) {
                    freshIc.performContextMenuAction(android.R.id.selectAll)
                    freshIc.commitText(translated, 1)
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
                val suggestions = NvidiaApiClient.getSuggestions(word)
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

    override fun onDestroy() {
        isInputActive = false
        serviceScope.cancel()
        super.onDestroy()
    }
}
