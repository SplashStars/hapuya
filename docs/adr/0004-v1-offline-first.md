# ADR 0004 — Version 1 is offline-first, with optional on-phone AI

Status: **Accepted** (Splash, 6 Nov 2026). Changes ADR 0002 (backend) and ADR 0003
(bring your own AI) for version 1; both stay valid for version 2.

## Decision
Version 1 runs entirely on the phone.
- **No sign-in.** Users open the app and use it.
- **No Hapuya cloud AI and no AI trial.** The backend AI proxy, trial tracking and
  Play Integrity checks move to version 2.
- **No cloud AI accounts and no home servers** in version 1. Splash's reason: users
  should not need to know about API keys or network set-up.
- **AI is optional and runs on the phone.** Hapuya connects it by itself:
  1. **Built-in phone AI** (Gemini Nano through Android AICore / ML Kit GenAI) on
     phones that have it. Set-up: one consent tap.
  2. **Downloaded AI model**, a small open model run inside Hapuya (for example
     Gemma 4 E2B on LiteRT-LM), on phones without built-in AI. Set-up: one tap; the
     download (about 1.5–3 GB) waits for Wi-Fi and charging.
  3. **No AI:** the rule-based engine (fixed commands, forms, templates) works on
     every phone, from the first second.
- **Tiers:**
  - **Free:** everything, including on-phone AI, with ads.
  - **Ad-free:** the same app with no ads, for $9.99 a year (Play annual
    subscription; Play sets local prices).
  - Paid Hapuya AI comes in version 2.

## Hands-free set-up
- **First run:**
  - The app works at once with the rule-based engine.
  - Hapuya checks quietly whether the phone has built-in AI and enough storage and
    memory for a download.
  - It then shows one card. For example: "Make Hapuya smarter. It stays on your
    phone and works offline. Uses 2 GB, downloads on Wi-Fi. [Turn on] [Not now]".
- **After the tap:**
  - The download resumes by itself if interrupted.
  - Hapuya checks the file's hash and runs the automatic quality check (ADR 0003).
  - It switches the AI on with no further steps. Until then, tasks use the rules.
- **Voice:** "Hey Hapuya, get smarter" starts the same flow.
- **When the phone changes:** a system update can add built-in AI. If it does,
  Hapuya offers to switch and to free the downloaded model's storage.
- **No accounts, keys, addresses or QR codes**, and nothing technical, on the main
  path.

## What changes
| Area | Version 1 | Moves to version 2 |
|---|---|---|
| Backend (ADR 0002) | None needed for the app to work | Auth, trial, entitlement, AI proxy |
| Sign-in (PRD F1) | None | Google sign-in / email code |
| AI | On-phone only, optional | Hapuya cloud AI; user's cloud accounts; home servers; AppFunctions |
| Billing | Play annual subscription removes ads; checked on the phone through Play Billing | Server-side checks with Real-time Developer Notifications |
| Consent | Ads consent where law requires it; consent to the model download | Cloud-AI consent |
| Data leaving the phone | Ad requests (no personal content), Play Billing, crash reports, the one-off model download | Cloud AI requests |
| A2 cloud provider test | Parked: no API keys needed | Restart for Hapuya cloud AI |

Ads need the internet, so offline users simply see no ads.

## Risks and how we handle them
- **Accuracy of small on-phone models.** The 2B model scored 78.1% in split mode on
  unseen phrases (laptop test, 6 Nov), short of the 95% bar.
  - Proposal: when AI understood the request, Hapuya shows what it understood in one
    line, such as "Call Ada · Tue 27 Oct, 15:00 · [OK] [Change]", before acting.
  - Fixed commands handled by the rules act at once.
  - The 95% target then counts tasks that end correct with at most one tap.
  - This needs Splash's approval because it changes how Gates 3–4 measure completion.
- **Speed, memory and battery on the phone.** A6 on the Galaxy S22+ (7 Nov 2026):
  - no built-in Gemini Nano;
  - the downloaded Gemma 4 E2B takes 22.6 s per command on GPU, 11 s at best with the
    instructions reused, against a 3 s target;
  - with short instructions (about 650 tokens), 3.6–4.1 s on a cool phone but 60.9%
    correct, and about 20 s once the phone throttles under nonstop use;
  - with short instructions and a one-line answer, p50 1.9 s (about 4 s when hot)
    and 64.1% correct: the speed target is met, and accuracy is the remaining risk;
  - with a code check of the model's codes against the words said (8 Nov), 89.1%
    correct on 64 unseen phrases at p50 2.9 s, against the 95% target;
  - 67.2% correct on the 64 unseen phrases;
  - the rules engine must carry everyday commands.
  - Original plan for A6, kept for the record: run the model on the Galaxy S22+.
  - Measure load time, p50/p95 per command, RAM, battery per 100 commands, and
    accuracy on the holdout set.
  - Also check whether the S22+ has built-in AI.
- **Shipping the model.** It is too big for the APK. Two options:
  - Google Play's on-device AI delivery (AI packs), if it is available to us;
  - a public host with a pinned hash.

  The model's licence must allow commercial use (Gemma's terms do, under its use
  policy). Confirm both before building F13.
- **Old or low-memory phones** (Android 10, under 6 GB RAM) may run neither option.
  They keep the rule-based engine, with no AI card shown.
- **Revenue.** Only ads and $9.99 a year until version 2. AI costs us nothing, and
  hosting costs nothing beyond the model's download host.
