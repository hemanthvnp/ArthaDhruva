# deploy-ops MCP server

The deployed stack, read-only, plus one action. It is the fifth server of `openspec/changes/mcp-servers-and-analyst-agent`
(task group 9).

The VM that runs ArthaDhruva is **deliberately switched off when idle** (`deploy/azure/idle-stop.sh`). So this server treats
"asleep" as a normal state, not a failure, and never reports a deallocated VM as an unhealthy deployment.

## Tools

| Tool | Does | Read-only |
|---|---|---|
| `health` | `asleep`, `starting`, `going-to-sleep`, `healthy`, `degraded`, `unhealthy` or `unknown`, with a hint and notes on any source that could not answer. | yes |
| `metric_query` | The current value of one **named** metric, with the time it was evaluated. | yes |
| `alert_state` | Alerts firing now (and pending, on request): name, labels, severity, since when. | yes |
| `wake` | Starts the VM if it is asleep, by calling the public wake URL. Idempotent. Costs money while the VM runs: call it only when asked. | **no** (it starts a VM; not destructive) |

## Configuration (environment only; everything is optional)

| Variable | |
|---|---|
| `DEPLOY_OPS_PROMETHEUS_URL` | default `http://localhost:9090` |
| `DEPLOY_OPS_APP_URL` | public URL of the application, to see whether it answers |
| `DEPLOY_OPS_WAKE_URL` | public wake function URL (`deploy/azure/wake`); needed by `wake`, and a second source of the power state |
| `DEPLOY_OPS_AZ_RESOURCE_GROUP`, `DEPLOY_OPS_AZ_VM` | together, to read the VM's power state with `az` |
| `DEPLOY_OPS_TIMEOUT_MS` (3000), `DEPLOY_OPS_RETRIES` (2, at most 3) | per-attempt timeout and retries |

With no URL set, `health` says there is nothing to assess. There is no secret in this configuration: the wake function needs
none (its own managed identity is the only thing that can start the VM), and `az` uses whatever sign-in the machine already has.

## How it is kept safe

- **Azure: an allowlist of one.** The server runs exactly one `az` command line (`vm get-instance-view` for the configured VM).
  Anything else, whether it changes things (`vm deallocate`, `vm start`, `group delete`, `deployment ...`) or merely is not listed
  (`vm list`), is `Forbidden` **before a process is spawned**, and the message says which kind it was. The resource group and VM
  name are checked against a strict pattern at start-up so they cannot carry an extra option. The repo rule that nothing here
  touches production infrastructure is enforced in code, not by caller discipline.
- **Prometheus: named queries only.** `metric_query` takes a metric *name* from a catalog (`src/prometheus.ts`); the PromQL is a
  constant there, the same expressions the alert rules in `observability/alerts.yml` use. Nothing a caller sends reaches a query.
- **Wake: one URL, two paths.** `POST <wake-url>/status` (a read) and `POST <wake-url>/wake`, with the `X-Wake: 1` header the
  function requires. `wake` reads the state first and asks for a start only from `deallocated` or `stopped`; from `running`,
  `starting` or a VM going down it reports and calls nothing. It is the only method marked mutating, and the server's registration
  check refuses a read-only tool that uses it.
- **Bounded time.** Every network call has a short timeout and at most `DEPLOY_OPS_RETRIES` retries with doubling backoff, so the
  worst case is known: with the defaults, 3 attempts of 3 s plus 0.6 s of waiting, 9.6 s. A 5xx, a timeout or no answer is retried; a
  4xx is the answer.
- **Asleep is not an error.** When the VM is asleep, starting or stopping, `health` does not ask the application or Prometheus
  (they cannot answer) and returns a normal state with a hint.

## Limits

- `health` can only tell "asleep" from "broken" if it has a power-state source (`az`, or the wake URL). Without one, an
  unanswering application is reported as `unknown`, which is normal, with a hint to configure one.
- After a `wake`, the application usually answers about two minutes later; poll `health`.
- Prometheus lives on the VM, so it is unreachable exactly when the VM is asleep. `metric_query` and `alert_state` then return
  `UpstreamDown` after the bounded retries; `health` does not call them in that case.
- This server never stops or deallocates the VM. The VM stops itself when idle.

## Tests

`npm test` from `tools/mcp`: `test/deploy-ops.test.ts`, 26 protocol tests over the real MCP transport with a fake network and a
fake `az` runner (no network, no Azure, no Docker), including a network that never answers, so the retry bounds are measured.
