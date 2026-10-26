package com.airkeys.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airkeys.*

/**
 * In-app Smart Keyboard: every tap sends a frame immediately, with no
 * hidden text field, no focus loss, and no delta-guessing. English,
 * Urdu and symbols layouts, plus an emoji row. Gboard mode is still
 * available for swipe / voice / predictive typing — this is the
 * direct, deterministic alternative when Gboard misbehaves.
 */
@Composable
fun SmartKeyboardScreen(vm: AirKeysViewModel) {
    var layout by remember { mutableStateOf(SmartLayout.ENGLISH) }
    var shift by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(8.dp).verticalScroll(rememberScrollState())) {
        // Modifier row (sticky, shared with the Gboard extra-key row).
        Row {
            listOf("Ctrl" to Modifiers.CTRL, "Alt" to Modifiers.ALT,
                   "Shift" to Modifiers.SHIFT, "Win" to Modifiers.WIN).forEach { (label, mask) ->
                Button(onClick = { vm.toggleModifier(mask) },
                    colors = if (vm.stickyModifiers and mask != 0) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.padding(2.dp)) { Text(label) }
            }
        }
        // Layout switch + shift.
        Row {
            Button(onClick = { layout = SmartLayout.ENGLISH }, modifier = Modifier.padding(2.dp)) { Text("EN") }
            Button(onClick = { layout = SmartLayout.URDU }, modifier = Modifier.padding(2.dp)) { Text("اردو") }
            Button(onClick = { layout = SmartLayout.SYMBOLS }, modifier = Modifier.padding(2.dp)) { Text("#@!") }
            Button(onClick = { shift = !shift },
                colors = if (shift) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.padding(2.dp)) { Text("⇧") }
        }
        KeyboardLayouts.rowsFor(layout).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    Button(onClick = {
                        vm.sendTextDirect(KeyboardLayouts.applyShift(key, shift))
                        if (shift && layout == SmartLayout.ENGLISH) shift = false
                    }, modifier = Modifier.weight(1f).padding(1.dp)) { Text(key) }
                }
            }
        }
        // Emoji row (horizontally scrollable).
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            KeyboardLayouts.EMOJI_ROW.forEach { emoji ->
                Button(onClick = { vm.sendTextDirect(emoji) },
                    modifier = Modifier.padding(1.dp)) { Text(emoji) }
            }
        }
        // Space / backspace / enter / arrows.
        Row(Modifier.fillMaxWidth()) {
            Button(onClick = { vm.sendTextDirect(" ") },
                modifier = Modifier.weight(2f).padding(2.dp)) { Text("Space") }
            Button(onClick = { vm.sendBackspace() },
                modifier = Modifier.weight(1f).padding(2.dp)) { Text("⌫") }
            Button(onClick = { vm.sendEnter() },
                modifier = Modifier.weight(1f).padding(2.dp)) { Text("Enter") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("←" to KeyCode.LEFT, "↑" to KeyCode.UP, "↓" to KeyCode.DOWN,
                   "→" to KeyCode.RIGHT).forEach { (label, code) ->
                Button(onClick = { vm.sendSpecial(code) },
                    modifier = Modifier.weight(1f).padding(2.dp)) { Text(label) }
            }
        }
        Text("Smart Keyboard sends each tap directly — no hidden field. " +
            "Use Gboard mode for swipe, voice and predictive text.",
            style = MaterialTheme.typography.bodySmall)
    }
}
