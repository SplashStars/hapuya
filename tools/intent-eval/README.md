# intent-eval (Phase 2, item A2)

Runs the 200-phrase reminder set (`05_Testing/testsets/reminder-phrases-v1.csv`)
through a candidate AI provider. Each phrase is scored on intent, time,
recurrence, duration and act/clarify/refuse against the PRD F5 bar (95%). The
tool also records latency and cost per phrase.

The parts that don't call a provider live in `:tools:intent-core`: the phrase and
intent types, the prompts and schemas, `Resolver` and the scorer. The on-phone spike
(`:spikes:onphone-ai`) uses the same module, so laptop and phone results compare like
for like.

| Provider | How it calls | Credentials (environment only) |
|---|---|---|
| `oracle` | Returns the expected answers; checks the pipeline end to end | none |
| `claude` | Official Anthropic Java SDK with structured outputs (`intent-schema.json`) | `ANTHROPIC_API_KEY` |
| `alt` | Any OpenAI-style `/chat/completions` endpoint, in JSON mode | `EVAL_ALT_BASE_URL`, `EVAL_ALT_API_KEY`, `EVAL_ALT_MODEL`, optional `EVAL_ALT_PRICE_IN` / `EVAL_ALT_PRICE_OUT` (USD per million tokens), `EVAL_ALT_REASONING_EFFORT` (e.g. `none` turns thinking off) and `EVAL_ALT_TIMEOUT_S` (default 60; raise it for local CPU models) |

The system prompt (`src/main/resources/intent-system-prompt.md`) holds the
product's date and time defaults. The backend's `/v1/ai/intent` will reuse it
and the schema.

## Run
```bash
./gradlew :tools:intent-eval:run --args="--provider oracle"
```
```bash
./gradlew :tools:intent-eval:run --args="--provider claude --model claude-haiku-5-5 --effort low"
```
```bash
./gradlew :tools:intent-eval:run --args="--provider alt"
```

**Results:** each run writes `A2-<provider>-<date>.md` (summary and failures) and
`.csv` (one row per phrase) to `../05_Testing/results/`.

**Spend controls:**
- `--max-usd` (default 1.00) stops new calls once that budget is spent.
- A rejected key (401) stops the run after the first call.

**Other options:** `--limit`, `--ids`, `--parallel`. Run without arguments for help.

## Tests
```bash
./gradlew :tools:intent-eval:test
```
The unit tests cover the scorer, the CSV parsing and the JSON parsing. One test
runs the oracle over the real phrase set; it skips itself when `05_Testing` isn't
next to the repo, as in CI.

## Findings
- **Free local baseline, 5 Nov 2026:** Gemma 4 E2B through Ollama on the build
  laptop (CPU only) scored 40.5% core with thinking off (p95 6.2 s) and 50.5% with
  thinking on (p95 64 s). The bar is 95% and under 3 s. Most errors are date maths
  (weekday off by one, missing 09:00 default). Takeaway for M3: the model returns
  the time expression and Kotlin code resolves the date. Full write-up in
  `05_Testing/A2-runbook.md`, outside this repo.
- **Split mode, 5 Nov 2026:** `--mode split` has the model return the time words
  it heard (codes in `intent-split-prompt.md`) and `Resolver.kt` compute the date
  and apply policy. Same Gemma model: 40.5% -> 73.5% core, same speed. The fixes
  were tuned on this phrase set, so confirm on a fresh set. `ResolverTest` checks
  the resolver alone against 120 rows with ideal codes.
- **Compact mode, 7 Nov 2026:** `--mode compact` is split mode with a one-line
  labelled answer (`intent-compact-format.md`) instead of JSON. Results:
  - holdout, laptop: 68.8% at 2.3 s, against 78.1% at 6.3 s for split;
  - on the phone it saved little time (see A6), so split JSON stays the default;
  - the `claude` provider doesn't support compact mode.
- **Short mode, 7 Nov 2026:** `--mode short` is split mode with about 650 tokens of
  instructions (`intent-short-prompt.md`) instead of about 1,700. Results:
  - holdout, laptop: first draft (code names only, no worked examples) 31.3%; with one
    worked example per code, 68.8% at 3.2 s. That draft was revised once after seeing
    holdout misses, so the 68.8% is a bit flattering;
  - phone: 60.9% and 3.6–4.1 s per command while cool (see A6);
  - the `claude` provider doesn't support short mode (no schema file).
- **Short-compact mode, 7 Nov 2026:** `--mode short-compact` combines the short
  instructions with the one-line answer. Holdout, laptop: 68.8% at 3.0 s. Phone: 64.1%
  at p50 1.9 s, the first setup to meet the 3 s target (see A6).
- **Accuracy work, 8 Nov 2026:**
  - What changed:
    - `Resolver.tidy` fixes model slips that have only one reading;
    - the line parser accepts codes without labels;
    - the short instructions were tightened, tuned only on `reminder-phrases-v1.csv`
      and holdout 1.
  - Short-compact results:
    - 200 rows: 65.0% → 81.0%;
    - holdout 1: 68.8% → 85.9%;
    - the new, unseen holdout 2: 76.6% → 87.5%.
- **Word check, 8 Nov 2026:** `CrossCheck.kt` corrects the model's codes when the
  user's words allow only one reading: day names, "next", holidays, "in N …",
  am/pm times, ranges, repeat words, event and timer lengths, and place reminders.
  - It is on by default. `--check off` turns it off; the result label then ends
    in `-nocheck`.
  - On the unseen holdout 3, short-compact: laptop 85.9% → 93.8%, phone 64.1% → 89.1%.
    Holdout 3 was then used for fixes, so the next honest score needs a holdout 4.
  - Rules only, no model: `--provider rules` (`CrossCheck.fromWords`). Fresh holdout 5
    (9 Nov): rules only 93.8%; laptop model + check 96.9%; phone model + check 92.2%.
  - To re-score saved on-phone answers (the A6 CSV keeps each raw answer) with and
    without the check, without running the model again:

    ```bash
    ./gradlew :tools:intent-eval:run --args="--rescore ../05_Testing/results/A6/<file>.csv --mode short-compact --dataset ../05_Testing/testsets/reminder-phrases-holdout-v3.csv"
    ```
