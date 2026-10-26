package com.airkeys.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.airkeys.*

/**
 * Android keyboard (v1.4, restored alongside Smart Keyboard).
 *
 * Why this replaces the v1.2 hidden BasicTextField + sentinel space:
 * a Compose BasicTextField only reports backspace when its text
 * actually changes. On an empty field Gboard has nothing to delete,
 * so Backspace was lost — and the sentinel hack still depended on
 * Gboard treating a lone space as deletable, which it does not do
 * reliably with composing / predictive text.
 *
 * This uses a real EditText with a custom InputConnection instead:
 * - commitText: the word/char is sent to the PC and NOT inserted
 *   locally, so the field stays empty and the next backspace still
 *   has a defined meaning (it is intercepted, not guessed).
 * - deleteSurroundingText(1, 0) on an empty, non-composing field:
 *   sends exactly one BACKSPACE frame to the PC, every time.
 * - While Gboard is composing (swipe / predictive word, underlined),
 *   backspace edits the composing word locally and sends nothing to
 *   the PC, because that word was never sent yet.
 * - sendKeyEvent KEYCODE_DEL / FORWARD_DEL / ENTER are intercepted
 *   the same way, so hardware and software backspace both work.
 * - All other keys (letters, digits, Urdu, symbols, emoji, space,
 *   voice-typed text) go through commitText and are sent as-is.
 */
private class RemoteInputConnection(
    target: InputConnection,
    mutable: Boolean,
    private val vm: AirKeysViewModel,
) : InputConnectionWrapper(target, mutable) {
    private var composing = ""

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composing = text?.toString() ?: ""
        return super.setComposingText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        composing = ""
        return super.finishComposingText()
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composing = ""
        if (!text.isNullOrEmpty()) vm.sendTextDirect(text.toString())
        // Do NOT insert locally: the PC already has the text, and
        // keeping the field empty makes backspace deterministic.
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (composing.isNotEmpty()) {
            // Edit the not-yet-sent composing word locally.
            composing = ""
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        repeat(beforeLength) { vm.sendBackspace() }
        repeat(afterLength) { vm.sendSpecial(KeyCode.DELETE) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    if (composing.isEmpty()) vm.sendBackspace()
                    else composing = composing.dropLast(1)
                    return true
                }
                KeyEvent.KEYCODE_FORWARD_DEL -> { vm.sendSpecial(KeyCode.DELETE); return true }
                KeyEvent.KEYCODE_ENTER -> { vm.sendEnter(); return true }
            }
        }
        return super.sendKeyEvent(event)
    }
}

private class RemoteEditText(context: Context, private val vm: AirKeysViewModel) : EditText(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val conn = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        return RemoteInputConnection(conn, true, vm)
    }
}

@Composable
fun AndroidKeyboardScreen(vm: AirKeysViewModel) {
    var editText by remember { mutableStateOf<EditText?>(null) }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        // The real field: composing (swipe/predictive) text shows here
        // underlined while you type; committed text goes to the PC.
        AndroidView(
            factory = { ctx ->
                RemoteEditText(ctx, vm).apply {
                    hint = "Type here — Gboard / your phone keyboard sends to the PC"
                    inputType = InputType.TYPE_CLASS_TEXT
                    isSingleLine = false
                    minLines = 2
                    editText = this
                }
            },
            modifier = Modifier.fillMaxWidth().height(72.dp)
                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)).padding(4.dp),
        )
        Button(onClick = {
            editText?.requestFocus()
            val imm = editText?.context?.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(editText, 0)
        }, modifier = Modifier.fillMaxWidth()) { Text("Tap to open keyboard") }
        // Fallback keys that always work, whatever the IME does:
        // Backspace here is a direct frame, independent of InputConnection.
        Row(Modifier.fillMaxWidth()) {
            Button(onClick = { vm.sendBackspace() }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("⌫ Backspace") }
            Button(onClick = { vm.sendEnter() }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("Enter") }
            Button(onClick = { vm.sendSpecial(KeyCode.ESC) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("Esc") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("←" to KeyCode.LEFT, "↑" to KeyCode.UP, "↓" to KeyCode.DOWN,
                   "→" to KeyCode.RIGHT).forEach { (label, code) ->
                Button(onClick = { vm.sendSpecial(code) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text(label) }
            }
        }
        Text("Android keyboard: swipe, voice, predictive and Urdu all go " +
            "to the PC. Backspace is intercepted even on an empty field, " +
            "so delete always works — and ⌫ above is a direct backup.",
            style = MaterialTheme.typography.bodySmall)
    }
}
