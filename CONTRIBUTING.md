# Contributing to AirKeys

Thanks for helping. AirKeys is offline-first and safety-first; keep both
intact in every change.

## Ground rules

1. No network code, ever. No HTTP, no analytics, no telemetry, no crash
   reporting that leaves the device. Bluetooth RFCOMM only.
2. Never invent input. The phone sends exactly what the user typed or did;
   the PC injects exactly what it received. No autocorrect on the PC side.
3. Release-all on disconnect is sacred. Any new held state (key, button,
   modifier) must be added to the release-all path on both sides.
4. Keep the protocol backward-compatible or bump `PROTOCOL.md` to v2 with a
   migration note. Never change a frame layout silently.
5. Keys, clicks and scrolls are never batched or delayed for coalescing.
   Only MOUSE_MOVE is latest-wins.

## Workflow

- Fork, branch from `main`, keep changes focused.
- Android: `./gradlew :app:testDebugUnitTest` must pass.
- Windows: `dotnet test windows/AirKeys.sln` must pass.
- Add a unit test for any new protocol, crypto, delta or gesture logic.
- Update `docs/PROTOCOL.md` if you touch the wire format, and README's
  gesture table if you touch gestures.

## Reporting bugs

Include: phone model + Android version, Windows version, steps, whether
the issue survives Ctrl+Alt+Pause + reconnect, and (for latency) the
measurements from docs/PERFORMANCE_TEST_PLAN.md.
