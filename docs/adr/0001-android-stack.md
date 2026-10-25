# ADR 0001 — Android stack

Status: **Accepted** (Splash, 25 Oct 2026) · drafted 24 Oct 2026 · Phase 2 item A1

## Context
Requirements:
- Android 10+ (API 29), Play-compliant.
- Must survive OEM battery managers and reboots.
- Private data encrypted at rest.
- One developer (Claude) plus one tester (Splash).
- Minimum spend.

Every choice should be a mainstream Google-supported path with good test tooling.

## Decision
| Concern | Choice | Why / notes |
|---|---|---|
| Language | Kotlin (JVM 17 target) | Default for Android; coroutines for the task engine |
| UI | Jetpack Compose + Material 3 | Default for new apps; good TalkBack and large-text support (NFR) |
| DI | Hilt | Google-supported; fits WorkManager (`HiltWorker`) and testing |
| Background work | WorkManager for deferrable work; `AlarmManager.setExactAndAllowWhileIdle` for reminders | WorkManager alone is not punctual enough for F5 (fire within 2 min). Exact alarms need `SCHEDULE_EXACT_ALARM` on Android 12+ and a user grant on 14+. The fallback is an inexact alarm plus a "needs you" receipt |
| Local DB | Room on SQLCipher (`net.zetetic:sqlcipher-android`) | Encrypted at rest. The DB passphrase is random and wrapped by an Android Keystore AES key. Jetpack Security Crypto is deprecated, so it is not used |
| Small settings | DataStore (Preferences), no secrets in it | Secrets go in the encrypted DB or as Keystore-wrapped blobs |
| Networking | OkHttp + Retrofit + kotlinx.serialization | Mature, simple to mock; certificate pinning available for the backend |
| Auth | Credential Manager (Sign in with Google) + email one-time code | PRD F1; the backend verifies the Google ID token |
| Speech | Android `SpeechRecognizer`: on-device recogniser on API 31+, `EXTRA_PREFER_OFFLINE` on 29–30 | Free tier stays on-device (fixed commands) |
| Billing (Phase 3) | Play Billing Library | F11 |
| Integrity (Phase 3) | Play Integrity API, verdict decoded server-side | F11 trial abuse |
| Crash reporting | Firebase Crashlytics, no custom keys with user content | Free; disclosed in the privacy centre |
| Ads (Phase 3) | Decided later (open PRD question); must support native units and a UMP-style consent flow | F12 |
| Testing | JUnit4, kotlinx-coroutines-test, Turbine, Robolectric, Compose UI test, UI Automator for device runs | Engine and policy fully unit-tested on the JVM |
| Static checks | Android lint, ktlint, detekt; gitleaks (pre-commit + CI) | No secrets is a Gate 2 criterion |
| Build | Gradle Kotlin DSL, version catalog, flavours `dev` / `staging` / `prod` | Backend URL per flavour; no keys in the APK |
| SDK levels | minSdk 29; targetSdk = the current Play requirement; compileSdk = latest stable | Moving to targetSdk 37 (Android 17) triggers the contacts rule; see the architecture doc |

## Consequences
- SQLCipher adds about 3–4 MB per ABI. Accepted for private data at rest.
- Exact-alarm permission handling becomes part of onboarding (F2).
- The M0 scaffold carries only the minimum (Compose, Material 3, JUnit). Each
  dependency above is added in M3 together with the code that uses it.

## Alternatives rejected
- **Koin:** lighter, but less standard and has weaker WorkManager and test integration.
- **Flutter or React Native:** the notification listener, assistant role, alarms and
  executors are all native APIs. A cross-platform layer would add bridges, not save
  work.
- **Unencrypted Room plus file encryption:** the encryption boundary is too easy to get
  wrong.
