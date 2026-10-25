# Hapuya — architecture (v0.1)

24 Oct 2026 · Phase 2 items C1 and C3 · Status: **Proposed**, needs Splash's sign-off.
Companion docs: [threat-model.md](threat-model.md), [ADR 0001](adr/0001-android-stack.md),
[ADR 0002](adr/0002-backend.md).

## 1. Shape

```
Entry points ──► Task engine ─────────────────────────────────────► Executors
(command bar,    Understand → Plan → Check policy → Execute → Verify → Receipt
 voice, assist,       │                    │                         │
 notification,     AI router            Policy engine            Receipt store
 briefing card)  (rules → small → large)  (permissions,           (encrypted)
                      │                    impact, consent,
                 Backend AI proxy          caps, duplicates)
```

Shared services: encrypted store, exclusion filter, consent and privacy centre,
entitlement (tier, trial, caps), cost meter.

## 2. Modules
| Module | Contents | Depends on |
|---|---|---|
| `app` | Activity, navigation, Hilt setup, flavours | everything |
| `core:model` | Pure Kotlin types: `Intent`, `Plan`, `Step`, `Receipt`, `ImpactLevel`, `PolicyDecision` | — |
| `core:engine` | Task state machine, step runner, stop/pause/retry | model, policy |
| `core:policy` | Permission and autonomy rules, impact classification, duplicate guard | model |
| `core:ai` | AI router, rule-based parser, backend client, intent schema validation | model |
| `core:store` | Room + SQLCipher, DAOs, Keystore key wrapping | model |
| `core:privacy` | Exclusion filter (OTP, banking, user list), consent state, local-only mode | model |
| `executor:reminder`, `executor:calendar`, `executor:alarm`, `executor:reply`, … | One executor per capability | model |
| `feature:home`, `feature:receipts`, `feature:onboarding`, … | Compose screens and view models | core:* |

`core:model`, `core:policy` and `core:engine` are pure Kotlin with no Android
dependencies, so the safety-critical logic runs as fast JVM unit tests.

M0 ships only `app`. Modules are split out in M3 as they get real code.

## 3. Task engine

### States
```
CREATED → UNDERSTANDING → (CLARIFYING ⇄ user) → PLANNED → CHECKING
  → AWAITING_CONFIRMATION ⇄ user → EXECUTING(step n) → VERIFYING
  → DONE | PARTIAL | FAILED | NEEDS_YOU | STOPPED | BLOCKED
```

### Rules
- **One step at a time.** Before every step the engine re-checks stop/pause and
  re-runs the policy check, because permissions can change mid-task.
- **Stop is cooperative but immediate.** A stop flag is checked before each executor
  call, and executors receive a cancellable coroutine scope. No new external action
  starts after stop (F9: 100%).
- **Retry** re-runs only failed steps, with the same idempotency keys, so a step that
  actually succeeded is never repeated.
- **At most one clarifying question** per task (F7). If it is still ambiguous after
  that, the task ends `NEEDS_YOU` with what is missing.
- **Every terminal state writes a receipt**, including `BLOCKED` and `STOPPED`.
- Tasks persist in the store, so a process death mid-task resumes at `CHECKING` for
  the current step rather than re-executing it.

## 4. Executor plug-in contract

```kotlin
interface Executor {
    val capability: Capability          // e.g. CREATE_REMINDER, SEND_REPLY
    val impact: ImpactLevel             // LOW, MEDIUM, HIGH (see policy)
    fun availability(ctx: DeviceContext): Availability   // permission/role/API level present?
    suspend fun execute(step: Step, key: IdempotencyKey): ExecResult
    suspend fun verify(step: Step, result: ExecResult): Verification  // VERIFIED / UNVERIFIABLE / FAILED
}
```

- Executors are registered through Hilt multibinding. The planner can only emit steps
  for registered, available capabilities. That allowlist is the AI's whole action
  space.
- The "where possible" in F7 becomes concrete: `verify()` must read the outcome back
  (event row exists, alarm registered, reply `PendingIntent` sent without exception).
  `UNVERIFIABLE` results produce a receipt that says so, never "done".
- Later plug-ins (AppFunctions callers, off-Play companion) implement the same
  interface and declare `availability` per device.

