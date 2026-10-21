---
name: compact-project-context
description: Compress an ongoing repository task into a minimal executable handoff while preserving goals, decisions, constraints, workspace changes, verification, blockers, and next actions. Use for long Codex tasks, cross-stack phase changes, task handoffs, context near 80k tokens, repeated logs, or after two failed approaches.
---

# Compact Project Context

## Workflow

1. Read `AGENTS.md` and `docs/PROJECT_SNAPSHOT.md`; do not reload the full project memory unless a disputed fact requires it.
2. Run `../tools/context-start.sh`, then inspect exact status only for task-related paths. Treat unrelated changes as user-owned.
3. Extract only the current goal, exclusions, settled decisions, hard constraints, relevant changed files, verification conclusions, unresolved issues, and at most three next actions.
4. Remove full logs, repeated tool output, superseded approaches, resolved errors, generic project background, and speculative conclusions.
5. Mark uncertain statements `待核验`. Preserve the distinction between static, Mock, local, degraded, integration, target-environment, and production evidence.
6. Produce the handoff using `references/handoff-template.md`.
7. Continue from the handoff, `AGENTS.md`, the short snapshot, and only the next required file. Do not reload settled history.

## Token Audit

When usage evidence is requested, run `scripts/audit_token_usage.py --days 7 --policy ../../../config/codex-cost-policy.json`, or use the repository wrapper `../tools/audit-ai-cost.sh` from the application root. The script reads only event metadata and token counters; it never emits message, reasoning, tool argument, or tool output content.

Do not call cached tokens “waste” without checking provider billing. Report cached, uncached, hit ratio, long-context distribution, provider switches, and compaction count separately.
