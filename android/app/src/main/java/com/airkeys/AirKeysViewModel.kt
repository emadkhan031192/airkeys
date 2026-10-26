package com.airkeys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class Mode { KEYBOARD, TOUCHPAD, SPLIT }

/**
 * UI state + input routing. All Bluetooth work goes through the foreground
 * service's ConnectionManager; this class never touches a socket directly,
 * so Compose recomposition can never block or duplicate input.
 */
class AirKeysViewModel : ViewModel() {
    companion object {
        // Service -> UI status bridge (service has no UI of its own).
        private var statusListener: ((String) -> Unit)? = null
        fun onServiceStatus(s: String) { statusListener?.invoke(s) }
        fun setStatusListener(l: ((String) -> Unit)?) { statusListener = l }
    }

    var mode by mutableStateOf(Mode.KEYBOARD); private set
    var status by mutableStateOf("Disconnected"); private set
    var stickyModifiers by mutableStateOf(0); private set
    var touchpadLocked by mutableStateOf(false); private set

    // Hidden keyboard buffer state. The field itself lives in Compose;
    // we keep the last text here only to compute deltas.
    var lastText by mutableStateOf(""); private set

    init { setStatusListener { status = it } }
    override fun onCleared() { setStatusListener(null); super.onCleared() }

    fun changeMode(m: Mode) { mode = m }
    fun setLocked(l: Boolean) { touchpadLocked = l }

    fun toggleModifier(mask: Int) {
        stickyModifiers = stickyModifiers xor mask
    }
    fun clearModifiers() { stickyModifiers = 0 }

    private fun conn(): ConnectionManager? = AirKeysService.instance?.connection()

    /** Called on every hidden-field change, from Compose onValueChange. */
    fun onHiddenTextChanged(newText: String) {
        val delta = TextDelta.compute(lastText, newText)
        lastText = newText
        if (delta.deleted > 0) conn()?.sendBackspace(delta.deleted)
        if (delta.inserted.isNotEmpty()) {
            if (stickyModifiers != 0) {
                // Modifiers + text: send as COMBO per char is wrong for text,
                // so clear sticky modifiers first is NOT done — instead the
                // PC tracks held modifiers; for plain text we send TEXT and
                // let the next special key carry the modifiers.
            }
            conn()?.sendText(delta.inserted)
        }
        // Clear the hidden buffer after sending so the next keystroke is a
        // fresh delta. The Compose field resets via resetSignal below.
        if (delta.deleted > 0 || delta.inserted.isNotEmpty()) {
            lastText = ""
            resetSignal++
        }
    }
    var resetSignal by mutableStateOf(0); private set

    fun sendSpecial(code: Int) {
        val c = conn() ?: return
        if (stickyModifiers != 0) {
            c.sendCombo(stickyModifiers, code, true)
            c.sendCombo(stickyModifiers, code, false)
            clearModifiers()
        } else {
            c.sendKey(code, true); c.sendKey(code, false)
        }
    }

    fun sendFunctionKey(n: Int) = sendSpecial(KeyCode.f(n))

    fun sendMouseButton(button: Int, down: Boolean) = conn()?.sendMouseButton(button, down)
    fun sendMouseMove(dx: Int, dy: Int, sensitivity: Float) =
        conn()?.sendMouseMove((dx * sensitivity).toInt(), (dy * sensitivity).toInt())
    fun sendScroll(dx: Int, dy: Int, speed: Float, natural: Boolean) {
        val sx = (dx * speed).toInt(); val sy = (dy * speed).toInt()
        conn()?.sendScroll(if (natural) -sx else sx, if (natural) -sy else sy)
    }
    fun flushMovesNow() = conn()?.flushMouseMovesNow(System.currentTimeMillis())
}
