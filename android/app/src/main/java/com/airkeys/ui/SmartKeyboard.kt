package com.airkeys.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airkeys.*
import com.airkeys.R

/**
 * Smart Keyboard, rebuilt in v1.3 in the style of the screenshot the
 * user sent (white board, black keys, red shift / backspace / enter /
 * arrows, number row on top, space row, arrow row at the bottom) and
 * merged with the existing Smart Keyboard layout:
 *
 * - EN / اردو / #@! layouts, with the bundled Noto Nastaliq Urdu font
 *   for Urdu keys (v1.2 tofu fix, kept).
 * - Sticky Ctrl / Alt / Win, Esc / Tab / Del, F1–F12, emoji row.
 * - One-shot Shift for Latin letters, direct tap-to-send (no hidden
 *   field, no Gboard). The Android (Gboard) keyboard mode is gone in
 *   v1.3 — this is the only keyboard.
 *
 * Keys are flat outlined boxes, not Material buttons, so the board
 * looks like a phone keyboard and every key is large enough to tap.
 */
private val UrduFontFamily = FontFamily(Font(R.font.noto_nastaliq_urdu))
private val KeyRed = Color(0xFFE53935)
private val KeyOrange = Color(0xFFFF6D00)
private val KeyGrey = Color(0xFFF5F5F5)
private val KeyBorder = Color(0xFFE0E0E0)

@Composable
private fun RowScope.Key(
    label: String,
    onClick: () -> Unit,
    weight: Float = 1f,
    style: TextStyle = TextStyle(fontSize = 20.sp, color = Color.Black),
    background: Color = Color.White,
    contentColor: Color = Color.Black,
) {
    Box(
        Modifier.weight(weight).padding(2.dp).height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, KeyBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = style.copy(color = contentColor)) }
}

@Composable
private fun RowScope.Chip(
    label: String,
    selected: Boolean,
    style: TextStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    onClick: () -> Unit,
) {
    Box(
        Modifier.padding(2.dp).height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) KeyOrange else KeyGrey)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = style.copy(color = if (selected) Color.White else Color.Black)) }
}

