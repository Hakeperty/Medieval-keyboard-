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
    private var lastCorrectedWord = ""  // tracks last autocorrect for suggestion tap-to-replace

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
            // Still composing — replace composing text with suggestion
            ic.finishComposingText()
            val before = ic.getTextBeforeCursor(composingWord.length, 0) ?: ""
            if (before.toString().equals(composingWord.toString(), ignoreCase = true)) {
                ic.deleteSurroundingText(composingWord.length, 0)
            }
            ic.commitText(suggestion + " ", 1)
            composingWord.clear()
        } else if (lastCorrectedWord.isNotEmpty()) {
            // Tap alternate suggestion to replace the last autocorrected word
            val beforeText = ic.getTextBeforeCursor(lastCorrectedWord.length + 1, 0)?.toString() ?: ""
            // Check if text before cursor ends with "lastCorrectedWord " or "lastCorrectedWord"
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

    private fun handleCharacter(ic: InputConnection, label: String) {
        val kbView = keyboardView ?: return
        val char = when (kbView.shiftState) {
            1 -> label.uppercase()
            2 -> if (composingWord.isEmpty()) label.uppercase() else label.lowercase()
            else -> label.lowercase()
        }
        composingWord.append(char)

        // Show inline preview of medieval translation while typing
        val preview = MedievalFallbackMap.translate(composingWord.toString().lowercase())
        if (preview != null) {
            val primary = preview.split(",").first().trim()
            ic.setComposingText(primary, 1)
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
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            composingWord.clear()
            autoCorrectAndCommit(ic, word, " ")
        } else {
            ic.commitText(" ", 1)
        }
        suggestionBar?.clearSuggestions()
    }

    private fun handlePunctuation(ic: InputConnection, punct: String) {
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            composingWord.clear()
            autoCorrectAndCommit(ic, word, punct)
        } else {
            ic.commitText(punct, 1)
        }
        suggestionBar?.clearSuggestions()
    }

    /**
     * Instant autocorrect: checks fallback map + cache synchronously first.
     * If no instant match, commits the word and fires an async API replace.
     */
    private fun autoCorrectAndCommit(ic: InputConnection, word: String, suffix: String) {
        // 1. Check local cache first (instant)
        val cached = TranslationCache.get(word)
        if (cached != null) {
            val primary = cached.split(",").first().trim()
            ic.finishComposingText()
            // finishComposingText committed whatever was shown as composing text — delete it
            val previewLen = MedievalFallbackMap.translate(word.lowercase())
                ?.split(",")?.first()?.trim()?.length ?: word.length
            ic.deleteSurroundingText(previewLen, 0)
            ic.commitText(primary + suffix, 1)
            lastCorrectedWord = primary
            suggestionBar?.setSuggestions(cached.split(",").map { it.trim() }.take(3))
            return
        }

        // 2. Check fallback map (instant, 400+ words)
        val fallback = MedievalFallbackMap.translate(word.lowercase())
        if (fallback != null) {
            val primary = fallback.split(",").first().trim()
            ic.finishComposingText()
            // The preview already showed the medieval word, so delete its length
            ic.deleteSurroundingText(primary.length, 0)
            ic.commitText(primary + suffix, 1)
            TranslationCache.put(word, fallback)
            lastCorrectedWord = primary
            suggestionBar?.setSuggestions(fallback.split(",").map { it.trim() }.take(3))
            return
        }

        // 3. No instant match — commit as-is, then try API in background
        ic.finishComposingText()
        ic.commitText(suffix, 1)
        lastCorrectedWord = word
        translateAndReplaceAsync(word, suffix)
    }

    private fun handleBackspace(ic: InputConnection) {
        if (composingWord.isNotEmpty()) {
            composingWord.deleteCharAt(composingWord.length - 1)
            if (composingWord.isEmpty()) {
                ic.finishComposingText()
                ic.deleteSurroundingText(1, 0)
                suggestionBar?.clearSuggestions()
            } else {
                // Show medieval preview while editing
                val preview = MedievalFallbackMap.translate(composingWord.toString().lowercase())
                if (preview != null) {
                    val primary = preview.split(",").first().trim()
                    ic.setComposingText(primary, 1)
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

    /**
     * Async API fallback: only called when the fallback map doesn't have the word.
     * Replaces the already-committed word in-place once the API responds.
     */
    private fun translateAndReplaceAsync(word: String, suffix: String) {
        serviceScope.launch {
            try {
                val translated = NvidiaApiClient.translateWord(word)
                val ic = currentInputConnection
                if (ic != null && isInputActive && translated != null) {
                    val primary = translated.split(",").first().trim()
                    // Delete the original word + suffix that was already committed
                    ic.deleteSurroundingText(word.length + suffix.length, 0)
                    ic.commitText(primary + suffix, 1)
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
