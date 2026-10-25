#!/usr/bin/env python3
"""
a2a-client-demo.py — 扮演「另一个 A2A 智能体」，演示如何发现并调用本仓库的 agent-hub。

流程（即 A2A 协议的客户端视角）：
  1. 发现（Discovery）  —— 通过 Nacos 服务注册中心找到 agent-hub 实例，
                            或直接使用已知的 Agent Card 地址；
  2. 自描述（Agent Card）—— GET /.well-known/agent.json，了解对端能做什么；
  3. 调用（JSON-RPC）    —— POST /a2a 的 message/send（或 message/stream, SSE）；
  4. 取回（tasks/get）   —— 用上一步返回的 task id 取回已完成的 Task。

仅依赖 Python 标准库（urllib / json / argparse），无需 pip install。

示例：
  # 默认：经本地 Nacos(8848) 发现 agent-hub，发送一条 message/send
  python3 a2a-client-demo.py --query "2010年以后评分大于4.5的书有哪些？"

  # 用 SSE 流式调用
  python3 a2a-client-demo.py --query "按年份统计图书数量" --stream

  # 跳过发现，直接指向某个实例的 Agent Card
  python3 a2a-client-demo.py --card-url http://localhost:8081/.well-known/agent.json --query "你好"
"""

import argparse
import json
import sys
import urllib.request
import urllib.error

DEFAULT_NACOS = "http://localhost:8848/nacos"
DEFAULT_SERVICE = "agent-hub"
HTTP_TIMEOUT = 120


