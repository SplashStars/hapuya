# Spikes (throwaway)

These are M2 experiments from `03_Product/PHASE2_PLAN.md`. They answer device
questions before the real executors are built. `:app` never depends on them, and
their code is not reused; the findings are.

| Module | Plan item | Question | Tester checklist |
|---|---|---|---|
| `:spikes:alarms` | A5 | Do exact alarms fire within 2 min under each brand's battery manager, overnight and after reboot? | `05_Testing/spikes/A5-reminder-reliability.md` |
| `:spikes:notify` | A4 | Do notifications arrive within 5 s, and do reply actions deliver on WhatsApp, Telegram, Google Messages and Samsung Messages? | `05_Testing/spikes/A4-notification-reply.md` |
| `:spikes:onphone-ai` | A6 | Does the phone have built-in Gemini Nano? How fast, accurate and battery-hungry is a downloaded Gemma 4 E2B (LiteRT-LM) in split mode? | `05_Testing/spikes/A6-onphone-ai.md` |

Build the debug APKs:

```bash
./gradlew :spikes:alarms:assembleDebug :spikes:notify:assembleDebug :spikes:onphone-ai:assembleDebug
```

The notify spike stores no message content, and every test reply needs a tap to
confirm. The onphone-ai spike uses only the test phrase sets. It downloads one model
file from a pinned URL, checks its SHA-256 and runs the same `Resolver` and scorer as
the laptop eval (`:tools:intent-core`).

## Findings

### A5 alarms: Samsung Galaxy S22+ (Android 16), 3-4 Nov 2026
Full log: `05_Testing/results/A5-S22plus.md`.

| Run | 1 h | 4 h | 8 h | Notes |
|---|---|---|---|---|
| 1 factory battery (Optimised) | 0.04 s | 0.21 s | 0.06 s | overnight; deep Doze 23:09-05:00 |
| 2 Unrestricted | 0.29 s | 0.25 s | 0.03 s | daytime; deep Doze from 07:05 |
| Reboot | 2 min 0.04 s before, 15 min 0.30 s after | | | rescheduled ~30 s after unlock |

All 10 probes fired within 0.3 s (target 2 min), with exact alarms allowed and
`setExactAndAllowWhileIdle`. Unplug was simulated with `dumpsys battery unplug`
while on USB, not a true unplugged night.

Carry into M3:
- Exact-alarm access is off by default on Android 14+; onboarding must ask for it and
  fall back to inexact alarms with a warning when declined.
- `BOOT_COMPLETED` only arrives after the first unlock, so reminders due between a
  reboot and that unlock wait. Make the scheduler direct-boot aware
  (`LOCKED_BOOT_COMPLETED`, schedule times only in device-protected storage).

Still needed for Gate 2: a second brand and an Android 10-12 phone.

### A4 notifications: Samsung Galaxy S22+ (Android 16), 5 Nov 2026 (self-test)
Full log: `05_Testing/spikes/A4-notification-reply.md`. With no second sender, the spike
posts its own MessagingStyle notifications with a RemoteInput reply and replies to them
over adb (`SelfTestReceiver`, DUMP-protected; never touches other apps' notifications).

- Capture 20/20, all under 5 s (avg 251 ms, max 262 ms), phone locked.
- Reply 10/10 delivered via RemoteInput (avg 6 ms).
- Samsung delivered each notification to the listener twice: de-duplicate in the inbox.
- Not covered: real messaging apps' reply handlers. Checked in beta.