### MVP executors
| Executor | API | Impact |
|---|---|---|
| reminder | Room + `AlarmManager` exact alarm, WorkManager fallback | LOW |
| alarm/timer | `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER` intents | LOW |
| calendar | `CalendarContract` (READ/WRITE_CALENDAR) | LOW (create), HIGH (delete) |
| reply | Notification `RemoteInput` action from the listener | MEDIUM to a known contact with send granted; HIGH otherwise |
| open-chat | Deep link or share intent, prefilled; the user sends | LOW (the user performs the send) |
| call | `ACTION_DIAL` (user taps call) | HIGH: always confirm |
| dnd/media | `NotificationManager` policy / `MediaSessionManager` | LOW |

## 5. Policy engine
A pure function:

```
decide(step, settings, contact, tier, consent, origin, history) → ALLOW | CONFIRM | BLOCK(reason)
```

The rules are the testable spec in
[03_Product/permission-matrix.md](../../03_Product/permission-matrix.md). Key points:
- Impact class comes from the executor, never from the AI.
- `HIGH` always produces `CONFIRM` (new contact, call, delete) or `BLOCK` (money:
  unsupported in MVP).
- **Origin matters.** A step whose instruction came from *content* (notification,
  web, email text) rather than from the user is `BLOCK`ed. Content can inform a plan
  but cannot request actions. See the threat model.
- **Duplicate guard**: the same capability, target and payload hash within 10
  minutes is `BLOCK(duplicate)` unless the user re-confirms.

## 6. AI router
1. **Rule-based parser** first: deterministic grammar for fixed commands and the
   common reminder/alarm phrase families. This is the whole free tier, and it is the
   offline and local-only fallback.
2. **Small model** via `POST /v1/ai/intent` when the rules fail and the tier, consent
   and cap all allow it.
3. **Larger model** only on schema-valid low-confidence results from the small model
   (Phase 3; measured in A2).

The model returns JSON matching a fixed intent schema, and the app validates it again.
The router passes untrusted text inside a delimited `content` field with
provenance. The policy engine, not the prompt, is the security boundary.

Each call records model, tokens, latency and estimated cost in the cost meter (cost
NFR).

## 7. Data and storage
- One SQLCipher database. Its passphrase is random, wrapped by a Keystore AES-GCM key
  (StrongBox when available) and never leaves the device.
- Tables: `task`, `step`, `receipt`, `reminder`, `notification_item`,
  `conversation`, `template`, `permission_setting`, `contact_ref`, `consent_event`,
  `cost_event`.
- Excluded notifications are dropped in the listener **before** any write. There is
  no "excluded" table, because what isn't stored can't leak (F3).
- Retention: receipts 90 days (F9); notification items 30 days (to confirm in B5).
- `android:allowBackup="false"`: an encrypted DB without its Keystore key is useless
  elsewhere anyway; export (F10) is the supported path.

## 8. Contacts (C3)
- Default: **Contact Picker** (`ACTION_PICK` / the Android 17 contact picker) whenever
  the user names a contact to act on. Hapuya stores only a `contact_ref` (lookup key,
  display name, the user's per-contact permission settings).
- Matching notification senders to contacts uses the name the notification itself
  provides. No address-book read is needed for the inbox.
- `READ_CONTACTS` is **not requested in MVP**. If a later feature truly needs it,
  write the justification first; apps targeting Android 17 must justify it instead of
  using the picker (enforced from 28 Oct 2026).

## 9. Permissions requested (only when needed, F2)
| Permission or role | First needed by |
|---|---|
| `POST_NOTIFICATIONS` (13+) | Reminder firing |
| `SCHEDULE_EXACT_ALARM` | Punctual reminders (fallback if denied) |
| `RECEIVE_BOOT_COMPLETED` | Reschedule after reboot |
| Notification listener access | Inbox, replies |
| Default assistant role (`ROLE_ASSISTANT`) | On-invocation screen context |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Calendar executor |
| `RECORD_AUDIO` | Voice commands |
| Battery optimisation exemption (guided) | Reliability on aggressive OEMs |

## 10. Gate 2 slice
For Gate 2, `app`, `core:model`, `core:engine`, `core:policy`, `core:ai` (rules plus
the proxy client), `core:store`, `core:privacy` (consent only) and
`executor:reminder` are real. Everything else stays an interface with no
implementation.
