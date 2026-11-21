package dev.wadackel.limenty.input

import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.input.ImeEditBuffer
import dev.wadackel.limenty.core.input.TerminalInputEvent
import dev.wadackel.limenty.core.input.TerminalKey

/**
 * The IME's view of the terminal.
 *
 * Every text method is overridden rather than delegated to [BaseInputConnection]: the base class
 * models a normal text field with a document behind it, and letting it answer even one query
 * would expose text the remote has already consumed and invite the IME to "correct" it.
 *
 * Callbacks are logged with their *lengths*, never their content — a preedit at a remote password
 * prompt is still a secret.
 */
class TerminalInputConnection(
    view: View,
    private val buffer: ImeEditBuffer,
    private val onEvents: (List<TerminalInputEvent>) -> Unit,
    private val onComposingChanged: () -> Unit,
    private val onKeyEvent: (KeyEvent) -> Boolean,
    private val onSelectionSettled: () -> Unit,
) : BaseInputConnection(view, true) {

    private val log = TermLog.logger(LogTags.IME)

    /** Never used for content; some framework paths call it unconditionally. */
    private val emptyEditable: Editable by lazy { SpannableStringBuilder() }

    override fun getEditable(): Editable = emptyEditable

    private var batchDepth = 0
    private var selectionMoved = false

    private fun emit(events: List<TerminalInputEvent>) {
        onComposingChanged()
        selectionMoved = true
        if (batchDepth == 0) settleSelection()
        if (events.isNotEmpty()) onEvents(events)
    }

    /**
     * Reports where the cursor and the composing region ended up, once the IME has stopped moving
     * them.
     *
     * Held until the batch closes rather than sent per call. An IME builds one visible change out
     * of several calls — commit, then compose again — and answering between them describes a state
     * it is half way through setting: Gboard reads that as the composition being taken from it and
     * abandons it, spilling the consonant it was holding as literal text. At the batch boundary
     * there is no half-set state left to contradict.
     *
     * Reported at all because an editor that never says it is composing is one that no accessibility
     * client can cooperate with. The app's own key remapper asks exactly this question before
     * touching a preedit, gets no answer, and deletes into a composition it cannot see.
     */
    private fun settleSelection() {
        if (!selectionMoved) return
        selectionMoved = false
        onSelectionSettled()
    }

    override fun beginBatchEdit(): Boolean {
        log.v("beginBatchEdit")
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        log.v("endBatchEdit")
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) settleSelection()
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString().orEmpty()
        log.d(
            "setComposingText",
            "len" to value.length,
            "cursor" to newCursorPosition,
            "script" to KeystrokeDescription.script(value),
        )
        emit(buffer.setComposingText(value, newCursorPosition))
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        log.d("setComposingRegion", "start" to start, "end" to end)
        emit(buffer.setComposingRegion(start, end))
        return true
    }

    override fun finishComposingText(): Boolean {
        log.d(
            "finishComposingText",
            "pendingLen" to buffer.composing.text.length,
            "script" to KeystrokeDescription.script(buffer.composing.text),
        )
        emit(buffer.endComposing())
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString().orEmpty()
        log.d(
            "commitText",
            "len" to value.length,
            "cursor" to newCursorPosition,
            "script" to KeystrokeDescription.script(value),
            "pendingLen" to buffer.composing.text.length,
        )
        emit(buffer.commitText(value, newCursorPosition))
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        val value = text?.text?.toString().orEmpty()
        log.d("commitCompletion", "len" to value.length)
        emit(buffer.commitText(value, 1))
        return true
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        log.d("commitCorrection")
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        log.d("deleteSurroundingText", "before" to beforeLength, "after" to afterLength)
        emit(buffer.deleteSurroundingText(beforeLength, afterLength))
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        log.d("deleteSurroundingTextInCodePoints", "before" to beforeLength, "after" to afterLength)
        emit(buffer.deleteSurroundingTextInCodePoints(beforeLength, afterLength))
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        log.d("setSelection", "start" to start, "end" to end)
        emit(buffer.setSelection(start, end))
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        log.d(
            "sendKeyEvent",
            "action" to event.action,
            "keyCode" to event.keyCode,
            "meta" to event.metaState,
        )
        return onKeyEvent(event)
    }

    /**
     * Navigation actions (next/previous field) have no terminal meaning; only the ones that mean
     * "submit this" become a Return, so a stray IME action cannot inject a newline into a command.
     */
    override fun performEditorAction(editorAction: Int): Boolean {
        log.d("performEditorAction", "action" to editorAction)
        emit(buffer.commitComposing())
        val submits = editorAction == EditorInfo.IME_ACTION_DONE ||
            editorAction == EditorInfo.IME_ACTION_GO ||
            editorAction == EditorInfo.IME_ACTION_SEND ||
            editorAction == EditorInfo.IME_ACTION_SEARCH ||
            editorAction == EditorInfo.IME_ACTION_UNSPECIFIED
        if (submits) emit(listOf(TerminalInputEvent.Key(TerminalKey.Enter)))
        return true
    }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = buffer.textBeforeCursor(length).also {
        log.v("getTextBeforeCursor", "requested" to length, "returnedLen" to it.length)
    }

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = buffer.textAfterCursor(length).also {
        log.v("getTextAfterCursor", "requested" to length, "returnedLen" to it.length)
    }

    override fun getSelectedText(flags: Int): CharSequence {
        log.v("getSelectedText")
        return ""
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        log.v("getExtractedText")
        return null
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        log.v("requestCursorUpdates", "mode" to cursorUpdateMode)
        return false
    }

    override fun performContextMenuAction(id: Int): Boolean {
        log.v("performContextMenuAction", "id" to id)
        return false
    }

    override fun clearMetaKeyStates(states: Int): Boolean = true

    override fun reportFullscreenMode(enabled: Boolean): Boolean {
        log.v("reportFullscreenMode", "enabled" to enabled)
        return false
    }

    override fun closeConnection() {
        log.d("closeConnection", "pendingLen" to buffer.composing.text.length)
        emit(buffer.endComposing())
        super.closeConnection()
    }
}
