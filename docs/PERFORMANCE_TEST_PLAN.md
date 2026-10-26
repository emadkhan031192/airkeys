# AirKeys Performance Test Plan

Goal: prove <30 ms keystroke latency and <20 ms mouse-move latency,
end to end, on a real phone + laptop pair. Do not measure on an emulator;
Bluetooth timing on an emulator is meaningless.

## Setup

- Phone: any Android 8+ device with Bluetooth Classic, Gboard installed.
- Laptop: Windows 10/11, Bluetooth Classic, AirKeys Windows app running.
- Test app on Windows: Notepad for keys; a small "cursor logger" is not
  needed — use the Windows app's built-in debug log (enable
  `AirKeys > Settings > Log frame timestamps` in a debug build) which logs
  `received_at - ping_timestamp` per PING/PONG and per-frame receipt times.
- Room: phone within 2 m of the laptop, no Bluetooth audio streaming.

## Test 1 — Keystroke latency (target: p95 < 30 ms)

1. On the phone, open the keyboard mode and focus the hidden field.
2. On the laptop, focus Notepad.
3. Type a fixed sentence of 100 characters at normal speed, then the same
   sentence with a fast burst (as fast as you can swipe/type).
4. Method A (instrumented, preferred): use a debug Android build that logs
   `System.nanoTime()` when a TEXT frame is queued, and a debug Windows
   build that logs when SendInput returns for that seq. Latency =
   windows_time - phone_time, corrected for clock offset using the
   PING/PONG round-trip / 2 measured immediately before the run.
5. Method B (no instrumentation): film both screens at 240 fps. The frame
   where the phone key highlights vs the frame where the character appears
   in Notepad gives latency to ~4 ms. Count 50 keystrokes.
6. Pass criteria: median < 20 ms, p95 < 30 ms, zero lost characters,
   zero out-of-order characters, zero duplicated characters.

## Test 2 — Mouse-move latency (target: p95 < 20 ms + coalescing window)

1. Move one finger slowly, then quickly, across the touchpad in straight
   lines, circles, and stop-start motions.
2. Instrumented: log phone move-accumulation time and Windows SendInput
   time for the same seq. Report latency excluding the deliberate 8 ms
   coalescing window separately from including it: raw transport should be
   <20 ms; with coalescing, perceived latency should still be <30 ms and
   feel smooth, with no stair-stepping.
3. Visual: film at 240 fps, compare finger position to cursor position.
4. Pass criteria: no visible cursor lag or jumping, no queued "catch-up"
   movement after the finger stops (catch-up >100 ms after finger-up = FAIL:
   it means moves were queued instead of coalesced), no dropped clicks.

## Test 3 — Click / scroll / drag correctness

1. Single tap, double tap, two-finger tap, three-finger tap, two-finger
   scroll both directions, tap-and-hold drag, double-tap drag — 20 reps each.
2. Pass criteria: 100% correct button mapping, scroll direction matches the
   natural-scroll setting, drag holds until finger-up, no phantom clicks
   on palm touches (palm rejection).

## Test 4 — Reconnect and keep-alive

1. With a connection live, turn the laptop's Bluetooth off for 10 s, then on.
   The phone must reconnect automatically within 30 s with no user tap.
2. Put the laptop to sleep for 60 s, wake it. Same: auto-reconnect <30 s.
3. Turn the phone screen off for 5 minutes while connected. Typing after
   screen-on must work (foreground service kept the socket alive).
4. Kill the Windows app mid-drag (hold a drag on the phone, quit the PC
   app). The PC must release the button on disconnect (verify: no stuck
   drag after reconnect).

## Test 5 — Load / soak

1. Type continuously for 5 minutes + move the mouse continuously.
2. Pass criteria: no latency growth over time (compare first-minute p95
   to last-minute p95 — growth >5 ms = investigate queueing), no memory
   growth on either side, no disconnects.

## Reporting

Record: phone model + Android version, laptop + Windows version + Bluetooth
chip, distance, whether Wi-Fi was on/off, median/p95/max for Tests 1–2,
pass/fail for Tests 3–5. A release is not "fast" because one run was fast;
report the p95 across at least 100 keystrokes and 200 mouse moves.
