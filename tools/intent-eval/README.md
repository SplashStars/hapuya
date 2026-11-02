# intent-eval (Phase 2, item A2)

Runs the 200-phrase reminder set (`05_Testing/testsets/reminder-phrases-v1.csv`)
through a candidate AI provider. Each phrase is scored on intent, time,
recurrence, duration and act/clarify/refuse against the PRD F5 bar (95%). The
tool also records latency and cost per phrase.

| Provider | How it calls | Credentials (environment only) |
|---|---|---|
| `oracle` | Returns the expected answers; checks the pipeline end to end | none |
| `claude` | Official Anthropic Java SDK with structured outputs (`intent-schema.json`) | `ANTHROPIC_API_KEY` |
| `alt` | Any OpenAI-style `/chat/completions` endpoint, in JSON mode | `EVAL_ALT_BASE_URL`, `EVAL_ALT_API_KEY`, `EVAL_ALT_MODEL`, optional `EVAL_ALT_PRICE_IN` / `EVAL_ALT_PRICE_OUT` (USD per million tokens) |

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
