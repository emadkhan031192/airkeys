package com.airkeys.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.airkeys.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AirKeysApp(vm: AirKeysViewModel, activity: MainActivity) {
    val settings = remember { SettingsStore(activity) }
    var showSettings by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    MaterialTheme {
        Column(Modifier.fillMaxSize()) {
            // Top bar: status + mode switch (one tap) + settings + connect.
            Row(Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("AirKeys", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                Text(vm.status, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { showDevices = true }) {
                    Text(if (vm.status.startsWith("Connected")) "Disconnect" else "Connect")
                }
                IconButton(onClick = { showSettings = !showSettings }) { Text("⚙") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .horizontalScroll(rememberScrollState())) {
                ModeButton("Keyboard", vm.mode == Mode.KEYBOARD) { vm.changeMode(Mode.KEYBOARD) }
                ModeButton("Smart KB", vm.mode == Mode.SMART_KEYBOARD) { vm.changeMode(Mode.SMART_KEYBOARD) }
                ModeButton("Touchpad", vm.mode == Mode.TOUCHPAD) { vm.changeMode(Mode.TOUCHPAD) }
                ModeButton("Split", vm.mode == Mode.SPLIT) { vm.changeMode(Mode.SPLIT) }
                Button(onClick = { vm.testInjection() }, modifier = Modifier.padding(end = 4.dp)) {
                    Text("Test A + cursor")
                }
                if (vm.mode == Mode.TOUCHPAD || vm.mode == Mode.SPLIT) {
                    Button(onClick = { vm.setLocked(!vm.touchpadLocked) }) {
                        Text(if (vm.touchpadLocked) "🔒 Locked" else "🔓 Lock")
                    }
                }
            }
            when (vm.mode) {
                Mode.KEYBOARD -> KeyboardScreen(vm, settings)
                Mode.SMART_KEYBOARD -> SmartKeyboardScreen(vm)
                Mode.TOUCHPAD -> TouchpadScreen(vm, settings, Modifier.weight(1f))
                Mode.SPLIT -> Column(Modifier.weight(1f)) {
                    TouchpadScreen(vm, settings, Modifier.weight(1f))
                    KeyboardScreen(vm, settings, compact = true)
                }
            }
        }
        if (showDevices) DeviceDialog(activity, vm, code, { code = it },
            onDismiss = { showDevices = false },
            onConnect = { addr, c -> activity.connect(addr, c.ifBlank { null }); showDevices = false },
            onDisconnect = { activity.disconnect(); showDevices = false })
        if (showSettings) SettingsDialog(settings) { showSettings = false }
    }
}

@Composable
private fun RowScope.ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(end = 4.dp),
        colors = if (selected) ButtonDefaults.buttonColors()
        else ButtonDefaults.outlinedButtonColors()) { Text(label) }
}

