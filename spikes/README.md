# Spikes (throwaway)

These are M2 experiments from `03_Product/PHASE2_PLAN.md`. They answer device
questions before the real executors are built. `:app` never depends on them, and
their code is not reused; the findings are.

| Module | Plan item | Question | Tester checklist |
|---|---|---|---|
| `:spikes:alarms` | A5 | Do exact alarms fire within 2 min under each brand's battery manager, overnight and after reboot? | `05_Testing/spikes/A5-reminder-reliability.md` |
| `:spikes:notify` | A4 | Do notifications arrive within 5 s, and do reply actions deliver on WhatsApp, Telegram, Google Messages and Samsung Messages? | `05_Testing/spikes/A4-notification-reply.md` |

Build the debug APKs:

```bash
./gradlew :spikes:alarms:assembleDebug :spikes:notify:assembleDebug
```

The notify spike stores no message content, and every test reply needs a tap to
confirm.
