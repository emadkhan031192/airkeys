package com.airkeys.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.airkeys.*


@Composable
fun AirKeysApp(vm: AirKeysViewModel, activity: MainActivity) {
    val settings = remember { SettingsStore(activity) }
    var showSettings by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    // v1.5: poll live health every 2 s, so the UI never repeats a
    // stale "Connected" when the transport is actually dead.
    LaunchedEffect(Unit) {
        while (true) { vm.refreshConnectionHealth(); delay(2000) }
    }
    MaterialTheme {
        Column(Modifier.fillMaxSize()) {
            // Top bar, two lines (v1.5 fix for "Air / Key / s" wrapping):
            // line 1 = title + Connect + gear, line 2 = live status.
            // The status line never trusts the status string alone: if
            // it says Connected but no transport is live, it says so
            // in red, with Test + Reconnect right there.
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AirKeys", fontSize = 20.sp, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    Button(onClick = { showDevices = true }) {
                        Text(if (vm.connectionOk) "Disconnect" else "Connect")
                    }
                    IconButton(onClick = { showSettings = !showSettings }) { Text("⚙") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val healthText = when {
                        vm.status.startsWith("Connected") && !vm.connectionOk ->
                            "⚠ Shows Connected, but no live Bluetooth transport"
                        vm.connectionOk ->
                            "${vm.status} • ${vm.framesSent} frames sent"
                        else -> vm.status
                    }
                    val healthColor = when {
                        vm.status.startsWith("Connected") && !vm.connectionOk -> Color.Red
                        vm.connectionOk -> Color(0xFF2E7D32)
                        else -> Color.Gray
                    }
                    Text(healthText, style = MaterialTheme.typography.bodySmall,
                        color = healthColor, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.testInjection() },
                        modifier = Modifier.padding(horizontal = 4.dp)) { Text("Test") }
                    if (!vm.connectionOk) {
                        Button(onClick = { showDevices = true }) { Text("Reconnect") }
                    }
                }
                vm.testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .horizontalScroll(rememberScrollState())) {
                ModeButton("Android KB", vm.mode == Mode.KEYBOARD) { vm.changeMode(Mode.KEYBOARD) }
                ModeButton("Smart KB", vm.mode == Mode.SMART_KEYBOARD) { vm.changeMode(Mode.SMART_KEYBOARD) }
                ModeButton("Touchpad", vm.mode == Mode.TOUCHPAD) { vm.changeMode(Mode.TOUCHPAD) }
                if (vm.mode == Mode.TOUCHPAD) {
                    Button(onClick = { vm.setLocked(!vm.touchpadLocked) }) {
                        Text(if (vm.touchpadLocked) "🔒 Locked" else "🔓 Lock")
                    }
                    Button(onClick = { vm.changeDragMode(!vm.dragMode) }) {
                        Text(if (vm.dragMode) "✋ Drag ON" else "Drag OFF")
                    }
                }
            }
            when (vm.mode) {
                // v1.4: both keyboards sit at the bottom, easy to reach.
                Mode.KEYBOARD -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                    AndroidKeyboardScreen(vm)
                }
                Mode.SMART_KEYBOARD -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                    SmartKeyboardScreen(vm)
                }
                Mode.TOUCHPAD -> TouchpadScreen(vm, settings, Modifier.weight(1f))
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
fun TouchpadScreen(vm: AirKeysViewModel, settings: SettingsStore, modifier: Modifier = Modifier) {
    val view = LocalView.current
    // Recreated when tap-to-click changes, so the new setting takes effect.
    val processor = remember(settings.tapToClick) {
        TouchpadGestureProcessor(sensitivity = settings.sensitivity,
            tapToClickEnabled = settings.tapToClick)
    }
    Column(modifier) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxHeight()
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
                                    // v1.4: Drag is explicit only. When Drag
                                    // mode is ON, pressing down starts a
                                    // drag (left button held) at once; when
                                    // OFF, no timer ever holds the button,
                                    // so moving the cursor can never
                                    // select text by accident.
                                    if (downIds.size == 1 && vm.dragMode) {
                                        dispatch(vm, settings, view, processor.beginHoldDrag())
                                    }
                                }
                                change.changedToUp() -> {
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
            Text(if (vm.dragMode) "Touchpad — Drag ON: moves select / drag"
                   else "Touchpad — move only, never selects (Drag OFF)",
                color = Color.White, modifier = Modifier.align(Alignment.Center))
            if (vm.touchpadLocked) Text("Locked — tap Lock to unlock",
                color = Color.Yellow, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
        }
        // v1.4 tiny separate scroll strips: right edge = vertical only,
        // bottom bar = horizontal only (mouse-wheel style).
        VerticalScrollStrip(vm)
        }
        HorizontalScrollBar(vm)
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

@Composable
fun VerticalScrollStrip(vm: AirKeysViewModel) {
    val wheel = remember { ScrollWheelAccumulator() }
    Box(
        Modifier.width(36.dp).fillMaxHeight()
            .background(Color(0xFF2E2E4A))
            .border(1.dp, Color.Gray)
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    val (_, cy) = wheel.accumulate(0f, dragAmount.y)
                    if (cy != 0) vm.sendScrollWheel(0, cy)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text("↕", color = Color.White) }
}

@Composable
fun HorizontalScrollBar(vm: AirKeysViewModel) {
    val wheel = remember { ScrollWheelAccumulator() }
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .background(Color(0xFF2E2E4A))
            .border(1.dp, Color.Gray)
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    val (cx, _) = wheel.accumulate(dragAmount.x, 0f)
                    if (cx != 0) vm.sendScrollWheel(cx, 0)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text("Scroll ↔  drag left / right", color = Color.White, style = MaterialTheme.typography.bodySmall) }
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
