# ADR 0003 — Bring your own AI

Status: **Accepted** (Splash, 6 Nov 2026). Tier: **Free with ads, or ad-free for $9.99 a year** (Splash, 6 Nov 2026). Build timing follows PHASE2_PLAN: the plug-in slot lands in M3, and
the user-facing feature (PRD F13) lands after Gate 2.

**Scoped by ADR 0004 (6 Nov 2026):** version 1 ships only the on-phone options (built-in
phone AI and a downloaded model), set up in one tap with nothing technical. Cloud keys,
home servers and AppFunctions move to version 2. The safety rules, quality check and
fallback below apply to version 1 unchanged.

## The ask
Hapuya must work offline. With the user's consent, it can also connect to the user's
own AI, setting up as much as possible by itself ("as hands-free as possible").

## What "the user's own AI" can be
| Kind | Example | Hands-free set-up | Offline? |
|---|---|---|---|
| **On-phone system AI** | Gemini Nano through Android AICore / ML Kit GenAI | Fully automatic: Hapuya checks whether the phone supports it, then downloads the model on Wi-Fi while charging | Yes |
| **Downloaded model** | A small open model run inside Hapuya (LiteRT / MediaPipe LLM) | Automatic after one consent: a background download (about 1–3 GB) | Yes |
| **Home or office server** | Ollama or LM Studio on the user's PC | Hapuya looks for it on the local network after consent. If it isn't found, the user scans one QR code shown by the PC | Home network only |
| **Cloud account** | The user's own OpenAI / Gemini / Claude key | The user signs in with the provider where the provider offers that; otherwise they paste a key or scan it as a QR code (one step) | No |
| **AI app installed on the phone** | ChatGPT, Gemini or Claude app | **Not possible today**: Android has no general way for one app to send another a request and read the answer. Hapuya will use AppFunctions when an AI app exposes one. Hapuya will **not** use Accessibility to drive other apps (banned on Play, ADR scope) | — |

## Decision
1. **One AI slot, many plug-ins.** The task engine talks to an `AiProvider`
   interface: Hapuya cloud (the proxy, ADR 0002), on-phone system AI, downloaded
   model, local server, user's cloud key, and later AppFunctions. Adding one is a new
   plug-in, not an engine change.
2. **The model only reports; code decides.** Every provider runs in split mode: the
   model returns intent and time words, while `Resolver` and the policy checks in
   code compute dates, permissions and refusals. A weak or untrusted model can lower
   accuracy, but it can never authorise an action. The A2 finding behind this: a 2B
   local model scored 40.5% on its own and 73.5–78.1% in split mode.
3. **Hands-free set-up ("Connect your AI").**
   - On first run, and again when the phone changes, Hapuya quietly checks what is
     available: whether the on-phone AI is supported, enough storage for a download,
     a known local server (only after the network consent below).
   - It then offers the best option in one card: "Use your phone's built-in AI? It
     works offline. [Use it] [Not now]".
   - Downloads wait for Wi-Fi and charging, and resume automatically.
   - Each new connection runs an **automatic quality check**: about 20 fixed test
     phrases, under a minute, with no user action. The result sets a badge:
     - **Full**: core accuracy at or above 95%.
     - **Basic**: lower accuracy, so Hapuya asks one more confirmation before acting.
     - **Not usable**: below 60%, or too slow. Hapuya does not connect it.
   - Every task picks a provider automatically: the user's preferred one first, then
     a fallback.
     - Offline, the fallback is on-phone AI, then the rule-based engine.
     - Online, it can fall back to Hapuya cloud if the user's tier and consent
       allow it.
     - The receipt always names the provider that was used.
   - If a provider fails, such as an expired key or a server that's off, Hapuya
     switches to the fallback and leaves one "Needs you" note. It doesn't stop the
     task to ask.
   - Voice: "Hey Hapuya, use my home AI" starts the same flow. Consent is read out
     and shown on screen together.
4. **Consent, once per connection, revocable.**
   - Before anything is sent off the phone, a screen says what goes where. For
     example: "Your commands and the messages you ask about go to OpenAI under your
     own account."
   - Local network discovery has its own consent, plus Android's local-network
     permission where the OS requires it.
   - The Privacy Centre lists each connection with a switch, the last-used time and a
     "Forget" button.
   - Turning a connection off stops requests to it before the next task.
5. **What never changes, whichever AI is connected.**
   - OTPs, banking, passwords and health data are filtered out on the phone before
     any provider call, including the user's own.
   - The prompt-injection rule (notification, email and web text are data) applies
     to all providers.
   - Confirm-before-send/delete/money applies to all providers.
6. **Secrets.**
   - The user's keys and server tokens live only on the phone, encrypted with an
     Android Keystore key. They are not backed up to our server, never logged, and
     excluded from crash reports and exports.
   - Hapuya's own provider keys stay server-only (ADR 0002).
   - Local-server traffic must use HTTPS, or a pinned token over the local network
     only, never to a public address. Plain HTTP is allowed only to a private LAN
     address, with a warning.
7. **Play compliance.**
   - Hapuya calls APIs and on-device features that the user has rights to. It never
     scrapes or remote-controls another app.
   - The Data safety form must list "shared with a third party chosen by the user"
     for the cloud-key option.

## Tier (decided 6 Nov 2026): Free with ads, or $9.99 a year without ads
- New plan, **Own AI (no ads)**: $9.99 a year (US base price; Play sets local
  prices). It is a Play Billing annual subscription that removes ads for users who
  bring their own AI. It does not include Hapuya's own AI.
- Paid (Hapuya's AI, no ads) stays priced by the pricing tests, and should sit above
  $9.99 a year because it carries our AI cost.
- Free users may connect their own AI and get the AI features through it, with ads
  as today. It costs us nothing to run, because the user's own AI does the work.
- Free still never uses Hapuya's own cloud AI. That stays trial and paid only, so
  Gate 1's "Free = no AI" now reads "Free = no Hapuya AI".
- What Paid still adds: Hapuya's AI with no set-up and no keys, the Full-quality
  route, and no ads.
- Ads rules don't change: native units in the inbox and briefing only, and no
  private data reaches ad networks. Nothing the user's AI reads or writes is passed
  to an ad network.
- To watch in beta: whether free BYO-AI pulls people away from Paid. Track the share
  of Free users who connect an AI and their conversion to Paid.

## Still open (Splash)
- **Which cloud providers to support first.** The suggestion is the two in the A2
  test plus OpenAI.
- **Minimum quality for "Full".** The suggestion is the same 95% core bar as F5.

## Build order
1. **M3 (now in plan):** `AiProvider` interface plus Hapuya-cloud and local-server
   plug-ins (the local server is already used by the A2 eval tool). No set-up UI.
2. **After Gate 2:**
   - user cloud key;
   - on-phone system AI (only on phones that support it);
   - automatic quality check and badges;
   - Privacy Centre entries.
3. **Later:** downloaded models; local-network discovery and QR pairing;
   AppFunctions for installed AI apps once any expose one.

## Risks
- **Quality:** small or offline models are below the bar. The badge and the extra
  confirmation keep this visible and safe, not hidden.
- **Support load:** users' own keys expire and their servers go offline. Automatic
  fallback and a single "Needs you" note limit the damage.
- **Device spread:** on-phone AI exists only on recent flagships. The S22+ test
  phone probably lacks it, so we need one supported phone (or Firebase Test Lab)
  before shipping that plug-in.
- **Platform change:** Android's local-network permission and the AppFunctions APIs
  are new and still changing. Recheck both when the work starts.
