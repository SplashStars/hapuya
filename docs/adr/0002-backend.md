# ADR 0002 — Backend host and shape

Status: **Accepted** (Splash, 25 Oct 2026) · drafted 24 Oct 2026 · Phase 2 item A3

## What the backend must do
1. **Auth**: verify the Google ID token, or run the email one-time code; issue
   short-lived session tokens; delete an account and its data (F1).
2. **Trial and entitlement**: start a trial at first sign-in (account-keyed plus a Play
   Integrity device check); track the 30-day window and the 30-tasks-a-day cap; verify
   Play subscriptions and receive Real-time Developer Notifications (F11).
3. **AI proxy**: hold the provider keys; enforce consent, cap and size limits; route to
   a small model first; log cost and latency **without message content** (F7, cost
   NFR).
4. **Crash intake**: handled by Crashlytics, so it stays off our backend.
5. Consent records and export support (F10).

Volume for beta: under 1,000 users, about 30 AI calls a day each at most. Must cost
roughly $0 at idle and scale linearly.

## Options
| | Cloudflare Workers + D1 (+ KV) | Firebase (Auth + Functions + Firestore) | Supabase (Postgres + Edge Functions) |
|---|---|---|---|
| Idle cost | Free tier, no pausing | Functions need the pay-as-you-go Blaze plan (card on file); small usage stays near $0 | Free projects pause after about a week without activity, which is bad for a beta |
| Latency to AI provider | Runs at the edge; fast cold start | Cold starts on Functions | Edge Functions are fine |
| Secrets | Worker secrets (encrypted) | Secret Manager | Project secrets |
| Data model | SQLite (D1): enough for accounts, trials, entitlements, usage counters, consent log | Document DB | Full Postgres |
| Lock-in | Low: plain TypeScript HTTP handlers | Medium | Low–medium |
| Fit with Google pieces (ID token, Play Integrity, Play Developer API, RTDN via Pub/Sub push) | All plain HTTPS + JWT; works | Native | Works |

## Decision
**Cloudflare Workers (TypeScript) + D1**, with KV for rate-limit counters.
Crashlytics handles crashes. Firebase Auth is **not** used: the Worker verifies
Google ID tokens directly against Google's public keys, and runs email one-time codes
through a transactional email provider's free tier (chosen in M3).

Why:
- About $0 at idle with no pausing.
- No card is needed for beta volume.
- Small, auditable code.
- The AI proxy sits close to users.
- Easy to move later, because it is plain HTTP.

**Free-tier limits have changed in the past. Confirm the current quotas when the
account is created** and record them in 07_Admin.

## API surface (v0, implemented in M3 only as far as Gate 2 needs)
| Endpoint | Purpose | Gate 2? |
|---|---|---|
| `POST /v1/auth/google` | ID token → session | Yes (dev bypass allowed in `dev` flavour only) |
| `POST /v1/auth/email/start`, `/verify` | One-time code fallback | No |
| `GET /v1/me` | Tier, trial days left, today's AI usage | Yes (stub) |
| `POST /v1/ai/intent` | Text → structured intent (JSON schema enforced) | **Yes** |
| `POST /v1/ai/draft` | Reply draft | No (stretch) |
| `POST /v1/consent` | Record consent given or revoked | Yes |
| `DELETE /v1/me` | Account deletion | No |
| `POST /v1/play/rtdn` | Play subscription notifications | No |

## Rules the proxy enforces
- **Content handling**: no request or response bodies in logs; log only account hash,
  model, tokens, latency, cost and outcome.
- **Excluded data**: rejects payloads flagged by the app's exclusion filter. The
  server re-checks for OTP-like patterns as defence in depth.
- **Consent and caps**: no consent record means 403. Trial accounts over 30
  tasks a day get 429 with a plain message.
- **Output**: the model must return the intent JSON schema. Invalid output is a
  failure, never a guess.

## Consequences
- Splash creates a Cloudflare account (free) and, in Phase 3, a Google Cloud project
  for Play Integrity and the Play Developer API.
- The backend repo lives in `04_Build/backend/` (same repo, separate CI job), with
  Wrangler config and **no** secrets committed (`.dev.vars` is gitignored).
