---
name: verify-delivery-claim
description: Audit evidence and state the strongest defensible delivery or verification claim for feedback-platform. Use when asked whether work is complete, deployed, integrated, production-ready, accepted, tested, or fixed; especially when evidence mixes static tests, mocks, local degradation, real external services, target environments, and production.
---

# Verify Delivery Claim

## Evidence Ladder

Use the strongest actually demonstrated level, never the intended level:

1. code or document inspection;
2. compile, lint, static or unit test;
3. Mock/fake contract test;
4. local degraded or fallback-path test;
5. local integration with real dependencies;
6. target-environment integration with real external services;
7. production deployment plus monitored acceptance.

## Workflow

1. Define the exact claim, scope, environment, version, dependency, data authority, and acceptance condition.
2. Read only the current snapshot and directly linked evidence; do not use archive as current status.
3. Match each assertion to reproducible evidence. Treat old evidence as proving only the version it tested.
4. Downgrade claims with missing dependencies, skipped tests, mocks, fallback implementations, stale screenshots, unlogged sessions, or local-only deployment.
5. Separate `完成`, `未完成`, `待核验`, and `阻塞条件`. Give the minimum next evidence needed to advance one level.
6. Update project memory only after a material verified change; record conclusions and links, not full logs.

Read `references/claim-language.md` for approved wording and recurring evidence traps.
