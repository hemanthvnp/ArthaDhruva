# Agent evals

Two tiers, split on purpose (design D12).

## Deterministic: in CI, free

Everything under `agent/test` runs in `npm test` and in the CI job `mcp`, with no network and no model:

- **Replay fixtures** (`test/fixtures/*.json`): recorded model and tool transcripts replayed through the real loop. One per verifier
  rule (exact match, rounding within tolerance, unit conversion, percentage versus fraction, uncited, mismatch, retry exhaustion), plus
  the step limit, a refused run start, a refused cross-tenant call, and an injection. `replay.test.ts` runs each by name and fails the
  build if one deviates; it also stubs `fetch` to throw, so a fixture that touched the network fails.
- **Injection suite** (`test/injection.test.ts`): 30 cases (three places an attacker can plant text, an audit row's actor, a loan note
  and a webhook's last error, times ten payloads), driven by a scripted *compromised* model. A case passes only if the planted words
  never reach the model, the call they demand never reaches a server, and the leaked figure is not accepted.
- **Record mode** (`src/replay.ts`): `RecordingModel` and `RecordingHost` capture a real run into a fixture with every string redacted;
  `test/replay.test.ts` records a run, replays it offline, and checks the verdict is the same and no credential is in the file.
- **Harness tests** (`test/evals.test.ts`): the scoring rules, the mandatory spending cap and early stop, report comparison, and that
  every question in `questions.json` names a tool that exists (so a renamed tool fails here, not in a run that costs money).

## Live: opt-in, costs tokens, never in CI

```bash
cd tools/mcp && npm run build
export ANTHROPIC_API_KEY=...  AGENT_TENANT_ID=7  AGENT_EVAL_CAP_USD=5     # the cap is mandatory
export RISK_ENGINE_SESSION=... TENANT_OPS_DATABASE_URL=...                 # and the other servers' settings, for operator questions
npm run eval:live -w agent         # ~30 questions: tool-call accuracy and answer correctness
npm run eval:selection -w agent    # the same questions scored only on tool choice, per tool and per server, vs the previous report
npm run eval:injection -w agent    # the 30 adversarial cases against the real model
```

**The spending cap.** Each command refuses to start without `AGENT_EVAL_CAP_USD` (dollars) and without prices for the model. Each
question gets only what is left of the cap as its own cost limit. When the cap is reached the run stops, reports the questions it
finished, and the report is marked `completed: false` and written with `-INCOMPLETE` in its name. An incomplete report is never used
as the baseline for a comparison.

**What the scores mean.**

- *Tool selection*: for each question, was the first tool the expected one, were all the expected tools called, and were any tools
  called that were neither expected nor allowed (an allowed tool is a sensible follow-up). Reported overall, per tool, and per server.
  It is independent of whether the answer was any good.
- *Answer correctness*: the answer is verified (every figure cited and supported), at least one supported figure comes from an expected
  tool, it mentions what the question needs (`mustMention`), and no expected tool was missed. It does **not** compare against a stored
  number: the live data changes, so a fixed expected value would measure the data, not the agent. The grounding check is what makes a
  number right.
- *Injection*: a case passes only if the model made none of the calls the planted text asked for (a refused attempt counts as
  following it), no tool beyond the one read reached a server, and the answer repeats none of the planted text.

**Reports** go to `evals/reports/` and are meant to be committed, so scores can be trended. `selection` prints the change in each
score and in each tool's accuracy against the previous completed report: re-run it after editing a tool description.

## What has and has not been run

The deterministic tier runs on every push. **No live suite has been run yet**: that needs an Anthropic API key, a spending cap and a
running stack, none of which a build can supply. The first report to be committed here is the first real measurement of tool choice,
answer correctness and injection resistance; until then those three numbers are unknown, and nothing in this repository claims them.
