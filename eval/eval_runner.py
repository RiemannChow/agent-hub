#!/usr/bin/env python3
"""Golden-set evaluation runner for agent-hub.

Replays a declarative YAML dataset against a running agent-hub instance and
evaluates deterministic assertions on the SSE event stream.

Usage:
    python eval/eval_runner.py --url http://localhost:8082

The runner authenticates through the demo SMS flow, then for each case posts
to POST /api/agent/chat and collects the SSE stream. Assertions are declared in
eval/golden.yaml; the runner itself contains no case-specific logic.

Design notes:
  - We assert on the trajectory (tool names, call counts) and on values
    extracted from the answer, not on exact wording or exact SQL text.
  - The agent may self-heal from a bad SQL query, so a failed first attempt is
    acceptable as long as the final answer is correct and the retry budget is
    respected.
  - SSE carries no token-usage field, so answer size is measured in characters
    as a deterministic cost proxy.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urljoin
from uuid import uuid4

import requests
import yaml

SSE_EVENT_RE = re.compile(r"^event:\s*(.+)$")
SSE_DATA_RE = re.compile(r"^data:\s*(.*)$")


@dataclass
class Trace:
    """Normalised execution trace collected from one chat SSE stream."""

    tools: list[str] = field(default_factory=list)
    tool_results: list[dict[str, Any]] = field(default_factory=list)
    answer: str = ""
    error: str | None = None
    completed: bool = False
    latency_ms: int = 0
    event_counts: dict[str, int] = field(default_factory=dict)

    @property
    def answer_chars(self) -> int:
        return len(self.answer)

    def to_dict(self) -> dict[str, Any]:
        return {
            "tools": self.tools,
            "tool_results": self.tool_results,
            "answer": self.answer,
            "error": self.error,
            "completed": self.completed,
            "latency_ms": self.latency_ms,
            "event_counts": self.event_counts,
            "answer_chars": self.answer_chars,
        }


@dataclass
class Result:
    case_id: str
    name: str
    category: str
    passed: bool
    failures: list[str]
    trace: Trace


class EvalError(Exception):
    """Infrastructure failure (auth, network) that is not a case assertion."""


def login(base_url: str, phone: str, timeout: int) -> str:
    """Authenticate via the demo SMS flow and return a bearer token."""
    send = requests.post(
        urljoin(base_url, "/api/auth/sms/send"),
        json={"phone": phone},
        timeout=timeout,
    )
    send.raise_for_status()
    payload = send.json()
    code = payload.get("code")
    if not code:
        raise EvalError(
            "No verification code returned. The instance must run with "
            "AGENT_AUTH_SMS_MODE=demo for the evaluation runner."
        )

    verify = requests.post(
        urljoin(base_url, "/api/auth/sms/verify"),
        json={"phone": phone, "code": code},
        timeout=timeout,
    )
    verify.raise_for_status()
    token = verify.json().get("token")
    if not token:
        raise EvalError("Login response did not contain a token.")
    return token


def collect_trace(
    base_url: str, token: str, message: str, conversation_id: str, timeout: int
) -> Trace:
    """POST one chat turn and collect its SSE stream into a normalised trace."""
    trace = Trace()
    started = time.monotonic()

    try:
        with requests.post(
            urljoin(base_url, "/api/agent/chat"),
            json={"message": message, "conversationId": conversation_id},
            headers={"Authorization": f"Bearer {token}"},
            stream=True,
            timeout=(10, timeout),
        ) as response:
            if response.status_code >= 400:
                raise EvalError(
                    f"chat endpoint returned HTTP {response.status_code}: "
                    f"{response.text[:200]}"
                )

            event_name: str | None = None
            for raw_line in response.iter_lines(decode_unicode=True):
                if raw_line is None:
                    continue
                line = raw_line.strip()
                if not line:
                    event_name = None
                    continue

                event_match = SSE_EVENT_RE.match(line)
                if event_match:
                    event_name = event_match.group(1).strip()
                    trace.event_counts[event_name] = (
                        trace.event_counts.get(event_name, 0) + 1
                    )
                    continue

                data_match = SSE_DATA_RE.match(line)
                if not data_match or event_name is None:
                    continue

                raw_data = data_match.group(1).strip()
                if not raw_data:
                    continue
                try:
                    data = json.loads(raw_data)
                except json.JSONDecodeError:
                    continue

                if event_name == "tool_calling":
                    trace.tools.append(str(data.get("toolName", "unknown")))
                elif event_name == "tool_result":
                    trace.tool_results.append(
                        {
                            "tool": str(data.get("toolName", "unknown")),
                            "result": str(data.get("result", ""))[:500],
                        }
                    )
                elif event_name == "token":
                    trace.answer += str(data.get("delta", ""))
                elif event_name == "completed":
                    trace.completed = True
                elif event_name == "error":
                    trace.error = str(data.get("message", raw_data))
    except requests.RequestException as exc:
        trace.error = f"network error: {exc}"

    trace.latency_ms = int((time.monotonic() - started) * 1000)
    return trace


def extract_numbers(text: str) -> set[int]:
    """Extract integer values from arbitrary answer text."""
    found: set[int] = set()
    for match in re.findall(r"\d[\d,]*", text):
        try:
            found.add(int(match.replace(",", "")))
        except ValueError:
            continue
    return found


def evaluate(case: dict[str, Any], defaults: dict[str, Any], trace: Trace) -> list[str]:
    """Return a list of failed assertion descriptions (empty means pass)."""
    failures: list[str] = []
    expect: dict[str, Any] = case.get("expect", {}) or {}

    # A transport failure is an infrastructure problem, not a behaviour failure.
    # Report it alone so the result is not polluted by downstream assertions that
    # had no chance to be evaluated.
    if trace.error and trace.error.startswith("network error:"):
        return [trace.error]
    if trace.error:
        failures.append(f"stream error: {trace.error}")
    if not trace.completed:
        failures.append("stream did not emit a completed event")

    tools = trace.tools
    if "tools" in expect:
        expected = list(expect["tools"])
        if tools != expected:
            failures.append(f"tools {tools} != expected {expected}")
    if "tools_any_of" in expect:
        allowed = set(expect["tools_any_of"])
        if not tools or not set(tools).intersection(allowed):
            failures.append(f"tools {tools} contain none of {sorted(allowed)}")
    if "tools_forbidden" in expect:
        forbidden = set(expect["tools_forbidden"])
        hit = forbidden.intersection(tools)
        if hit:
            failures.append(f"forbidden tools used: {sorted(hit)}")
    if "min_tool_calls" in expect and len(tools) < int(expect["min_tool_calls"]):
        failures.append(
            f"tool calls {len(tools)} < min {expect['min_tool_calls']}"
        )
    if "max_tool_calls" in expect and len(tools) > int(expect["max_tool_calls"]):
        failures.append(
            f"tool calls {len(tools)} > max {expect['max_tool_calls']}"
        )

    answer = trace.answer
    for fragment in expect.get("answer_contains", []) or []:
        if str(fragment) not in answer:
            failures.append(f"answer missing fragment: {fragment}")
    for fragment in expect.get("answer_not_contains", []) or []:
        if str(fragment) in answer:
            failures.append(f"answer contains forbidden fragment: {fragment}")
    if "answer_numeric" in expect:
        wanted = int(expect["answer_numeric"])
        if wanted not in extract_numbers(answer):
            failures.append(
                f"answer has no value {wanted}; numbers found: "
                f"{sorted(extract_numbers(answer))}"
            )
    pattern = expect.get("answer_regex")
    if pattern and not re.search(str(pattern), answer):
        failures.append(f"answer does not match regex: {pattern}")

    max_latency = int(expect.get("max_latency_ms", defaults.get("max_latency_ms", 60000)))
    if trace.latency_ms > max_latency:
        failures.append(f"latency {trace.latency_ms}ms > {max_latency}ms")

    max_chars = int(expect.get("max_answer_chars", defaults.get("max_answer_chars", 10000)))
    if trace.answer_chars > max_chars:
        failures.append(f"answer {trace.answer_chars} chars > {max_chars}")

    return failures


def run(args: argparse.Namespace) -> int:
    dataset = yaml.safe_load(Path(args.cases).read_text(encoding="utf-8"))
    defaults: dict[str, Any] = dataset.get("defaults", {}) or {}
    cases: list[dict[str, Any]] = dataset.get("cases", []) or []

    if args.category:
        cases = [c for c in cases if c.get("category") == args.category]
    if args.case_id:
        cases = [c for c in cases if c.get("id") in args.case_id]

    if not cases:
        print("No cases selected.", file=sys.stderr)
        return 2

    base_url = args.url.rstrip("/") + "/"
    print(f"Authenticating against {base_url} ...")
    try:
        token = login(base_url, args.phone, args.timeout)
    except (EvalError, requests.RequestException) as exc:
        print(f"Authentication failed: {exc}", file=sys.stderr)
        return 2

    # Every run owns a fresh conversation namespace. Reusing a fixed case id
    # would replay the whole run's accumulated history into the next run's
    # prompt, changing tool choices and latency between evaluations.
    run_id = uuid4().hex[:8]
    results: list[Result] = []
    for index, case in enumerate(cases, start=1):
        case_id = str(case.get("id", f"case-{index}"))
        name = str(case.get("name", case_id))
        category = str(case.get("category", "uncategorised"))
        print(f"[{index}/{len(cases)}] {case_id}: {name}", flush=True)

        message = str(case.get("message", ""))
        conversation_id = f"{run_id}-{case_id}"
        trace = collect_trace(base_url, token, message, conversation_id, args.timeout)
        if trace.error and trace.error.startswith("network error:"):
            # A saturated model gateway can stall one turn without emitting any
            # SSE frame. Retry once in a fresh conversation; assertion failures
            # are never retried because those are real behaviour regressions.
            print(f"  transport error, retrying {case_id} once", flush=True)
            trace = collect_trace(
                base_url, token, message, f"{conversation_id}-retry", args.timeout
            )
        failures = evaluate(case, defaults, trace)
        results.append(Result(case_id, name, category, not failures, failures, trace))

    passed = sum(1 for r in results if r.passed)
    report: dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "url": base_url,
        "dataset": args.cases,
        "summary": {
            "total": len(results),
            "passed": passed,
            "failed": len(results) - passed,
            "pass_rate": round(passed / len(results), 4),
        },
        "results": [
            {
                "id": r.case_id,
                "name": r.name,
                "category": r.category,
                "passed": r.passed,
                "failures": r.failures,
                "trace": r.trace.to_dict(),
            }
            for r in results
        ],
    }

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("\n=== Summary ===")
    print(f"{passed}/{len(results)} passed ({report['summary']['pass_rate']:.0%})")
    for result in results:
        mark = "PASS" if result.passed else "FAIL"
        print(
            f"  [{mark}] {result.case_id} | {result.category} | "
            f"{result.trace.latency_ms}ms | tools={result.trace.tools}"
        )
        for failure in result.failures:
            print(f"         - {failure}")
    print(f"\nReport written to {out_path}")
    return 0 if passed == len(results) else 1


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8082", help="Base URL of a running agent-hub")
    parser.add_argument("--cases", default="eval/golden.yaml", help="Path to the golden dataset YAML")
    parser.add_argument("--out", default="eval-report.json", help="Path of the JSON report")
    parser.add_argument("--phone", default="13900139000", help="Demo-login phone number")
    parser.add_argument(
        "--timeout",
        type=int,
        default=120,
        help="Per-request timeout in seconds (must exceed the dataset's max_latency_ms)",
    )
    parser.add_argument("--category", help="Only run cases from this category")
    parser.add_argument("--case-id", action="append", help="Only run this case id (repeatable)")
    return parser.parse_args(argv)


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