@Composable
fun DeviceDialog(activity: MainActivity, vm: AirKeysViewModel, code: String,
                 onCode: (String) -> Unit, onDismiss: () -> Unit,
                 onConnect: (String, String) -> Unit,
                 onDisconnect: () -> Unit) {
    // v1.2: Bluetooth only, as requested. LAN/Auto options were removed
    // from this dialog (and from the Windows tray) — the LAN code stays
    // in the repo but is not offered as a connection choice.
    var devices by remember { mutableStateOf(activity.bondedDevices()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connect to laptop") },
        text = {
            Column {
                Text("Bluetooth only: pick a bonded laptop. If it is not listed, bond it first in Android Bluetooth settings, then reopen this dialog.")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(devices) { (name, addr) ->
                        Button(onClick = { onConnect(addr, code) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("$name\n$addr")
                        }
                    }
                }
                if (devices.isEmpty()) Text("No bonded devices found.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = code, onValueChange = { if (it.length <= 6) onCode(it) },
                    label = { Text("6-digit pairing code (first time only)") },
                    singleLine = true)
                TextButton(onClick = { devices = activity.bondedDevices() }) { Text("Refresh list") }
                TextButton(onClick = onDisconnect) { Text("Disconnect current") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
fun SettingsDialog(settings: SettingsStore, onDismiss: () -> Unit) {
    var sens by remember { mutableStateOf(settings.sensitivity) }
    var scroll by remember { mutableStateOf(settings.scrollSpeed) }
    var natural by remember { mutableStateOf(settings.naturalScroll) }
    var haptics by remember { mutableStateOf(settings.haptics) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    var buttons by remember { mutableStateOf(settings.showClickButtons) }
    var tapClick by remember { mutableStateOf(settings.tapToClick) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Settings") },
        text = {
            Column {
                Text("Sensitivity: ${"%.2f".format(sens)}")
                Slider(value = sens, onValueChange = { sens = it; settings.sensitivity = it },
                    valueRange = 0.25f..3f)
                Text("Scroll speed: ${"%.2f".format(scroll)}")
                Slider(value = scroll, onValueChange = { scroll = it; settings.scrollSpeed = it },
                    valueRange = 0.25f..3f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(natural, { natural = it; settings.naturalScroll = it }); Text("Natural scroll")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(haptics, { haptics = it; settings.haptics = it }); Text("Haptics on click")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keepOn, { keepOn = it; settings.keepScreenOn = it }); Text("Keep screen on")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(buttons, { buttons = it; settings.showClickButtons = it }); Text("Show click buttons")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(tapClick, { tapClick = it; settings.tapToClick = it }); Text("Tap to click (off = buttons only)")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
fun KeyboardScreen(vm: AirKeysViewModel, settings: SettingsStore, compact: Boolean = false) {
    // Hidden field: the phone's own keyboard edits this buffer via the
    // platform InputConnection, so swipe / voice / predictive all work.
    // It always holds one sentinel space, rendered transparent so it
    // looks empty — that space is what Backspace deletes (v1.2 fix:
    // backspace on a truly empty field never fired onValueChange at all).
    var text by remember { mutableStateOf(" ") }
    val focusRequester = remember { FocusRequester() }
    val resetSignal = vm.resetSignal
    LaunchedEffect(resetSignal) { if (resetSignal > 0) text = " " }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Box(Modifier.fillMaxWidth().height(if (compact) 1.dp else 48.dp)) {
            BasicTextField(value = text, onValueChange = {
                text = it; vm.onHiddenTextChanged(it)
            }, modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent))
        }
        Button(onClick = { focusRequester.requestFocus() },
            modifier = Modifier.fillMaxWidth()) { Text("Tap to open keyboard") }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        // Extra key row: sticky modifiers + special keys.
        ExtraKeyRow(vm)
        if (!compact) {
            Text("Type on your phone keyboard — Urdu, Pashto, Arabic, English, " +
                "emoji, swipe and voice all go to the PC cursor.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ExtraKeyRow(vm: AirKeysViewModel) {
    @Composable
    fun ModBtn(label: String, mask: Int) {
        Button(onClick = { vm.toggleModifier(mask) },
            colors = if (vm.stickyModifiers and mask != 0) ButtonDefaults.buttonColors()
            else ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.padding(2.dp)) { Text(label) }
    }
    Column {
        Row { ModBtn("Ctrl", Modifiers.CTRL); ModBtn("Alt", Modifiers.ALT)
              ModBtn("Shift", Modifiers.SHIFT); ModBtn("Win", Modifiers.WIN) }
        Row {
            listOf("Esc" to KeyCode.ESC, "Tab" to KeyCode.TAB, "Del" to KeyCode.DELETE,
                   "Enter" to KeyCode.ENTER).forEach { (label, code) ->
                Button(onClick = { vm.sendSpecial(code) }, modifier = Modifier.padding(2.dp)) { Text(label) }
            }
            // Guaranteed backspace, independent of any keyboard's
            // InputConnection behaviour — direct BACKSPACE frame.
            Button(onClick = { vm.sendBackspace() }, modifier = Modifier.padding(2.dp)) { Text("⌫ Backspace") }
        }
        Row {
            listOf("←" to KeyCode.LEFT, "↑" to KeyCode.UP, "↓" to KeyCode.DOWN,
                   "→" to KeyCode.RIGHT, "Home" to KeyCode.HOME, "End" to KeyCode.END,
                   "PgUp" to KeyCode.PAGE_UP, "PgDn" to KeyCode.PAGE_DOWN).forEach { (label, code) ->
                Button(onClick = { vm.sendSpecial(code) }, modifier = Modifier.padding(2.dp)) { Text(label) }
            }
        }
        Row {
            (1..12).forEach { n ->
                Button(onClick = { vm.sendFunctionKey(n) }, modifier = Modifier.padding(1.dp)) { Text("F$n") }
            }
        }
    }
}

@Composable
fun TouchpadScreen(vm: AirKeysViewModel, settings: SettingsStore, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // Recreated when tap-to-click changes, so the new setting takes effect.
    val processor = remember(settings.tapToClick) {
        TouchpadGestureProcessor(sensitivity = settings.sensitivity,
            tapToClickEnabled = settings.tapToClick)
    }
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()
            .background(Color(0xFF1A1A2E))
            .border(1.dp, Color.Gray)
            .pointerInput(vm.touchpadLocked, settings) {
                awaitEachGesture {
                    // Track multi-finger taps: collect down/up quickly.
                    // v1.2 fix: a two-finger SCROLL lifting a finger must
                    // never become a right click. Only a quick multi-tap
                    // with no scroll movement counts as a multi-finger tap.
                    val downIds = mutableSetOf<Long>()
                    var lastEventTime = 0L
                    var multiDownAtMs = 0L
                    var multiMoved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val now = System.currentTimeMillis()
                        lastEventTime = now
                        if (vm.touchpadLocked) continue
                        for (change in event.changes) {
                            val id = change.id.value.toInt()
                            when {
                                change.changedToDown() -> {
                                    downIds += change.id.value
                                    if (downIds.size == 2) { multiDownAtMs = now; multiMoved = false }
                                    val ev = processor.onPointer(id, PointerAction.DOWN,
                                        change.position.x, change.position.y, now)
                                    dispatch(vm, settings, view, ev)
                                    if (downIds.size == 1) {
                                        holdJob?.cancel()
                                        holdJob = scope.launch {
                                            delay(400)
                                            dispatch(vm, settings, view, processor.beginHoldDrag())
                                        }
                                    }
                                }
                                change.changedToUp() -> {
                                    holdJob?.cancel()
                                    val wasMulti = downIds.size >= 2
                                    val isQuickMultiTap = wasMulti && !multiMoved &&
                                        (now - multiDownAtMs) <= 250 && settings.tapToClick
                                    val ev = processor.onPointer(id, PointerAction.UP,
                                        change.position.x, change.position.y, now)
                                    if (isQuickMultiTap && downIds.size in 2..3) {
                                        // Genuine multi-finger tap only.
                                        dispatch(vm, settings, view,
                                            processor.tapWithFingers(downIds.size))
                                    } else {
                                        dispatch(vm, settings, view, ev)
                                    }
                                    downIds -= change.id.value
                                    if (downIds.isEmpty()) vm.flushMovesNow()
                                }
                                change.positionChanged() -> {
                                    if (downIds.size >= 2) multiMoved = true
                                    val ev = processor.onPointer(id, PointerAction.MOVE,
                                        change.position.x, change.position.y, now)
                                    // Moves go through the coalescer; other
                                    // events are sent immediately.
                                    ev.forEach {
                                        when (it) {
                                            is MouseEvent.Move -> vm.sendMouseMove(
                                                it.dx, it.dy, 1f)
                                            is MouseEvent.Scroll -> vm.sendScroll(
                                                it.dx, it.dy, settings.scrollSpeed,
                                                settings.naturalScroll)
                                            is MouseEvent.Button -> vm.sendMouseButton(
                                                it.button, it.down)
                                            else -> {}
                                        }
                                    }
                                }
                            }
                        }
                        if (event.changes.all { !it.pressed }) break
                    }
                }
            }) {
            Text(if (settings.tapToClick) "Touchpad — move, tap, two-finger scroll"
                   else "Touchpad — move only, use Left / Right buttons to click",
                color = Color.White, modifier = Modifier.align(Alignment.Center))
            if (vm.touchpadLocked) Text("Locked — tap Lock to unlock",
                color = Color.Yellow, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
        }
        if (settings.showClickButtons) {
            Row(Modifier.fillMaxWidth()) {
                Button(onClick = { vm.sendMouseButton(0, true); vm.sendMouseButton(0, false) },
                    modifier = Modifier.weight(1f)) { Text("Left click") }
                Button(onClick = { vm.sendMouseButton(1, true); vm.sendMouseButton(1, false) },
                    modifier = Modifier.weight(1f)) { Text("Right click") }
            }
        }
    }
}

private fun dispatch(vm: AirKeysViewModel, settings: SettingsStore,
                     view: android.view.View, events: List<MouseEvent>) {
    events.forEach {
        when (it) {
            is MouseEvent.Move -> vm.sendMouseMove(it.dx, it.dy, 1f)
            is MouseEvent.Button -> {
                vm.sendMouseButton(it.button, it.down)
                if (it.down && settings.haptics)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            is MouseEvent.Scroll -> vm.sendScroll(it.dx, it.dy, settings.scrollSpeed, settings.naturalScroll)
            is MouseEvent.Click -> vm.sendMouseButton(0, true)
        }
    }
}
