package com.airkeys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

// v1.3: the Android (Gboard) keyboard and Split modes were removed, as
// requested. The Smart Keyboard is now the only keyboard.
enum class Mode { SMART_KEYBOARD, TOUCHPAD }

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

    var mode by mutableStateOf(Mode.SMART_KEYBOARD); private set
    var status by mutableStateOf("Disconnected"); private set
    var stickyModifiers by mutableStateOf(0); private set
    var touchpadLocked by mutableStateOf(false); private set

    // Hidden keyboard buffer state. The field itself lives in Compose;
    // it always holds one sentinel space (see TextDelta.computeWithSentinel).
    // lastText is therefore " " at rest, never "".
    var lastText by mutableStateOf(" "); private set

    init { setStatusListener { status = it } }
    override fun onCleared() { setStatusListener(null); super.onCleared() }

    fun changeMode(m: Mode) { mode = m }
    fun setLocked(l: Boolean) { touchpadLocked = l }

    fun toggleModifier(mask: Int) {
        stickyModifiers = stickyModifiers xor mask
    }
    fun clearModifiers() { stickyModifiers = 0 }

    private fun conn(): InputTransport? = AirKeysService.instance?.connection()

    /** Direct text, used by the Smart Keyboard (no hidden-field delta). */
    fun sendTextDirect(text: String) = conn()?.sendText(text)
    /**
     * Scroll-wheel clicks, used by the touchpad scroll strip (v1.3).
     * Unlike sendScroll(), this does NOT apply speed/natural here:
     * Windows applies speed (120 per click) and natural-scroll once,
     * so applying them here too would double-scale / double-invert.
     */
    fun sendScrollWheel(dxClicks: Int, dyClicks: Int) = conn()?.sendScroll(dxClicks, dyClicks)
    fun sendBackspace() = conn()?.sendBackspace(1)
    fun sendEnter() = sendSpecial(KeyCode.ENTER)

    /** Diagnostics: types "A" and nudges the cursor, so the user can tell
     *  transport failure apart from injection failure in one tap. */
    fun testInjection() {
        val c = conn() ?: return
        c.sendText("A")
        c.sendMouseMove(10, 0)
        c.flushMouseMovesNow(System.currentTimeMillis())
    }

    /** Called on every hidden-field change, from Compose onValueChange.
     *  Sentinel-aware: backspace on the sentinel is a real change, so it
     *  reaches here even when the user-visible field looks empty. */
    fun onHiddenTextChanged(newText: String) {
        val delta = TextDelta.computeWithSentinel(lastText, newText)
        // Restore-loop guard: the Compose effect resetting the field to
        // the sentinel must not send anything or increment again.
        if (delta.deleted == 0 && delta.inserted.isEmpty()) return
        // Restore the sentinel BEFORE signalling, so the effect's
        // onValueChange(" ") hits the guard above and stops.
        lastText = " "
        if (delta.deleted > 0) conn()?.sendBackspace(delta.deleted)
        if (delta.inserted.isNotEmpty()) conn()?.sendText(delta.inserted)
        resetSignal++
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
