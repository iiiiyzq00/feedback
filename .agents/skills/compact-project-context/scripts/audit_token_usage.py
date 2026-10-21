#!/usr/bin/env python3
"""Aggregate Codex token metadata without reading or printing conversation content."""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path


def parse_time(value: str) -> datetime | None:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None


def bucket(tokens: int) -> str:
    if tokens < 20_000:
        return "<20k"
    if tokens < 50_000:
        return "20-50k"
    if tokens < 100_000:
        return "50-100k"
    if tokens < 200_000:
        return "100-200k"
    return ">=200k"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--codex-home", type=Path, default=Path.home() / ".codex")
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--json", action="store_true", dest="as_json")
    parser.add_argument("--enforce", action="store_true")
    args = parser.parse_args()
    cutoff = datetime.now(timezone.utc) - timedelta(days=args.days)
    roots = [args.codex_home / "sessions", args.codex_home / "archived_sessions"]
    totals = Counter()
    buckets: dict[str, Counter] = defaultdict(Counter)
    providers = Counter()
    event_types = Counter()
    scanned_files = 0
    active_files = 0
    sessions: dict[str, Counter] = defaultdict(Counter)
    session_providers: dict[str, set[str]] = defaultdict(set)

    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob("*.jsonl"):
            scanned_files += 1
            file_active = False
            session_key = path.stem.rsplit("-", 5)[-1]
            try:
                handle = path.open("r", encoding="utf-8")
            except OSError:
                continue
            with handle:
                for line in handle:
                    try:
                        event = json.loads(line)
                    except (json.JSONDecodeError, UnicodeDecodeError):
                        continue
                    timestamp = parse_time(event.get("timestamp"))
                    if timestamp is None or timestamp < cutoff:
                        continue
                    if not file_active:
                        active_files += 1
                        file_active = True
                    payload = event.get("payload") or {}
                    event_type = payload.get("type") or event.get("type")
                    if event_type:
                        event_types[event_type] += 1
                    if event_type == "session_meta":
                        provider = payload.get("model_provider", "unknown")
                        session_id = payload.get("id")
                        if session_id:
                            session_key = str(session_id)
                        session_providers[session_key].add(provider)
                    if event_type == "context_compacted":
                        sessions[session_key]["compactions"] += 1
                    if event_type != "token_count":
                        continue
                    usage = ((payload.get("info") or {}).get("last_token_usage") or {})
                    if not usage:
                        continue
                    input_tokens = int(usage.get("input_tokens") or 0)
                    cached_tokens = int(usage.get("cached_input_tokens") or 0)
                    output_tokens = int(usage.get("output_tokens") or 0)
                    totals.update(calls=1, input=input_tokens, cached=cached_tokens, output=output_tokens)
                    session = sessions[session_key]
                    session.update(calls=1, input=input_tokens, cached=cached_tokens, output=output_tokens)
                    session["max_input"] = max(session["max_input"], input_tokens)
                    group = buckets[bucket(input_tokens)]
                    group.update(calls=1, input=input_tokens, cached=cached_tokens)

    for provider_set in session_providers.values():
        for provider in provider_set:
            providers[provider] += 1

    uncached = totals["input"] - totals["cached"]
    hit = 100 * totals["cached"] / totals["input"] if totals["input"] else 0
    over_100k_calls = buckets["100-200k"]["calls"] + buckets[">=200k"]["calls"]
    over_200k_calls = buckets[">=200k"]["calls"]
    over_100k_percent = 100 * over_100k_calls / totals["calls"] if totals["calls"] else 0
    over_200k_percent = 100 * over_200k_calls / totals["calls"] if totals["calls"] else 0
    policy = {}
    if args.policy:
        with args.policy.open("r", encoding="utf-8") as handle:
            policy = json.load(handle)
    warnings = []
    thresholds = policy.get("thresholds", {})
    checks = (
        (hit < thresholds.get("min_cache_hit_percent", 0), "cache_hit_below_target"),
        (
            over_100k_percent > thresholds.get("max_over_100k_call_percent", 100),
            "over_100k_call_share_above_target",
        ),
        (
            over_200k_percent > thresholds.get("max_over_200k_call_percent", 100),
            "over_200k_call_share_above_target",
        ),
        (
            totals["input"] > thresholds.get("max_input_tokens", 2**63 - 1),
            "input_tokens_above_budget",
        ),
        (
            uncached > thresholds.get("max_uncached_input_tokens", 2**63 - 1),
            "uncached_input_tokens_above_budget",
        ),
    )
    warnings.extend(reason for condition, reason in checks if condition)
    result = {
        "window_days": args.days,
        "files": active_files,
        "scanned_files": scanned_files,
        "calls": totals["calls"],
        "input_tokens": totals["input"],
        "cached_input_tokens": totals["cached"],
        "uncached_input_tokens": uncached,
        "output_tokens": totals["output"],
        "cache_hit_percent": round(hit, 2),
        "over_100k_call_percent": round(over_100k_percent, 2),
        "over_200k_call_percent": round(over_200k_percent, 2),
        "context_compacted": event_types["context_compacted"],
        "providers": dict(providers.most_common()),
        "top_sessions": [
            {
                "session_id": session_id,
                "calls": values["calls"],
                "input_tokens": values["input"],
                "cached_input_tokens": values["cached"],
                "max_input_tokens": values["max_input"],
                "compactions": values["compactions"],
                "providers": sorted(session_providers.get(session_id, set())),
            }
            for session_id, values in sorted(
                sessions.items(), key=lambda item: item[1]["input"], reverse=True
            )[:10]
        ],
        "warnings": warnings,
    }
    if args.as_json:
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
        return 1 if args.enforce and warnings else 0
    print(
        f"window_days={args.days} active_files={active_files} "
        f"scanned_files={scanned_files} calls={totals['calls']}"
    )
    print(
        f"input={totals['input']} cached={totals['cached']} uncached={uncached} "
        f"output={totals['output']} hit_ratio={hit:.2f}%"
    )
    for name in ("<20k", "20-50k", "50-100k", "100-200k", ">=200k"):
        group = buckets[name]
        group_hit = 100 * group["cached"] / group["input"] if group["input"] else 0
        print(
            f"bucket={name} calls={group['calls']} input={group['input']} "
            f"cached={group['cached']} hit_ratio={group_hit:.2f}%"
        )
    print(f"context_compacted={event_types['context_compacted']}")
    if providers:
        print("provider_sessions=" + ",".join(f"{key}:{value}" for key, value in providers.most_common()))
    for session_id, values in sorted(sessions.items(), key=lambda item: item[1]["input"], reverse=True)[:10]:
        provider_names = ",".join(sorted(session_providers.get(session_id, set()))) or "unknown"
        print(
            f"top_session={session_id} calls={values['calls']} input={values['input']} "
            f"cached={values['cached']} max_input={values['max_input']} "
            f"compactions={values['compactions']} providers={provider_names}"
        )
    print(f"over_100k_call_percent={over_100k_percent:.2f}% over_200k_call_percent={over_200k_percent:.2f}%")
    print("budget_status=" + ("WARN warnings=" + ",".join(warnings) if warnings else "PASS"))
    return 1 if args.enforce and warnings else 0


if __name__ == "__main__":
    raise SystemExit(main())
