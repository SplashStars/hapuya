# intent-eval (Phase 2, item A2)

Runs the 200-phrase reminder set (`05_Testing/testsets/reminder-phrases-v1.csv`)
through a candidate AI provider. Each phrase is scored on intent, time,
recurrence, duration and act/clarify/refuse against the PRD F5 bar (95%). The
tool also records latency and cost per phrase.

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