def _http(method, url, body=None, accept="application/json", timeout=HTTP_TIMEOUT):
    """最小化的 HTTP 助手：返回 (status, parsed_json_or_text)。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", accept)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            status = resp.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        status = e.code
    try:
        return status, json.loads(raw) if raw else None
    except json.JSONDecodeError:
        return status, raw


def discover_via_nacos(nacos_url, service):
    """经 Nacos 列出 agent-hub 服务实例，返回首个实例的 (a2a_endpoint, a2a_card)。"""
    list_url = (
        f"{nacos_url.rstrip('/')}/v1/ns/instance/list"
        f"?serviceName={service}&namespaceId="
    )
    status, payload = _http("GET", list_url)
    if status != 200 or not isinstance(payload, dict):
        raise RuntimeError(f"Nacos 查询失败 (status={status}): {payload}")
    hosts = payload.get("hosts", [])
    if not hosts:
        raise RuntimeError(f"Nacos 中未找到服务 '{service}' 的任何实例")
    h = hosts[0]
    meta = h.get("metadata", {})
    ip, port = h.get("ip"), h.get("port")
    endpoint = meta.get("a2a-endpoint") or f"http://{ip}:{port}/a2a"
    card = meta.get("a2a-card") or f"http://{ip}:{port}/.well-known/agent.json"
    return endpoint, card


def fetch_card(card_url):
    status, card = _http("GET", card_url)
    if status != 200 or not isinstance(card, dict):
        raise RuntimeError(f"获取 Agent Card 失败 (status={status}): {card}")
    return card


def build_send_params(text, context_id=None):
    msg = {"role": "user", "parts": [{"kind": "text", "text": text}]}
    params = {"message": msg}
    if context_id:
        params["contextId"] = context_id
    return params


def call_send(endpoint, text):
    """message/send —— 一次性返回已完成的 Task。返回 (task_id, answer_text)。"""
    body = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "message/send",
        "params": build_send_params(text),
    }
    status, resp = _http("POST", endpoint, body, accept="application/json")
    if status != 200 or not isinstance(resp, dict):
        raise RuntimeError(f"message/send 失败 (status={status}): {resp}")
    if resp.get("error"):
        raise RuntimeError(f"message/send 返回错误: {resp['error']}")
    task = resp["result"]
    text_parts = []
    for art in task.get("artifacts", []):
        for p in art.get("parts", []):
            if p.get("text"):
                text_parts.append(p["text"])
    return task.get("id"), "\n".join(text_parts)


def call_stream(endpoint, text):
    """message/stream —— SSE 流式返回，逐个事件打印。返回最终 task_id。"""
    body = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "message/stream",
        "params": build_send_params(text),
    }
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(endpoint, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream")

    task_id = None
    print("  [SSE] 开始接收流式事件：")
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
        for raw in resp:
            line = raw.decode("utf-8").rstrip("\n")
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            try:
                env = json.loads(payload)
            except json.JSONDecodeError:
                continue
            if env.get("error"):
                print(f"    ✗ error: {env['error']}")
                continue
            result = env.get("result") or {}
            if "status" in result:
                print(f"    • status -> {result['status'].get('state')}")
            elif "artifact" in result:
                art = result["artifact"]
                chunk = "".join(
                    p.get("text", "") for p in art.get("parts", [])
                )
                if chunk:
                    print(f"    • artifact (append={art.get('append')}): {chunk[:70]}")
                if not art.get("append", True):
                    # 末块携带完整 Task 信息
                    task_id = result.get("contextId") or task_id
            elif "id" in result and "status" in result:
                task_id = result.get("id")
    return task_id


def call_get_task(endpoint, task_id):
    body = {
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tasks/get",
        "params": {"id": task_id},
    }
    status, resp = _http("POST", endpoint, body, accept="application/json")
    if status != 200 or not isinstance(resp, dict):
        raise RuntimeError(f"tasks/get 失败 (status={status}): {resp}")
    if resp.get("error"):
        raise RuntimeError(f"tasks/get 返回错误: {resp['error']}")
    return resp["result"]


def main():
    ap = argparse.ArgumentParser(description="A2A 客户端演示：发现并调用 agent-hub")
    ap.add_argument("--nacos", default=DEFAULT_NACOS, help="Nacos 地址（默认 %(default)s）")
    ap.add_argument("--service", default=DEFAULT_SERVICE, help="Nacos 服务名（默认 %(default)s）")
    ap.add_argument("--card-url", default=None, help="跳过发现，直接指定对端 Agent Card 地址")
    ap.add_argument("--query", default="2010年以后、评分大于4.5分的书有哪些？", help="发给对端的文本")
    ap.add_argument("--stream", action="store_true", help="使用 message/stream（SSE 流式）而非 message/send")
    args = ap.parse_args()

    print("=" * 64)
    print(" A2A 客户端演示：从「另一个智能体」调用 agent-hub")
    print("=" * 64)

    # 1) 发现
    print("\n[1/4] 发现对端智能体")
    if args.card_url:
        card_url = args.card_url
        endpoint = card_url.rsplit("/.well-known/agent.json", 1)[0] + "/a2a"
        print(f"  跳过 Nacos，直接使用 Agent Card: {card_url}")
    else:
        endpoint, card_url = discover_via_nacos(args.nacos, args.service)
        print(f"  经 Nacos 发现服务 '{args.service}'")
        print(f"  -> a2a-endpoint : {endpoint}")
        print(f"  -> a2a-card    : {card_url}")

    # 2) Agent Card
    print("\n[2/4] 获取 Agent Card（自描述）")
    card = fetch_card(card_url)
    caps = card.get("capabilities", {})
    skills = [s.get("id") for s in card.get("skills", [])]
    print(f"  name      : {card.get('name')}")
    print(f"  version   : {card.get('version')}")
    print(f"  streaming : {caps.get('streaming')}")
    print(f"  skills    : {skills}")

    # 3) 调用
    print(f"\n[3/4] 调用 {'message/stream (SSE)' if args.stream else 'message/send'}")
    print(f"  查询: {args.query}")
    if args.stream:
        task_id = call_stream(endpoint, args.query)
        print(f"  [SSE] 流结束，task id: {task_id}")
    else:
        task_id, answer = call_send(endpoint, args.query)
        print(f"  task id: {task_id}")
        print(f"  回答:\n-----")
        print(answer)
        print("-----")

    # 4) 取回
    if task_id:
        print("\n[4/4] tasks/get 取回该 Task")
        task = call_get_task(endpoint, task_id)
        print(f"  state    : {task.get('status', {}).get('state')}")
        print(f"  artifacts: {len(task.get('artifacts', []))}")
    print("\n演示完成。")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as e:
        print(f"\n✗ 演示失败: {e}", file=sys.stderr)
        sys.exit(1)