@Composable
fun SmartKeyboardScreen(vm: AirKeysViewModel) {
    var layout by remember { mutableStateOf(SmartLayout.ENGLISH) }
    var shift by remember { mutableStateOf(false) }
    var emojiMode by remember { mutableStateOf(false) }
    val isUrdu = layout == SmartLayout.URDU
    val keyStyle = if (isUrdu) TextStyle(fontFamily = UrduFontFamily, fontSize = 24.sp)
                   else TextStyle(fontSize = 20.sp)
    val sticky = vm.stickyModifiers

    fun sendKey(text: String) {
        vm.sendTextDirect(KeyboardLayouts.applyShift(text, shift))
        if (shift && layout == SmartLayout.ENGLISH) shift = false
    }

    Column(
        Modifier.fillMaxWidth().background(Color.White)
            .verticalScroll(rememberScrollState()).padding(4.dp)
    ) {
        // v1.5: arrow + F rows moved ABOVE the main keys, so the
        // main keyboard (utility strip through Space) is the last
        // thing on screen — flush to the bottom, as annotated.
        // Arrow row, as in the screenshot: red arrows (moved up in v1.5).
        Row(Modifier.fillMaxWidth()) {
            Key("↑", { vm.sendSpecial(KeyCode.UP) }, contentColor = KeyRed,
                style = TextStyle(fontSize = 22.sp, color = KeyRed))
            Key("↓", { vm.sendSpecial(KeyCode.DOWN) }, contentColor = KeyRed,
                style = TextStyle(fontSize = 22.sp, color = KeyRed))
            Key("←", { vm.sendSpecial(KeyCode.LEFT) }, contentColor = KeyRed,
                style = TextStyle(fontSize = 22.sp, color = KeyRed))
            Key("→", { vm.sendSpecial(KeyCode.RIGHT) }, contentColor = KeyRed,
                style = TextStyle(fontSize = 22.sp, color = KeyRed))
        }
        // F-keys, kept from the old extra-key row, small and scrollable.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (1..12).forEach { n ->
                Box(
                    Modifier.padding(2.dp).height(40.dp).width(52.dp)
                        .clip(RoundedCornerShape(8.dp)).background(KeyGrey)
                        .border(1.dp, KeyBorder, RoundedCornerShape(8.dp))
                        .clickable { vm.sendFunctionKey(n) },
                    contentAlignment = Alignment.Center,
                ) { Text("F$n", style = TextStyle(fontSize = 14.sp)) }
            }
        }
        // Top strip: layout + sticky modifiers + Esc/Tab/Del.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            Chip("EN", layout == SmartLayout.ENGLISH) { layout = SmartLayout.ENGLISH; emojiMode = false }
            Chip("اردو", layout == SmartLayout.URDU, style = TextStyle(fontFamily = UrduFontFamily, fontSize = 18.sp)) { layout = SmartLayout.URDU; emojiMode = false }
            Chip("#@!", layout == SmartLayout.SYMBOLS) { layout = SmartLayout.SYMBOLS; emojiMode = false }
            Spacer(Modifier.width(8.dp))
            Chip("Ctrl", sticky and Modifiers.CTRL != 0) { vm.toggleModifier(Modifiers.CTRL) }
            Chip("Alt", sticky and Modifiers.ALT != 0) { vm.toggleModifier(Modifiers.ALT) }
            Chip("Win", sticky and Modifiers.WIN != 0) { vm.toggleModifier(Modifiers.WIN) }
            Spacer(Modifier.width(8.dp))
            Chip("Esc", false) { vm.sendSpecial(KeyCode.ESC) }
            Chip("Tab", false) { vm.sendSpecial(KeyCode.TAB) }
            Chip("Del", false) { vm.sendSpecial(KeyCode.DELETE) }
        }

        if (emojiMode) {
            // Emoji board replaces the letter rows; ABC goes back.
            KeyboardLayouts.EMOJI_ROW.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { emoji -> Key(emoji, { vm.sendTextDirect(emoji) }, style = TextStyle(fontSize = 24.sp)) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Key("ABC", { emojiMode = false }, background = KeyGrey)
            }
        } else {
            val rows = KeyboardLayouts.rowsFor(layout)
            when (layout) {
                SmartLayout.ENGLISH -> {
                    // Screenshot rows: numbers, q-row, a-row, then
                    // Shift + z-row + Backspace on one row.
                    Row(Modifier.fillMaxWidth()) { rows[0].forEach { Key(it, { sendKey(it) }, style = keyStyle) } }
                    Row(Modifier.fillMaxWidth()) { rows[1].forEach { Key(it, { sendKey(it) }, style = keyStyle) } }
                    Row(Modifier.fillMaxWidth()) { rows[2].forEach { Key(it, { sendKey(it) }, style = keyStyle) } }
                    Row(Modifier.fillMaxWidth()) {
                        Key("⇧", { shift = !shift }, weight = 1.5f,
                            background = if (shift) KeyOrange else Color.White,
                            contentColor = if (shift) Color.White else KeyRed,
                            style = TextStyle(fontSize = 22.sp, color = KeyRed))
                        rows[3].forEach { Key(it, { sendKey(it) }, style = keyStyle) }
                        Key("⌫", { vm.sendBackspace() }, weight = 1.5f,
                            contentColor = KeyRed, style = TextStyle(fontSize = 22.sp, color = KeyRed))
                    }
                }
                SmartLayout.URDU -> {
                    // Urdu digits + 3 letter rows, then
                    // Shift + last letter row + Backspace.
                    rows.take(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) { row.forEach { Key(it, { sendKey(it) }, style = keyStyle) } }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Key("⇧", { shift = !shift }, weight = 1.5f,
                            background = if (shift) KeyOrange else Color.White,
                            contentColor = if (shift) Color.White else KeyRed,
                            style = TextStyle(fontSize = 22.sp, color = KeyRed))
                        rows[4].forEach { Key(it, { sendKey(it) }, style = keyStyle) }
                        Key("⌫", { vm.sendBackspace() }, weight = 1.5f,
                            contentColor = KeyRed, style = TextStyle(fontSize = 22.sp, color = KeyRed))
                    }
                }
                SmartLayout.SYMBOLS -> {
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth()) { row.forEach { Key(it, { sendKey(it) }, style = keyStyle) } }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Key("⇧", { shift = !shift }, weight = 1f,
                            background = if (shift) KeyOrange else Color.White,
                            contentColor = if (shift) Color.White else KeyRed,
                            style = TextStyle(fontSize = 22.sp, color = KeyRed))
                        Key("⌫", { vm.sendBackspace() }, weight = 1f,
                            contentColor = KeyRed, style = TextStyle(fontSize = 22.sp, color = KeyRed))
                    }
                }
            }
        }

        // Space row, as in the screenshot: emoji, comma, wide space,
        // period, red Enter.
        Row(Modifier.fillMaxWidth()) {
            Key("😊", { emojiMode = !emojiMode }, background = KeyGrey)
            Key(",", { vm.sendTextDirect(",") })
            Key("Space", { vm.sendTextDirect(" ") }, weight = 3f, background = KeyGrey)
            Key(".", { vm.sendTextDirect(".") })
            Key("↩", { vm.sendEnter() }, contentColor = KeyRed,
                style = TextStyle(fontSize = 24.sp, color = KeyRed))
        }


            modifier = Modifier.padding(4.dp))
    }
}
