package dev.wadackel.limenty.input

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.input.ComposingState
import dev.wadackel.limenty.core.input.ImeEditBuffer
import dev.wadackel.limenty.core.input.InputArbiter
import dev.wadackel.limenty.core.input.KeyDecision
import dev.wadackel.limenty.core.input.TerminalInputEvent

/**
 * The focusable surface that owns terminal input.
 *
 * It is a bare [View] rather than an `EditText` so no framework text machinery sits between the
 * IME and [ImeEditBuffer]; the terminal renderer draws on top of it.
 */
@SuppressLint("ViewConstructor")
open class TerminalInputView(context: Context) : View(context) {

    protected val log = TermLog.logger(LogTags.KEY)

    val buffer = ImeEditBuffer()
    private val arbiter = InputArbiter(buffer)

    var editorConfiguration: EditorConfiguration = EditorConfiguration.Default
        set(value) {
            if (field == value) return
            field = value
            log.i("editor.configure", "config" to value.name)
            restartInput()
        }

    /**
     * Whether the IME may learn from what is typed here.
     *
     * Off by default: a terminal carries commands and password prompts alike. Leaving it on also
     * lets the keyboard keep copies in its clipboard history, which is why it is worth offering
     * rather than deciding for everyone.
     */
    var allowPersonalizedLearning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            log.i("editor.personalizedLearning", "allowed" to value)
            restartInput()
        }

    var onInputEvents: (List<TerminalInputEvent>) -> Unit = {}
    var onComposingChanged: (ComposingState) -> Unit = {}

    /** Returns true when the app consumed the key, so the terminal can decline system keys. */
    var onUnhandledKey: (KeyEvent) -> Boolean = { false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = editorConfiguration.inputType
        outAttrs.imeOptions = editorConfiguration.imeOptionsWith(allowPersonalizedLearning)
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        outAttrs.initialCapsMode = 0

        log.i(
            "inputConnection.create",
            "config" to editorConfiguration.name,
            "personalizedLearning" to allowPersonalizedLearning,
            "inputType" to "0x%08X".format(outAttrs.inputType),
            "imeOptions" to "0x%08X".format(outAttrs.imeOptions),
        )

        return TerminalInputConnection(
            view = this,
            buffer = buffer,
            onEvents = onInputEvents,
            onComposingChanged = { onComposingChanged(buffer.composing) },
            onKeyEvent = { event -> dispatchTerminalKey(event) },
            // At the batch boundary, never inside one. The connection holds the report until the
            // IME has finished moving things; what must not happen is answering mid-batch, which
            // Gboard reads as the composition being taken away from it.
            onSelectionSettled = { notifySelection() },
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = dispatchTerminalKey(event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = !AndroidKeyMapper.isSystemKey(keyCode) && keyCode != KeyEvent.KEYCODE_BACK

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        // KEYCODE_UNKNOWN carries a string payload: some IMEs and remote keyboards deliver
        // multi-character input this way rather than through the input connection.
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val characters = event.characters
            if (!characters.isNullOrEmpty()) {
                log.d("key.multiple", "len" to characters.length)
                onInputEvents(listOf(TerminalInputEvent.Text(characters)))
                return true
            }
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun dispatchTerminalKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return event.action == KeyEvent.ACTION_UP && !AndroidKeyMapper.isSystemKey(event.keyCode)
        }

        val keyCode = event.keyCode

        // Back drives navigation; claiming it here would strand the user in the terminal.
        if (keyCode == KeyEvent.KEYCODE_BACK) return onUnhandledKey(event)
        if (AndroidKeyMapper.isSystemKey(keyCode)) return onUnhandledKey(event)
        if (AndroidKeyMapper.isModifierKey(keyCode)) return true

        val modifiers = AndroidKeyMapper.modifiers(event.metaState)
        val terminalKey = AndroidKeyMapper.terminalKey(keyCode)
        val codePoint = if (terminalKey != null) {
            0
        } else {
            resolveCodePoint(event)
        }

        val carriesText = KeystrokeDescription.carriesText(keyCode, codePoint)
        log.d(
            "key.down",
            // The key code alone spells a password on a physical keyboard, so it is withheld for
            // text keys once records are being retained; named keys stay legible either way.
            "keyCode" to KeystrokeDescription.keyCode(keyCode, carriesText),
            "modifiers" to modifiers,
            "terminalKey" to (terminalKey?.name ?: "-"),
            "codePoint" to KeystrokeDescription.codePoint(codePoint),
            "repeat" to event.repeatCount,
        )

        val decision = arbiter.onKey(terminalKey, codePoint, modifiers)
        syncImeAfter(decision)
        onComposingChanged(buffer.composing)
        if (decision.events.isNotEmpty()) onInputEvents(decision.events)
        if (decision.handled) return true

        // Without this record, "the key never reached the app" and "the app dropped it" look
        // identical in a report, and they need different fixes.
        log.i(
            "key.unhandled",
            "keyCode" to KeystrokeDescription.keyCode(keyCode, carriesText),
            "terminalKey" to (terminalKey?.name ?: "-"),
        )
        return onUnhandledKey(event)
    }

    /**
     * Realigns the IME whenever arbitration ended a composition behind its back.
     *
     * The IME keeps its own copy of the preedit. If the app drops or confirms a composition from a
     * hardware key the IME never saw, the next `setComposingText` would resurrect the text the user
     * just discarded — so the connection is restarted to force the IME to start over.
     */
    private fun syncImeAfter(decision: KeyDecision) {
        val endedComposition = decision.reason == KeyDecision.Reason.CompositionCancelled ||
            decision.reason == KeyDecision.Reason.CompositionCommitted ||
            decision.reason == KeyDecision.Reason.CompositionInterrupted
        if (!endedComposition) {
            notifySelection()
            return
        }
        log.i("ime.restart", "reason" to decision.reason)
        context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
    }

    /**
     * Says where the cursor and the composing region are.
     *
     * Reached from two places: an arbiter edit, which moved the preedit under the IME, and the end
     * of an IME batch, where the IME has just finished moving it itself. The second looks
     * redundant — the IME knows what it set — but the report is not only for the IME. An
     * accessibility service editing text asks this question and nothing else to find out whether a
     * composition is live, and an editor that stays silent is one it cannot avoid trampling.
     */
    private fun notifySelection() {
        val state = buffer.composing
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        val composingStart = if (state.active) 0 else -1
        val composingEnd = if (state.active) state.text.length else -1
        imm.updateSelection(this, state.cursor, state.cursor, composingStart, composingEnd)
    }

    private fun resolveCodePoint(event: KeyEvent): Int {
        val metaState = AndroidKeyMapper.effectiveMetaStateForCharacter(event.metaState)
        val unicode = event.getUnicodeChar(metaState)
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            // A dead key produces no character until the next press composes with it. Sending the
            // accent marker itself would put a stray codepoint on the remote.
            log.d("key.deadKey", "keyCode" to event.keyCode)
            return 0
        }
        return unicode
    }

    fun showSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        log.i("softKeyboard.show")
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(windowToken, 0)
        log.i("softKeyboard.hide")
    }

    private fun restartInput() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        buffer.reset()
        onComposingChanged(buffer.composing)
        imm?.restartInput(this)
    }
}
