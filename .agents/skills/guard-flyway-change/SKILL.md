---
name: guard-flyway-change
description: Design, implement, review, and verify safe forward-only Flyway and MySQL schema changes for feedback-platform. Use for new migrations, checksum failures, schema drift, data backfills, table-count claims, migration deployment, or any request that might modify an already-applied migration.
---

# Guard Flyway Change

## Workflow

1. Read the migration boundary in `AGENTS.md` and the current Flyway version from `docs/PROJECT_SNAPSHOT.md`.
2. Check `git status --short`; preserve all unrelated and untracked migrations.
3. Resolve the exact next version from normal Git-text migration files. Never infer it from an old design document.
4. Before editing, determine whether the target migration has succeeded in any authoritative database. If yes or uncertain, never edit it; add a new forward migration.
5. Keep MySQL authoritative. Treat Elasticsearch and Milvus changes as rebuildable derived-index work, not schema truth.
6. Make backfills deterministic, idempotent where practical, bounded, and explicit about time zone and null semantics.
7. Validate SQL is not a Git LFS pointer. Run static checks and a migration test against the dedicated test database when the change warrants execution.
8. Report code delivery, local migration, target-environment migration, and production readiness as separate states.

Read `references/pitfalls.md` when handling checksum repair, triggers, historical data recomputation, shared test databases, or migration count discrepancies.
