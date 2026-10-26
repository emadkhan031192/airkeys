# AirKeys Protocol v1 (v1.1 transport update)

100% offline. v1.0 used one Bluetooth Classic RFCOMM (SPP) connection.
v1.1 keeps those exact frames and adds a TCP LAN transport; the phone
tries LAN first, then Bluetooth, and both carry the same frames with the
same AES-GCM keys and seq rules. LAN is any local network already in use
(phone hotspot shared for internet, home Wi-Fi, USB tethering) — it is
not a new hotspot, and traffic never leaves the LAN.

LAN transport details:
- TCP port 47820, one client at a time, `TcpNoDelay` on, same
  [length][type][seq][payload][tag] frames as §1.
- UDP discovery port 47821: the PC broadcasts
  `AirKeys|47820|<PC name>` every second. The phone listens, then
  connects by TCP to the sender. Fallback: parallel TCP scan of the
  common /24s (192.168.43.x phone hotspot, 192.168.137.x Windows
  hotspot, 192.168.42.x USB tethering, 192.168.0/1.x home routers).
- Pairing is identical (§4): same 6-digit code, same PBKDF2 derivation,
  same PAIR steps. LAN keys are stored separately from Bluetooth keys
  (Windows: `lan:<phone name>`, Android: `lan:<PC IP>`, plus a
  first-LAN-key fallback when a hotspot IP changes).
- Honest status rule (v1.1): a transport is "Connected" only after a
  PAIR reply decrypts successfully. Socket-open without PAIR is
  "Pairing…", then "Pairing failed — re-enter code". Never "Connected"
  while input is being dropped.

Bluetooth transport (fallback):
One Bluetooth Classic RFCOMM (SPP) connection, custom UUID
`00001101-0000-1000-8000-00805F9B34FB` is NOT used; AirKeys uses its own UUID so
it never collides with generic SPP devices:

```
UUID: 7a3e9b20-4f1c-4a2d-9c7e-1a2b3c4d5e6f
Service name: AirKeys
```

Direction is overwhelmingly phone -> PC. The PC only sends PONG and PAIR replies.

## 1. Frame format (on the wire, little-endian)

```
[uint16 length][uint8 type][uint32 seq][payload...][16-byte GCM tag]
```

* `length` = 1 + 4 + len(payload) + 16. It does NOT include itself.
  Max frame: 8192 bytes. A TEXT frame larger than that is split by the sender
  into multiple TEXT frames, in order, with consecutive seq numbers.
* `type` — see §2.
* `seq` — unsigned 32-bit, per-direction, starts at 1, increments by 1 per
  frame, wraps at 2^32-1 to 0. The sender MUST NOT reuse a seq for a different
  frame under the same key.
* `payload` — type-specific, see §2. All integers little-endian, all text UTF-8.
* `tag` — AES-256-GCM authentication tag over (type || seq || payload).

Encryption: AES-256-GCM, 12-byte nonce, 16-byte tag, AAD = type || seq
(5 bytes). The nonce is NEVER transmitted; it is derived deterministically
so both sides agree without extra bytes:

```
nonce[0]    = direction: 0x00 phone->PC, 0x01 PC->phone
nonce[1..7] = 0x00
nonce[8..11]= seq, little-endian
```

Because seq is unique per direction under a key, the nonce is unique. This is
safe ONLY because seq is never reused and the key changes on re-pair.

## 2. Frame types

| Value | Name | Payload | Delivery |
|---|---|---|---|
| 0x01 | TEXT | UTF-8 text, 1..8000 bytes | Reliable, ordered |
| 0x02 | BACKSPACE | uint16 count (LE) | Reliable, ordered |
| 0x03 | KEY | uint8 keyCode, uint8 down (1=down, 0=up) | Reliable, ordered |
| 0x04 | COMBO | uint8 modifiers, uint8 keyCode, uint8 down | Reliable, ordered |
| 0x05 | MOUSE_MOVE | int16 dx, int16 dy | Latest-wins |
| 0x06 | MOUSE_BUTTON | uint8 button (0=left,1=right,2=middle), uint8 down | Reliable, ordered |
| 0x07 | MOUSE_SCROLL | int16 dx, int16 dy (wheel clicks, signed) | Reliable, ordered |
| 0x08 | PING | uint64 unix-ms of sender (LE) | Reliable |
| 0x09 | PONG | uint64 echoed timestamp (LE) | Reliable |
| 0x0A | PAIR | see §4 | Reliable |

Key codes are Windows virtual-key codes (VK_*): Esc=27, Tab=9, Enter=13,
Backspace=8, Delete=46, Space=32, Left=37, Up=38, Right=39, Down=40,
Home=36, End=35, PageUp=33, PageDown=34, F1..F12=112..123, A..Z=65..90,
0..9=48..57. TEXT is always preferred for printable characters so that
Urdu, Pashto, Arabic, English and emoji arrive via KEYEVENTF_UNICODE and do
not depend on the PC keyboard layout. KEY/COMBO are for non-printable keys
and shortcuts.

Modifiers bitmask (COMBO): Ctrl=0x01, Alt=0x02, Shift=0x04, Win=0x08.
A COMBO down frame means "press modifiers, then press key"; a COMBO up frame
means "release key, then release modifiers". Sticky modifiers on the phone
are implemented by sending COMBO frames with the latched mask; the PC also
tracks held modifiers so a held Ctrl keeps working across TEXT frames.

MOUSE_SCROLL: dy>0 means scroll up in the phone's logical direction; the PC
applies the natural-scroll setting (inverts dy/dx) before SendInput.
One click = 120 wheel units on Windows (WHEEL_DELTA).

