# FindPhone

English | [日本語](README.ja.md)

Ring a misplaced phone from a PC on the same network.
The phone announces itself with sound, a flashing screen, a blinking flashlight and vibration.

- **Android**: `FindPhone-Android.apk` (Android 8.0 or later)
- **Windows**: `FindPhone-Windows.exe` (no installation required)

Both are available from [Releases](../../releases).

## Setup

### 1. Phone (Android)

1. Download `FindPhone-Android.apk` from Releases onto the phone and open it.
2. If Android asks about installing unknown apps, allow it for that browser (or file manager).
3. Open the app and allow notifications. Listening starts automatically and a notification appears.
4. Open "Help & troubleshooting" and press "Fix" until every check shows ✓ (turning off battery optimization is recommended).

### 2. PC (Windows)

1. Download `FindPhone-Windows.exe` from Releases, put it anywhere and run it (no installation required).
2. If Windows Firewall asks on first launch, allow "Private networks".
3. If SmartScreen shows a warning, choose "More info" → "Run anyway".

### 3. Pairing (first time only)

1. On the phone, press "Show" under "Pairing code" (or "Share" to send it to your own PC).
2. Enter the code in "Pair" on the PC app and press OK. If the code is on the clipboard, it is filled in automatically.
3. Pairing is complete when the top of the PC app shows a green "Connected".

### 4. Use

- Press the round button on the PC app to ring the phone. `Enter` = ring / `Esc` = stop.
- On the phone, stop with the "STOP" button on screen or the "Stop" action in the notification. Ringing stops automatically after the set duration (90 seconds by default).
- Sound, duration, screen flash, flashlight and vibration can be changed in the phone's "Settings".

The PC and the phone must be connected to the same router (a guest Wi-Fi will not work).

## Troubleshooting

Open "Help & troubleshooting" in the phone app. It diagnoses the cause automatically and offers a "Fix" button
(listening stopped, no Wi-Fi, VPN, notifications, battery optimization, permission to flash on the lock screen).
If that does not solve it, ask on Discord and attach the output of "Copy log" from the same screen.

## Support

Support is provided on Discord only (issues, e-mail and direct messages are not answered): https://discord.gg/hXv7G3xtVf

## Languages

English / 日本語 / 简体中文 / 繁體中文 / 한국어 / Español / Français / Deutsch / Português / Русский
(English by default. Change it in "Settings" → "Language" on the phone, and with "Language" at the bottom of the PC app.)

## Security

- All traffic is signed with HMAC-SHA256 using a shared key (128-bit random). The key itself is never sent.
- Challenge and response (single use, valid for 10 seconds) prevents replay of captured traffic.
- Packets from outside the LAN are ignored, and the number of answers to searches is rate-limited.
- Key storage: encrypted with Android Keystore on the phone and with Windows DPAPI on the PC.
- Screenshots and screen recording are blocked while the pairing code is shown, and the code is hidden when you leave the screen.
- Ringing stops automatically after the set duration (90 seconds by default).

## Protocol

UDP 47321 (the phone listens).

```
PC    -> 'H' + cn(8)                 search
Phone -> 'C' + cn + sn(16) + HMAC    challenge
PC    -> 'R'|'S' + sn + HMAC         ring / stop
Phone -> 'A' + cmd + sn + HMAC       acknowledgement
```

## License

Proprietary license (this is not open source). The source code is published so that it can be inspected,
but redistribution, modification, building, re-signing, selling and listing on other sites are not permitted.
Contact the author on Discord before featuring this software in an article or video.
See [LICENSE](LICENSE) for the full terms (Japanese).
