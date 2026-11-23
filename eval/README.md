# Golden-set evaluation

Deterministic evaluation of agent behaviour. The runner replays `golden.yaml`
against a **running** agent-hub instance, collects the SSE event stream for each
case and evaluates declarative assertions on the execution trajectory and the
final answer.

## Why trajectory assertions

A correct number reached by luck, or by bypassing the data tools, is still a
regression. The SSE stream already exposes `thinking`, `tool_calling`,
`tool_result`, `token` and `completed` events, so the runner can assert on:

- which tools were called, in what order, and how many times;
- which tools were **not** called;
- values extracted from the final answer;
- latency and answer size.

Exact SQL text is deliberately **not** asserted. The agent is expected to
self-heal from a rejected query (see `self_healing` cases), so pinning SQL would
penalise correct recovery.

## Prerequisites

The target instance must run with the demo SMS mode so the runner can log in:

```bash
AGENT_AUTH_SMS_MODE=demo
AGENT_MODEL_API_KEY=<your key>
```

Start agent-hub and its dependencies (see the repository root `docker-compose.yml`),
then:

```bash
python -m pip install -r eval/requirements.txt
python eval/eval_runner.py --url http://localhost:8082 --out eval-report.json
```

Useful flags:

```bash
--category probe          # only probe heartbeat cases
--case-id probe-zh        # one case (repeatable)
--timeout 120             # per-request timeout (default 120s; must exceed max_latency_ms)
--phone 13900139000       # demo-login phone
```

Each run uses a fresh conversation namespace so previous runs cannot leak
history into the next evaluation. A case that fails only because the HTTP
stream timed out is retried once in a new conversation; assertion failures are
never retried.

The process exits `0` when every case passes and `1` otherwise, so it can be
used as a CI gate once a baseline is established.

## CI credential preflight

The GitHub Actions job probes the model gateway with `AGENT_MODEL_API_KEY`
before booting the stack. A disabled/expired gateway package returns HTTP 401
or 403 and makes **every** trajectory case fail for reasons unrelated to the
agent, so in that situation the job records `status: "skipped"` in the report
and warns instead of reporting a false regression. Other HTTP statuses do not
trigger a skip.

To restore evaluation, either renew the `AGENT_MODEL_API_KEY` secret / the
LKEAP package, or configure a fallback:

- secret `AGENT_FALLBACK_API_KEY`
- variable `AGENT_FALLBACK_MODEL_NAME` (required to enable the fallback)
- optional variable `AGENT_FALLBACK_BASE_URL` (defaults to the primary gateway)

The preflight also probes the fallback; if it is reachable, the whole golden
set runs against it. The primary endpoint and model are configurable through
variables `AGENT_MODEL_BASE_URL` and `AGENT_MODEL_NAME`.

## Assertion reference

| Key | Meaning |
|---|---|
| `tools` | Exact ordered list of tool names. `[]` means "no tool calls". |
| `tools_any_of` | At least one listed tool must be called. |
| `tools_forbidden` | None of the listed tools may be called. |
| `min_tool_calls` / `max_tool_calls` | Bounds on the total number of tool calls. |
| `answer_contains` / `answer_not_contains` | Literal substrings of the final answer. |
| `answer_numeric` | An integer that must appear in the answer. |
| `answer_regex` | Regex the answer must match. |
| `max_latency_ms` / `max_answer_chars` | Per-case cost bounds; override `defaults`. |

Numeric ground truth comes from `sql/bestsellers_with_categories.csv`: 550 books
in total, 240 Fiction, 310 Non Fiction, 50 books per year.

## Report

The runner writes a JSON report containing per-case pass/fail, failed assertions,
the normalised tool trajectory, answer text, latency and event counts. CI
uploads it as an artifact so trends can be compared across runs.

## Scope

This is the P0 layer (L2 trajectory evaluation) tracked in issue #27. LLM-as-judge,
online Langfuse feedback and Testcontainers integration tests are intentionally
out of scope here.
