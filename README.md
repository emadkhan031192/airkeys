# AirKeys — Your Android phone is a wireless keyboard + touchpad for Windows

![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Windows-blue)
![Offline](https://img.shields.io/badge/offline-100%25-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

AirKeys turns an Android phone into a wireless keyboard **and** mouse/touchpad
for a Windows laptop. No internet, no cloud, no account, no relay server,
no analytics.

**v1.2 (current): Bluetooth only, as requested.** The v1.1 LAN/Auto
options were removed from the Android connect dialog and the Windows
tray — Bluetooth Classic (RFCOMM/SPP) is the only connection choice
offered. The LAN code stays in the repo, unused, for a future re-enable.
"Connected" is only shown after pairing actually succeeds — a socket that
opened but never paired now shows "Pairing failed", never "Connected".
v1.2 also fixes: Gboard Backspace (sentinel hidden field + a dedicated
⌫ Backspace button), Smart Keyboard Urdu tofu boxes (bundled Noto
Nastaliq Urdu font), and touchpad auto-clicks (tap-to-click OFF by
default; use the Left/Right buttons, or turn tap-to-click on in Settings).

Open the Android app, tap **Connect**, and:

- **Keyboard:** whatever you type on the phone's own keyboard — Urdu,
  Pashto, Arabic, English, emoji, swipe typing, voice typing — appears
  instantly at the cursor in any Windows app.
- **Mouse:** the phone screen is a laptop touchpad — move, click, drag,
  scroll, right-click, middle-click, all at the same time as typing.
- Switch between keyboard and touchpad with **one tap**, or use split mode
  (touchpad on top, keyboard button below).

> Screenshots: add `docs/screenshots/keyboard.png`,
> `docs/screenshots/touchpad.png`, `docs/screenshots/windows-tray.png`
> here once you have them — the placeholders are intentional.

## Features

- One persistent Bluetooth RFCOMM connection carries keyboard + mouse.
- AES-256-GCM encryption on every frame; 6-digit app pairing code, entered once.
- <30 ms keystrokes, <20 ms mouse movement (see docs/PERFORMANCE_TEST_PLAN.md).
- Mouse-move coalescing every ~8 ms (latest delta wins, stale moves dropped).
- Auto-reconnect, 3 s keep-alive ping, foreground service (works screen-off).
- Panic hotkey on PC: **Ctrl+Alt+Pause** releases all stuck keys/buttons and disconnects.
- Pointer acceleration curve, sensitivity, scroll speed, natural-scroll toggle.
- Correct right-to-left text (Urdu/Arabic/Pashto) via `KEYEVENTF_UNICODE`.
- Sticky Ctrl/Alt/Shift/Win, F1–F12, arrows, Home/End, PageUp/PageDown.
- Palm rejection and a touchpad lock on the phone.

## Setup in 5 steps (Bluetooth only, v1.2)

1. **Windows:** install `AirKeys.exe` from the latest GitHub Release. It
   lives in the system tray. Tray > Re-pair shows a 6-digit pairing code.
2. **Android:** install the APK from the same Release. Grant Bluetooth +
   Notifications when asked.
3. **Bond:** bond the phone to the laptop in Android Bluetooth settings
   first, if not already bonded.
4. **Connect:** in the AirKeys app, tap **Connect**, pick the laptop,
   and type the 6-digit code from the PC screen when asked (within
   120 seconds). The code is never asked again for that pair. If the
   PC tray says "waiting for pairing" while the phone says anything
   else, pairing has not succeeded — re-pair.
5. **Use it:** tap the keyboard / Smart KB / touchpad switch. Smart KB
   is the built-in English/Urdu/symbols keyboard that sends each tap
   directly; Keyboard mode uses your phone keyboard (Gboard/SwiftKey)
   for swipe, voice and predictive text. Touchpad: tap-to-click is OFF
   by default — move with one finger, click with the Left/Right
   buttons below the pad, or turn tap-to-click on in Settings.

## Gesture cheat sheet

| Gesture | Result |
|---|---|
| One-finger move | Move cursor |
| Single tap | Left click |
| Double tap | Double click |
| Two-finger tap | Right click |
| Three-finger tap | Middle click |
| Two-finger drag | Scroll (vertical + horizontal) |
| Tap-and-hold, then drag | Drag and drop |
| Double-tap-and-drag | Drag and drop |
| Left / Right buttons at the bottom | Left / right click (optional toggle) |

Keyboard extra row: Esc, Tab, Ctrl, Alt, Shift, Win, arrows, Del, Enter,
F1–F12. Tap a modifier once to latch it for the next key (sticky).

## Troubleshooting

**Bluetooth not found / laptop not in the list**
- Make sure the Windows app is running (tray icon visible) and Bluetooth
  is on on both devices. Bluetooth Classic, not BLE, is required.
- Bond first in Android Settings > Bluetooth. Then reopen AirKeys and
  pull the device list again.
- On Windows, check Settings > Bluetooth & devices: the phone must appear
  as a paired device. Remove and re-bond if it is stuck in "paired" but
  never connects.

**Permission denied**
- Android 12+: AirKeys needs Nearby Devices (Bluetooth Connect/Scan) and
  Notifications (for the foreground service). If you denied one, go to
  App info > Permissions and allow them, then tap Connect again.
- Android 11 and older: Location permission is needed for Bluetooth
  discovery on those versions; grant it only for discovery, then it can
  be revoked after bonding.

**Lag**
- Move closer (under ~5 m, line of sight is best). Bluetooth shares the
  2.4 GHz band with Wi-Fi — turning the phone's Wi-Fi off often helps,
  even though AirKeys does not use Wi-Fi.
- Close other Bluetooth audio devices streaming at the same time.
- If keys lag but the mouse is smooth (or vice versa), check the PC is
  not under heavy CPU load; SendInput is fast, but a saturated machine
  will show it first in input latency.

**Cursor too fast / too slow**
- Windows tray > Settings: lower/raise **Sensitivity** first (0.5–2.0),
  then **Acceleration**. High acceleration makes fast flicks fly and slow
  moves precise; set it to 0 for a purely linear cursor.
- The phone's Settings has the same sensitivity control; the Windows
  setting wins for the final cursor speed, so change one side at a time.

**Stuck key or stuck mouse button**
- Press **Ctrl+Alt+Pause** on the PC keyboard. It releases everything and
  disconnects. Reconnect from the phone.
- Disconnecting from the phone also releases everything automatically.

**Pairing code rejected**
- Codes expire after 120 s. On the PC, use Tray > Re-pair for a fresh code.
- After 5 wrong guesses the PC locks that device out for 60 s — wait, then retry.

## Building from source

See "Build and run" at the end of the release notes / in this repo's
`docs/` — short version: `dotnet publish` for Windows, Android Studio
(or `./gradlew assembleDebug`) for Android. Full commands are in the
delivery notes accompanying this repository.

## Project layout

```
android/   Kotlin + Jetpack Compose app (min SDK 26)
windows/   C# .NET 8 WinForms tray app + xUnit tests
docs/      PROTOCOL.md, PERFORMANCE_TEST_PLAN.md
```

## Security model

Standard Bluetooth bonding plus a one-time 6-digit app code. Every frame is
AES-256-GCM encrypted with a key derived from that code (PBKDF2-HMAC-SHA256,
100k iterations) and stored locally (DPAPI on Windows, Keystore-wrapped on
Android). Unpaired devices are ignored. Nothing leaves the Bluetooth link.

## License

MIT — see LICENSE. Contributions welcome — see CONTRIBUTING.md.