## 3. Reliability, ordering, replay protection

* The transport is a single ordered RFCOMM stream, so frames normally arrive
  in order. Ordering is still enforced at the protocol layer.
* Receiver state: `lastSeq` per direction, initially 0.
  A frame is accepted iff `seq != lastSeq` and `(seq - lastSeq) mod 2^32 < 2^31`.
  Otherwise it is a duplicate or replay and is silently dropped. This also
  handles the 2^32 wrap correctly.
* Reliable frames (TEXT, BACKSPACE, KEY, COMBO, MOUSE_BUTTON, MOUSE_SCROLL,
  PING, PONG, PAIR) are written to the socket immediately, in seq order, from
  a single writer thread. There is no batching delay for keys: a key frame is
  flushed to the socket the moment it is produced.
* Latest-wins frames (MOUSE_MOVE only): the phone coalesces moves. It
  accumulates dx/dy and, every ~8 ms, sends ONE MOUSE_MOVE with the
  accumulated delta and drops any older, unsent moves. It never queues moves.
  The PC applies a MOUSE_MOVE immediately on receipt; it never queues them
  either. A lost MOUSE_MOVE is harmless: the next move carries fresh deltas
  and the cursor simply ends up where the user's finger is.
* Lost reliable frames cannot happen silently on a live RFCOMM stream: a
  socket error tears the connection down, both sides release all held
  input, and the phone reconnects and resumes. The user retypes only what
  was in flight during the drop, which is bounded by the 3 s ping timeout.
* No frame is ever retransmitted with a reused seq. A reconnect starts a
  fresh seq space only after a fresh PAIR handshake (see §4), so a stale
  frame from an old connection cannot be replayed into a new one.

## 4. Pairing and key derivation

Prerequisites: standard Bluetooth bonding (the OS-level pairing that
encrypts the RFCOMM link) must succeed first. App-level pairing is on top
of bonding, never instead of it.

First pairing:
1. The PC generates a random 6-digit code (100000..999999), shows it in the
   pairing window, and keeps it valid for 120 seconds. It rate-limits
   guesses: max 5 failed PAIR attempts per device, then a 60 s lockout.
2. The user types the code into the phone, once.
3. Both sides derive the same 32-byte key:
   `key = PBKDF2-HMAC-SHA256(code-as-UTF8, salt="AirKeys-v1", 100000, 32)`.
   The code itself is never sent over the wire in any form.
4. The phone sends PAIR step=1: payload `[0x01][device-name UTF-8]`,
   encrypted with the derived key, seq=1.
5. The PC tries to decrypt with the key derived from the code it displayed.
   Success = pairing accepted. Failure = ignored (and counted toward the
   lockout). On success the PC stores the key (DPAPI-protected) keyed by
   the phone's Bluetooth address/name, and replies PAIR step=2
   `[0x02][pc-name UTF-8]` encrypted with the same key.
6. The phone stores the key (Android Keystore-wrapped) keyed by the PC's
   address. Later connections skip the code entirely.

Reconnect (stored key exists on both sides):
1. Phone sends PAIR step=3 `[0x03][device-name UTF-8]` encrypted with the
   stored key, seq=1.
2. PC decrypts with its stored key for that device. Success = session open,
   seq spaces reset, `lastSeq` reset. Failure = session rejected; the phone
   falls back to "enter pairing code" and the user re-pairs.
3. PC replies PAIR step=4 `[0x04]` as acknowledgement.

Unpaired devices: any device with no stored key that sends a non-PAIR frame,
or a PAIR frame that fails decryption, is ignored and disconnected. The PC
never injects input from an unauthenticated frame.

Key storage: Windows DPAPI (`ProtectedData.Protect`, CurrentUser scope) in
`%APPDATA%\AirKeys\pairing.bin`. Android: the pairing key is encrypted with
an AES key held in the Android Keystore and stored in SharedPreferences.
Neither side ever logs, displays, or transmits the key or the code.

## 5. Keep-alive, reconnect, release-all

* Phone sends PING every 3 s (seq-ordered, reliable). PC replies PONG
  echoing the timestamp. If the phone sees no PONG (or any frame) for 9 s
  it closes the socket and reconnects with backoff 1 s, 2 s, 4 s, ... capped
  at 30 s, indefinitely while the user leaves Connect on.
* PC disconnects a peer with no frame for 15 s.
* On ANY disconnect, socket error, or panic: the PC releases every held
  key (KEYEVENTF_KEYUP for each tracked VK) and every held mouse button
  (MOUSEEVENTF_*UP), clears held-modifier state, and only then closes.
  This is the single most important safety rule: a stuck Ctrl or left
  button must never survive a disconnect.
* Laptop sleep/wake: the socket breaks on sleep; the phone's reconnect loop
  re-establishes it after wake, with a fresh PAIR handshake. The user does
  not need to tap anything.

## 6. Latency budget (why <30 ms keys / <20 ms mouse is achievable)

RFCOMM over classic Bluetooth is typically 5-15 ms round-trip on a bonded
link with no other traffic. The budget is: phone input handling <2 ms,
frame build + encrypt <1 ms, RFCOMM write immediate (no Nagle-like batching
on this stream), PC read + decrypt + SendInput <3 ms. The only deliberate
delay in the whole system is the 8 ms mouse-move coalescing window, which
trades at most 8 ms of move latency for a smooth, unqueued cursor. Keys,
clicks and scrolls have zero deliberate delay. See docs/PERFORMANCE_TEST_PLAN.md
for how to measure this.
