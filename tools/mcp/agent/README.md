# Analyst agent

Answers questions about **one organization's** portfolio, models and operations by calling the read-only MCP tools, under hard
limits, and checks that every number in its answer came from a tool result it cites. It is separate from the in-product
`/assistant/chat` and changes nothing under `backend/`.

```bash
export ANTHROPIC_API_KEY=...            # or `ant auth login`; the SDK reads it, this code never sees it
export AGENT_TENANT_ID=7                # the organization, fixed for the run
export RISK_ENGINE_SESSION=...          # ad_session cookie of an administrator of that organization
export TENANT_OPS_DATABASE_URL=postgres://arthadhruva_app:...@localhost/...
node dist/cli.js ask "Which portfolio runs are stuck, and why?" [--operator] [--allow-run-start] [--json]
```

Exit status: 0 verified, 2 could not verify, 3 stopped by a limit, 4 refused by the model, 1 error.

## What happens to a question

1. A session is made with the tenant and the tools the servers offer. Only tools their server marks read-only are offered;
   `portfolio_run_start` only with `--allow-run-start`. The set is frozen.
2. The model (Claude Opus 5.5 by default, adaptive thinking, effort `high`, refusal fallback on) is asked in a tool-use loop.
3. Every call it proposes is checked by the session first. An unoffered tool, a run start without the flag, or any argument naming a
   tenant other than the session's is **refused without reaching a server**. The refusal is shown to the model as an error.
4. Every result is stored whole under an id (`r1`, `r2`, ...). The model is shown a fenced, **neutralised** copy: strings that read
   like instructions are replaced by a marker saying they were withheld.
5. The answer must cite the id after every figure. The verifier checks each figure against the stored raw result, by unit and to the
   precision written. A failure gets **one correction**; after that the answer is withheld as "could not verify", listing the figures.
6. Limits on model calls, tokens, seconds and estimated dollars stop the question at the first one reached, and say which. What the
   tools returned so far is reported.
7. A local trace (steps, tool calls, outcomes, tokens, cost, verdict; no credentials, no result bodies) is written to
   `.agent-traces/`.

## The citation contract

Figures are numbers with a unit, a decimal or a count. A citation `[r3]` (or `[r3, r5]`) follows the figure with at most two words
between: `400 loans [r3]`, `0.31% of exposure [r1]`. It covers only the number just before it, so write `70 [r1] of 400 loans [r1]`.
Not figures: identifiers (`pd_24m`, `L-104`), hyphenated labels (`12-month`), dates, a year alone, list markers. Units: `%` against a
fraction or a percentage; `bps` against a fraction, a percentage or bps; `$`, `k`, `m`, `bn`, `million` scaled; to the precision
written (3.4% matches 0.03412, not 3.46%). Full rules: the header of `src/verifier.ts`.

**What it does not check:** claims in words. "The VM is asleep" is not verified, only the numbers are. The contract is that the figures
are real, not that the prose is true; an opt-in judge for prose is not built.

## Limits and cost

| Variable | Default | |
|---|---|---|
| `AGENT_MAX_STEPS` | 12 | model calls |
| `AGENT_MAX_TOKENS` | 300000 | input plus output, summed |
| `AGENT_MAX_SECONDS` | 180 | wall clock |
| `AGENT_MAX_COST_USD` | 2 | estimated, from token counts and prices; `none` to rely on the token limit |
| `AGENT_MODEL` / `AGENT_EFFORT` | `claude-opus-5-5` / `high` | |
| `AGENT_REFUSAL_FALLBACK` | `on` | a safety decline is re-run on a fallback inside the same call |

Cost is an **estimate**. The SDK reports tokens, not dollars, so it is right only if the prices are: Opus 5.5 is built in ($4 in,
$20 out per million tokens, cache reads $0.20); any other model needs `AGENT_PRICE_INPUT_PER_MTOK` and `AGENT_PRICE_OUTPUT_PER_MTOK`
or `AGENT_MAX_COST_USD=none`. The token limit needs no prices and is the dependable backstop.

## Limits of the defence

The injection defence is layered (fencing, neutralising, the system prompt, the frozen read-only tool set, the verifier) so that no
single layer is trusted. CI proves the layers that do not depend on the model with a scripted *compromised* model: 30 cases where the
model obeys the planted text and leaks a figure. It cannot show that a real model resists; that is the live injection run. With
`--allow-run-start` a planted instruction CAN start a run, which is why the flag is off unless you ask for it. See
[../ARCHITECTURE.md](../ARCHITECTURE.md).

## Tests

`npm test` from `tools/mcp`: the verifier rules (25), the session (14), the loop and every limit (32), the 30 injection cases, 12 replay
fixtures (one per verifier rule plus budget, permission and injection cases), the model adapter against the documented request shape,
the host against a real in-process server and a real stdio server, and the eval harness. No network, no model, no cost.
